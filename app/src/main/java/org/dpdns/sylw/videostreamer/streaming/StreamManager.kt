package org.dpdns.sylw.videostreamer.streaming

import android.view.Surface
import org.dpdns.sylw.videostreamer.encoding.VideoFrameRateDiagnostics
import org.dpdns.sylw.videostreamer.streaming.livesuite.LiveSuiteLowLatencyTransport

/** UI 层的协议选择与配置入口。 */
class StreamManager(
    private val onSurfaceReady: (Surface) -> Unit,
    private var currentConfig: StreamingConfig = StreamingConfig()
) {
    private enum class ProtocolType { RTMP, QUIC, UDP }

    private var protocol: IStreamingProtocol? = null

    var onStreamingStateChanged: ((Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onInfo: ((String) -> Unit)? = null
    var onVideoFrameRateMeasured: ((VideoFrameRateDiagnostics) -> Unit)? = null
    var onLatencyMeasured: ((StreamingLatencyDiagnostics) -> Unit)? = null

    fun init(protocol: String = "RTMP") {
        this.protocol?.release()
        val type = runCatching { ProtocolType.valueOf(protocol.uppercase()) }
            .getOrDefault(ProtocolType.RTMP)

        this.protocol = when (type) {
            ProtocolType.RTMP -> RtmpStreamingProtocol(onSurfaceReady)
            ProtocolType.QUIC -> LiveSuiteStreamingProtocol(
                onSurfaceReady,
                LiveSuiteLowLatencyTransport.Transport.QUIC
            )
            ProtocolType.UDP -> LiveSuiteStreamingProtocol(
                onSurfaceReady,
                LiveSuiteLowLatencyTransport.Transport.UDP
            )
        }.apply {
            onStreamingStateChanged = { streaming ->
                this@StreamManager.onStreamingStateChanged?.invoke(streaming)
            }
            onError = { message -> this@StreamManager.onError?.invoke(message) }
            onInfo = { message -> this@StreamManager.onInfo?.invoke(message) }
            onVideoFrameRateMeasured = { diagnostics ->
                this@StreamManager.onVideoFrameRateMeasured?.invoke(diagnostics)
            }
            onLatencyMeasured = { diagnostics ->
                this@StreamManager.onLatencyMeasured?.invoke(diagnostics)
            }
        }
    }

    fun setVideoParams(
        width: Int,
        height: Int,
        bitrate: Int,
        frameRate: Int = 30,
        iFrameInterval: Int = 1,
        videoMode: String = "CBR",
        videoQuality: Int = 70,
        repeatPreviousFrameAfterUs: Long? = null
    ) {
        currentConfig = currentConfig.copy(
            width = width,
            height = height,
            videoBitrate = bitrate,
            frameRate = frameRate,
            iFrameInterval = iFrameInterval,
            videoMode = videoMode,
            videoQuality = videoQuality,
            repeatPreviousFrameAfterUs = repeatPreviousFrameAfterUs
        )
    }

    fun setAudioCaptureCallbacks(onStart: () -> Unit, onStop: () -> Unit) {
        currentConfig = currentConfig.copy(
            onAudioCaptureStart = onStart,
            onAudioCaptureStop = onStop
        )
    }

    fun setAudioGroupDurationUs(durationUs: Long) {
        currentConfig = currentConfig.copy(audioGroupDurationUs = durationUs.coerceAtLeast(0L))
    }

    fun startStreaming(url: String) {
        val activeProtocol = protocol
        if (activeProtocol == null) {
            onError?.invoke("推流协议未初始化")
            return
        }
        activeProtocol.start(url, currentConfig)
    }

    fun stopStreaming() {
        protocol?.stop()
    }

    fun isStreaming(): Boolean = protocol?.isStreaming() ?: false

    fun submitExternalAudioData(pcmData: ByteArray, size: Int, timestampNs: Long) {
        protocol?.submitExternalAudioData(pcmData, size, timestampNs)
    }

    fun updateResolution(newWidth: Int, newHeight: Int) {
        if (!isStreaming()) return
        val url = getCurrentUrl()?.takeIf { it.isNotBlank() } ?: return
        stopStreaming()
        currentConfig = currentConfig.copy(width = newWidth, height = newHeight)
        startStreaming(url)
    }

    fun getCurrentUrl(): String? = protocol?.getCurrentUrl()

    fun release() {
        protocol?.release()
        protocol = null
    }
}
