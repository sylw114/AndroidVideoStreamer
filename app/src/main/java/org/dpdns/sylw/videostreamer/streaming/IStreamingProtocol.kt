package org.dpdns.sylw.videostreamer.streaming

import android.view.Surface
import org.dpdns.sylw.videostreamer.encoding.VideoFrameRateDiagnostics

/** UI/业务层使用的通用推流会话接口。 */
interface IStreamingProtocol {
    fun start(url: String, config: StreamingConfig)
    fun stop()
    fun isStreaming(): Boolean
    fun switchUrl(newUrl: String)
    fun updateBitrate(bitrate: Int)
    fun updateFrameRate(frameRate: Int)
    fun submitExternalAudioData(pcmData: ByteArray, size: Int, timestampNs: Long)
    fun getCurrentUrl(): String?
    fun release()

    var onStreamingStateChanged: ((Boolean) -> Unit)?
    var onError: ((String) -> Unit)?
    var onInfo: ((String) -> Unit)?
    var onVideoFrameRateMeasured: ((VideoFrameRateDiagnostics) -> Unit)?
    var onLatencyMeasured: ((StreamingLatencyDiagnostics) -> Unit)?

    /** 编码器输入 Surface 就绪后交给摄像头或录屏模块。 */
    val onSurfaceReady: (Surface) -> Unit
}

data class StreamingLatencyDiagnostics(
    val protocol: String,
    val transport: String,
    val latencyMinMs: Double?,
    val latencyMaxMs: Double?,
    val encodeMinMs: Double?,
    val encodeMaxMs: Double?,
    val clockRttMs: Double?,
    val packetLossRatio: Double,
    val recoveredFragments: Long,
    val droppedFrames: Long,
    val bitrateKbps: Int,
    val framesPerSecond: Double
)

data class StreamingConfig(
    val width: Int = 1920,
    val height: Int = 1080,
    val dpi: Int = 320,
    val videoBitrate: Int = 2_500_000,
    val frameRate: Int = 30,
    // LiveSuite QUIC 视频使用可靠流；UDP fallback 仍使用短 GOP 以便丢包后尽快恢复。
    val iFrameInterval: Int = 1,
    val videoMode: String = "CBR",
    val videoQuality: Int = 70,
    /** Surface 输入长时间没有新画面时，要求编码器重复上一帧；仅录屏源需要。 */
    val repeatPreviousFrameAfterUs: Long? = null,
    val useAudio: Boolean = true,
    val onAudioCaptureStart: (() -> Unit)? = null,
    val onAudioCaptureStop: (() -> Unit)? = null,
    val audioSampleRate: Int = 48_000,
    val audioChannelCount: Int = 2,
    val audioBitrate: Int = 128_000,
    val audioGroupDurationUs: Long = 0L,
    val requireHardwareVideoEncoder: Boolean = false
)
