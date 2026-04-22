package org.dpdns.sylw.videostreamer.rtmpStreamer

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresPermission
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.dpdns.sylw.videostreamer.MediaProjectionService

/**
 * SurfaceTexture 编码器
 * 从 SurfaceTexture 获取视频数据，编码为 H.264 并通过 RTMP 推送
 * 
 * 🔥 支持两种模式：
 * 1. MediaProjection 模式：用于屏幕录制推流
 * 2. Camera 模式：用于摄像头推流（不需要 MediaProjection）
 */
class SurfaceTextureEncoder(
    private val width: Int,
    private val height: Int,
    private val dpi: Int,
    private val videoBitrate: Int,        // 视频码率 bps
    private val frameRate: Int = 30,       // 帧率
    private val iFrameInterval: Int = 1,   // 🔥 极低延迟：I帧间隔从2秒降到1秒
    private val useAudio: Boolean = true,  // 是否使用音频
    private val audioSampleRate: Int = 48000,  // 音频采样率
    private val audioChannelCount: Int = 2,    // 音频声道数
    private val audioBitrate: Int = 128000,     // 音频码率 bps
    private val externalAudioSource: (() -> ByteArray?)? = null,  // 外部 PCM 音频源回调
    private val isCameraMode: Boolean = false  // 🔥 Camera 模式标志
) {
    companion object {
        private const val TAG = "SurfaceTextureEncoder"
        private const val VIDEO_MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val AUDIO_MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC
    }

    // 媒体投影相关
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    
    // 视频编码相关
    private var mediaCodecVideo: MediaCodec? = null
    private var surface: Surface? = null
    private var videoBufferInfo: MediaCodec.BufferInfo? = null
    
    // 音频编码相关
    private var mediaCodecAudio: MediaCodec? = null
    private var audioBufferInfo: MediaCodec.BufferInfo? = null
    private var audioThread: Thread? = null
    private var isAudioRecording = false
    private var inputBuffers: Array<ByteBuffer>? = null  // 用于 API < 21 的兼容性
    
    // 外部音频源标记
    private val useExternalAudio = externalAudioSource != null
    
    // 编码线程
    private var encodeThread: Thread? = null
    private var isEncoding = false
    
    // RTMP 推流器
    private var rtmpPusher: RtmpPusher? = null
    
    // 状态回调
    var onStreamStateChanged: ((Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    
    // 缓存的 AVCC 配置（从 csd-0/csd-1 合并）
    private var cachedAVCCConfig: ByteArray? = null
    
    // 🔥 缓存的 AudioSpecificConfig（从音频编码器 csd-0 提取）
    private var cachedAudioSpecificConfig: ByteArray? = null
    
    // 🔥 统一的时间基准：推流开始时的系统时间（纳秒）
    private var streamStartTimeNs = -1L
    
    /**
     * 去除 NAL 单元的起始码（00 00 00 01 或 00 00 01）
     */
    private fun stripStartCode(nal: ByteArray): ByteArray {
        // 4 字节起始码 00 00 00 01
        if (nal.size >= 4 && nal[0] == 0x00.toByte() && nal[1] == 0x00.toByte() &&
            nal[2] == 0x00.toByte() && nal[3] == 0x01.toByte()) {
            return nal.copyOfRange(4, nal.size)
        }
        // 3 字节起始码 00 00 01
        if (nal.size >= 3 && nal[0] == 0x00.toByte() && nal[1] == 0x00.toByte() &&
            nal[2] == 0x01.toByte()) {
            return nal.copyOfRange(3, nal.size)
        }
        return nal
    }
    
    /**
     * 判断是否是 AnnexB 格式（支持 3 字节或 4 字节起始码）
     */
    private fun isAnnexB(data: ByteArray): Boolean {
        if (data.size < 3) return false
        
        // 检查 4 字节起始码 00 00 00 01
        if (data.size >= 4 && 
            data[0] == 0x00.toByte() && data[1] == 0x00.toByte() &&
            data[2] == 0x00.toByte() && data[3] == 0x01.toByte()) {
            return true
        }
        
        // 检查 3 字节起始码 00 00 01
        if (data[0] == 0x00.toByte() && data[1] == 0x00.toByte() &&
            data[2] == 0x01.toByte()) {
            return true
        }
        
        return false
    }
    
    /**
     * 将 AnnexB 格式转换为 AVCC 格式（增强版：支持混合起始码长度）
     * AnnexB: 00 00 00 01 [NAL data] 或 00 00 01 [NAL data]
     * AVCC: [4-byte length] [NAL data]
     */
    private fun annexBToAvcc(annexBData: ByteArray): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < annexBData.size) {
            // 查找起始码（支持 3 或 4 字节）
            var startCodeLen = 0
            if (i + 3 < annexBData.size &&
                annexBData[i] == 0x00.toByte() && annexBData[i+1] == 0x00.toByte() &&
                annexBData[i+2] == 0x00.toByte() && annexBData[i+3] == 0x01.toByte()) {
                startCodeLen = 4
            } else if (i + 2 < annexBData.size &&
                annexBData[i] == 0x00.toByte() && annexBData[i+1] == 0x00.toByte() &&
                annexBData[i+2] == 0x01.toByte()) {
                startCodeLen = 3
            }

            if (startCodeLen > 0) {
                i += startCodeLen
                // 查找下一个起始码（3 或 4 字节）
                var end = annexBData.size
                var j = i
                while (j < annexBData.size - 2) {
                    if (annexBData[j] == 0x00.toByte() && annexBData[j+1] == 0x00.toByte()) {
                        if (j+2 < annexBData.size && annexBData[j+2] == 0x01.toByte()) {
                            end = j
                            break
                        }
                        if (j+3 < annexBData.size && annexBData[j+2] == 0x00.toByte() && annexBData[j+3] == 0x01.toByte()) {
                            end = j
                            break
                        }
                    }
                    j++
                }
                val naluLength = end - i
                if (naluLength > 0) {
                    // 写入 4 字节长度（大端）
                    output.write((naluLength shr 24) and 0xFF)
                    output.write((naluLength shr 16) and 0xFF)
                    output.write((naluLength shr 8) and 0xFF)
                    output.write(naluLength and 0xFF)
                    // 写入 NAL 数据
                    output.write(annexBData, i, naluLength)
                }
                i = end
            } else {
                i++
            }
        }
        return output.toByteArray()
    }
    
    /**
     * 设置 MediaProjection（必须在调用 start 之前）
     */
    fun setMediaProjection(projection: MediaProjection) {
        this.mediaProjection = projection
    }
    
    // MediaProjectionService binder (用于更新 VirtualDisplay surface)
    private var mediaProjectionServiceBinder: Any? = null
    
    /**
     * 设置 MediaProjectionService binder
     */
    fun setMediaProjectionServiceBinder(binder: Any) {
        this.mediaProjectionServiceBinder = binder
//        Log.d(TAG, "MediaProjectionService binder set")
    }
    
    /**
     * 获取编码器的 input surface
     */
    fun getInputSurface(): Surface? {
        val s = surface
        if (s == null) {
//            Log.w(TAG, "getInputSurface() returned null - surface is null")
        } else {
//            Log.d(TAG, "getInputSurface() returned valid surface: $s")
        }
        return s
    }
    
    /**
     * 开始编码和推流
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(rtmpUrl: String) {
        // 🔥 Camera 模式不需要 MediaProjection
        if (!isCameraMode && mediaProjection == null) {
            onError?.invoke("MediaProjection 未设置")
            return
        }
        
        try {
//            Log.d(TAG, "Starting encoder: ${width}x${height}, bitrate=$videoBitrate, fps=$frameRate, cameraMode=$isCameraMode")
            
            // 1. 初始化 RTMP 推流器
            rtmpPusher = RtmpPusher()
            rtmpPusher?.setVideoParams(width, height, videoBitrate, frameRate)
            rtmpPusher?.setAudioParams(audioSampleRate, audioChannelCount)
            
            // 在后台线程中连接，避免 NetworkOnMainThreadException
            rtmpPusher?.onConnectionStateChanged = { isConnected ->
//                Log.d(TAG, "RTMP connection state changed: $isConnected")
                // 🔥 关键修复：将 RTMP 连接状态传递给上层推流状态
                // 如果连接断开或失败，推流状态也应该为 false
                if (!isConnected && isEncoding) {
//                    Log.w(TAG, "RTMP disconnected while encoding, updating stream state to false")
                    onStreamStateChanged?.invoke(false)
                }
            }
            
            // 阻塞等待RTMP连接成功
            var rtmpConnected = false
            var connectException: Exception? = null
            val rtmpConnectThread = Thread {
                try {
                    rtmpPusher?.connect(rtmpUrl)
                    rtmpConnected = true
//                    Log.d(TAG, "RTMP connected successfully")
                } catch (e: Exception) {
//                    Log.e(TAG, "Failed to connect RTMP: ${e.message}")
                    connectException = e
                    onError?.invoke("RTMP连接失败：${e.message}")
                }
            }
            rtmpConnectThread.start()
            
            // 等待RTMP连接（最多5秒）
            rtmpConnectThread.join(5000)
            
            if (!rtmpConnected) {
//                Log.w(TAG, "RTMP connection timeout or failed")
                // 🔥 关键修复：连接超时或失败时，不应设置推流状态为 true
                // 停止所有已初始化的资源
                stop()
                throw connectException ?: java.io.IOException("RTMP connection timeout")
            }
            
            // 2. 初始化视频编码器 (先创建 surface)
            initVideoEncoder()
            
            // 🔥 关键修复：记录统一的推流开始时间（纳秒）
            streamStartTimeNs = System.nanoTime()
//            Log.d(TAG, "🕒 Stream start time baseline: ${streamStartTimeNs}ns")
                        
            // 3. 🔥 Camera 模式跳过 VirtualDisplay 创建
            if (!isCameraMode) {
                createVirtualDisplay()
            } else {
//                Log.d(TAG, "Camera mode: skipping VirtualDisplay creation")
            }
                        
            // 4. 如果启用音频，初始化音频录制
            if (useAudio) {
                // 🔥 关键修复：无论是否使用外部音频源，都需要初始化 MediaCodec 音频编码器
                if (useExternalAudio) {
//                    Log.d(TAG, "Using external PCM audio source, initializing MediaCodec encoder")
                } else {
//                    Log.d(TAG, "Using internal audio recording")
                }
                initAudioEncoder()  // 🔥 总是初始化音频编码器
            }
            
            // 5. 启动编码线程
            startEncodeThread()
            
            isEncoding = true
            onStreamStateChanged?.invoke(true)
            
//            Log.d(TAG, "Encoder started successfully")
            
        } catch (e: Exception) {
//            Log.e(TAG, "Failed to start encoder", e)
            onError?.invoke("启动编码器失败：${e.message}")
            stop()
        }
    }
    
    /**
     * 停止编码和推流
     */
    fun stop() {
//        Log.d(TAG, "Stopping encoder...")
        isEncoding = false
        
        // 停止编码线程
        stopEncodeThread()
        
        // 停止音频录制
        stopAudioRecorder()
        
        // 释放视频编码器
        releaseVideoEncoder()
        
        // 断开 RTMP 连接
        rtmpPusher?.disconnect()
        rtmpPusher = null
        
        isEncoding = false
        onStreamStateChanged?.invoke(false)
        
//        Log.d(TAG, "Encoder stopped")
    }
    
    /**
     * 切换推流地址（动态切换）
     */
    fun switchRtmpUrl(newUrl: String) {
        rtmpPusher?.disconnect()
        rtmpPusher = RtmpPusher()
        rtmpPusher?.connect(newUrl)
//        Log.d(TAG, "Switched to new RTMP URL: $newUrl")
    }
    
    /**
     * 初始化视频编码器
     */
    private fun initVideoEncoder() {
        try {
//            Log.d(TAG, "Initializing video encoder...")
            
            // 创建 MediaFormat
            val format = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, 
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                // 🔥 极低延迟优化：启用编码器低延迟模式
                setInteger("low-latency", 1)  // 某些设备支持此私有参数
                // 🔥 提高操作速率，减少编码队列延迟
                setInteger(MediaFormat.KEY_OPERATING_RATE, frameRate * 2)
                // 🔥 关键优化：降低编码质量以换取速度（可选）
                setInteger(MediaFormat.KEY_QUALITY, 0)  // 0=最快，100=最高质量
                // 🔥 禁用B帧和参考帧优化，进一步降低延迟
//                setInteger("vendor.qti-ext-enc-disable-b-frame", 1)  // Qualcomm专有
            }
            
//            Log.d(TAG, "Creating encoder with format: $format")
            
            // 创建编码器
            mediaCodecVideo = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                surface = this@apply.createInputSurface()
                start()
                
//                Log.d(TAG, "MediaCodec started, will get SPS/PPS from codec config buffer and/or output format")
                
                // 某些编码器会在 start 后更新 outputFormat 包含 csd-0/csd-1
                // 我们先保存这个 format，稍后可能需要用它补充缺失的 PPS
                val actualFormat = this.outputFormat
//                Log.d(TAG, "Output format: ${actualFormat.toString()}")
                
                // 检查是否有 csd-0 和 csd-1
                val spsBuffer = actualFormat.getByteBuffer("csd-0")
                val ppsBuffer = actualFormat.getByteBuffer("csd-1")
                
                if (spsBuffer != null && ppsBuffer != null) {
                    val sps = ByteArray(spsBuffer.remaining())
                    spsBuffer.get(sps)
                    val pps = ByteArray(ppsBuffer.remaining())
                    ppsBuffer.get(pps)
                    
//                    Log.d(TAG, "Found SPS in output format, size=${sps.size}")
//                    Log.d(TAG, "Found PPS in output format, size=${pps.size}")
                    
                    // 去除起始码
                    val strippedSps = stripStartCode(sps)
                    val strippedPps = stripStartCode(pps)
                    
//                    Log.d(TAG, "SPS: ${sps.size} bytes -> ${strippedSps.size} bytes (after stripping start code)")
//                    Log.d(TAG, "PPS: ${pps.size} bytes -> ${strippedPps.size} bytes (after stripping start code)")
                    
                    // 立即合并成完整的 AVCDecoderConfigurationRecord（使用去除起始码的数据）
                    val avccConfig = mergeSpsPpsToAVCC(strippedSps, strippedPps)
//                    Log.d(TAG, "Merged AVCC config size=${avccConfig.size}, preview: ${avccConfig.take(20).joinToString(" ") { "%02X".format(it) }}")
                    
                    // 保存到字段，等待 codec config flag 时再发送（双保险）
                    cachedAVCCConfig = avccConfig
                } else {
//                    Log.w(TAG, "Incomplete SPS/PPS in output format, will wait for codec config buffer")
                }
            }
            
//            Log.d(TAG, "Video encoder initialized: ${width}x${height}, bitrate=$videoBitrate")
            
        } catch (e: Exception) {
//            Log.e(TAG, "Failed to initialize video encoder", e)
            throw e
        }
    }
    
    /**
     * 创建虚拟显示器（使用编码器的 input surface）
     */
    private fun createVirtualDisplay() {
        val displaySurface = surface ?: run {
//            Log.e(TAG, "Cannot create VirtualDisplay: surface is null")
            return
        }
        
        // 不再自己创建 VirtualDisplay，而是通知 MediaProjectionService 更新它的 VirtualDisplay
        // VirtualDisplay 已经在 Service 启动时创建，现在只需要更新它的 surface
//        android.util.Log.d(TAG, "Requesting to update VirtualDisplay surface with encoder's input surface")
        
        // 通过 binder 调用 MediaProjectionService 的 updateVirtualDisplaySurface
        mediaProjectionServiceBinder?.let { binder ->
            if (binder is MediaProjectionService.LocalBinder) {
                try {
                    binder.updateVirtualDisplaySurface(displaySurface, width, height)
//                    Log.d(TAG, "✓ VirtualDisplay surface updated successfully")
                } catch (e: Exception) {
//                    Log.e(TAG, "✗ Failed to update VirtualDisplay surface: ${e.message}", e)
                }
            } else {
//                Log.w(TAG, "Invalid binder type, cannot update VirtualDisplay surface")
            }
        } ?: run {
//            Log.w(TAG, "MediaProjectionService binder not set, VirtualDisplay will use dummy surface")
        }
    }

    
    /**
     * 初始化 AAC 音频编码器
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun initAudioEncoder() {
        try {
//            Log.d("AudioCapture", "🔊 [INIT] Starting audio encoder initialization...")
//            Log.d("AudioCapture", "🔊 [INIT] Params: sampleRate=$audioSampleRate, channels=$audioChannelCount, bitrate=$audioBitrate")
            
            val format = MediaFormat.createAudioFormat(AUDIO_MIME_TYPE, audioSampleRate, audioChannelCount).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, audioBitrate)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                // 🔥 关键修复：明确设置为非 ADTS 格式（裸 AAC ES 流）
                // RTMP/FLV 规范要求 AAC raw frame 必须是纯 ES 流，不带 ADTS 头
                // 参考：https://www.cnblogs.com/8335IT/p/18208384
                setInteger(MediaFormat.KEY_IS_ADTS, 0) // 0=false, 输出裸 AAC ES 流
//                Log.d("AudioCapture", "🎼 [INIT] Audio encoder format: KEY_IS_ADTS=0 (RAW AAC ES, no ADTS header)")
            }
            
//            Log.d("AudioCapture", "🔊 [INIT] Creating MediaCodec encoder...")
            
            // 🔥 关键修复：尝试使用软件编码器，避免硬件编码器不兼容问题
            try {
                // 先尝试创建硬件编码器（性能更好）
                mediaCodecAudio = MediaCodec.createEncoderByType(AUDIO_MIME_TYPE).apply {
//                    Log.d("AudioCapture", "🔊 [INIT] Using hardware AAC encoder")
                    configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    start()
//                    Log.d("AudioCapture", "🎼 [INIT] ✅ Audio encoder started successfully")
                }
            } catch (e: Exception) {
//                Log.w(TAG, "⚠️ Hardware encoder failed: ${e.message}, trying software encoder...")
                
                // 查找软件编码器
                val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                var softwareCodecName: String? = null
                
                for (codecInfo in codecList.codecInfos) {
                    if (codecInfo.isEncoder && codecInfo.supportedTypes.any { it.equals(AUDIO_MIME_TYPE, ignoreCase = true) }) {
                        if (codecInfo.name.contains("google|ffmpeg|software", ignoreCase = true)) {
                            softwareCodecName = codecInfo.name
                            break
                        }
                    }
                }
                
                if (softwareCodecName != null) {
//                    Log.d(TAG, "🔊 [INIT] Using software AAC encoder: $softwareCodecName")
                    mediaCodecAudio = MediaCodec.createByCodecName(softwareCodecName).apply {
                        configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        start()
//                        Log.d("AudioCapture", "🎼 [INIT] ✅ Software audio encoder started successfully")
                    }
                } else {
                    throw IllegalStateException("No AAC encoder found on this device!")
                }
            }
            
            audioBufferInfo = MediaCodec.BufferInfo()
//            Log.d("AudioCapture", "✅ [INIT] Audio encoder fully initialized: sampleRate=$audioSampleRate, channels=$audioChannelCount, bitrate=$audioBitrate")
//            Log.d("AudioCapture", "🔊 [INIT] Ready to accept PCM data")
            
        } catch (e: Exception) {
//            Log.e(TAG, "❌ [INIT] Failed to initialize audio encoder: ${e.message}", e)
            // 音频初始化失败不影响视频
        }
    }
    
    /**
     * 在启动后尽快尝试提取 ASC（避免 CODEC_CONFIG 丢失）
     */
    fun tryExtractAudioASCEarly() {
        if (mediaCodecAudio == null) {
//            Log.w(TAG, "Cannot extract ASC - audio encoder not initialized")
            return
        }
        
        try {
            // 非阻塞尝试获取输出
            val outputBufferIndex = mediaCodecAudio!!.dequeueOutputBuffer(audioBufferInfo!!, 0)
            
            if (outputBufferIndex >= 0 && (audioBufferInfo!!.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                val currentFormat = mediaCodecAudio!!.outputFormat
                val csd0Buffer = currentFormat.getByteBuffer("csd-0")
                
                if (csd0Buffer != null) {
                    csd0Buffer.rewind()
                    val asc = ByteArray(csd0Buffer.remaining())
                    csd0Buffer.get(asc)
                    
                    cachedAudioSpecificConfig = asc
//                    Log.d("AudioCapture", "✅ [EARLY] Extracted ASC: ${asc.size} bytes, ${asc.joinToString(" ") { "%02X".format(it) }}")
                    
                    // 🔥 关键修复：改用 setAudioSpecificConfigAndSend 来立即发送 Sequence Header
                    rtmpPusher?.setAudioSpecificConfigAndSend(asc)
//                    Log.d("AudioCapture", "📤 [EARLY] AAC Sequence Header sent!")
                }
                
                mediaCodecAudio!!.releaseOutputBuffer(outputBufferIndex, false)
            }
        } catch (e: Exception) {
            // 不关键，继续
        }
    }
    
    /**
     * 旧的 AudioRecord 初始化方法（保留备用）
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun initAudioRecorder() {
        try {
            val channelConfig = if (audioChannelCount == 2) {
                AudioFormat.CHANNEL_IN_STEREO
            } else {
                AudioFormat.CHANNEL_IN_MONO
            }
            
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioRecord.getMinBufferSize(audioSampleRate, channelConfig, audioFormat)
            
            // 不再使用 AudioRecord，改用 MediaCodec
//            Log.d(TAG, "Using MediaCodec AAC encoder instead of AudioRecord")
            
        } catch (e: Exception) {
//            Log.e(TAG, "Failed to initialize audio recorder", e)
        }
    }
    
    /**
     * 启动编码线程
     */
    private fun startEncodeThread() {
        encodeThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            
            if (useAudio) {
                startAudioRecording()
            }
            
            encodeVideoLoop()
            
        }, "SurfaceTextureEncoder-EncodeThread")
        
        encodeThread?.start()
