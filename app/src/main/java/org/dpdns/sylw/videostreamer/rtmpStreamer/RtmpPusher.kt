package org.dpdns.sylw.videostreamer.rtmpStreamer

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 修复后的 RTMP 推流器
 * 专注于视频推流稳定性，修复了握手、连接命令及分块封装逻辑
 */
class RtmpPusher {
    companion object {
        private const val TAG = "RtmpPusher"

        // RTMP 消息类型
        private const val MSG_SET_CHUNK_SIZE = 0x01
        private const val MSG_ABORT_MESSAGE = 0x02
        private const val MSG_ACKNOWLEDGE = 0x03
        private const val MSG_USER_CONTROL = 0x04
        private const val MSG_WINDOW_ACK_SIZE = 0x05
        private const val MSG_SET_PEER_BANDWIDTH = 0x06
        private const val MSG_AUDIO = 0x08
        private const val MSG_VIDEO = 0x09
        private const val MSG_AMF0_DATA = 0x12
        private const val MSG_AMF0_COMMAND = 0x14

        // 🔥 低延迟优化：减小 Chunk Size 以减少头部阻塞(HOL blocking)
        // 小chunk允许音频帧快速穿插在视频帧之间
        private const val DEFAULT_CHUNK_SIZE = 4096  // 4KB (从64KB降低)
        private const val TARGET_CHUNK_SIZE = 1024   // 1KB 用于低延迟推流
    }

    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null

    private var serverHost: String = ""
    private var serverPort: Int = 1935
    private var appName: String = ""
    private var streamName: String = ""
    private var serverUrl: String = ""

    private var streamId: Int = 0 // 由 createStream 响应分配
    private var transactionId = 0.0
    
    // 🔥 Chunk Size 管理（双向独立）
    private var outChunkSize = DEFAULT_CHUNK_SIZE  // 客户端发送给服务器的 chunk size
    private var inChunkSize = DEFAULT_CHUNK_SIZE   // 服务器发送给客户端的 chunk size

    private var isConnected = false
    private var initialVideoPts = -1L
    private var handshakeTimestamp = 0L // 握手时的时间戳

    // 视频参数
    private var videoWidth = 1920
    private var videoHeight = 1080
    private var videoBitrate = 2500000
    private var frameRate = 30
    
    // 音频参数
    private var audioSampleRate = 48000
    private var audioChannelCount = 2
    private var initialAudioPts = -1L
    private var audioSpecificConfig: ByteArray? = null // AAC config data (from MediaCodec csd-0)
    private var audioSequenceHeaderSent = false
    private var audioOutputFrameCount = 0L // 已发送的音频帧数
    
    /**
     * 🔥 设置 AudioSpecificConfig（从 MediaCodec 音频编码器的 csd-0 提取）
     */
    fun setAudioSpecificConfig(asc: ByteArray) {
        audioSpecificConfig = asc
//        Log.d(TAG, "🎼 AudioSpecificConfig set from encoder: ${asc.size} bytes")
//        Log.d(TAG, "   ASC: ${asc.joinToString(" ") { "%02X".format(it) }}")
    }
    
    /**
     * 设置并立即发送 AAC Sequence Header
     * 🔥 关键修复：在编码器输出 CODEC_CONFIG 时立即发送，而不是等第一个数据帧
     */
    fun setAudioSpecificConfigAndSend(asc: ByteArray) {
        audioSpecificConfig = asc
//        Log.d(TAG, "🎼 AudioSpecificConfig set from encoder: ${asc.size} bytes")
//        Log.d(TAG, "   ASC: ${asc.joinToString(" ") { "%02X".format(it) }}")

        // 🔥 立即发送 AAC Sequence Header
        if (!audioSequenceHeaderSent) {
            sendAudioSpecificConfig()
            audioSequenceHeaderSent = true
//            Log.d(TAG, "✅ AAC Sequence Header sent immediately!")
            
            // 🔥 关键修复：不再重发 metadata！
            // 原因：FFmpeg 不支持 AAC 参数动态变更，重发 metadata 会导致解码器报错
            // "This decoder does not support parameter changes"
            // 正确的做法：初始 metadata 就必须包含完整的音频信息
//            Log.d(TAG, "⚠️ Skipping metadata resend to avoid FFmpeg PARAM_CHANGE error")
        }
    }
    
    // 连接状态回调
    var onConnectionStateChanged: ((Boolean) -> Unit)? = null

    fun connect(url: String) {
        try {
//            Log.d(TAG, "Connecting to: $url")
            parseRtmpUrl(url)

            socket = Socket()
            socket?.connect(InetSocketAddress(serverHost, serverPort), 10000)
            socket?.tcpNoDelay = true
            outputStream = socket?.outputStream
            inputStream = socket?.inputStream

            if (outputStream == null || inputStream == null) throw IOException("Socket error")

            handshake()
            
            // 握手成功后设置连接状态为 true，开始发送控制消息
            isConnected = true
                        
            // 0. Set chunk size BEFORE connect (recommended by spec)
            // Send on CSID=2 (protocol control channel) - ONLY ONCE
            sendSetChunkSize(TARGET_CHUNK_SIZE)
            // 🔥 关键修复：同时更新 outChunkSize，确保我们发送的 chunk size 与告知服务器的一致
            outChunkSize = TARGET_CHUNK_SIZE
                        
            // 1. Send window acknowledgement size (server controls flow)
            sendWindowAckSize(2500000)
                        
            // 2. Send Set Peer Bandwidth (dynamic)
            sendSetPeerBandwidth(2500000, 2) // Dynamic bandwidth
                        
            // 3. Send connect command
            rtmpConnect()
            
            // 4. Create stream and publish
            createStream()
            publish()

            // 5. Send Stream Begin User Control Message (REQUIRED after publish)
            // This signals that the stream is ready for playback
            sendStreamBegin(streamId)

            // 6. Send metadata
            sendMetadata()
            
            // 🔥 启动发送线程（在所有握手和控制消息完成后）
            startSendThread()

            onConnectionStateChanged?.invoke(true)
//            Log.d(TAG, "RTMP Connection established")
        } catch (e: Exception) {
//            Log.e(TAG, "Connect failed: ${e.message}")
            // 🔥 关键修复：确保连接失败时通知状态变化
            if (isConnected) {
                isConnected = false
                onConnectionStateChanged?.invoke(false)
            }
            disconnect()
            throw e
        }
    }

