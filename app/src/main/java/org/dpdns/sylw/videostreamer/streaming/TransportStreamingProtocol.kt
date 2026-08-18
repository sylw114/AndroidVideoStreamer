package org.dpdns.sylw.videostreamer.streaming

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.Surface
import org.dpdns.sylw.videostreamer.encoding.MediaCodecEncoder
import org.dpdns.sylw.videostreamer.encoding.StreamingEncoder
import org.dpdns.sylw.videostreamer.encoding.VideoFrameRateDiagnostics
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 编码器与网络传输的唯一生命周期所有者。
 *
 * 所有协议共用这套状态机；编码器只输出媒体，传输只连接和发送，二者都不再互相
 * 创建或停止。这样可以保证启动失败、主动停止和异步断线都只清理一次。
 */
open class TransportStreamingProtocol(
    final override val onSurfaceReady: (Surface) -> Unit,
    private val transportFactory: () -> StreamingTransport
) : IStreamingProtocol {

    private enum class SessionState { IDLE, STARTING, RUNNING, STOPPING }

    private val lifecycleLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var state = SessionState.IDLE
    private var generation = 0L
    private var encoder: StreamingEncoder? = null
    private var transport: StreamingTransport? = null
    private var currentUrl: String? = null
    private var currentConfig: StreamingConfig? = null
    private var audioCaptureStarted = false

    override var onStreamingStateChanged: ((Boolean) -> Unit)? = null
    override var onError: ((String) -> Unit)? = null
    override var onInfo: ((String) -> Unit)? = null
    override var onVideoFrameRateMeasured: ((VideoFrameRateDiagnostics) -> Unit)? = null
    override var onLatencyMeasured: ((StreamingLatencyDiagnostics) -> Unit)? = null

    @SuppressLint("MissingPermission")
    override fun start(url: String, config: StreamingConfig) {
        val nextTransport = try {
            transportFactory().also { validateUrl(url, it.capabilities.urlScheme) }
        } catch (error: Exception) {
            reportStartFailure("启动推流失败：${error.message}")
            return
        }

        val effectiveConfig = if (config.useAudio && !nextTransport.capabilities.supportsAudio) {
            dispatch {
                onInfo?.invoke("${nextTransport.capabilities.displayName} 当前只传输视频，已关闭音频编码")
            }
            config.copy(useAudio = false)
        } else {
            config
        }

        var started = false
        var failureMessage: String? = null
        synchronized(lifecycleLock) {
            stopLocked()

            val token = ++generation
            state = SessionState.STARTING
            currentUrl = url
            currentConfig = effectiveConfig
            transport = nextTransport
            wireTransport(nextTransport, token)

            try {
                val nextEncoder = createEncoder(effectiveConfig, nextTransport)
                encoder = nextEncoder
                wireEncoder(nextEncoder, token)

                // 先准备 MediaCodec 以得到真实可用帧率，但此时尚未把 Surface 交给采集端。
                nextEncoder.prepare()
                connectOffMainThread(
                    nextTransport,
                    url,
                    effectiveConfig.toDescription(nextEncoder.effectiveFrameRate)
                )
                nextEncoder.start()
                check(nextEncoder.isRunning()) { "编码器未能进入运行状态" }

                if (effectiveConfig.useAudio) {
                    effectiveConfig.onAudioCaptureStart?.invoke()
                    audioCaptureStarted = true
                }
                state = SessionState.RUNNING
                started = true
            } catch (error: Exception) {
                state = SessionState.STOPPING
                cleanupActiveLocked()
                currentUrl = null
                currentConfig = null
                state = SessionState.IDLE
                generation++
                failureMessage = "启动 ${nextTransport.capabilities.displayName} 推流失败：${error.message}"
            }
        }

        if (started) {
            dispatch { onStreamingStateChanged?.invoke(true) }
        } else {
            reportStartFailure(failureMessage ?: "启动推流失败")
        }
    }

    override fun stop() {
        val wasActive = synchronized(lifecycleLock) {
            val active = state != SessionState.IDLE || encoder != null || transport != null
            stopLocked()
            active
        }
        if (wasActive) dispatch { onStreamingStateChanged?.invoke(false) }
    }

    override fun isStreaming(): Boolean = synchronized(lifecycleLock) {
        state == SessionState.RUNNING && encoder?.isRunning() == true
    }

    override fun switchUrl(newUrl: String) {
        val config = synchronized(lifecycleLock) { currentConfig }
        if (config == null) {
            dispatch { onError?.invoke("无法切换地址：当前没有推流配置") }
            return
        }
        start(newUrl, config)
    }

    override fun updateBitrate(bitrate: Int) {
        restartWith { it.copy(videoBitrate = bitrate) }
    }

    override fun updateFrameRate(frameRate: Int) {
        restartWith { it.copy(frameRate = frameRate) }
    }

    override fun submitExternalAudioData(pcmData: ByteArray, size: Int, timestampNs: Long) {
        encoder?.submitExternalAudioData(pcmData, size, timestampNs)
    }

    override fun getCurrentUrl(): String? = synchronized(lifecycleLock) { currentUrl }

    override fun release() {
        stop()
        onStreamingStateChanged = null
        onError = null
        onInfo = null
        onVideoFrameRateMeasured = null
        onLatencyMeasured = null
    }

    private fun createEncoder(config: StreamingConfig, output: StreamingTransport): StreamingEncoder =
        MediaCodecEncoder(
            width = config.width,
            height = config.height,
            videoBitrate = config.videoBitrate,
            frameRate = config.frameRate,
            iFrameInterval = config.iFrameInterval,
            videoMode = config.videoMode,
            videoQuality = config.videoQuality,
            repeatPreviousFrameAfterUs = config.repeatPreviousFrameAfterUs,
            useAudio = config.useAudio,
            audioSampleRate = config.audioSampleRate,
            audioChannelCount = config.audioChannelCount,
            audioBitrate = config.audioBitrate,
            requireHardwareVideoEncoder = config.requireHardwareVideoEncoder,
            onSurfaceReady = onSurfaceReady,
            output = output
        )

    private fun wireEncoder(activeEncoder: StreamingEncoder, token: Long) {
        activeEncoder.onError = { message -> requestFailure(token, message) }
        activeEncoder.onInfo = { message ->
            if (isCurrent(token)) dispatch { onInfo?.invoke(message) }
        }
        activeEncoder.onVideoFrameRateMeasured = { diagnostics ->
            if (isCurrent(token)) dispatch { onVideoFrameRateMeasured?.invoke(diagnostics) }
        }
    }

    private fun wireTransport(activeTransport: StreamingTransport, token: Long) {
        activeTransport.onConnectionStateChanged = { connected ->
            if (!connected) {
                requestFailure(token, "${activeTransport.capabilities.displayName} 连接已断开")
            }
        }
        activeTransport.onDiagnostics = { diagnostics ->
            if (isCurrent(token)) dispatch { onLatencyMeasured?.invoke(diagnostics) }
        }
        activeTransport.onError = { message -> requestFailure(token, message) }
    }

    private fun requestFailure(token: Long, message: String) {
        Thread({
            val handled = synchronized(lifecycleLock) {
                if (token != generation ||
                    (state != SessionState.STARTING && state != SessionState.RUNNING)
                ) {
                    false
                } else {
                    stopLocked()
                    true
                }
            }
            if (handled) {
                dispatch {
                    onStreamingStateChanged?.invoke(false)
                    onError?.invoke(message)
                }
            }
        }, "StreamingSession-failure").start()
    }

    private fun stopLocked() {
        if (state == SessionState.IDLE && encoder == null && transport == null) return

        state = SessionState.STOPPING
        generation++
        cleanupActiveLocked()
        currentUrl = null
        currentConfig = null
        state = SessionState.IDLE
    }

    private fun cleanupActiveLocked() {
        val activeEncoder = encoder
        val activeTransport = transport
        val config = currentConfig
        encoder = null
        transport = null

        activeEncoder?.onError = null
        activeTransport?.onConnectionStateChanged = null
        activeTransport?.onError = null
        activeTransport?.onDiagnostics = null

        if (audioCaptureStarted) {
            try {
                config?.onAudioCaptureStop?.invoke()
            } catch (_: Exception) {
                // 资源清理继续执行。
            }
            audioCaptureStarted = false
        }
        try {
            activeEncoder?.stop()
        } catch (_: Exception) {
            // 资源清理继续执行。
        }
        try {
            activeTransport?.disconnect()
        } catch (_: Exception) {
            // 资源清理继续执行。
        }
    }

    private fun restartWith(update: (StreamingConfig) -> StreamingConfig) {
        val snapshot = synchronized(lifecycleLock) {
            val config = currentConfig ?: return
            Triple(currentUrl, update(config), state == SessionState.RUNNING)
        }
        if (snapshot.third && snapshot.first != null) {
            start(snapshot.first!!, snapshot.second)
        } else {
            synchronized(lifecycleLock) { currentConfig = snapshot.second }
        }
    }

    private fun isCurrent(token: Long): Boolean = synchronized(lifecycleLock) {
        token == generation && state != SessionState.IDLE && state != SessionState.STOPPING
    }

    private fun connectOffMainThread(
        activeTransport: StreamingTransport,
        url: String,
        description: StreamDescription
    ) {
        val failure = AtomicReference<Throwable?>()
        val finished = CountDownLatch(1)
        val connectionThread = Thread({
            try {
                activeTransport.connect(url, description)
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                finished.countDown()
            }
        }, "${activeTransport.capabilities.displayName}-connect")
        connectionThread.start()

        val completed = try {
            finished.await(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (error: InterruptedException) {
            connectionThread.interrupt()
            Thread.currentThread().interrupt()
            throw error
        }
        if (!completed) {
            connectionThread.interrupt()
            throw SocketTimeoutException("协议连接超时")
        }
        failure.get()?.let { error ->
            if (error is Exception) throw error
            throw IllegalStateException(error.message, error)
        }
    }

    private fun validateUrl(url: String, expectedScheme: String) {
        require(url.isNotBlank()) { "推流地址为空" }
        val scheme = URI(url).scheme?.lowercase() ?: throw IllegalArgumentException("推流地址缺少协议")
        require(scheme == expectedScheme) { "当前协议需要 $expectedScheme:// 地址" }
    }

    private fun StreamingConfig.toDescription(actualFrameRate: Int) = StreamDescription(
        width = width,
        height = height,
        videoBitrate = videoBitrate,
        frameRate = actualFrameRate,
        audioEnabled = useAudio,
        audioSampleRate = audioSampleRate,
        audioChannelCount = audioChannelCount,
        audioBitrate = audioBitrate,
        audioGroupDurationUs = audioGroupDurationUs
    )

    private fun reportStartFailure(message: String) {
        dispatch {
            onStreamingStateChanged?.invoke(false)
            onError?.invoke(message)
        }
    }

    private fun dispatch(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private companion object {
        const val CONNECTION_TIMEOUT_SECONDS = 13L
    }
}