//        Log.d(TAG, "Encode thread started")
    }
    
    /**
     * 停止编码线程
     */
    private fun stopEncodeThread() {
        encodeThread?.interrupt()
        encodeThread?.join(1000)
        encodeThread = null
//        Log.d(TAG, "Encode thread stopped")
    }
    
    /**
     * 视频编码循环
     */
    private var spsPpsSent = false  // 标记是否已发送 SPS/PPS
    private var initialVideoPtsUs = -1L // 第一个视频帧的时间戳（用于计算相对时间）
    
    // 🔥 关键修复：视频时间戳必须独立维护，保证单调递增
    // RTMP 协议要求：音频流、视频流各自单调递增，混合流也应单调递增
    private var lastVideoTimestampMs = 0L // 上一个视频帧的时间戳
    
    private fun encodeVideoLoop() {
        videoBufferInfo = MediaCodec.BufferInfo()
        var consecutiveErrors = 0
        val maxConsecutiveErrors = 10
        
        while (!Thread.interrupted() && isEncoding) {
            try {
                // 1. 从编码器获取输出 buffer
                val outputBufferIndex = mediaCodecVideo?.dequeueOutputBuffer(videoBufferInfo!!, 10000)
                    ?: continue
                
                when {
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
//                        Log.d(TAG, "Output format changed")
                        mediaCodecVideo?.outputFormat?.let { newFormat ->
//                            Log.d(TAG, "New format: $newFormat")
                                                    
                            // 立即从新的 format 中提取 SPS/PPS 并发送
                            val spsBuffer = newFormat.getByteBuffer("csd-0")
                            val ppsBuffer = newFormat.getByteBuffer("csd-1")
                                                    
                            if (spsBuffer != null && ppsBuffer != null) {
//                                Log.d(TAG, "Extracting SPS/PPS from output format")
                                spsBuffer.rewind()
                                ppsBuffer.rewind()
                                                        
                                val sps = ByteArray(spsBuffer.remaining())
                                val pps = ByteArray(ppsBuffer.remaining())
                                spsBuffer.get(sps)
                                ppsBuffer.get(pps)
                                                        
//                                Log.d(TAG, "Raw SPS size: ${sps.size}, Raw PPS size: ${pps.size}")
//                                Log.d(TAG, "Raw SPS preview: ${sps.take(10).joinToString(" ") { "%02X".format(it) }}")
//                                Log.d(TAG, "Raw PPS preview: ${pps.take(10).joinToString(" ") { "%02X".format(it) }}")
                                                        
                                // 去除起始码
                                val strippedSps = stripStartCode(sps)
                                val strippedPps = stripStartCode(pps)
                                                        
//                                Log.d(TAG, "Stripped SPS size: ${strippedSps.size}, Stripped PPS size: ${strippedPps.size}")
//                                Log.d(TAG, "Stripped SPS preview: ${strippedSps.take(10).joinToString(" ") { "%02X".format(it) }}")
//                                Log.d(TAG, "Stripped PPS preview: ${strippedPps.take(10).joinToString(" ") { "%02X".format(it) }}")
                                                        
                                // 合并成 AVCC 格式（使用去除起始码的数据）
                                val avccConfig = mergeSpsPpsToAVCC(strippedSps, strippedPps)
                                cachedAVCCConfig = avccConfig
//                                Log.d(TAG, "Created AVCC config from output format, size=${avccConfig.size}")
                                                        
                                // 打印 AVCC 前 20 字节验证
                                val hexPreview = avccConfig.take(20).joinToString(" ") { "%02X".format(it) }
//                                Log.d(TAG, "AVCC config preview: $hexPreview")
                                                        
                                // 立即发送 SPS/PPS(RTMP Sequence Header)
                                if (!spsPpsSent && rtmpPusher != null) {
                                    rtmpPusher!!.sendVideoSpsPps(avccConfig)
                                    spsPpsSent = true
//                                    Log.d(TAG, "✓✓✓ Video Sequence Header sent successfully from INFO_OUTPUT_FORMAT_CHANGED! ✓✓✓")
                                }
                            } else {
//                                Log.w(TAG, "Output format changed but csd-0/csd-1 not available yet")
                            }
                        }
                    }
                    outputBufferIndex >= 0 -> {
                        val outputBuffer = mediaCodecVideo?.getOutputBuffer(outputBufferIndex)
                        
                        if (outputBuffer != null && videoBufferInfo!!.size > 0) {
                            // 读取编码数据
                            val chunk = ByteArray(videoBufferInfo!!.size)
                            outputBuffer.get(chunk)
                            outputBuffer.clear()
                            
                            // 处理编码数据
                            try {
                                processVideoData(chunk, videoBufferInfo!!)
                                consecutiveErrors = 0
                            } catch (e: Exception) {
                                consecutiveErrors++
//                                Log.e(TAG, "Error processing video data ($consecutiveErrors/$maxConsecutiveErrors): ${e.message}")
                                if (consecutiveErrors >= maxConsecutiveErrors) {
//                                    Log.e(TAG, "Too many consecutive errors, stopping encoding")
                                    isEncoding = false
                                    onError?.invoke("视频处理错误过多，停止编码")
                                }
                            }
                        } else {
//                            Log.w(TAG, "Empty output buffer at index $outputBufferIndex")
                        }
                        
                        // 释放 buffer
                        mediaCodecVideo?.releaseOutputBuffer(outputBufferIndex, false)
                    }
                    outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // 暂时没有数据，继续
                    }
                }
                
            } catch (e: InterruptedException) {
//                Log.d(TAG, "Encode loop interrupted")
                break
            } catch (e: Exception) {
                if (isEncoding) {
//                    Log.e(TAG, "Error in video encoding loop", e)
                }
            }
        }
        