    private fun handshake() {
        val startTime = System.currentTimeMillis()
        
        // C0: Version 3
        outputStream?.write(0x03)
        
        // C1: 1536 bytes
        // Format: 4 bytes timestamp (milliseconds) + 4 bytes zero + 1528 bytes random
        val c1Timestamp = (startTime and 0xFFFFFFFFL).toInt() // Use milliseconds for better precision
        val c1 = ByteBuffer.allocate(1536).apply {
            order(ByteOrder.BIG_ENDIAN)
            putInt(c1Timestamp) // 4 bytes timestamp (milliseconds, can be any value)
            putInt(0) // 4 bytes zero
            // Fill remaining 1528 bytes with random data using SecureRandom
            val randomData = ByteArray(1528)
            java.security.SecureRandom().nextBytes(randomData) // Cryptographically strong random
            put(randomData)
        }.array()
        outputStream?.write(c1)
        outputStream?.flush()

        // Read S0S1S2 with proper timeout handling
        val s0s1s2 = ByteArray(1 + 1536 + 1536)
        var totalRead = 0
        val timeout = 5000L // 5 seconds timeout
        val readStartTime = System.currentTimeMillis()
        
        while (totalRead < s0s1s2.size && (System.currentTimeMillis() - readStartTime) < timeout) {
            val bytesRead = inputStream?.read(s0s1s2, totalRead, s0s1s2.size - totalRead) ?: -1
            if (bytesRead == -1) {
                throw IOException("Handshake: Server closed connection before sending complete S0S1S2")
            }
            totalRead += bytesRead
        }
        
        if (totalRead < s0s1s2.size) {
            throw IOException("Handshake timeout: did not receive complete S0S1S2 in ${timeout}ms")
        }

        // Validate S0 (version)
        if (s0s1s2[0] != 0x03.toByte()) {
//            Log.w(TAG, "Handshake: Server returned non-standard version: ${s0s1s2[0]}. Expected 3.")
            // According to spec, should downgrade to version 3 or abort
            // For compatibility, we continue but log the warning
        }

        // Parse S1 to extract timestamp for C2
        val s1Timestamp = ByteBuffer.wrap(s0s1s2, 1, 4).order(ByteOrder.BIG_ENDIAN).int
        
        // C2: 1536 bytes
        // Format: 4 bytes S1 timestamp (Time) + 4 bytes current time when received S1 (Time2) + 1528 bytes S1 random echo
        val receivedS1Time = System.currentTimeMillis()
        val c2 = ByteBuffer.allocate(1536).apply {
            order(ByteOrder.BIG_ENDIAN)
            putInt(s1Timestamp) // Time: echo S1's timestamp (required by spec)
            putInt((receivedS1Time and 0xFFFFFFFFL).toInt()) // Time2: local time when received S1 (NOT when sent C1!)
            put(s0s1s2, 9, 1528) // Random echo: copy S1's random data
        }.array()
        outputStream?.write(c2)
        outputStream?.flush()
        
//        Log.d(TAG, "Handshake complete (C1 ts=$c1Timestamp, S1 ts=$s1Timestamp, received at $receivedS1Time)")
    }

    private fun rtmpConnect() {
        transactionId = 1.0
        val buffer = ByteBuffer.allocate(1024).apply {
            order(ByteOrder.BIG_ENDIAN)
            putString("connect")
            putAmfNumber(transactionId)

            // Command Object (AMF0 Object)
            put(0x03.toByte())
            putProperty("app", appName)
            putProperty("flashVer", "FMLE/3.0 (compatible; FMSc/1.0)")
            putProperty("tcUrl", "rtmp://$serverHost:$serverPort/$appName")
            putProperty("fpad", false)
            putProperty("capabilities", 15.0)
            putProperty("audioCodecs", 3191.0) // Support AAC (bitmask: 1<<10 + 1<<9 + ... = 3191)
            putProperty("videoCodecs", 252.0)
            putProperty("videoFunction", 1.0)
            putProperty("objectEncoding", 0.0)
            // End of object marker (0x00 0x00 0x09)
            putShort(0) // Empty property name marks end
            put(0x09.toByte()) // Object end type marker
        }
//        Log.d(TAG, "Sending connect command to app: $appName")
        sendPacket(3, 0, 0, MSG_AMF0_COMMAND, buffer.array().copyOf(buffer.position()))
        val response = readResponse("_result", transactionId)
//        Log.d(TAG, "Connect command successful")
    }

    private fun createStream() {
        transactionId++
        val buffer = ByteBuffer.allocate(128).apply {
            order(ByteOrder.BIG_ENDIAN)
            putString("createStream")
            putAmfNumber(transactionId)
            put(0x05.toByte()) // Null
        }
//        Log.d(TAG, "Sending createStream command")
        sendPacket(3, 0, 0, MSG_AMF0_COMMAND, buffer.array().copyOf(buffer.position()))

        // 解析响应获取 streamId
        val response = readResponse("_result", transactionId)
        val amf = ByteBuffer.wrap(response).order(ByteOrder.BIG_ENDIAN)
        skipAmfValue(amf) // Skip command name
        skipAmfValue(amf) // Skip transaction ID
        skipAmfValue(amf) // Skip command object
        if (amf.hasRemaining() && amf.get() == 0x00.toByte()) {
            streamId = amf.double.toInt()
//            Log.d(TAG, "Stream created with ID: $streamId")
        } else {
//            Log.w(TAG, "Failed to parse stream ID from createStream response")
        }
    }

    private fun publish() {
        // According to RTMP spec, publish command should use transactionId = 0
        // because server doesn't reply with _result, only sends onStatus
        val buffer = ByteBuffer.allocate(512).apply {
            order(ByteOrder.BIG_ENDIAN)
            // Command name
            putString("publish")
            // Transaction ID = 0 for publish (spec requirement)
            putAmfNumber(0.0)
            // Command object (null)
            put(0x05.toByte())
            // Stream name (as string value)
            val streamNameBytes = streamName.toByteArray(Charsets.UTF_8)
            put(0x02.toByte())  // String type
            putShort(streamNameBytes.size.toShort())
            put(streamNameBytes)
            // Publish type (as string value)
            val publishTypeBytes = "live".toByteArray(Charsets.UTF_8)
            put(0x02.toByte())  // String type
            putShort(publishTypeBytes.size.toShort())
            put(publishTypeBytes)
        }
//        Log.d(TAG, "Sending publish command for stream: $streamName")
        sendPacket(3, streamId, 0, MSG_AMF0_COMMAND, buffer.array().copyOf(buffer.position()))
//        Log.d(TAG, "Publish command sent")
        
        // Wait for onStatus to confirm publish started
        if (readOnStatus()) {
//            Log.d(TAG, "Publish confirmed by server")
        } else {
//            Log.w(TAG, "No publish confirmation received from server")
        }
    }

    private fun sendSetChunkSize(size: Int) {
        val buffer = ByteBuffer.allocate(4).apply {
            order(ByteOrder.BIG_ENDIAN)
            putInt(size and 0x7FFFFFFF)
        }
        sendPacket(2, 0, 0, MSG_SET_CHUNK_SIZE, buffer.array())
        outChunkSize = size
    }

    private fun sendWindowAckSize(size: Int) {
        val buffer = ByteBuffer.allocate(4).apply {
            order(ByteOrder.BIG_ENDIAN)
            putInt(size)
        }
        sendPacket(2, 0, 0, MSG_WINDOW_ACK_SIZE, buffer.array())
//        Log.d(TAG, "Sent Window Acknowledgement Size: $size")
    }

    private fun sendSetPeerBandwidth(bandwidthBytesPerSec: Int, limitType: Int = 2) {
        val buffer = ByteBuffer.allocate(5).apply {
            order(ByteOrder.BIG_ENDIAN)
            putInt(bandwidthBytesPerSec)
            put(limitType.toByte())  // 0=hard, 1=soft, 2=dynamic
        }
        sendPacket(2, 0, 0, MSG_SET_PEER_BANDWIDTH, buffer.array())
//        Log.d(TAG, "Sent Set Peer Bandwidth: $bandwidthBytesPerSec bytes/sec, type=$limitType")
    }
    
    /**
     * 发送 Stream Begin 用户控制消息 (User Control Event Type 0)
     * 必须在 publish 成功后发送，通知服务器流已准备好
     */
    private fun sendStreamBegin(streamId: Int) {
        val buffer = ByteBuffer.allocate(6).apply {
            order(ByteOrder.BIG_ENDIAN)
            putShort(0) // Event Type: Stream Begin (0)
            putInt(streamId) // Event Data: Stream ID
        }
        sendPacket(2, 0, 0, MSG_USER_CONTROL, buffer.array())
//        Log.d(TAG, "Sent Stream Begin event for stream $streamId")
    }

