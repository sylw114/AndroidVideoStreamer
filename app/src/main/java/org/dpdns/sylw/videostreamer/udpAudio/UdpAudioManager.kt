package org.dpdns.sylw.videostreamer.udpAudio

import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.util.Log
import kotlinx.coroutines.*
import org.dpdns.sylw.videostreamer.StreamConfig
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * 低延迟UDP音频流管理器
 * 
 * 协议设计参考文档: UdpAudioProtocol.md
 */
class UdpAudioManager {

    companion object {
        private const val TAG = "UdpAudioManager"
        private const val LATENCY_INVALID_THRESHOLD = -20L // ms
    }

    data class LatencyRecord(val seq: Int, val minLatency: Long, val maxLatency: Long)

    private var serverIp: String = ""
    private var tcpPort: Int = 0
    private var udpPort: Int = 0
    private var isEnabled: Boolean = false
    private var isRedundantTransmissionEnabled: Boolean = StreamConfig.getUdpAudioRedundant() ?: false

    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private var isCapturing: Boolean = false

    private var udpSocket: DatagramSocket? = null
    private var tcpSocket: java.net.Socket? = null
    private var tcpOutputStream: java.io.OutputStream? = null
    private var serverAddress: InetAddress? = null
    private var isConnected: Boolean = false
    private var mtu: Int = 1400 // Default value

