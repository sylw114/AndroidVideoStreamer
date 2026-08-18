package org.dpdns.sylw.videostreamer.udpAudio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.dpdns.sylw.videostreamer.R
import org.dpdns.sylw.videostreamer.StreamConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 低延迟音频会话所有者。
 *
 * 采集和 Opus 编码只维护一份，QUIC/UDP 的连接、控制反馈与发包由独立传输实现。
 */
class LowLatencyAudioManager {
    companion object {
        private const val TAG = "LowLatencyAudio"
        private const val CODEC_PCM = 0x00
        private const val CODEC_OPUS = 0x01
    }

    data class LatencyRecord(val sequence: Int, val minimumMs: Long, val maximumMs: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val starting = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private val capturing = AtomicBoolean(false)

    private var serverIp = ""
    private var controlPort = 0
    private var mediaPort = 0
    private var enabled = false
    private var protocol = AudioTransportProtocol.QUIC
    private var redundantTransmission = StreamConfig.getUdpAudioRedundant() ?: false
    private var opusEnabled = StreamConfig.getUdpAudioOpusEnabled() ?: true
    private var opusBitrate = StreamConfig.getUdpAudioOpusBitrate() ?: 96_000
    private var opusFrameMs = StreamConfig.getUdpAudioOpusFrameMs() ?: 10

    private var currentSampleRate = 48_000
    private var currentChannelConfig = AudioFormat.CHANNEL_IN_STEREO
    private var audioRecord: AudioRecord? = null
    private var opusEncoder: OpusAudioEncoder? = null
    private var captureThread: Thread? = null
    private var activeTransport: LowLatencyAudioTransport? = null
    private var sequenceNumber = 0

    private var latencyLogFile: File? = null
    private var latencyRecording = false
    private val latencyRecords = mutableListOf<LatencyRecord>()

    var onConnectionStateChanged: ((Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onLatencyUpdated: ((Long, Long) -> Unit)? = null

    fun getLatencyLogFile(): File? = latencyLogFile

    fun updateConfig(
        ip: String,
        controlPort: Int,
        mediaPort: Int,
        enabled: Boolean,
        protocol: AudioTransportProtocol = AudioTransportProtocol.from(StreamConfig.getAudioTransport()),
        redundantTransmission: Boolean = StreamConfig.getUdpAudioRedundant() ?: false,
        opusEnabled: Boolean = StreamConfig.getUdpAudioOpusEnabled() ?: true,
        opusBitrate: Int = StreamConfig.getUdpAudioOpusBitrate() ?: 96_000,
        opusFrameMs: Int = StreamConfig.getUdpAudioOpusFrameMs() ?: 10
    ) {
        this.serverIp = ip
        this.controlPort = controlPort
        this.mediaPort = mediaPort
        this.enabled = enabled
        this.protocol = protocol
        this.redundantTransmission = redundantTransmission
        this.opusEnabled = opusEnabled
        this.opusBitrate = opusBitrate.coerceIn(8_000, 256_000)
        this.opusFrameMs = opusFrameMs.takeIf { it in setOf(10, 20, 40) } ?: 10
        if (!enabled) stop()
    }

    fun start(
        context: Context,
        audioConfig: AudioPlaybackCaptureConfiguration?,
        logFile: File? = null,
        recordEnabled: Boolean = false,
        latencyLogHeader: String = "包序号\t最小(ms)\t最大(ms)\n"
    ) {
        if (!enabled || connected.get() || capturing.get() || !starting.compareAndSet(false, true)) return
        if (audioConfig == null) {
            starting.set(false)
            reportError(context.getString(R.string.error_audio_start_failed, "录屏音频授权不可用"))
            return
        }

        latencyLogFile = if (recordEnabled) logFile else null
        latencyRecording = recordEnabled
        synchronized(latencyRecords) { latencyRecords.clear() }
        if (recordEnabled) {
            try {
                logFile?.writeText(latencyLogHeader)
            } catch (error: Exception) {
                Log.e(TAG, "初始化延迟日志失败：${error.message}")
            }
        }

        scope.launch {
            var transport: LowLatencyAudioTransport? = null
            try {
                resolveAudioConfig()
                resolveOpusFrameMs()
                val channelCount = if (currentChannelConfig == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
                val sessionConfig = AudioSessionConfig(
                    sampleRate = currentSampleRate,
                    channelCount = channelCount,
                    codec = if (opusEnabled) CODEC_OPUS else CODEC_PCM,
                    frameMs = opusFrameMs,
                    opusBitrate = opusBitrate
                )
                val endpoint = AudioEndpoint(
                    host = serverIp,
                    controlPort = controlPort,
                    mediaPort = mediaPort,
                    redundantTransmission = protocol == AudioTransportProtocol.UDP && redundantTransmission
                )
                transport = when (protocol) {
                    AudioTransportProtocol.QUIC -> QuicAudioTransport()
                    AudioTransportProtocol.UDP -> LegacyUdpAudioTransport()
                }
                transport.connect(
                    endpoint = endpoint,
                    config = sessionConfig,
                    callbacks = AudioTransportCallbacks(
                        onLatency = ::handleLatency,
                        onDisconnected = ::handleTransportDisconnected,
                        onError = ::reportError
                    )
                )
                if (!starting.get()) {
                    transport.close()
                    return@launch
                }
                activeTransport = transport
                connected.set(true)
                dispatch { onConnectionStateChanged?.invoke(true) }
                startAudioCapture(audioConfig, transport)
            } catch (error: Exception) {
                transport?.close()
                activeTransport = null
                connected.set(false)
                releaseCaptureResources()
                reportError(context.getString(R.string.error_audio_start_failed, error.message ?: ""))
                dispatch { onConnectionStateChanged?.invoke(false) }
            } finally {
                starting.set(false)
            }
        }
    }

    fun stop() {
        starting.set(false)
        capturing.set(false)
        captureThread?.interrupt()
        captureThread = null
        releaseCaptureResources()
        val transport = activeTransport
        activeTransport = null
        transport?.close()
        if (connected.getAndSet(false)) dispatch { onConnectionStateChanged?.invoke(false) }
    }

    fun release() {
        stop()
        scope.cancel()
        onConnectionStateChanged = null
        onError = null
        onLatencyUpdated = null
    }

    private fun resolveAudioConfig() {
        val formats = listOf(
            48_000 to AudioFormat.CHANNEL_IN_STEREO,
            44_100 to AudioFormat.CHANNEL_IN_STEREO,
            48_000 to AudioFormat.CHANNEL_IN_MONO,
            44_100 to AudioFormat.CHANNEL_IN_MONO
        )
        val supported = formats.firstOrNull { (sampleRate, channels) ->
            AudioRecord.getMinBufferSize(sampleRate, channels, AudioFormat.ENCODING_PCM_16BIT) > 0
        } ?: throw IllegalStateException("设备不支持可用的回放采集格式")
        currentSampleRate = supported.first
        currentChannelConfig = supported.second
    }

    private fun resolveOpusFrameMs() {
        if (!opusEnabled) return
        val channelCount = if (currentChannelConfig == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
        opusFrameMs = OpusFrameDurationResolver.resolveSupportedFrameMs(
            requestedFrameMs = opusFrameMs,
            sampleRate = currentSampleRate,
            channelCount = channelCount,
            bitrate = opusBitrate
        )
    }

    @Suppress("MissingPermission")
    private fun startAudioCapture(
        playbackConfig: AudioPlaybackCaptureConfiguration,
        transport: LowLatencyAudioTransport
    ) {
        val minimumBufferSize = AudioRecord.getMinBufferSize(
            currentSampleRate,
            currentChannelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        )
        require(minimumBufferSize > 0) { "无法计算音频采集缓冲区" }

        val recorder = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(currentSampleRate)
                    .setChannelMask(currentChannelConfig)
                    .build()
            )
            .setBufferSizeInBytes(minimumBufferSize)
            .setAudioPlaybackCaptureConfig(playbackConfig)
            .build()
        require(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord 初始化失败" }

        val encoder = if (opusEnabled) {
            OpusAudioEncoder(
                sampleRate = currentSampleRate,
                channelCount = if (currentChannelConfig == AudioFormat.CHANNEL_IN_STEREO) 2 else 1,
                bitrate = opusBitrate,
                frameMs = opusFrameMs
            )
        } else {
            null
        }
        audioRecord = recorder
        opusEncoder = encoder
        recorder.startRecording()
        require(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AudioRecord 未进入采集状态" }

        capturing.set(true)
        sequenceNumber = 0
        captureThread = Thread(
            { captureLoop(minimumBufferSize, transport) },
            "LowLatencyAudio-capture"
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun captureLoop(bufferSize: Int, transport: LowLatencyAudioTransport) {
        val buffer = ByteArray(bufferSize)
        try {
            while (capturing.get() && connected.get() && !Thread.currentThread().isInterrupted) {
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (bytesRead <= 0) continue
                val encodedFrames = opusEncoder?.encode(buffer, bytesRead)
                if (encodedFrames != null) {
                    encodedFrames.forEach { sendPayload(transport, it) }
                } else {
                    sendPcm(transport, buffer, bytesRead)
                }
            }
        } catch (error: Exception) {
            if (capturing.get()) reportError("音频发送失败：${error.message}")
        } finally {
            if (capturing.getAndSet(false)) handleTransportDisconnected()
        }
    }

    private fun sendPcm(transport: LowLatencyAudioTransport, buffer: ByteArray, bytesRead: Int) {
        val maximum = transport.maximumPayloadSize.coerceAtLeast(256)
        var offset = 0
        while (offset < bytesRead) {
            val size = minOf(maximum, bytesRead - offset)
            sendPayload(transport, buffer.copyOfRange(offset, offset + size))
            offset += size
        }
    }

    private fun sendPayload(transport: LowLatencyAudioTransport, payload: ByteArray) {
        val sequence = sequenceNumber and 0xff
        sequenceNumber = (sequenceNumber + 1) and 0xff
        transport.send(sequence, payload, System.currentTimeMillis())
    }

    private fun handleLatency(range: AudioLatencyRange) {
        val record = LatencyRecord(range.sequence, range.minimumMs, range.maximumMs)
        if (latencyRecording) {
            synchronized(latencyRecords) { latencyRecords.add(record) }
            try {
                latencyLogFile?.appendText("${record.sequence}\t${record.minimumMs}\t${record.maximumMs}\n")
            } catch (error: Exception) {
                Log.e(TAG, "写入延迟日志失败：${error.message}")
            }
        }
        dispatch { onLatencyUpdated?.invoke(record.minimumMs, record.maximumMs) }
    }

    private fun handleTransportDisconnected() {
        capturing.set(false)
        releaseCaptureResources()
        val transport = activeTransport
        activeTransport = null
        transport?.close()
        if (connected.getAndSet(false)) dispatch { onConnectionStateChanged?.invoke(false) }
    }

    private fun releaseCaptureResources() {
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        audioRecord?.release()
        audioRecord = null
        opusEncoder?.release()
        opusEncoder = null
    }

    private fun reportError(message: String) {
        Log.e(TAG, message)
        dispatch { onError?.invoke(message) }
    }

    private fun dispatch(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private class OpusAudioEncoder(
        sampleRate: Int,
        channelCount: Int,
        bitrate: Int,
        private val frameMs: Int
    ) {
        private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        private val frameBytes = sampleRate * frameMs / 1_000 * channelCount * 2
        private var pcmBuffer = ByteArray(frameBytes * 2)
        private var pcmSize = 0
        private var presentationTimeUs = 0L

        init {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_OPUS,
                sampleRate,
                channelCount
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, frameBytes)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        }

        fun encode(input: ByteArray, size: Int): List<ByteArray> {
            ensureCapacity(pcmSize + size)
            input.copyInto(pcmBuffer, destinationOffset = pcmSize, endIndex = size)
            pcmSize += size
            val frames = mutableListOf<ByteArray>()
            while (pcmSize >= frameBytes) {
                if (!queueFrame()) break
                frames += drain()
                val remaining = pcmSize - frameBytes
                if (remaining > 0) pcmBuffer.copyInto(pcmBuffer, endIndex = pcmSize, destinationOffset = 0, startIndex = frameBytes)
                pcmSize = remaining
            }
            frames += drain()
            return frames
        }

        private fun queueFrame(): Boolean {
            val index = codec.dequeueInputBuffer(10_000)
            if (index < 0) return false
            val inputBuffer = codec.getInputBuffer(index) ?: return false
            inputBuffer.clear()
            inputBuffer.put(pcmBuffer, 0, frameBytes)
            codec.queueInputBuffer(index, 0, frameBytes, presentationTimeUs, 0)
            presentationTimeUs += frameMs * 1_000L
            return true
        }

        private fun drain(): List<ByteArray> {
            val frames = mutableListOf<ByteArray>()
            val info = MediaCodec.BufferInfo()
            while (true) {
                val index = codec.dequeueOutputBuffer(info, 0)
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                if (index < 0) continue
                val codecConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (!codecConfig && info.size > 0) {
                    codec.getOutputBuffer(index)?.let { output ->
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        frames += ByteArray(info.size).also(output::get)
                    }
                }
                codec.releaseOutputBuffer(index, false)
            }
            return frames
        }

        private fun ensureCapacity(required: Int) {
            if (required <= pcmBuffer.size) return
            pcmBuffer = pcmBuffer.copyOf(maxOf(required, pcmBuffer.size * 2))
        }

        fun release() {
            try {
                codec.stop()
            } catch (_: Exception) {
            }
            codec.release()
        }
    }
}
