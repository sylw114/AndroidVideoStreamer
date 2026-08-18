package org.dpdns.sylw.videostreamer.streaming

import org.dpdns.sylw.videostreamer.encoding.EncodedMediaOutput

data class StreamDescription(
    val width: Int,
    val height: Int,
    val videoBitrate: Int,
    val frameRate: Int,
    val audioEnabled: Boolean,
    val audioSampleRate: Int,
    val audioChannelCount: Int,
    val audioBitrate: Int,
    /** AudioRecord 一次采集并交给编码器的 PCM 组覆盖的时长。 */
    val audioGroupDurationUs: Long = 0L
)

data class TransportCapabilities(
    val displayName: String,
    val urlScheme: String,
    val supportsAudio: Boolean
)

/** 网络传输只负责会话连接和消费已经编码好的媒体。 */
interface StreamingTransport : EncodedMediaOutput {
    val capabilities: TransportCapabilities

    var onConnectionStateChanged: ((Boolean) -> Unit)?
    var onDiagnostics: ((StreamingLatencyDiagnostics) -> Unit)?
    var onError: ((String) -> Unit)?

    fun connect(url: String, description: StreamDescription)
    fun disconnect()
}