    private fun sendMetadata() {
        // 🔥 关键修复：初始 metadata 就必须包含正确的音频信息
        // 不能将音频字段设为 null，否则播放器会认为没有音频流
        val audioDataRate = audioSampleRate * audioChannelCount * 16 / 1000.0 // bits per second

        val buffer = ByteBuffer.allocate(1024).apply {
            order(ByteOrder.BIG_ENDIAN)
            // Command name (String without type marker in command context)
            putString("onMetaData")
            
            // 🔥 关键修复：必须添加 Transaction ID = 0（meta data 不需要 transaction）
            putAmfNumber(0.0)
            
            // ECMA Array format:
            // Type marker (0x08) + count (4 bytes) + properties + end marker
            val propertyCount = 11 // width, height, videocodecid, videodatarate, framerate, duration, encoder, audiocodecid, audiodatarate, audiosamplerate, audiosamplesize

            put(0x08.toByte()) // ECMA Array type marker
            putInt(propertyCount) // Number of elements in associative array
            
            // Write all properties using putProperty (which handles name+value correctly)
            putProperty("width", videoWidth.toDouble())
            putProperty("height", videoHeight.toDouble())
            putProperty("videocodecid", 7.0) // H.264
            putProperty("videodatarate", videoBitrate / 1024.0)
            putProperty("framerate", frameRate.toDouble())
            putProperty("duration", 0.0) // Live stream
            putProperty("encoder", "Android MediaCodec")
            
            // 🔥 关键：音频字段必须完整声明！
            putProperty("audiocodecid", 10.0) // AAC
            putProperty("audiodatarate", audioDataRate)
            putProperty("audiosamplerate", audioSampleRate.toDouble()) // 🔥 新增：采样率
            putProperty("audiosamplesize", 16.0) // 🔥 新增：采样位数 (16-bit)
            putProperty("stereo", if (audioChannelCount >= 2) 1.0 else 0.0) // 🔥 新增：立体声标记

            // End of ECMA Array: 0x00 0x00 0x09
            putShort(0) // Empty string marks end
            put(0x09.toByte()) // Object end marker
        }
        val finalData = buffer.array().copyOf(buffer.position())
//        Log.d(TAG, "📋 Sending metadata: ${videoWidth}x${videoHeight}, bitrate=${videoBitrate}, fps=$frameRate, audio=${audioSampleRate}Hz/${audioChannelCount}ch")
//        Log.d(TAG, "📦 Metadata AMF0 packet (${finalData.size} bytes):")
        // 打印关键字段验证格式
//        Log.d(TAG, "   Bytes 0-10: Command='onMetaData'")
//        Log.d(TAG, "   Bytes 11-18: TransactionID=0 (Number)")
//        Log.d(TAG, "   Byte 19: ECMA Array marker=0x08")
//        Log.d(TAG, "   Bytes 20-23: Property count=11")
        sendPacket(4, streamId, 0, MSG_AMF0_DATA, finalData)
//        Log.d(TAG, "Metadata sent")
    }
    
    /**
     * 重发 metadata，包含正确的音频信息
     * 在发送 AAC Sequence Header 后调用，让播放器识别音频流
     */
    private fun resendMetadataWithAudio() {
        val audioDataRate = audioSampleRate * audioChannelCount * 16 / 1000.0 // bits per second
        
        val buffer = ByteBuffer.allocate(1024).apply {
            order(ByteOrder.BIG_ENDIAN)
            // Command name
            putString("onMetaData")
            
            // 🔥 关键修复：必须添加 Transaction ID = 0
            putAmfNumber(0.0)
            
            val propertyCount = 11 // width, height, videocodecid, videodatarate, framerate, duration, encoder, audiocodecid, audiodatarate, audiosamplerate, audiosamplesize
            
            put(0x08.toByte())
            putInt(propertyCount)
            
            putProperty("width", videoWidth.toDouble())
            putProperty("height", videoHeight.toDouble())
            putProperty("videocodecid", 7.0) // H.264
            putProperty("videodatarate", videoBitrate / 1024.0)
            putProperty("framerate", frameRate.toDouble())
            putProperty("duration", 0.0)
            putProperty("encoder", "Android MediaCodec")
            
            // 🔥 关键：声明音频流存在！
            putProperty("audiocodecid", 10.0) // AAC
            putProperty("audiodatarate", audioDataRate)
            putProperty("audiosamplerate", audioSampleRate.toDouble()) // 🔥 新增：采样率
            putProperty("audiosamplesize", 16.0) // 🔥 新增：采样位数 (16-bit)
            putProperty("stereo", if (audioChannelCount >= 2) 1.0 else 0.0) // 🔥 新增：立体声标记
            
            putShort(0)
            put(0x09.toByte())
        }
        val finalData = buffer.array().copyOf(buffer.position())
//        Log.d(TAG, "🔊 Resending metadata with audio: ${audioSampleRate}Hz, ${audioChannelCount}ch, ${audioDataRate.toInt()}kbps")
//        Log.d(TAG, "📦 Metadata AMF0 packet (${finalData.size} bytes)")
        sendPacket(4, streamId, 0, MSG_AMF0_DATA, finalData)
//        Log.d(TAG, "Audio metadata sent")
    }

