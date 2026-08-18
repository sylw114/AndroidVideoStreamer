package org.dpdns.sylw.videostreamer.encoding

/** 编码器输出的视频格式。当前所有传输都明确消费 AVCC，而不是隐式猜测字节布局。 */
enum class VideoBitstreamFormat {
    AVCC
}

/** AVCDecoderConfigurationRecord（SPS/PPS）。 */
data class VideoCodecConfig(
    val data: ByteArray,
    val format: VideoBitstreamFormat = VideoBitstreamFormat.AVCC
)

/** 一帧已经编码完成的视频访问单元。 */
data class EncodedVideoFrame(
    val data: ByteArray,
    val presentationTimeUs: Long,
    val isKeyFrame: Boolean,
    val captureTimeNs: Long,
    val encodedTimeNs: Long,
    val format: VideoBitstreamFormat = VideoBitstreamFormat.AVCC
) {
    val presentationTimeMs: Long
        get() = presentationTimeUs / 1_000L
}

/** MPEG-4 AudioSpecificConfig。 */
data class AudioCodecConfig(val data: ByteArray)

/** 不带 ADTS 头的 AAC 原始访问单元。 */
data class EncodedAudioFrame(
    val data: ByteArray,
    val presentationTimeUs: Long,
    val captureTimeNs: Long,
    val encodedTimeNs: Long
) {
    val presentationTimeMs: Long
        get() = presentationTimeUs / 1_000L
}

/**
 * 编码器的纯输出端。
 *
 * 此接口故意不包含 URL、连接或断开方法；编码器只生产媒体，传输生命周期由会话层拥有。
 */
interface EncodedMediaOutput {
    fun sendVideoConfig(config: VideoCodecConfig)
    fun sendVideoFrame(frame: EncodedVideoFrame)
    fun sendAudioConfig(config: AudioCodecConfig)
    fun sendAudioFrame(frame: EncodedAudioFrame)
}

interface StreamingEncoder {
    val effectiveFrameRate: Int
    var onError: ((String) -> Unit)?
    var onInfo: ((String) -> Unit)?
    var onVideoFrameRateMeasured: ((VideoFrameRateDiagnostics) -> Unit)?

    fun prepare()
    fun start()
    fun stop()
    fun isRunning(): Boolean
    fun submitExternalAudioData(pcmData: ByteArray, size: Int, timestampNs: Long)
}

data class VideoFrameRateDiagnostics(
    val requestedFps: Int,
    val actualFps: Double,
    val wallClockFps: Double
)
