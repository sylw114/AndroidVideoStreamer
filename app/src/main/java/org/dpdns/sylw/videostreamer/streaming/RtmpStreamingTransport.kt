package org.dpdns.sylw.videostreamer.streaming

import org.dpdns.sylw.videostreamer.encoding.AudioCodecConfig
import org.dpdns.sylw.videostreamer.encoding.EncodedAudioFrame
import org.dpdns.sylw.videostreamer.encoding.EncodedVideoFrame
import org.dpdns.sylw.videostreamer.encoding.VideoBitstreamFormat
import org.dpdns.sylw.videostreamer.encoding.VideoCodecConfig
import org.dpdns.sylw.videostreamer.rtmpStreamer.RtmpPusher

/** 将通用编码输出适配到现有 RTMP 推流器。 */
class RtmpStreamingTransport : StreamingTransport {
    override val capabilities = TransportCapabilities(
        displayName = "RTMP",
        urlScheme = "rtmp",
        supportsAudio = true
    )

    override var onConnectionStateChanged: ((Boolean) -> Unit)? = null
    override var onDiagnostics: ((StreamingLatencyDiagnostics) -> Unit)? = null
    override var onError: ((String) -> Unit)? = null

    private var pusher: RtmpPusher? = null

    override fun connect(url: String, description: StreamDescription) {
        check(pusher == null) { "RTMP 传输已经连接" }
        val next = RtmpPusher().apply {
            setVideoParams(
                description.width,
                description.height,
                description.videoBitrate,
                description.frameRate
            )
            setAudioParams(description.audioSampleRate, description.audioChannelCount)
            setAudioEnabled(description.audioEnabled)
            onConnectionStateChanged = { connected ->
                this@RtmpStreamingTransport.onConnectionStateChanged?.invoke(connected)
            }
        }
        pusher = next
        try {
            next.connect(url)
        } catch (error: Exception) {
            pusher = null
            throw error
        }
    }

    override fun disconnect() {
        val active = pusher
        pusher = null
        try {
            active?.disconnect()
        } catch (error: Exception) {
            onError?.invoke("RTMP 断开失败：${error.message}")
        }
    }

    override fun sendVideoConfig(config: VideoCodecConfig) {
        require(config.format == VideoBitstreamFormat.AVCC) { "RTMP 仅接受 AVCC 视频配置" }
        pusher?.sendVideoSpsPps(config.data)
    }

    override fun sendVideoFrame(frame: EncodedVideoFrame) {
        require(frame.format == VideoBitstreamFormat.AVCC) { "RTMP 仅接受 AVCC 视频帧" }
        pusher?.sendVideoData(frame.data, frame.presentationTimeMs, frame.isKeyFrame)
    }

    override fun sendAudioConfig(config: AudioCodecConfig) {
        pusher?.setAudioSpecificConfigAndSend(config.data)
    }

    override fun sendAudioFrame(frame: EncodedAudioFrame) {
        pusher?.sendAudioData(frame.data, frame.presentationTimeMs)
    }
}