    /**
     * 发送视频 Sequence Header (SPS/PPS)
     * 注意：MediaCodec 输出的 codec config buffer 已经是 AVCDecoderConfigurationRecord 格式
     * 不需要解析，直接包装成 RTMP Video Tag 发送
     */
    fun sendVideoSpsPps(data: ByteArray) {
        // MediaCodec 输出的 data 已经是完整的 AVCDecoderConfigurationRecord
        // 格式：version(1) + profile/level(3) + lengthSizeMinusOne(1) + numOfSPS(1) + SPS + numOfPPS(1) + PPS
//        Log.d(TAG, "📋 Sending Video Sequence Header (AVC config from MediaCodec): ${data.size} bytes")
        
        // 🔥 关键调试：验证 AVCC 数据结构
        if (data.size < 8) {
//            Log.e(TAG, "❌ ERROR: AVCDecoderConfigurationRecord too small! Size=${data.size}, minimum should be 8 bytes")
            return
        }
        
        // 验证关键字段
        val version = data[0].toInt() and 0xFF
        val profile = data[1].toInt() and 0xFF
        val level = data[3].toInt() and 0xFF
        val lengthSizeMinusOne = (data[4].toInt() and 0x03)
        val actualLengthSize = lengthSizeMinusOne + 1
        val numOfSPS = data[5].toInt() and 0x1F
        
//        Log.d(TAG, "🔍 AVCC structure validation:")
//        Log.d(TAG, "   - Configuration Version: $version (should be 1)")
//        Log.d(TAG, "   - Profile: 0x${profile.toString(16).uppercase()}, Level: 0x${level.toString(16).uppercase()}")
//        Log.d(TAG, "   - LengthSizeMinusOne: $lengthSizeMinusOne (actual NALU length size: $actualLengthSize bytes, should be 4)")
//        Log.d(TAG, "   - NumOfSPS: $numOfSPS")
//
//        if (version != 1) {
//            Log.e(TAG, "⚠️ WARNING: Unexpected configuration version!")
//        }
//        if (actualLengthSize != 4) {
//            Log.e(TAG, "⚠️ WARNING: NALU length size is $actualLengthSize bytes, but should be 4! This will cause decoder errors!")
//        }
        
        // 打印完整数据预览
        val hexPreview = data.take(minOf(32, data.size)).joinToString(" ") { "%02X".format(it) }
//        Log.d(TAG, "📦 Full AVCC data preview ($hexPreview...)")
        
        val body = ByteBuffer.allocate(data.size + 5).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(0x17.toByte()) // Keyframe (1) + CodecID (7=AVC)
            put(0x00.toByte()) // AVC sequence header
            put(byteArrayOf(0, 0, 0)) // Composition time offset (always 0 for sequence header)
            put(data) // Direct copy of AVCDecoderConfigurationRecord
        }
        val fullPacket = body.array().copyOf(body.position())
//        Log.d(TAG, "🎬 RTMP Video Sequence Header packet (${fullPacket.size} bytes):")
//        Log.d(TAG, "   Byte 0: 0x${fullPacket[0].toString(16).uppercase()} = FrameType=1 (Keyframe), CodecID=7 (AVC)")
//        Log.d(TAG, "   Byte 1: 0x${fullPacket[1].toString(16).uppercase()} = AVCPacketType=0 (Sequence Header)")
//        Log.d(TAG, "   Bytes 2-4: CompositionTime = 0")
//        Log.d(TAG, "   Bytes 5+: AVCDecoderConfigurationRecord")
//
        sendPacket(4, streamId, 0, MSG_VIDEO, fullPacket)
//        Log.d(TAG, "✅ Video Sequence Header sent successfully")
    }

    /**
     * 发送视频帧数据
     * 注意：MediaCodec 输出的 data 已经是 AVCC 格式（4 字节长度前缀 + NALU）
     * 不需要转换，直接使用
     */
    fun sendVideoData(data: ByteArray, tsMs: Long, isKey: Boolean) {
        if (!isConnected) return
        
        // 🔥 关键修复：直接使用 Long 时间戳，不在这里转换成 Int
        // RTMP 协议允许时间戳回绕，播放器会使用 RFC1982 算法处理
        val timestamp = tsMs.coerceAtLeast(0L)
    
        // MediaCodec 输出的 data 已经是 AVCC 格式
        // 直接包装成 RTMP Video Tag
//        Log.d(TAG, "sendVideoData: input size=${data.size}, isKey=$isKey, ts=$timestamp")
        if (data.size >= 4) {
            val hex = data.take(16).joinToString(" ") { "%02X".format(it) }
//            Log.d(TAG, "Video data preview (first 16 bytes): $hex")
            
            // 验证 AVCC 长度前缀
            val lengthPrefix = ((data[0].toInt() and 0xFF) shl 24) or
                               ((data[1].toInt() and 0xFF) shl 16) or
                               ((data[2].toInt() and 0xFF) shl 8) or
                               (data[3].toInt() and 0xFF)
//            Log.d(TAG, "AVCC length prefix: $lengthPrefix, actual remaining size: ${data.size - 4}")
            
            if (lengthPrefix != data.size - 4) {
//                Log.e(TAG, "⚠️ WARNING: AVCC length mismatch! Length prefix=$lengthPrefix, but actual size-4=${data.size - 4}")
            }
        }
        
        val body = ByteBuffer.allocate(data.size + 5).apply {
            order(ByteOrder.BIG_ENDIAN)
            put((if (isKey) 0x17 else 0x27).toByte()) // Frame type + Codec ID
            put(0x01.toByte()) // AVC NALU (not sequence header)
            put(byteArrayOf(0, 0, 0)) // Composition time offset (usually 0 for no B-frames)
            put(data) // Direct copy of AVCC data
        }
        val fullPacket = body.array().copyOf(body.position())
//        Log.d(TAG, "RTMP video tag byte breakdown: 0x${fullPacket[0].toString(16).uppercase()} (FrameType|CodecID), 0x${fullPacket[1].toString(16).uppercase()} (AVCPacketType), [CompositionTime], [Data ${data.size} bytes]")
//        Log.d(TAG, "RTMP video tag size=${body.position()} bytes (payload=${data.size} + header=5)")
        sendPacket(4, streamId, timestamp, MSG_VIDEO, fullPacket)
    }

    // 🔥 低延迟优化：使用专用发送线程避免并发错误
    // 通过 LinkedBlockingQueue 实现无锁化的线程间通信
    private data class RtmpPacket(
        val csid: Int,
        val msid: Int,
        val ts: Long,
        val type: Int,
        val payload: ByteArray
    )
    
    private val packetQueue = java.util.concurrent.LinkedBlockingQueue<RtmpPacket>(100)
    private var sendThread: Thread? = null
    private var isSendThreadRunning = false
    
    // 🔥 低延迟优化：跟踪 flush 计数
    private var packetCountSinceFlush = 0
    
    /**
     * 启动发送线程（在 connect 成功后调用）
     */
    private fun startSendThread() {
        if (isSendThreadRunning) return
        
        isSendThreadRunning = true
        sendThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            
            while (isSendThreadRunning && !Thread.interrupted()) {
                try {
                    // 阻塞等待下一个包（超时100ms以便检查退出标志）
                    val packet = packetQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                        ?: continue
                    
                    // 在单线程中顺序执行实际的发送逻辑
                    doSendPacket(packet.csid, packet.msid, packet.ts, packet.type, packet.payload)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
//                    Log.e(TAG, "Error in send thread: ${e.message}")
                    isConnected = false
                    onConnectionStateChanged?.invoke(false)
                }
            }
//            Log.d(TAG, "Send thread exited")
        }, "RtmpSendThread")
        
        sendThread?.start()