//        Log.d(TAG, "Video encoding loop finished")
    }
    
    /**
     * 处理视频编码数据
     */
    private fun processVideoData(data: ByteArray, bufferInfo: MediaCodec.BufferInfo) {
        // 在发送前确保 RTMP 已初始化
        val pusher = rtmpPusher
        if (pusher == null) {
//            Log.w(TAG, "RTMP Pusher not initialized, dropping frame")
            return
        }
        
        try {
            // 检查是否是 codec config 数据（包含 SPS/PPS）
            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
//                Log.d(TAG, "Processing codec config buffer, size=${data.size}")
                
                // 打印前 20 字节查看格式
                val hexPreview = data.take(20).joinToString(" ") { "%02X".format(it) }
//                Log.d(TAG, "Codec config preview: $hexPreview")
                
                // 如果还没有发送过 SPS/PPS，则发送
                if (!spsPpsSent) {
                    // 优先使用缓存的 AVCC 配置（从 csd-0/csd-1 合并的标准格式）
                    val avccConfig = cachedAVCCConfig
                    if (avccConfig != null) {
//                        Log.d(TAG, "Using cached AVCC config from csd-0/csd-1, size=${avccConfig.size}")
                        pusher.sendVideoSpsPps(avccConfig)
                        spsPpsSent = true
//                        Log.d(TAG, "SPS/PPS sent successfully using cached AVCC config!")
                    } else {
                        // 备用方案：尝试从 codec config buffer 中提取 SPS/PPS
//                        Log.w(TAG, "No cached AVCC config, attempting to parse codec config buffer...")
                        
                        // 检查是否是 AnnexB 格式（00 00 00 01 开头）
                        if (data.size > 4 && data[0] == 0x00.toByte() && data[1] == 0x00.toByte() && 
                            data[2] == 0x00.toByte() && data[3] == 0x01.toByte()) {
//                            Log.d(TAG, "Codec config is AnnexB format, extracting SPS/PPS")
                            
                            // 简单提取：假设包含 SPS 和 PPS
                            var pos = 0
                            val nalUnits = mutableListOf<ByteArray>()
                            
                            while (pos < data.size - 4) {
                                // 查找起始码
                                if (data[pos] == 0x00.toByte() && data[pos + 1] == 0x00.toByte() &&
                                    (data[pos + 2] == 0x00.toByte() || (data[pos + 2] == 0x01.toByte() && data[pos + 3] == 0x01.toByte()))) {
                                    
                                    // 跳过起始码
                                    val startCodeSize = if (data[pos + 2] == 0x01.toByte()) 3 else 4
                                    pos += startCodeSize
                                    
                                    // 找到下一个起始码
                                    val nextPos = data.indexOfFirst { 
                                        it == 0x00.toByte() && pos + it < data.size - 3 &&
                                        data[pos + it + 1] == 0x00.toByte() &&
                                        (data[pos + it + 2] == 0x00.toByte() || data[pos + it + 2] == 0x01.toByte())
                                    }.let { if (it == -1) data.size else pos + it }
                                    
                                    val nalUnit = data.copyOfRange(pos, nextPos)
                                    val nalType = (nalUnit[0].toInt() and 0x1F)
                                    
                                    if (nalType == 7) {
                                        nalUnits.add(nalUnit) // SPS
//                                        Log.d(TAG, "Extracted SPS from AnnexB, size=${nalUnit.size}")
                                    } else if (nalType == 8) {
                                        nalUnits.add(nalUnit) // PPS
//                                        Log.d(TAG, "Extracted PPS from AnnexB, size=${nalUnit.size}")
                                    }
                                    
                                    pos = nextPos
                                } else {
                                    pos++
                                }
                            }
                            
                            // 如果有 SPS 和 PPS，合并成 AVCC
                            if (nalUnits.size >= 2) {
                                val sps = nalUnits.first()
                                val pps = nalUnits.last()
                                val avccFromAnnexB = mergeSpsPpsToAVCC(sps, pps)
                                pusher.sendVideoSpsPps(avccFromAnnexB)
                                spsPpsSent = true
//                                Log.d(TAG, "SPS/PPS extracted from AnnexB and sent!")
                            } else {
//                                Log.e(TAG, "Failed to extract complete SPS/PPS from codec config buffer")
                            }
                        } else {
//                            Log.e(TAG, "Unknown codec config format, dropping")
                        }
                    }
                } else {
//                    Log.v(TAG, "Skipping duplicate codec config buffer")
                }
                return
            }
            
            // 检查是否是关键帧
            val isKeyFrame = bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
            
            // 🔥 关键修复：视频时间戳使用 MediaCodec 的 presentationTimeUs（monotonic clock）
            // 协议依据：OBS/FFmpeg 标准做法 - 视频帧使用系统单调时钟，而非固定增量
            // 优势：
            //   1. 反映实际编码时间，不受帧率波动影响
            //   2. MediaCodec 内部已保证单调递增
            //   3. 与音频的固定增量模式形成互补，播放器可正确同步
            
            val presentationTimeMs = if (initialVideoPtsUs == -1L) {
                // 第一帧：初始化基准
                initialVideoPtsUs = bufferInfo.presentationTimeUs
//                Log.d(TAG, "📊 Initial video PTS: $initialVideoPtsUs us")
                0L
            } else {
                // 后续帧：计算相对于第一帧的时间（微秒 → 毫秒）
                val relativePtsUs = bufferInfo.presentationTimeUs - initialVideoPtsUs
                val timestampMs = relativePtsUs / 1000
                
                // 🔥 防御性检查：确保单调递增（理论上 MediaCodec 已保证）
                if (timestampMs <= lastVideoTimestampMs) {
                    // 异常情况：时间戳倒退，使用上一个 + 1ms
//                    Log.w(TAG, "⚠️ Video timestamp would go backwards! Corrected from $timestampMs to ${lastVideoTimestampMs + 1}")
                    lastVideoTimestampMs + 1
                } else {
                    timestampMs
                }.also { lastVideoTimestampMs = it }
            }
            
//            Log.d(TAG, "🎬 Video frame: key=$isKeyFrame, ts=$presentationTimeMs ms (pts=${bufferInfo.presentationTimeUs}us)")
            
            // 发送视频数据（带错误检查和格式转换）
            try {
                // 检测帧数据格式
                val frameData = if (data.size >= 4) {
                    // 🔥 性能优化：移除高频日志，减少 GC 压力
                    // val hex = data.take(4).joinToString(" ") { "%02X".format(it) }
                    // Log.d(TAG, "Frame start bytes: $hex")
                    
                    // 判断是否是 AnnexB 格式
                    if (isAnnexB(data)) {
                        // 检查是 3 字节还是 4 字节起始码
                        val startCodeSize = if (data[2] == 0x00.toByte()) 4 else 3
//                        Log.w(TAG, "WARNING: Frame is in AnnexB format (${startCodeSize}-byte start code)! Converting to AVCC...")
                        annexBToAvcc(data)
                    } else {
//                        Log.v(TAG, "Frame appears to be in AVCC format, sending directly")
                        data
                    }
                } else {
                    data
                }
                
                pusher.sendVideoData(frameData, presentationTimeMs, isKeyFrame)
                
                // 🔥 性能优化：移除高频诊断日志，仅在关键帧打印
                if (isKeyFrame) {
//                    Log.d(TAG, "Sent key frame, size=${frameData.size}, pts=${presentationTimeMs}ms")
                } else {
                    // Log.v(TAG, "Sent video frame, size=${frameData.size}, pts=${presentationTimeMs}ms")
                }
            } catch (e: Exception) {
//                Log.e(TAG, "Failed to send video frame: ${e.message}")
            }
            
        } catch (e: Exception) {
//            Log.e(TAG, "Error processing video data", e)
        }
    }
    
    /**
     * 开始音频录制
     */
    private fun startAudioRecording() {
        if (useExternalAudio) {
//            Log.d("AudioCapture", "🔍 Starting external audio capture")
            
            // 🔥 初始化音频时间戳计数器
            audioLastOutputTimeMs = 0.0
//            Log.d("AudioEncode", "🎯 Audio timestamp counter initialized")
            
            startExternalAudioCapture()
            
            // 🔥 注意：不再提前提取 ASC，因为编码器需要先输入 PCM 才能输出
            // ASC 会在 encodeAndSendAudioData 中自然提取
//            Log.d("AudioCapture", "ℹ️ ASC will be extracted during first encoding")
        } else {
            // 如果没有 external audio source，改用 MediaCodec 编码
//            Log.d("AudioCapture", "Audio encoder mode not yet implemented, audio disabled")
        }
    }

    
    /**
     * 从外部 PCM 源捕获音频 - 修复版
     */
    private fun startExternalAudioCapture() {
        audioThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            
            val bytesPerFrame = audioSampleRate * audioChannelCount * 2 / frameRate // 每帧的字节数
            var frameCount = 0L
            var consecutiveFailures = 0
            val maxFailures = 100
            var lastSuccessTime = System.currentTimeMillis() // 🔥 记录最后成功时间
            
//            Log.d("AudioCapture", "🎵 External audio capture started, expected frame size: $bytesPerFrame bytes")
//            Log.d("AudioCapture", "Audio params: sampleRate=$audioSampleRate, channels=$audioChannelCount, frameRate=$frameRate")
//            Log.d("AudioCapture", "🔍 externalAudioSource is ${if (externalAudioSource != null) "set" else "NULL!"}")

            while (!Thread.interrupted() && isEncoding && consecutiveFailures < maxFailures) {
                try {
                    // 🔥 关键诊断：检查 externalAudioSource 回调
                    if (externalAudioSource == null) {
//                        Log.e("AudioEncode", "❌ [CRITICAL] externalAudioSource is NULL! Cannot get PCM data!")
                        Thread.sleep(10)
                        continue
                    }
                    
                    val pcmData = externalAudioSource.invoke()
                    
                    // 🔥 详细诊断 PCM 数据获取
                    if (pcmData == null) {
                        consecutiveFailures++
                        // 🔥 关键修复：增加等待时间，避免过度消耗 CPU 和造成假性繁忙
                        val sleepTime = when {
                            consecutiveFailures <= 5 -> 10L  // 前 5 次快速重试
                            consecutiveFailures <= 20 -> 50L // 中期放慢速度
                            else -> 100L                     // 长期无数据时大幅降低频率
                        }
                        
                        // 🔥 降低日志频率，只在关键节点打印
                        if (consecutiveFailures <= 5 || consecutiveFailures % 10 == 0) {
//                            Log.w("AudioEncode", "⚠️ [Frame #$frameCount] externalAudioSource returned NULL! Failures: $consecutiveFailures, sleep=${sleepTime}ms")
                        }
                        Thread.sleep(sleepTime)
                        continue
                    }
                    
                    if (pcmData.isEmpty()) {
                        consecutiveFailures++
                        // 🔥 关键修复：增加等待时间
                        val sleepTime = when {
                            consecutiveFailures <= 5 -> 10L
                            consecutiveFailures <= 20 -> 50L
                            else -> 100L
                        }
                        
                        if (consecutiveFailures <= 5 || consecutiveFailures % 10 == 0) {
//                            Log.w("AudioEncode", "⚠️ [Frame #$frameCount] externalAudioSource returned EMPTY array! Failures: $consecutiveFailures, sleep=${sleepTime}ms")
                        }
                        Thread.sleep(sleepTime)
                        continue
                    }
                    
                    // ✅ 成功获取 PCM 数据
                    consecutiveFailures = 0
                    frameCount++
                    lastSuccessTime = System.currentTimeMillis() // 🔥 更新最后成功时间
                    
                    // 🔥 降低日志频率，避免影响性能
                    if (frameCount % 100 == 0L) {
//                        Log.d("AudioCapture", "📥 [Frame #$frameCount] Got PCM from source: size=${pcmData.size} bytes")
//                        Log.d("AudioCapture", "   Sample rate: $audioSampleRate Hz, Channels: $audioChannelCount")
                    }
                    
                    // 将 PCM 数据输入到 MediaCodec 进行编码
                    encodeAndSendAudioData(pcmData)
                    
                    // 🔥 关键修复：移除固定延迟，改用动态等待策略
                    // 原因：75ms 延迟导致编码器输入输出不同步，造成数据堆积和损坏
                    // 新策略：让编码器尽快处理数据，通过 RingBuffer 自然调节
                    
                } catch (e: InterruptedException) {
                    // 线程中断，正常退出
//                    Log.d("AudioCapture", "Audio capture thread interrupted")
                    break
                } catch (e: Exception) {
                    if (isEncoding) {
//                        Log.e(TAG, "Error reading external audio data: ${e.message}")
                    }
                    consecutiveFailures++
                    Thread.sleep(5)
                }
                
                // 🔥 关键修复：检测音频采集超时（超过 5 秒无数据），提示用户检查屏幕状态
                val noDataDuration = System.currentTimeMillis() - lastSuccessTime
                if (noDataDuration > 5000 && consecutiveFailures > 10) {
//                    Log.e("AudioCapture", "❌ [CRITICAL] No audio data for ${noDataDuration}ms! MediaProjection audio may be paused by system.")
//                    Log.e("AudioCapture", "   Please ensure: 1) Screen is ON, 2) App is in foreground")
                    // 重置时间戳，避免重复打印
                    lastSuccessTime = System.currentTimeMillis()
                }
            }
            
//            Log.d("AudioCapture", "External audio capture thread finished, total frames received: $frameCount, total failures: $consecutiveFailures")
            
        }, "SurfaceTextureEncoder-ExternalAudioThread")
        
        audioThread?.start()