    var onConnectionStateChanged: ((Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onLatencyUpdated: ((Long, Long) -> Unit)? = null // (min, max) 最新有效组延迟

    private var sequenceNumber: UByte = 0u
    private val packetTimestampMap = LongArray(256) // 存储每个序号的发送时间

    private val latencyRecords = mutableListOf<LatencyRecord>()

    private var currentSampleRate: Int = 48000
    private var currentChannelConfig: Int = AudioFormat.CHANNEL_IN_STEREO

    private var latencyLogFile: File? = null
    private var recordEnabled: Boolean = false

    fun getLatencyLogFile(): File? = latencyLogFile

    fun updateConfig(ip: String, tcpPort: Int, udpPort: Int, enabled: Boolean, redundantTransmission: Boolean = StreamConfig.getUdpAudioRedundant() ?: false) {
        this.serverIp = ip
        this.tcpPort = tcpPort
        this.udpPort = udpPort
        this.isEnabled = enabled
        this.isRedundantTransmissionEnabled = redundantTransmission
        if (!enabled) stop()
    }

    fun start(audioConfig: AudioPlaybackCaptureConfiguration?, logFile: File? = null, recordEnabled: Boolean = false) {
        // 🔥 如果之前处于连接状态，强制进行一次探测性清理，防止残留状态
        if (isConnected) {
            val socket = tcpSocket
            if (socket == null || socket.isClosed || !socket.isConnected) {
                Log.w(TAG, "Detected stale connection, forcing disconnect.")
                disconnect()
            } else {
                return // 确实还在连着
            }
        }

        if (!isEnabled || isCapturing) return

        latencyLogFile = if (recordEnabled) logFile else null
        this.recordEnabled = recordEnabled
        try {
            if (recordEnabled) logFile?.writeText("包序号\t最小(ms)\t最大(ms)\n")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init latency log file: ${e.message}")
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                serverAddress = InetAddress.getByName(serverIp)
                
                // 1. 建立 TCP 控制连接
                tcpSocket = java.net.Socket(serverAddress, tcpPort)
                tcpSocket?.tcpNoDelay = true // 禁用 Nagle 算法，减小延迟
                tcpOutputStream = tcpSocket?.outputStream
                
                // 2. 初始化 UDP 数据 Socket
                udpSocket = DatagramSocket()
                // 获取当前活动网络的 MTU
                try {
                    val networkInterfaces = java.net.NetworkInterface.getNetworkInterfaces()
                    while (networkInterfaces.hasMoreElements()) {
                        val ni = networkInterfaces.nextElement()
                        if (ni.isUp && !ni.isLoopback) {
                            mtu = ni.mtu
                            Log.d(TAG, "Detected MTU: $mtu for interface: ${ni.displayName}")
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get MTU: ${e.message}")
                }
                
                isConnected = true
                synchronized(latencyRecords) {
                    latencyRecords.clear()
                }

                // 3. 启动控制循环
                startTcpControlLoop()
                
                // 4. 发送握手包
                // 🔥 在握手前计算音频配置，确保发送的是真实支持的配置
                resolveAudioConfig()
                sendHandshake()
                
                onConnectionStateChanged?.invoke(true)
                startAudioCapture(audioConfig)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start dual-protocol audio: ${e.message}")
                onError?.invoke("启动失败: ${e.message}")
                disconnect()
            }
        }
    }

    private fun resolveAudioConfig() {
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val formats = listOf(
            Pair(48000, AudioFormat.CHANNEL_IN_STEREO),
            Pair(44100, AudioFormat.CHANNEL_IN_STEREO),
            Pair(48000, AudioFormat.CHANNEL_IN_MONO),
            Pair(44100, AudioFormat.CHANNEL_IN_MONO)
        )
        
        for (f in formats) {
            val size = AudioRecord.getMinBufferSize(f.first, f.second, audioFormat)
            if (size > 0) {
                currentSampleRate = f.first
                currentChannelConfig = f.second
                return
            }
        }
    }

    private fun sendHandshake() {
        val sampleRateIndex = if (currentSampleRate == 48000) 0x01.toByte() else 0x00.toByte()
        val channels = if (currentChannelConfig == AudioFormat.CHANNEL_IN_STEREO) 0x02.toByte() else 0x01.toByte()
        
        // [SampleRateIdx][Channels]
        val handshake = byteArrayOf(sampleRateIndex, channels)
        tcpOutputStream?.write(handshake)
        tcpOutputStream?.flush()
    }

    fun stop() {
        isCapturing = false
        captureThread?.interrupt()
        captureThread = null
        releaseAudioRecord()
        disconnect()
    }

    fun release() {
        stop()
    }

    private fun disconnect() {
        try {
            tcpOutputStream?.close()
            tcpSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing TCP: ${e.message}")
        }
        tcpSocket = null
        udpSocket?.close()
        udpSocket = null
        isConnected = false
        onConnectionStateChanged?.invoke(false)
    }

    @Suppress("MissingPermission")
    private fun startAudioCapture(audioConfig: AudioPlaybackCaptureConfiguration?) {
        if (audioConfig == null) return

        val bufferSize = AudioRecord.getMinBufferSize(currentSampleRate, currentChannelConfig, AudioFormat.ENCODING_PCM_16BIT)
        if (bufferSize <= 0) return

        audioRecord = AudioRecord.Builder()
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(currentSampleRate)
                .setChannelMask(currentChannelConfig)
                .build())
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(audioConfig)
            .build()

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            releaseAudioRecord()
            return
        }

        audioRecord?.startRecording()
        isCapturing = true
        sequenceNumber = 0u
        captureThread = Thread({ audioCaptureLoop(bufferSize) }, "UdpAudioCaptureThread")
        captureThread?.start()
    }

    private fun startTcpControlLoop() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val inputStream = tcpSocket?.getInputStream() ?: return@launch
                val rawBuffer = ByteArray(4096)
                val accumulator = java.io.ByteArrayOutputStream()
                var hbSendTimestamp: Long = 0

                val heartbeatJob = launch {
                    delay(1010)
                    while (isConnected) {
                        val timestamp = System.currentTimeMillis()
                        hbSendTimestamp = timestamp
                        val hb = java.nio.ByteBuffer.allocate(9)
                            .put(0x02.toByte())
                            .putLong(timestamp)
                            .array()
                        tcpOutputStream?.write(hb)
                        tcpOutputStream?.flush()
                        delay(1000)
                    }
                }

                while (isConnected) {
                    val bytesRead = inputStream.read(rawBuffer)
                    if (bytesRead == -1) break
                    accumulator.write(rawBuffer, 0, bytesRead)

                    // 协议格式: [TotalLen:2][Seq:1][udpTs:8]×N + [ServerTs:8]
                    // TotalLen 大端序，含自身，最小值 10（N=0）
                    val data = accumulator.toByteArray()
                    var pos = 0
                    var lastValidMin = 0L
                    var lastValidMax = 0L
                    var hasValidInBatch = false
                    while (pos < data.size) {
                        if (data.size - pos < 2) break
                        val frameLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                        if (frameLen < 10) { pos++; continue }
                        if (data.size - pos < frameLen) break
                        val now = System.currentTimeMillis()
                        val serverTs = java.nio.ByteBuffer.wrap(data, pos + frameLen - 8, 8).long
                        val entryCount = (frameLen - 10) / 9

                        for (i in 0 until entryCount) {
                            val entryOffset = pos + 2 + i * 9
                            val seq = data[entryOffset].toInt() and 0xFF
                            val udpTs = java.nio.ByteBuffer.wrap(data, entryOffset + 1, 8).long
                            val clientSentTime = packetTimestampMap[seq]
                            val drift = clientSentTime - udpTs
                            val minLatency = hbSendTimestamp - serverTs - drift
                            val maxLatency = now - serverTs - drift
                            Log.d(TAG, "Seq=$seq min=${minLatency}ms max=${maxLatency}ms serverTs=$serverTs")
                            if (minLatency >= LATENCY_INVALID_THRESHOLD) {
                                val clampedMin = maxOf(0L, minLatency)
                                val clampedMax = maxOf(0L, maxLatency)
                                val record = LatencyRecord(seq = seq, minLatency = clampedMin, maxLatency = clampedMax)
                                if (recordEnabled) {
                                    synchronized(latencyRecords) { latencyRecords.add(record) }
                                    try {
                                        latencyLogFile?.appendText("${record.seq}\t${record.minLatency}\t${record.maxLatency}\n")
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Failed to write latency record: ${e.message}")
                                    }
                                }
                                lastValidMin = clampedMin
                                lastValidMax = clampedMax
                                hasValidInBatch = true
                            }
                        }

                        pos += frameLen
                    }
                    if (hasValidInBatch) {
                        onLatencyUpdated?.invoke(lastValidMin, lastValidMax)
                    }
                    accumulator.reset()
                    if (pos < data.size) accumulator.write(data, pos, data.size - pos)
                }
                heartbeatJob.cancel()
            } catch (e: Exception) {
                Log.e(TAG, "TCP Control Loop Error: ${e.message}")
            }
            if (isConnected) stop()
        }
    }

    fun getLatencyRecords(): List<LatencyRecord> = synchronized(latencyRecords) { latencyRecords.toList() }

    fun exportLatencyRecords(): String {
        val records = getLatencyRecords()
        if (records.isEmpty()) return "暂无延迟记录"
        val sb = StringBuilder()
        sb.appendLine("延迟记录 (共${records.size}条)")
        sb.appendLine("包序号\t最小(ms)\t最大(ms)")
        records.forEach { sb.appendLine("${it.seq}\t${it.minLatency}\t${it.maxLatency}") }
        val allMin = records.minOf { it.minLatency }
        val allMax = records.maxOf { it.maxLatency }
        sb.appendLine("---")
        sb.appendLine("全局最小: ${allMin}ms  全局最大: ${allMax}ms")
        return sb.toString()
    }

    private fun audioCaptureLoop(bufferSize: Int) {
        val buffer = ByteArray(bufferSize)
        val fragmentLimit = mtu - 100
        // 对齐到8字节: 计算最大 payload 长度
        val maxPayloadSize = (fragmentLimit - 1) / 8 * 8

        while (isCapturing && !Thread.interrupted() && isConnected) {
            try {
                val bytesRead = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                if (bytesRead > 0) {
                    var offset = 0
                    while (offset < bytesRead) {
                        val remaining = bytesRead - offset
                        val chunkSize = minOf(remaining, maxPayloadSize)
                        
                        // 每个分片携带 Seq 和 数据
                        val packetData = ByteArray(chunkSize + 1)
                        
                        packetTimestampMap[sequenceNumber.toInt()] = System.currentTimeMillis()
                        packetData[0] = sequenceNumber.toByte()
                        System.arraycopy(buffer, offset, packetData, 1, chunkSize)

                        // 循环递增序号，范围 0x00 - 0xFF
                        sequenceNumber ++

                        val repeatCount = if (isRedundantTransmissionEnabled) 3 else 1
                        repeat(repeatCount) {
                            val packet = DatagramPacket(packetData, packetData.size, serverAddress, udpPort)
                            udpSocket?.send(packet)
                        }
                        
                        offset += chunkSize
                    }
                }
                Thread.yield()
            } catch (e: Exception) {
                break
            }
        }
    }

    private fun releaseAudioRecord() {
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }
}