//        Log.d(TAG, "Send thread started")
    }
    
    /**
     * 停止发送线程
     */
    private fun stopSendThread() {
        isSendThreadRunning = false
        sendThread?.interrupt()
        sendThread?.join(1000)
        sendThread = null
        packetQueue.clear()
//        Log.d(TAG, "Send thread stopped")
    }
    
    /**
     * 实际的发送逻辑（在发送线程中执行）
     */
    private fun doSendPacket(csid: Int, msid: Int, ts: Long, type: Int, payload: ByteArray) {
        if (!isConnected) {
//                Log.w(TAG, "Cannot send packet: RTMP not connected")
            return
        }
        
        try {
            var offset = 0
            var firstChunk = true
            // 🔥 关键修复：Extended Timestamp 阈值判断（协议规范：>= 而非 >）
            // 当 ts >= 0xFFFFFF (16777215) 时必须使用 extended timestamp
            // 规范依据：RTMP Spec v1.0 Section 5.3.1.3
            val useExtendedTimestamp = ts >= 0xFFFFFFL
                
            while (offset < payload.size) {
                val size = minOf(payload.size - offset, outChunkSize)
                    
                if (firstChunk) {
                    // Type 0 Chunk Header
                    // Standard (no extended): 1 (basic) + 3 (ts) + 3 (len) + 1 (type) + 4 (msid) = 12 bytes
                    // With extended timestamp: 12 + 4 (extended ts) = 16 bytes
                    val headerSize = if (useExtendedTimestamp) 16 else 12
                    val header = ByteBuffer.allocate(headerSize).apply {
                        order(ByteOrder.BIG_ENDIAN)
                                        
                        // Basic Header: fmt (2 bits) + csid (6 bits) - 1 byte
                        put(((0x00 shl 6) or (csid and 0x3F)).toByte())
                                        
                        // Message Header (Type 0)
                        // 🔥 关键修复：Timestamp 字段处理
                        // 如果使用 extended timestamp，3 字节字段必须填 0xFFFFFF
                        // 否则填实际时间戳的低 24 位
                        val ts32 = ts.toInt() // 直接转换，保留二进制位（可能为负数，但 putInt 会正确写入）
                        val ts24 = if (useExtendedTimestamp) 0xFFFFFF else (ts32 and 0xFFFFFF)
                        put(((ts24 ushr 16) and 0xFF).toByte())  // 3 bytes total
                        put(((ts24 ushr 8) and 0xFF).toByte())
                        put((ts24 and 0xFF).toByte())
                                        
                        // Message Length: 3 bytes
                        put(((payload.size ushr 16) and 0xFF).toByte())
                        put(((payload.size ushr 8) and 0xFF).toByte())
                        put((payload.size and 0xFF).toByte())
                                        
                        // Message Type ID: 1 byte
                        put(type.toByte())
                                        
                        // Message Stream ID: 4 bytes (LITTLE-ENDIAN per RTMP spec!)
                        // 🔥 关键修复：RTMP 规范规定 Message Stream ID 使用小端序
                        put((msid and 0xFF).toByte())           // Byte 0 (LSB)
                        put(((msid ushr 8) and 0xFF).toByte())  // Byte 1
                        put(((msid ushr 16) and 0xFF).toByte()) // Byte 2
                        put(((msid ushr 24) and 0xFF).toByte()) // Byte 3 (MSB)
                                        
                        // Extended Timestamp: 4 bytes (only in Type 0/1/2, never in Type 3)
                        // 🔥 关键修复：Extended Timestamp 是完整的 4 字节时间戳，不是增量
                        // 只有当 ts >= 0xFFFFFF 时才写入（注意是 >= 不是 >）
                        if (useExtendedTimestamp) {
                            putInt(ts32) // 完整的 32 位时间戳
                        }
                    }
                    outputStream?.write(header.array())
                    firstChunk = false
                } else {
                    // Type 3 Chunk (continuation)
                    // 🔥 关键修复：为了兼容 NMS/SRS，当 useExtendedTimestamp=true 时，Type 3 也需要携带 Extended Timestamp
                    // 虽然 RTMP 规范没有强制要求，但 NMS 期望 Type 3 Chunk 在扩展时间戳场景下也携带 4 字节时间戳
                    // 参考：https://www.cnblogs.com/zzugyl/articles/7611448.html
                    val fmt3Header = (0xC0 or (csid and 0x3F)).toByte()
                    outputStream?.write(fmt3Header.toInt())
                    
                    // 🔥 如果启用了扩展时间戳，Type 3 Chunk 也需要写入 4 字节的完整时间戳
                    if (useExtendedTimestamp) {
                        val ts32 = ts.toInt()
                        val extTsBuffer = ByteBuffer.allocate(4).apply {
                            order(ByteOrder.BIG_ENDIAN)
                            putInt(ts32)
                        }
                        outputStream?.write(extTsBuffer.array())
                    }
                }
                    
                outputStream?.write(payload, offset, size)
                offset += size
            }
            
            // 🔥 极低延迟优化：更激进的 flush 策略
            // 每5个packet或关键帧/音频帧立即flush
            packetCountSinceFlush++
            val isKeyFrame = (type == MSG_VIDEO && payload.size > 0 && (payload[0].toInt() and 0x10) != 0)
            val shouldFlush = packetCountSinceFlush >= 5 || isKeyFrame || type == MSG_AUDIO
            
            if (shouldFlush) {
                outputStream?.flush()
                packetCountSinceFlush = 0
            }
        } catch (e: Exception) {
//                Log.e(TAG, "Error sending packet: ${e.message}")
            isConnected = false
            onConnectionStateChanged?.invoke(false)
        }
    }
    
    /**
     * 公共发送接口（智能选择：连接阶段直接发送，推流阶段入队）
     */
    private fun sendPacket(csid: Int, msid: Int, ts: Long, type: Int, payload: ByteArray) {
        if (!isConnected) {
//            Log.w(TAG, "Cannot queue packet: RTMP not connected")
            return
        }
        
        // 🔥 关键修复：如果发送线程未启动（连接阶段），直接同步发送
        if (!isSendThreadRunning) {
            doSendPacket(csid, msid, ts, type, payload)
            return
        }
        
        // 发送线程已启动，使用队列异步发送
        val packet = RtmpPacket(csid, msid, ts, type, payload)
        if (!packetQueue.offer(packet)) {
            // 队列满，尝试移除最旧的包再插入
            packetQueue.poll()
            packetQueue.offer(packet)
//            Log.w(TAG, "Packet queue full, dropped oldest packet")
        }
    }

    private fun readResponse(expectedCmd: String, expectedTid: Double): ByteArray {
        val start = System.currentTimeMillis()
        var lastChunkBasicHeader: Byte? = null
        var lastChunkFmt: Int? = null
        var lastMsgLen: Int = 0
        var lastMsgType: Int = 0
        var lastTimestamp: Int = 0
            
        while (System.currentTimeMillis() - start < 5000) {
            // Read with timeout handling
            if ((inputStream?.available() ?: 0) == 0) {
                Thread.sleep(10)
                continue
            }
                
            val b = inputStream?.read() ?: -1
            if (b == -1) throw IOException("Disconnected")
    
            val fmt = (b shr 6) and 0x03
            val csid = b and 0x3F
                
            // Chunk Format determines header size (excluding first byte already read)
            val headerSize = when(fmt) { 
                0 -> 11  // Format 0: timestamp(3) + length(3) + type(1) + msid(4)
                1 -> 7   // Format 1: timestamp(3) + length(3) + type(1)
                2 -> 3   // Format 2: timestamp(3)
                3 -> 0   // Format 3: no additional header (continuation)
                else -> 0
            }
                
            // Read remaining header bytes
            val header = ByteArray(headerSize)
            if (headerSize > 0) {
                var totalRead = 0
                while (totalRead < headerSize) {
                    val n = inputStream?.read(header, totalRead, headerSize - totalRead) ?: -1
                    if (n == -1) throw IOException("Disconnected while reading header")
                    totalRead += n
                }
            }
    
            // Parse header based on fmt
            val result = when(fmt) {
                0 -> {
                    // Full header
                    val ts = ((header[0].toInt() and 0xFF) shl 16) or 
                             ((header[1].toInt() and 0xFF) shl 8) or 
                             (header[2].toInt() and 0xFF)
                    val len = ((header[3].toInt() and 0xFF) shl 16) or 
                              ((header[4].toInt() and 0xFF) shl 8) or 
                              (header[5].toInt() and 0xFF)
                    val type = header[6].toInt() and 0xFF
                    // 🔥 关键修复：Message Stream ID 是小端序！
                    val msId = ByteBuffer.wrap(header, 7, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    Triple(len, type, ts) to msId
                }
                1 -> {
                    // Partial header (no msid)
                    val ts = ((header[0].toInt() and 0xFF) shl 16) or 
                             ((header[1].toInt() and 0xFF) shl 8) or 
                             (header[2].toInt() and 0xFF)
                    val len = ((header[3].toInt() and 0xFF) shl 16) or 
                              ((header[4].toInt() and 0xFF) shl 8) or 
                              (header[5].toInt() and 0xFF)
                    val type = header[6].toInt() and 0xFF
                    Triple(len, type, ts) to (lastChunkBasicHeader?.toInt() ?: 0)
                }
                2 -> {
                    // Timestamp delta only
                    val tsDelta = ((header[0].toInt() and 0xFF) shl 16) or 
                                  ((header[1].toInt() and 0xFF) shl 8) or 
                                  (header[2].toInt() and 0xFF)
                    Triple(lastMsgLen, lastMsgType, tsDelta) to (lastChunkBasicHeader?.toInt() ?: 0)
                }
                3 -> {
                    // Continuation - use previous values
                    Triple(lastMsgLen, lastMsgType, lastTimestamp) to (lastChunkBasicHeader?.toInt() ?: 0)
                }
                else -> Triple(0, 0, 0) to 0
            }
            
            val msgLen = result.first.first
            val msgType = result.first.second
            val timestamp = result.first.third
            val msid = result.second
                
            // Update state
            lastChunkBasicHeader = csid.toByte()
            lastChunkFmt = fmt
            if (fmt <= 1) {
                lastMsgLen = msgLen
                lastMsgType = msgType
            }
            
            // Extended timestamp handling:
            // If 3-byte timestamp == 0xFFFFFF, extended timestamp (4 bytes) follows the standard header
            // It is NOT part of payload, but part of the chunk header structure
            val hasExtendedTimestamp = (timestamp == 0xFFFFFF)
            val actualTimestamp = if (hasExtendedTimestamp && fmt <= 2) {
                // For fmt=0/1/2, extended timestamp comes after standard header
                // We need to read it separately from the stream
                val extTs = ByteArray(4)
                var totalRead = 0
                while (totalRead < 4) {
                    val n = inputStream?.read(extTs, totalRead, 4 - totalRead) ?: -1
                    if (n == -1) throw IOException("Disconnected while reading extended timestamp")
                    totalRead += n
                }
                val extendedTs = ByteBuffer.wrap(extTs).order(ByteOrder.BIG_ENDIAN).int
                // 🔥 关键修复：使用 extended timestamp 更新 lastTimestamp
                extendedTs
            } else {
                timestamp
            }
            
            // Update lastTimestamp with the actual timestamp (extended or not)
            lastTimestamp = actualTimestamp
    
            if (msgLen > 0) {
                // Read payload (without extended timestamp bytes)
                val payload = ByteArray(msgLen)
                var r = 0
                while (r < msgLen) {
                    val n = inputStream?.read(payload, r, msgLen - r) ?: -1
                    if (n == -1) break
                    r += n
                }
                    
                // Handle protocol control messages
                when (msgType) {
                    MSG_SET_CHUNK_SIZE -> {
                        val newChunkSize = ByteBuffer.wrap(payload, 0, 4).order(ByteOrder.BIG_ENDIAN).int
//                        Log.d(TAG, "📦 Received Set Chunk Size from server: $newChunkSize bytes")
                        
                        // 🔥 关键修复：更新输入 chunk size（服务器 → 客户端方向）
                        // RTMP 规范：通信双方各自维护 chunk size，互不影响
                        inChunkSize = newChunkSize
                        
//                        Log.d(TAG, "   inChunkSize updated: $inChunkSize (for receiving chunks from server)")
//                        Log.d(TAG, "   outChunkSize remains: $outChunkSize (for sending chunks to server)")
                        
                        // Continue waiting for expected command
                        continue
                    }
                    MSG_WINDOW_ACK_SIZE -> {
                        val ackSize = ByteBuffer.wrap(payload, 0, 4).order(ByteOrder.BIG_ENDIAN).int
//                        Log.d(TAG, "Received Window Ack Size: $ackSize")
                        continue
                    }
                    MSG_SET_PEER_BANDWIDTH -> {
//                        Log.d(TAG, "Received Set Peer Bandwidth")
                        continue
                    }
                    MSG_USER_CONTROL -> {
                        val eventType = ByteBuffer.wrap(payload, 0, 2).order(ByteOrder.BIG_ENDIAN).short
//                        Log.v(TAG, "Received User Control message: type=$eventType")
                        continue
                    }
                    MSG_AMF0_COMMAND -> {
                        val amf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
                        if (amf.get() == 0x02.toByte()) { // String
                            val len = amf.short.toInt() and 0xFFFF
                            val name = String(ByteArray(len) { amf.get() })
                            if (name == expectedCmd) {
                                return payload
                            }
                        }
                    }
                }
            }
        }
        throw IOException("Timeout waiting for $expectedCmd")
    }

    /**
     * 读取 onStatus 消息确认发布状态
     */
    private fun readOnStatus(): Boolean {
        try {
            val start = System.currentTimeMillis()
            var lastChunkBasicHeader: Byte? = null
            var lastChunkFmt: Int? = null
            var lastMsgLen: Int = 0
            var lastMsgType: Int = 0
            var lastTimestamp: Int = 0
            
            while (System.currentTimeMillis() - start < 2000) {
                if ((inputStream?.available() ?: 0) > 0) {
                    val b = inputStream?.read() ?: break

                    val fmt = (b shr 6) and 0x03
                    val csid = b and 0x3F
                    
                    val headerSize = when(fmt) { 
                        0 -> 11
                        1 -> 7
                        2 -> 3
                        3 -> 0
                        else -> 0
                    }
                    
                    val header = ByteArray(headerSize)
                    if (headerSize > 0) {
                        var totalRead = 0
                        while (totalRead < headerSize) {
                            val n = inputStream?.read(header, totalRead, headerSize - totalRead) ?: -1
                            if (n == -1) break
                            totalRead += n
                        }
                    }

                    // Parse header based on fmt
                    val result = when(fmt) {
                        0 -> {
                            val ts = ((header[0].toInt() and 0xFF) shl 16) or 
                                     ((header[1].toInt() and 0xFF) shl 8) or 
                                     (header[2].toInt() and 0xFF)
                            val len = ((header[3].toInt() and 0xFF) shl 16) or 
                                      ((header[4].toInt() and 0xFF) shl 8) or 
                                      (header[5].toInt() and 0xFF)
                            val type = header[6].toInt() and 0xFF
                            Triple(len, type, ts)
                        }
                        1 -> {
                            val ts = ((header[0].toInt() and 0xFF) shl 16) or 
                                     ((header[1].toInt() and 0xFF) shl 8) or 
                                     (header[2].toInt() and 0xFF)
                            val len = ((header[3].toInt() and 0xFF) shl 16) or 
                                      ((header[4].toInt() and 0xFF) shl 8) or 
                                      (header[5].toInt() and 0xFF)
                            val type = header[6].toInt() and 0xFF
                            Triple(len, type, ts)
                        }
                        2 -> {
                            val tsDelta = ((header[0].toInt() and 0xFF) shl 16) or 
                                          ((header[1].toInt() and 0xFF) shl 8) or 
                                          (header[2].toInt() and 0xFF)
                            Triple(lastMsgLen, lastMsgType, tsDelta)
                        }
                        3 -> {
                            Triple(lastMsgLen, lastMsgType, lastTimestamp)
                        }
                        else -> Triple(0, 0, 0)
                    }
                    
                    val msgLen = result.first
                    val msgType = result.second
                    val timestamp = result.third
                    
                    lastChunkBasicHeader = csid.toByte()
                    lastChunkFmt = fmt
                    if (fmt <= 1) {
                        lastMsgLen = msgLen
                        lastMsgType = msgType
                    }
                    
                    // Extended timestamp handling
                    val hasExtendedTimestamp = (timestamp == 0xFFFFFF)
                    val actualTimestamp = if (hasExtendedTimestamp && fmt <= 2) {
                        val extTs = ByteArray(4)
                        var totalRead = 0
                        while (totalRead < 4) {
                            val n = inputStream?.read(extTs, totalRead, 4 - totalRead) ?: -1
                            if (n == -1) break
                            totalRead += n
                        }
                        if (totalRead == 4) {
                            ByteBuffer.wrap(extTs).order(ByteOrder.BIG_ENDIAN).int
                        } else {
                            timestamp
                        }
                    } else {
                        timestamp
                    }
                    
                    lastTimestamp = actualTimestamp

                    if (msgLen > 0 && msgType == MSG_AMF0_COMMAND) {
                        val payload = ByteArray(msgLen)
                        var r = 0
                        while (r < msgLen) {
                            val n = inputStream?.read(payload, r, msgLen - r) ?: -1
                            if (n == -1) break
                            r += n
                        }
                        val amf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
                        if (amf.get() == 0x02.toByte()) { // String
                            val len = amf.short.toInt() and 0xFFFF
                            val name = String(ByteArray(len) { amf.get() })
                            if (name == "onStatus") {
//                                Log.d(TAG, "Received onStatus message")
                                
                                // Skip transaction ID (usually 0)
                                skipAmfValue(amf)
                                
                                // Skip command object (null)
                                skipAmfValue(amf)
                                
                                // Read status object
                                if (amf.get() == 0x03.toByte()) { // Object
                                    while (amf.hasRemaining() && amf.position() < amf.limit() - 3) {
                                        val propLen = amf.short.toInt() and 0xFFFF
                                        if (propLen == 0) {
                                            amf.get() // Skip object end marker
                                            break
                                        }
                                        val propName = String(ByteArray(propLen) { amf.get() })
                                        if (propName == "code") {
                                            if (amf.get() == 0x02.toByte()) {
                                                val codeLen = amf.short.toInt() and 0xFFFF
                                                val code = String(ByteArray(codeLen) { amf.get() })
//                                                Log.d(TAG, "onStatus code: $code")
                                                // Accept any success code
                                                if (code.startsWith("NetStream.Publish")) {
                                                    return true
                                                }
                                            }
                                        } else {
                                            skipAmfValue(amf)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Thread.sleep(10)
                }
            }
        } catch (e: Exception) {
//            Log.w(TAG, "Error reading onStatus: ${e.message}")
        }
        return false
    }

    // --- 辅助方法 ---

    private fun parseRtmpUrl(url: String) {
        val withoutProtocol = url.removePrefix("rtmp://")
        val parts = withoutProtocol.split("/")
        val hostParts = parts[0].split(":")
        serverHost = hostParts[0]
        serverPort = hostParts.getOrNull(1)?.toIntOrNull() ?: 1935
        appName = parts.getOrNull(1) ?: ""
        streamName = if (parts.size > 2) parts.subList(2, parts.size).joinToString("/") else ""
        serverUrl = url
    }

    private fun ByteBuffer.putString(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        put(0x02.toByte())  // String type
        putShort(b.size.toShort())
        put(b)
    }

    private fun ByteBuffer.putAmfNumber(n: Double) {
        put(0x00.toByte())  // Number type
        putDouble(n)
    }

    /**
     * 在 AMF0 Object 中写入一个属性
     * 格式：String(name) + Value(根据类型)
     */
    private fun ByteBuffer.putProperty(name: String, value: Any?) {
        // 写入属性名（String without type marker，在 Object 中属性名总是 string）
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        putShort(nameBytes.size.toShort())
        put(nameBytes)
            
        // 写入属性值（包含 type marker）
        when {
            value == null -> {
                put(0x05.toByte())  // Null type
            }
            value is String -> {
                put(0x02.toByte())  // String type
                val valBytes = value.toByteArray(Charsets.UTF_8)
                putShort(valBytes.size.toShort())
                put(valBytes)
            }
            value is Double -> {
                put(0x00.toByte())  // Number type
                putDouble(value)
            }
            value is Boolean -> {
                put(0x01.toByte())  // Boolean type
                put(if (value) 1.toByte() else 0.toByte())
            }
            else -> {
                put(0x05.toByte())  // Null type (fallback)
            }
        }
    }

    private fun skipAmfValue(buf: ByteBuffer) {
        if (!buf.hasRemaining()) return
        try {
            when(buf.get().toInt() and 0xFF) {
                0x00 -> buf.position(buf.position() + 8) // Number
                0x01 -> buf.position(buf.position() + 1) // Boolean
                0x02 -> { // String
                    val len = buf.short.toInt() and 0xFFFF
                    buf.position(buf.position() + len)
                }
                0x03 -> { // Object
                    while(buf.hasRemaining()) {
                        val propLen = buf.short.toInt() and 0xFFFF
                        if (propLen == 0) {
                            // End of object marker (0x00 0x00 0x09)
                            if (buf.hasRemaining()) buf.get()
                            break
                        }
                        // Skip property name
                        buf.position(buf.position() + propLen)
                        // Skip property value recursively
                        skipAmfValue(buf)
                    }
                }
                0x05 -> {} // Null (no data)
                0x08 -> { // ECMA Array
                    buf.int // Skip associative array count
                    while(buf.hasRemaining()) {
                        val propLen = buf.short.toInt() and 0xFFFF
                        if (propLen == 0) {
                            if (buf.hasRemaining()) buf.get()
                            break
                        }
                        buf.position(buf.position() + propLen)
                        skipAmfValue(buf)
                    }
                }
            }
        } catch (e: Exception) {
//            Log.w(TAG, "Error skipping AMF value: ${e.message}")
        }
    }

    fun disconnect() {
        // 🔥 停止发送线程
        stopSendThread()
        
        if (!isConnected) {
            try {
                socket?.close()
            } catch (e: Exception) {
//                Log.w(TAG, "Error closing socket: ${e.message}")
            }
            socket = null
            return
        }
        
        try {
            // Standard RTMP disconnection sequence
            // 1. Close stream
            if (streamId > 0) {
                closeStream()
                Thread.sleep(50)
            }
            
            // 2. Delete stream
            if (streamId > 0) {
                deleteStream()
                Thread.sleep(50)
            }
            
            // 3. Flush any pending data
            outputStream?.flush()
            Thread.sleep(100)
            
        } catch (e: Exception) {
//            Log.w(TAG, "Error during RTMP disconnect sequence: ${e.message}")
        } finally {
            try {
                isConnected = false
                outputStream?.flush()
                socket?.shutdownInput()
                socket?.shutdownOutput()
                socket?.close()
//                Log.d(TAG, "Disconnected from RTMP server")
            } catch (e: Exception) {
//                Log.e(TAG, "Error closing socket: ${e.message}")
            } finally {
                socket = null
                outputStream = null
                inputStream = null
            }
        }
    }

    /**
     * 关闭流（标准 RTMP closeStream 命令）
     */
    private fun closeStream() {
        try {
            transactionId++
            val buffer = ByteBuffer.allocate(128).apply {
                order(ByteOrder.BIG_ENDIAN)
                putString("closeStream")
                putAmfNumber(transactionId)
                put(0x05.toByte()) // Null
            }
            sendPacket(3, streamId, 0, MSG_AMF0_COMMAND, buffer.array().copyOf(buffer.position()))
//            Log.d(TAG, "Sent closeStream command")
            
            // Don't wait for response - just send and continue
            // Some servers may not respond to closeStream
        } catch (e: Exception) {
//            Log.w(TAG, "Error sending closeStream: ${e.message}")
        }
    }
    
    /**
     * 删除流（标准 RTMP deleteStream 命令）
     */
    private fun deleteStream() {
        try {
            transactionId++
            val buffer = ByteBuffer.allocate(128).apply {
                order(ByteOrder.BIG_ENDIAN)
                putString("deleteStream")
                putAmfNumber(transactionId)
                put(0x05.toByte()) // Null
                putAmfNumber(streamId.toDouble())
            }
            sendPacket(3, 0, 0, MSG_AMF0_COMMAND, buffer.array().copyOf(buffer.position()))
//            Log.d(TAG, "Sent deleteStream command")
        } catch (e: Exception) {
//            Log.w(TAG, "Error sending deleteStream: ${e.message}")
        }
    }

    fun setVideoParams(w: Int, h: Int, b: Int, f: Int) { videoWidth = w; videoHeight = h; videoBitrate = b; frameRate = f }
    fun setAudioParams(sampleRate: Int, channels: Int) { audioSampleRate = sampleRate; audioChannelCount = channels }
    fun getCurrentUrl() = serverUrl
    
    /**
     * 发送音频数据（AAC 格式）
     * 
     * 🔥 协议依据：RTMP/FLV 规范 v10 第 10 页
     * AudioTagHeader 格式：
     *   Byte 0: SoundFormat(4bit) + SoundRate(2bit) + SoundSize(1bit) + SoundType(1bit)
     *   Byte 1: AACPacketType (仅当 SoundFormat=10/AAC 时存在)
     *     - 0x00 = AAC sequence header (AudioSpecificConfig)
     *     - 0x01 = AAC raw (纯 AAC ES 流，无 ADTS 头)
     * 
     * 注意：
     * - SoundRate 对于 AAC 固定为 3 (44.1kHz)，实际采样率在 ASC 中指定
     * - AAC raw frame 必须是裸 ES 流（MediaCodec.KEY_IS_ADTS=0）
     */
    fun sendAudioData(data: ByteArray, ts: Long) {
        if (!isConnected) return

        // 🔥 关键修复：检查 ASC 是否已设置
        if (audioSpecificConfig == null) {
//            Log.w(TAG, "⚠️ AudioSpecificConfig not set yet! Cannot send audio frame. Waiting...")
            return
        }
        
        // 🔥 关键修复：不再检查 audioSequenceHeaderSent！
        // 因为 setAudioSpecificConfigAndSend 已经立即发送了 Sequence Header
        // 如果在这里重复发送，会导致播放器收到多个 Sequence Header，造成 DTS 混乱
        
        // 🔥 关键修复：直接使用 Long 时间戳，不在这里转换成 Int
        // RTMP 协议允许时间戳回绕，播放器会使用 RFC1982 算法处理
        val timestamp = ts.coerceAtLeast(0L)
        
        // 🔥 移除单调性检查：SurfaceTextureEncoder 已经保证时间戳单调递增
        // 如果在这里强制修正，会导致累积误差，使音频时间戳超前
        
        // 🔥 协议依据：RTMP/FLV 规范 v10 - AAC 的 SoundFormat/SoundRate/SoundSize/SoundType 必须固定
        // 实际音频参数在 AudioSpecificConfig 中指定，播放器以 ASC 为准
        val soundFormat = 10 // AAC
        val soundRate = 3 // Fixed to 3 (44.1kHz) for AAC - actual rate is in ASC
        val soundSize = 1 // Fixed to 1 (16-bit) for AAC
        val soundType = 1 // Fixed to 1 (Stereo) for AAC - actual channels is in ASC
        
        val audioHeader = ((soundFormat and 0x0F) shl 4) or 
                          ((soundRate and 0x03) shl 2) or 
                          ((soundSize and 0x01) shl 1) or 
                          (soundType and 0x01)
        
        val body = ByteBuffer.allocate(data.size + 2).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(audioHeader.toByte())  // Audio header with correct sample rate and channels
            put(0x01.toByte())  // AACPacketType=0x01 (AAC raw frame, not sequence header)
            put(data)  // Pure AAC ES stream (no ADTS header, KEY_IS_ADTS=0)
        }
        
        val sentBytes = body.array().copyOf(body.position())
        
        // 🔥 关键诊断：验证发送前的数据
        if (sentBytes[0] != 0xAF.toByte()) {
//            Log.e(TAG, "❌ CRITICAL: Audio header corruption BEFORE send!")
//            Log.e(TAG, "   Expected: 0xAF, Got: 0x${String.format("%02X", sentBytes[0])}")
//            Log.e(TAG, "   audioHeader var: 0x${audioHeader.toString(16).uppercase()}")
//            Log.e(TAG, "   Full packet: ${sentBytes.take(16).joinToString(" ") { "%02X".format(it) }}")
        }
        
        sendPacket(4, streamId, timestamp, MSG_AUDIO, sentBytes)
        
        audioOutputFrameCount++
        
        // 🔥 关键诊断：打印前几个字节的 AAC 数据，确认格式正确
        if (audioOutputFrameCount <= 10 || audioOutputFrameCount % 50 == 0L) {
            val firstBytes = data.take(minOf(16, data.size)).joinToString(" ") { "%02X".format(it) }
//            Log.d(TAG, "🔊 [Frame #$audioOutputFrameCount] AAC ES data preview: $firstBytes...")
            
            // 🔥 验证 AAC 数据有效性
            if (data.size < 2) {
//                Log.e(TAG, "❌ [Frame #$audioOutputFrameCount] AAC frame too small! size=${data.size}")
            }
            // AAC ADTS 头通常以 0xFFF 开头，但我们是 RAW AAC（无 ADTS 头），所以不检查这个
        }
        
        // 音频帧详细日志
//        Log.d(TAG, "🔊 Sent AAC raw frame: size=${data.size}, ts=$timestamp ms, header=0x${audioHeader.toString(16).uppercase()} (Format=$soundFormat, Rate=$soundRate, Size=$soundSize, Type=$soundType), actual=${audioSampleRate}Hz/${audioChannelCount}ch")
    }
        
    /**
     * 发送音频 Sequence Header (Audio Specific Config)
     * 
     * 🔥 协议依据：RTMP/FLV 规范 v10 第 10 页
     * AAC Sequence Header 格式：
     *   Byte 0: SoundFormat(4bit) + SoundRate(2bit) + SoundSize(1bit) + SoundType(1bit)
     *   Byte 1: AACPacketType=0x00 (sequence header)
     *   Byte 2..N: AudioSpecificConfig (从 MediaCodec csd-0 提取)
     * 
     * AudioSpecificConfig 结构（ISO/IEC 14496-3）：
     *   - audioObjectType (5 bits): AAC LC = 2
     *   - samplingFrequencyIndex (4 bits): 采样率索引
     *   - channelConfiguration (4 bits): 声道数
     */
    private fun sendAudioSpecificConfig() {
        // 🔥 协议依据：优先使用 MediaCodec 编码器生成的 csd-0 (AudioSpecificConfig)
        val asc = audioSpecificConfig ?: run {
//            Log.w(TAG, "⚠️ ASC not set from encoder, building manually (this should not happen!)")
            
            // Fallback: build ASC manually (ISO/IEC 14496-3)
            val samplingIndex = when (audioSampleRate) {
                96000 -> 0
                88200 -> 1
                64000 -> 2
                48000 -> 3
                44100 -> 4
                32000 -> 5
                24000 -> 6
                22050 -> 7
                16000 -> 8
                12000 -> 9
                11025 -> 10
                8000 -> 11
                else -> 3 // Default to 48kHz
            }
                
            val channelConfig = when (audioChannelCount) {
                1 -> 1
                2 -> 2
                3 -> 3
                4 -> 4
                5 -> 5
                6 -> 6
                else -> 2 // Default to stereo
            }
                
            // Build 2-byte ASC for AAC LC (audioObjectType=2)
            // 🔥 协议依据：ISO/IEC 14496-3 AudioSpecificConfig
            // audioObjectType 直接使用 2 (AAC-LC)，不需要减 1
            ByteBuffer.allocate(2).apply {
                order(ByteOrder.BIG_ENDIAN)
                // Byte 1: audioObjectType(5bits) + samplingFrequencyIndex high 3 bits
                // audioObjectType = 2 (AAC-LC), no need to subtract 1!
                val byte1 = ((2 and 0x1F) shl 3) or ((samplingIndex ushr 1) and 0x07)
                // Byte 2: samplingFrequencyIndex low 1 bit + channelConfiguration(4bits) + extensionFlag(3bits=0)
                val byte2 = ((samplingIndex and 0x01) shl 7) or ((channelConfig and 0x0F) shl 3)
                put(byte1.toByte())
                put(byte2.toByte())
            }.array()
        }
        
        // 🔥 协议依据：RTMP/FLV 规范 v10 - AAC 的 SoundFormat/SoundRate/SoundSize/SoundType 必须固定
        val soundFormat = 10 // AAC
        val soundRate = 3 // Fixed for AAC (actual rate is in ASC)
        val soundSize = 1 // Fixed to 1 (16-bit) for AAC
        val soundType = 1 // Fixed to 1 (Stereo) for AAC
        val audioHeader = ((soundFormat and 0x0F) shl 4) or 
                          ((soundRate and 0x03) shl 2) or 
                          ((soundSize and 0x01) shl 1) or 
                          (soundType and 0x01)
            
        // 🔥 协议依据：FLV Tag 结构
        // Send as sequence header (timestamp must be 0)
        val body = ByteBuffer.allocate(asc.size + 2).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(audioHeader.toByte())  // SoundFormat + SoundRate + SoundSize + SoundType
            put(0x00.toByte())  // AACPacketType=0x00 (sequence header)
            put(asc)  // AudioSpecificConfig from csd-0
        }
        // 🔥 协议依据：使用 csid=4 (audio stream), timestamp=0 for sequence header
        sendPacket(4, streamId, 0, MSG_AUDIO, body.array().copyOf(body.position()))
        
        // 详细日志
        val audioHeaderByte = audioHeader.toByte()
//        Log.d(TAG, "🎼 Sent AAC Audio Specific Config: ${asc.size} bytes, actual=${audioSampleRate}Hz/${audioChannelCount}ch")
//        Log.d(TAG, "   ASC bytes: ${asc.joinToString(" ") { "%02X".format(it) }}")
//        Log.d(TAG, "   Full packet: ${body.array().toList().joinToString(" ") { "%02X".format(it) }}")
//        Log.d(TAG, "   Header=0x${audioHeaderByte.toString(16).uppercase()} (Format=$soundFormat, Rate=$soundRate, Size=$soundSize, Type=$soundType)")
    }
}