//        Log.d("AudioCapture", "✅ External audio capture thread started")
    }
    
    /**
     * 停止音频录制
     */
    private fun stopAudioRecorder() {
        isAudioRecording = false
        audioThread?.interrupt()
        audioThread?.join(1000)
        audioThread = null
        
        try {
            mediaCodecAudio?.stop()
            mediaCodecAudio?.release()
        } catch (e: Exception) {
//            Log.e(TAG, "Error releasing audio encoder", e)
        }
        mediaCodecAudio = null
        
//        Log.d("AudioCapture", "Audio recorder stopped")
    }
    
    /**
     * 将 PCM 数据编码为 AAC 并发送到 RTMP
     * 
     * 🔥 协议依据：
     * 1. RTMP/FLV 规范：AAC Sequence Header + AAC Raw Frame
     * 2. Android MediaCodec 官方文档：CODEC_CONFIG buffer 必须在普通数据之前处理
     * 3. 时间戳规范：音视频必须使用同一系统时间基准
     * 
     * 🔥 关键修复点：
     * - 输入缓冲区满时，主动处理输出来腾出空间
     * - 时间戳基于输入的采样点数计算，而非系统时间
     * - 确保 CODEC_CONFIG 数据被完整处理
     */
    private var audioInputFrameCount = 0L
    private var audioOutputFrameCount = 0L
    private var audioTotalSamplesQueued = 0L // 已输入编码器的总采样数
    
    private fun encodeAndSendAudioData(pcmData: ByteArray) {
        if (mediaCodecAudio == null) {
//            Log.w(TAG, "❌ Audio encoder not initialized!")
            return
        }
        
        try {
            // ========== 第一步：处理所有待输出的数据 ==========
            // 这很关键！必须先处理输出，否则输入缓冲区会堆积
            drainAudioEncoderOutput()
            
            // ========== 第二步：输入新的 PCM 数据 ==========
            queueAudioInputData(pcmData)
            
            // ========== 第三步：再次处理可能生成的新输出 ==========
            // 某些编码器会立即产生输出
            drainAudioEncoderOutput()
            
        } catch (e: Exception) {
//            Log.e(TAG, "❌ Error encoding audio: ${e.message}", e)
        }
    }
    
    /**
     * 处理编码器输出（提取所有待发送的 AAC 数据）
     * 
     * 🔥 关键修复：音频时间戳基于实际输出的帧数累加
     * 优势：
     *   1. 完全避免累积误差（理论值就是精确的）
     *   2. 批量输出时自然保持正确间隔
     *   3. 视频时间戳会与此对齐，保证音画同步
     */
    private fun drainAudioEncoderOutput() {
        if (mediaCodecAudio == null) return
        
        var drainedCount = 0
        
        while (true) {
            // 🔥 关键修复：使用更短的超时时间（1ms），让编码器尽快输出
            // 原因：之前的 0 超时可能导致某些编码器不输出数据
            val outputBufferIndex = mediaCodecAudio!!.dequeueOutputBuffer(audioBufferInfo!!, 1000) // 1ms
            
            when {
                outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // 没有更多输出
                    if (drainedCount > 0) {
//                        Log.v("AudioEncode", "🔄 Drained $drainedCount output buffers")
                    }
                    break
                }
                
                outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
//                    Log.d("AudioEncode", "🎼 Audio output format changed")
                    // 继续处理
                }
                
                outputBufferIndex >= 0 -> {
                    val bufferInfo = audioBufferInfo!!
                    
                    // 检查是否是 CODEC_CONFIG（包含 AudioSpecificConfig）
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
//                        Log.d("AudioEncode", "🎼 Found CODEC_CONFIG buffer, size=${bufferInfo.size}")
                        
                        // 从 outputFormat 获取 ASC
                        val currentFormat = mediaCodecAudio!!.outputFormat
                        val csd0Buffer = currentFormat.getByteBuffer("csd-0")
                        
                        if (csd0Buffer != null) {
                            csd0Buffer.rewind()
                            val asc = ByteArray(csd0Buffer.remaining())
                            csd0Buffer.get(asc)
                            
                            // 🔥 关键修复：只在第一次设置 ASC，避免重复发送 Sequence Header
                            if (cachedAudioSpecificConfig == null) {
                                cachedAudioSpecificConfig = asc
//                                Log.d("AudioEncode", "✅ Extracted ASC: ${asc.size} bytes, ${asc.joinToString(" ") { "%02X".format(it) }}")
                                
                                // 🔥 立即发送到 RTMP - 不要等第一个数据帧！
                                if (rtmpPusher != null) {
                                    rtmpPusher!!.setAudioSpecificConfigAndSend(asc)
//                                    Log.d("AudioEncode", "📤 AAC Sequence Header sent immediately!")
                                }
                            } else {
//                                Log.d("AudioEncode", "⚠️ ASC already set, ignoring duplicate CODEC_CONFIG")
                            }
                        }
                        
                        mediaCodecAudio!!.releaseOutputBuffer(outputBufferIndex, false)
                        drainedCount++
                        continue
                    }
                    
                    // 处理普通 AAC 音频数据
                    if (bufferInfo.size > 0) {
                        // 🔥 关键修复：确保 ASC 已设置，否则跳过该帧
                        if (cachedAudioSpecificConfig == null) {
//                            Log.w("AudioEncode", "⚠️ Skipping audio frame #$audioOutputFrameCount: ASC not ready yet. Waiting for CODEC_CONFIG...")
                            mediaCodecAudio!!.releaseOutputBuffer(outputBufferIndex, false)
                            continue
                        }
                        
                        val outputBuffer = mediaCodecAudio!!.getOutputBuffer(outputBufferIndex)
                        val aacData = ByteArray(bufferInfo.size)
                        outputBuffer?.get(aacData, 0, bufferInfo.size)
                        outputBuffer?.clear()
                        
                        // 🔥 关键修复：音频时间戳基于统一的 streamStartTimeNs 计算
                        // 这样可以确保音频和视频使用相同的时间基准，避免起点不一致导致的音画不同步
                        val elapsedNs = System.nanoTime() - streamStartTimeNs
                        val timestampMs = elapsedNs / 1_000_000
                        
                        // 🔥 诊断日志（降低频率）
                        if (audioOutputFrameCount % 50 == 0L) {
//                            Log.d("AudioEncode", "📤 [Frame #$audioOutputFrameCount] AAC frame: size=${aacData.size}, ts=$timestampMs ms")
                        }
                        
                        if (rtmpPusher != null) {
                            rtmpPusher!!.sendAudioData(aacData, timestampMs.toLong())
                            audioOutputFrameCount++
                            
                            if (audioOutputFrameCount % 100 == 0L) {
//                                Log.d("AudioEncode", "📤 Sent AAC frame #$audioOutputFrameCount: size=${aacData.size}, ts=$timestampMs ms")
                            }
                        } else {
//                            Log.w("AudioEncode", "⚠️ RtmpPusher not available, dropping AAC frame")
                        }
                    }
                    
                    mediaCodecAudio!!.releaseOutputBuffer(outputBufferIndex, false)
                    drainedCount++
                }
            }
        }
    }
    
    /**
     * 向编码器输入 PCM 数据 - 修复版：按 AAC 帧大小对齐
     * 
     * 🔥 关键修复：解决编码器死锁和时间戳溢出问题
     * 🔥 低延迟优化：减小缓冲阈值从10帧到3帧 (~64ms @48kHz)
     * 
     * 问题根源：
     * - AAC 编码器期望每次输入 1024 采样点/声道（4096 字节@48kHz 立体声）
     * - 外部源每次提供 15360 字节（3.75 帧），导致编码器缓存 0.75 帧
     * - 累积几次后输入缓冲区满，编码器无法输出，形成死锁
     * - 时间戳持续累加导致溢出
     * 
     * 解决方案：
     * - 将 PCM 数据拆分为完整的 AAC 帧（每帧 4096 字节）
     * - 不完整的帧累积到下次再发送
     * - 时间戳基于当前帧的相对位置，而非总累加值
     */
    private var audioPendingBuffer = ByteArrayOutputStream() // 缓存未完成的 PCM 数据
    private val AAC_FRAME_SIZE = 1024 * audioChannelCount * 2 // 4096 bytes @48kHz stereo
    private var audioLastInputTimeUs = 0L // 上一个输入帧的时间戳（微秒）
    
    // 🔥 音频输出时间戳：使用 Double 累加避免整数除法精度丢失
    private var audioLastOutputTimeMs = 0.0 // 理论累加时间戳（毫秒）
    
    // 🔥 关键修复：音频时间戳校准机制
    private var audioFirstOutputRealtimeMs = -1L // 第一帧音频输出时的系统时间
    private var audioCalibrationFrameCount = 0L // 用于定期校准的帧计数
    
    private val AUDIO_SAMPLES_PER_FRAME = 1024 // AAC 每帧 1024 个采样点
    private var audioPendingOffset = 0 // 🔥 待处理缓冲区的起始偏移量
    
    private fun queueAudioInputData(pcmData: ByteArray) {
        if (mediaCodecAudio == null) {
//            Log.e("AudioEncode", "❌ mediaCodecAudio is NULL! Encoder released?")
            return
        }
        
        // 🔥 关键修复：检查编码器状态（降低日志频率）
        if (audioInputFrameCount % 100 == 0L) {
            try {
                val codecInfo = mediaCodecAudio!!.codecInfo
//                Log.v("AudioEncode", "✅ Audio encoder alive: ${codecInfo?.name}")
            } catch (e: Exception) {
//                Log.e("AudioEncode", "❌ Audio encoder DEAD! Cannot access codecInfo: ${e.message}")
                // 编码器已死，停止采集线程
                isAudioRecording = false
                return
            }
        }
        
        // 🔥 关键：将新数据添加到待处理缓冲区
        audioPendingBuffer.write(pcmData)
        
        // 🔥 循环提交完整的 AAC 帧（使用偏移量避免重复复制）
        while (audioPendingBuffer.size() - audioPendingOffset >= AAC_FRAME_SIZE) {
            // 🔥 性能优化：直接获取内部数组，避免 toByteArray() 复制
            val allData = audioPendingBuffer.toByteArray()
            val frameData = allData.copyOfRange(audioPendingOffset, audioPendingOffset + AAC_FRAME_SIZE)
            
            // 🔥 移动偏移量，指向下一帧的起始位置
            audioPendingOffset += AAC_FRAME_SIZE
            
            // 提交单个完整帧到编码器
            submitAudioFrameToEncoder(frameData)
        }
        
        // 🔥 关键优化：当已处理的数据足够多时，压缩缓冲区（只复制一次剩余数据）
        // 🔥 低延迟优化：从10帧降到3帧，减少缓冲延迟 (~64ms)
        if (audioPendingOffset > AAC_FRAME_SIZE * 3) { // 累积了 3 帧以上就压缩
            val remaining = audioPendingBuffer.size() - audioPendingOffset
            if (remaining > 0) {
                // 🔥 性能优化：直接使用 System.arraycopy，避免 copyOfRange 创建中间对象
                val newData = ByteArray(remaining)
                val allData = audioPendingBuffer.toByteArray()
                System.arraycopy(allData, audioPendingOffset, newData, 0, remaining)
                audioPendingBuffer = ByteArrayOutputStream(remaining)
                audioPendingBuffer.write(newData)
            } else {
                audioPendingBuffer.reset()
            }
            audioPendingOffset = 0 // 重置偏移量
        }
        
        // 🔥 诊断日志：显示缓冲区状态
        if (audioInputFrameCount % 50 == 0L) {
//            Log.d("AudioEncode", "📊 Pending buffer: ${audioPendingBuffer.size() - audioPendingOffset} bytes (${(audioPendingBuffer.size() - audioPendingOffset) / AAC_FRAME_SIZE.toFloat()} frames), offset=$audioPendingOffset")
        }
    }
    
    /**
     * 提交单个 AAC 帧到编码器
     */
    private fun submitAudioFrameToEncoder(frameData: ByteArray) {
        // 尝试获取输入缓冲区
        val inputBufferIndex = mediaCodecAudio!!.dequeueInputBuffer(0)  // 非阻塞
        
        if (inputBufferIndex < 0) {
            // 输入缓冲区满，这是正常现象（输入太快）
            if (audioInputFrameCount % 100 == 0L) {
//                Log.v("AudioEncode", "⚠️ Input buffer full, queued frames: $audioInputFrameCount, output frames: $audioOutputFrameCount, pending: ${audioPendingBuffer.size()} bytes")
            }
            // 🔥 关键：数据已经在 audioPendingBuffer 中，下次会自动重试
            return
        }
        
        try {
            val inputBuffer = mediaCodecAudio!!.getInputBuffer(inputBufferIndex)
            if (inputBuffer == null) {
//                Log.w("AudioEncode", "⚠️ Failed to get input buffer at index $inputBufferIndex")
                return
            }
            
            inputBuffer.clear()
            
            // 确保 PCM 数据不超过缓冲区
            if (frameData.size > inputBuffer.capacity()) {
//                Log.w("AudioEncode", "⚠️ Frame data too large! ${frameData.size} > ${inputBuffer.capacity()}, truncating")
                inputBuffer.put(frameData, 0, inputBuffer.capacity())
            } else {
                inputBuffer.put(frameData)
            }
            
            // 🔥 关键修复：计算递增的时间戳（避免累加溢出）
            // 每帧 AAC 固定 1024 个采样点，时间增量 = 1024 / sampleRate 秒
            val timeIncrementUs = (AUDIO_SAMPLES_PER_FRAME * 1_000_000L) / audioSampleRate
            audioLastInputTimeUs += timeIncrementUs
            
            mediaCodecAudio!!.queueInputBuffer(
                inputBufferIndex,
                0,
                frameData.size,
                audioLastInputTimeUs,
                0  // flags = 0 (不是 EOS)
            )
            
            audioInputFrameCount++
            
            if (audioInputFrameCount % 100 == 0L) {
//                Log.d("AudioEncode", "📥 Queued PCM frame #$audioInputFrameCount: size=${frameData.size}, ts=${audioLastInputTimeUs}us")
            }
            
        } catch (e: Exception) {
//            Log.e("AudioEncode", "❌ Error queuing audio input: ${e.message}")
        }
    }
    
    /**
     * 释放视频编码器
     */
    private fun releaseVideoEncoder() {
        try {
            surface?.release()
            surface = null
            
            mediaCodecVideo?.stop()
            mediaCodecVideo?.release()
            mediaCodecVideo = null
            
//            Log.d(TAG, "Video encoder released")
            
        } catch (e: Exception) {
//            Log.e(TAG, "Error releasing video encoder", e)
        }
    }
    
    /**
     * 获取编码器状态
     */
    fun isRunning(): Boolean = isEncoding
    
    /**
     * 获取当前推流地址
     */
    fun getCurrentRtmpUrl(): String? = rtmpPusher?.getCurrentUrl()
    
    /**
     * 合并 SPS 和 PPS 成标准的 AVCDecoderConfigurationRecord (AVCC 格式)
     */
    private fun mergeSpsPpsToAVCC(sps: ByteArray, pps: ByteArray): ByteArray {
        // AVCDecoderConfigurationRecord 结构：
        // version(1) + profile/level(3) + lengthSizeMinusOne(1) + numOfSPS(1) + spsSize(2) + sps + numOfPPS(1) + ppsSize(2) + pps
        val config = ByteBuffer.allocate(11 + sps.size + pps.size).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(0x01.toByte()) // configurationVersion = 1
            put(sps[1]) // AVCProfileIndication
            put(sps[2]) // profile_compatibility
            put(sps[3]) // AVCLevelIndication
            put(0xFF.toByte()) // 6 bits reserved (111111) + 2 bits lengthSizeMinusOne (11 = 4 bytes)
            put(0xE1.toByte()) // 3 bits reserved (111) + 5 bits numOfSequenceParameterSets (00001 = 1)
            putShort(sps.size.toShort()) // sequenceParameterSetLength
            put(sps) // sequenceParameterSetNALUnit
            put(0x01.toByte()) // numOfPictureParameterSets (1)
            putShort(pps.size.toShort()) // pictureParameterSetLength
            put(pps) // pictureParameterSetNALUnit
        }
        return config.array()
    }
}
