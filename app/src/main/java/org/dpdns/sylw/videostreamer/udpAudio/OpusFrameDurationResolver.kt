package org.dpdns.sylw.videostreamer.udpAudio

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log

object OpusFrameDurationResolver {
    private const val TAG = "OpusFrameResolver"
    private val allowedFrameMs = setOf(10, 20, 40)

    fun resolveSupportedFrameMs(
        requestedFrameMs: Int,
        sampleRate: Int = 48000,
        channelCount: Int = 2,
        bitrate: Int = 32000
    ): Int {
        val normalizedFrameMs = requestedFrameMs.takeIf { it in allowedFrameMs } ?: 20
        return try {
            probeSupportedFrameMs(normalizedFrameMs, sampleRate, channelCount, bitrate)
                .takeIf { it in allowedFrameMs && it >= normalizedFrameMs }
                ?: normalizedFrameMs
        } catch (e: Exception) {
            Log.w(TAG, "无法探测 Opus 帧长支持情况，使用配置值: ${e.message}")
            normalizedFrameMs
        }
    }

    private fun probeSupportedFrameMs(
        requestedFrameMs: Int,
        sampleRate: Int,
        channelCount: Int,
        bitrate: Int
    ): Int {
        val requestedFrameBytes = sampleRate * requestedFrameMs / 1000 * channelCount * 2
        val silence = ByteArray(requestedFrameBytes)
        val info = MediaCodec.BufferInfo()
        var codec: MediaCodec? = null

        try {
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_OPUS,
                sampleRate,
                channelCount
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, requestedFrameBytes)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            repeat(6) { frameIndex ->
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                    inputBuffer?.clear()
                    inputBuffer?.put(silence)
                    codec.queueInputBuffer(
                        inputIndex,
                        0,
                        requestedFrameBytes,
                        frameIndex * requestedFrameMs * 1000L,
                        0
                    )
                }

                val firstOutputAfterMs = drainUntilAudioOutput(codec, info)
                if (firstOutputAfterMs != null) {
                    return normalizeFrameMs((frameIndex + 1) * requestedFrameMs)
                }
            }

            return requestedFrameMs
        } finally {
            codec?.let {
                try {
                    it.stop()
                } catch (_: Exception) {
                }
                it.release()
            }
        }
    }

    private fun drainUntilAudioOutput(codec: MediaCodec, info: MediaCodec.BufferInfo): Int? {
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return null
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                outputIndex < 0 -> continue
                else -> {
                    val isCodecConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (!isCodecConfig && info.size > 0) return 0
                }
            }
        }
    }

    private fun normalizeFrameMs(frameMs: Int): Int {
        return allowedFrameMs.minBy { kotlin.math.abs(it - frameMs) }
            .takeIf { frameMs <= it }
            ?: allowedFrameMs.max()
    }
}
