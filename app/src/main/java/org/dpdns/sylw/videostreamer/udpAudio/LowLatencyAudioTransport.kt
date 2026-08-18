package org.dpdns.sylw.videostreamer.udpAudio

enum class AudioTransportProtocol {
    QUIC,
    UDP;

    companion object {
        fun from(value: String?): AudioTransportProtocol =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: QUIC
    }
}

internal data class AudioSessionConfig(
    val sampleRate: Int,
    val channelCount: Int,
    val codec: Int,
    val frameMs: Int,
    val opusBitrate: Int
)

internal data class AudioEndpoint(
    val host: String,
    val controlPort: Int,
    val mediaPort: Int,
    val redundantTransmission: Boolean
)

internal data class AudioLatencyRange(
    val sequence: Int,
    val minimumMs: Long,
    val maximumMs: Long
)

internal data class AudioTransportCallbacks(
    val onLatency: (AudioLatencyRange) -> Unit,
    val onDisconnected: () -> Unit,
    val onError: (String) -> Unit
)

/** 音频采集和编码只依赖此接口，不感知 TCP、UDP 或 QUIC 的连接细节。 */
internal interface LowLatencyAudioTransport {
    val maximumPayloadSize: Int

    fun connect(
        endpoint: AudioEndpoint,
        config: AudioSessionConfig,
        callbacks: AudioTransportCallbacks
    )

    fun send(sequence: Int, payload: ByteArray, sentAtEpochMs: Long)

    fun close()
}
