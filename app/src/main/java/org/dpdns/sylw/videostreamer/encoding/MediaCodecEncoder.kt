package org.dpdns.sylw.videostreamer.encoding

import android.Manifest
import android.media.*
import android.os.Bundle
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresPermission
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.floor

/**
 * 基于 MediaCodec 的音视频编码器。
 *
 * 编码器只负责创建输入 Surface、编码以及输出 AVCC/AAC 数据。URL、协议连接、
 * 重连和传输状态均由上层会话管理，避免编码热路径与具体网络协议互相持有。
 */
class MediaCodecEncoder(
    private val width: Int,
    private val height: Int,
    private val videoBitrate: Int,        // 视频码率 bps
    private val frameRate: Int = 30,       // 帧率
    private val iFrameInterval: Int = 1,   // LiveSuite 的实时媒体需要较短 GOP 以便丢帧后快速恢复
    private val videoMode: String = "CBR",
    private val videoQuality: Int = 70,
    private val repeatPreviousFrameAfterUs: Long? = null,
    private val useAudio: Boolean = true,  // 是否使用音频
    private val audioSampleRate: Int = 48000,  // 音频采样率
    private val audioChannelCount: Int = 2,    // 音频声道数
    private val audioBitrate: Int = 128000,     // 音频码率 bps
    private val requireHardwareVideoEncoder: Boolean = false,
    private val onSurfaceReady: ((Surface) -> Unit),
    private val output: EncodedMediaOutput
) : StreamingEncoder {
    companion object {
        private const val TAG = "MediaCodecEncoder"
        private const val VIDEO_MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val AUDIO_MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC
        // H.264 规范的单边最大尺寸，能力表粗筛时使用（超出必不支持）
        private const val MAX_FRAME_DIMENSION = 4096

        /**
         * 根据分辨率×帧率计算 H.264 的最小 AVC Level
         *
         * H.264 Level 定义了宏块/秒上限（MB/s）：
         *   Level 4.0 = 245,760  → 1080p@30
         *   Level 4.2 = 522,240  → 1080p@60
         *   Level 5.0 = 589,824  → ~1080p@72
         *   Level 5.1 = 983,040  → 1080p@120
         *   Level 5.2 = 2,073,600 → 4K@60
         */
        fun computeMinAvcLevel(width: Int, height: Int, fps: Int): Int {
            // 宏块数/帧 = ceil(w/16) * ceil(h/16)
            val mbPerFrame = ((width + 15) / 16) * ((height + 15) / 16)
            val mbPerSec = mbPerFrame.toLong() * fps

            return when {
                mbPerSec <= 245_760  -> MediaCodecInfo.CodecProfileLevel.AVCLevel4
                mbPerSec <= 522_240  -> MediaCodecInfo.CodecProfileLevel.AVCLevel42
                mbPerSec <= 589_824  -> MediaCodecInfo.CodecProfileLevel.AVCLevel5
                mbPerSec <= 983_040  -> MediaCodecInfo.CodecProfileLevel.AVCLevel51
                mbPerSec <= 2_073_600 -> MediaCodecInfo.CodecProfileLevel.AVCLevel52
                else -> MediaCodecInfo.CodecProfileLevel.AVCLevel52 // 最高
            }
        }
    }

    // 视频编码相关
    private var mediaCodecVideo: MediaCodec? = null
    private var surface: Surface? = null
    private var videoBufferInfo: MediaCodec.BufferInfo? = null
    override var effectiveFrameRate: Int = frameRate
        private set
    private var selectedVideoEncoderName: String? = null
    // 按优先级排序的视频编码器候选列表（硬件优先、帧率降序），initVideoEncoder 逐个尝试
    private var videoEncoderCandidates: List<VideoEncoderChoice>? = null
    
    // 音频编码相关
    private var mediaCodecAudio: MediaCodec? = null
    private var audioBufferInfo: MediaCodec.BufferInfo? = null
    @Volatile
    private var isAudioRecording = false
    
    // 编码线程
    private var encodeThread: Thread? = null
    @Volatile
    private var isEncoding = false
    private var isPrepared = false
    private val cleanupLock = Any()
    private var isCleaningUp = false
    
    // 状态回调
    override var onError: ((String) -> Unit)? = null
    override var onInfo: ((String) -> Unit)? = null
    override var onVideoFrameRateMeasured: ((VideoFrameRateDiagnostics) -> Unit)? = null
    
    // 缓存的 AVCC 配置（从 csd-0/csd-1 合并）
    private var cachedAVCCConfig: ByteArray? = null
    
    // 🔥 缓存的 AudioSpecificConfig（从音频编码器 csd-0 提取）
    private var cachedAudioSpecificConfig: ByteArray? = null
    
    // 音视频共用单调时钟；PCM 缓存保存尚未提交帧的真实采集起点。
    private var mediaTimelineOriginNs = 0L
    private var audioPendingCaptureTimeNs = -1L

    /** 视频输出端失败时必须穿透到编码循环，不能继续伪装成正常推流。 */
    private class VideoOutputException(cause: Throwable) :
        IllegalStateException("视频帧发送失败：${cause.message}", cause)
    
    override fun prepare() {
        check(!isPrepared && !isEncoding) { "编码器已经准备或启动" }
        try {
//            Log.d(TAG, "Starting encoder: ${width}x${height}, bitrate=$videoBitrate, fps=$frameRate")
            val encoderChoices = selectVideoEncoder(width, height, frameRate)
            if (encoderChoices.isEmpty()) {
                val encoderType = if (requireHardwareVideoEncoder) "硬件 H.264 视频编码器" else "H.264 视频编码器"
                throw IllegalStateException("当前设备没有可用于 ${width}x${height}@${frameRate}fps 的$encoderType")
            }
            // 保存完整候选列表，initVideoEncoder 会逐个尝试（configure 失败切下一个候选）
            videoEncoderCandidates = encoderChoices
            val bestChoice = encoderChoices.first()
            selectedVideoEncoderName = bestChoice.codecName
            effectiveFrameRate = bestChoice.frameRate
            if (effectiveFrameRate < frameRate) {
                onInfo?.invoke("当前分辨率不支持 ${frameRate}fps 编码，已自动降级为 ${effectiveFrameRate}fps")
            }
            
            initVideoEncoder()
            isPrepared = true
        } catch (e: Exception) {
            cleanupEncoderResources()
            throw e
        }
    }

    /** 开始编码。传输必须由会话层预先连接完成。 */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun start() {
        check(!isEncoding) { "编码器已经启动" }
        if (!isPrepared) prepare()
        try {
            mediaTimelineOriginNs = System.nanoTime()
            createVirtualDisplay()
            if (useAudio) {
                initAudioEncoder()
            }

            isEncoding = true
            startEncodeThread()
        } catch (e: Exception) {
            cleanupEncoderResources()
            throw e
        }
    }
    
    /**
     * 停止编码并释放 MediaCodec 资源
     */
    override fun stop() {
        cleanupEncoderResources()
    }

    private fun handleEncoderFailure(message: String) {
        cleanupEncoderResources()
        onError?.invoke(message)
    }

    private fun cleanupEncoderResources() {
        synchronized(cleanupLock) {
            if (isCleaningUp) return
            isCleaningUp = true
        }

        try {
//            Log.d(TAG, "Stopping encoder...")
            isEncoding = false

            stopEncodeThread()
            stopAudioRecorder()
            releaseVideoEncoder()

            cachedAVCCConfig = null
            cachedAudioSpecificConfig = null
            selectedVideoEncoderName = null
            videoEncoderCandidates = null
            effectiveFrameRate = frameRate
            isPrepared = false
            videoOutputFrameCount = 0L
            videoOutputStartNs = 0L
            frameRateWindowStartNs = 0L
            frameRateWindowStartPtsUs = -1L
            frameRateWindowFrameCount = 0
            actualFrameRateWarned = false
            spsPpsSent = false
            mediaTimelineOriginNs = 0L
            audioPendingCaptureTimeNs = -1L
            audioPendingBuffer.reset()
            audioPendingOffset = 0
            audioInputFrameCount = 0L
            audioOutputFrameCount = 0L

//            Log.d(TAG, "Encoder stopped")
        } finally {
            synchronized(cleanupLock) {
                isCleaningUp = false
            }
        }
    }
    
    /**
     * 初始化视频编码器：按候选列表逐个尝试，configure/start 失败自动切下一个候选。
     *
     * 能力表在真机上不可靠：可能误报"不支持"（导致可用编码器被跳过），
     * 也可能 configure 成功但实际跑不起来。运行时验证才是最可靠的判定，
     * 只有全部候选都失败才抛出异常，交由上层降级/报错。
     */
    private fun initVideoEncoder() {
        val announcedFps = effectiveFrameRate
        val choices = videoEncoderCandidates
            ?: selectedVideoEncoderName?.let { listOf(VideoEncoderChoice(it, effectiveFrameRate)) }
            ?: selectVideoEncoder(width, height, frameRate)
        if (choices.isEmpty()) {
            val encoderType = if (requireHardwareVideoEncoder) "硬件 H.264 视频编码器" else "H.264 视频编码器"
            throw IllegalStateException("当前设备没有可用于 ${width}x${height}@${frameRate}fps 的$encoderType")
        }

        var lastError: Exception? = null
        for (choice in choices) {
            try {
                tryInitVideoEncoder(choice.codecName, choice.frameRate)
                // 初始化成功：记录实际使用的编码器和帧率
                selectedVideoEncoderName = choice.codecName
                effectiveFrameRate = choice.frameRate
                if (effectiveFrameRate < announcedFps) {
                    onInfo?.invoke("视频编码器降级：实际使用 ${effectiveFrameRate}fps")
                }
                return
            } catch (e: Exception) {
                lastError = e
//                Log.w(TAG, "视频编码器 ${choice.codecName} 初始化失败（${e.message}），尝试下一个候选")
                releaseVideoEncoder()
            }
        }

        throw lastError ?: IllegalStateException("当前设备没有可用于 ${width}x${height} 的 H.264 视频编码器")
    }

    /**
     * 用指定编码器完成一次视频编码初始化（创建 + configure + 启动 + 提取 SPS/PPS）
     */
    private fun tryInitVideoEncoder(encoderName: String, fps: Int) {
        // 创建 MediaFormat
        val format = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate)
            setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps.toFloat())
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, 
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
            // Main 比 High 更容易被 Android 硬件编码器和 OBS/FFmpeg 组合稳定解码，
            // 同时保留 CABAC，画质/码率损失小于强制 Baseline。
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileMain)
            // 根据帧率+分辨率选择满足宏块率要求的 AVC Level。
            setInteger(MediaFormat.KEY_LEVEL, computeMinAvcLevel(width, height, fps))
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            repeatPreviousFrameAfterUs
                ?.takeIf { it > 0L }
                ?.let { setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, it) }
            setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
            
            if (videoMode == "CBR") {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            } else {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)
                setInteger(MediaFormat.KEY_QUALITY, videoQuality)
            }
        }
        
//        Log.d(TAG, "Creating encoder with format: $format")
        
        // 创建编码器
        mediaCodecVideo = MediaCodec.createByCodecName(encoderName).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = this@apply.createInputSurface()
            start()
            
//            Log.d(TAG, "MediaCodec started, will get SPS/PPS from codec config buffer and/or output format")
            
            // 某些编码器会在 start 后更新 outputFormat 包含 csd-0/csd-1
            // 我们先保存这个 format，稍后可能需要用它补充缺失的 PPS
            val actualFormat = this.outputFormat
//            Log.d(TAG, "Output format: ${actualFormat.toString()}")
            
            // 检查是否有 csd-0 和 csd-1
            val spsBuffer = actualFormat.getByteBuffer("csd-0")
            val ppsBuffer = actualFormat.getByteBuffer("csd-1")
            
            if (spsBuffer != null && ppsBuffer != null) {
                val sps = ByteArray(spsBuffer.remaining())
                spsBuffer.get(sps)
                val pps = ByteArray(ppsBuffer.remaining())
                ppsBuffer.get(pps)
                
//                Log.d(TAG, "Found SPS in output format, size=${sps.size}")
//                Log.d(TAG, "Found PPS in output format, size=${pps.size}")
                
                // 去除起始码
                val strippedSps = H264Avcc.stripStartCode(sps)
                val strippedPps = H264Avcc.stripStartCode(pps)
                
//                Log.d(TAG, "SPS: ${sps.size} bytes -> ${strippedSps.size} bytes (after stripping start code)")
//                Log.d(TAG, "PPS: ${pps.size} bytes -> ${strippedPps.size} bytes (after stripping start code)")
                
                // 立即合并成完整的 AVCDecoderConfigurationRecord（使用去除起始码的数据）
                val avccConfig = H264Avcc.mergeParameterSets(strippedSps, strippedPps)
//                Log.d(TAG, "Merged AVCC config size=${avccConfig.size}, preview: ${avccConfig.take(20).joinToString(" ") { "%02X".format(it) }}")
                
                // 保存到字段，等待 codec config flag 时再发送（双保险）
                cachedAVCCConfig = avccConfig
            } else {
//                Log.w(TAG, "Incomplete SPS/PPS in output format, will wait for codec config buffer")
            }
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
        onSurfaceReady(displaySurface)
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
                // 输出统一采用不带 ADTS 头的 AAC ES，具体封装由传输层完成
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
     * 启动编码线程
     */
    private fun startEncodeThread() {
        encodeThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            
            if (useAudio) {
                startAudioRecording()
            }
            
            encodeVideoLoop()
            
        }, "MediaCodecEncoder-EncodeThread")
        
        encodeThread?.start()
//        Log.d(TAG, "Encode thread started")
    }
    
    /**
     * 停止编码线程
     */
    private fun stopEncodeThread() {
        val thread = encodeThread
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.interrupt()
                thread.join(1000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
//                Log.w(TAG, "Error stopping encode thread", e)
            }
        }
        if (thread == null || thread == Thread.currentThread() || !thread.isAlive) {
            encodeThread = null
        }
//        Log.d(TAG, "Encode thread stopped")
    }

    private data class VideoEncoderChoice(
        val codecName: String,
        val frameRate: Int
    )

    private data class CapabilityQuerySize(
        val width: Int,
        val height: Int,
        val isExactSupported: Boolean
    )

    private fun alignUp(value: Int, alignment: Int): Int {
        if (alignment <= 1) return value
        return ((value + alignment - 1) / alignment) * alignment
    }

    private fun getCapabilityQuerySize(
        videoCapabilities: MediaCodecInfo.VideoCapabilities,
        width: Int,
        height: Int
    ): CapabilityQuerySize? {
        if (videoCapabilities.isSizeSupported(width, height)) {
            return CapabilityQuerySize(width, height, isExactSupported = true)
        }

        val alignedWidth = alignUp(width, videoCapabilities.widthAlignment)
        val alignedHeight = alignUp(height, videoCapabilities.heightAlignment)
        if (videoCapabilities.isSizeSupported(alignedWidth, alignedHeight)) {
            return CapabilityQuerySize(alignedWidth, alignedHeight, isExactSupported = true)
        }

        // 真机实测表明能力表并不可靠：部分厂商会误报竖屏/大尺寸（如 Nokia 1 上报上限 176x132 却能正常编码 720p），
        // 也有反向误报（configure 成功但实际跑不起来）。因此这里只做"明显越界"的粗筛，
        // 只要尺寸落在 H.264 规范上限（≤4096）且不低于能力表下限，就作为候选交给运行时验证
        // （initVideoEncoder 逐个尝试 + 编码循环无输出看门狗兜底），不再用能力表的每边上限硬卡。
        val widthRange = videoCapabilities.supportedWidths
        val heightRange = videoCapabilities.supportedHeights
        return if (
            alignedWidth >= widthRange.lower && alignedHeight >= heightRange.lower &&
            alignedWidth <= MAX_FRAME_DIMENSION && alignedHeight <= MAX_FRAME_DIMENSION
        ) {
            CapabilityQuerySize(alignedWidth, alignedHeight, isExactSupported = false)
        } else null
    }

    /**
     * 高帧率必须先按编码器能力协商，否则部分硬件会在 dequeueOutputBuffer 时直接进入错误态。
     *
     * 返回按优先级排序的候选编码器列表（硬件优先、帧率降序）。
     * 真机实测能力表并不可靠，这里不再做"是否支持"的硬性判定，
     * 最终由 initVideoEncoder 逐个尝试（configure 失败切下一个候选）和编码循环看门狗兜底。
     */
    private fun selectVideoEncoder(width: Int, height: Int, requestedFps: Int): List<VideoEncoderChoice> {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val codecInfos = codecList.codecInfos.sortedWith(compareBy<MediaCodecInfo> {
            if (it.isSoftwareOnly) 1 else 0
        }.thenBy { it.name })
        val choices = mutableListOf<VideoEncoderChoice>()

        for (codecInfo in codecInfos) {
            if (!codecInfo.isEncoder) continue
            if (requireHardwareVideoEncoder && (codecInfo.isSoftwareOnly || !codecInfo.isHardwareAccelerated)) continue
            if (codecInfo.supportedTypes.none { it.equals(VIDEO_MIME_TYPE, ignoreCase = true) }) continue

            val capabilities = runCatching { codecInfo.getCapabilitiesForType(VIDEO_MIME_TYPE) }.getOrNull()
                ?: continue
            val videoCapabilities = capabilities.videoCapabilities ?: continue
            val querySize = getCapabilityQuerySize(videoCapabilities, width, height)
                ?: continue

            val supportedFps = if (querySize.isExactSupported) {
                if (requireHardwareVideoEncoder || videoCapabilities.areSizeAndRateSupported(querySize.width, querySize.height, requestedFps.toDouble())) {
                    requestedFps
                } else {
                    runCatching {
                        floor(videoCapabilities.getSupportedFrameRatesFor(querySize.width, querySize.height).upper).toInt()
                    }.getOrDefault(30).coerceAtLeast(1)
                }
            } else {
                if (requireHardwareVideoEncoder) {
                    // 相机模式需要硬件编码 Surface 直连 Camera2，高速帧率不信任编码器能力表的粗略上界。
                    // 部分设备能力表会低报 30fps，但 configure + Camera2 实际可以按高速 request 输入。
                    requestedFps
                } else {
                    runCatching {
                        videoCapabilities.supportedFrameRates.upper
                    }.getOrDefault(30).coerceAtLeast(1)
                }
            }

            val choice = VideoEncoderChoice(codecInfo.name, supportedFps.coerceIn(1, requestedFps))
            Log.i(
                TAG,
                "编码器候选: ${choice.codecName}, requested=${requestedFps}fps, " +
                    "selected=${choice.frameRate}fps, hardwareRequired=$requireHardwareVideoEncoder, exactSize=${querySize.isExactSupported}"
            )
            choices += choice
        }

        // 稳定排序：帧率降序，帧率相同时保持原有顺序（硬件在前、软件在后）
        return choices.sortedWith(compareByDescending<VideoEncoderChoice> { it.frameRate })
    }
    
    /**
     * 视频编码循环
     */
    private var spsPpsSent = false  // 标记是否已发送 SPS/PPS
    private var videoOutputFrameCount = 0L
    private var videoOutputStartNs = 0L
    private var frameRateWindowStartNs = 0L
    private var frameRateWindowStartPtsUs = -1L
    private var frameRateWindowFrameCount = 0
    private var actualFrameRateWarned = false
    
    private fun encodeVideoLoop() {
        videoBufferInfo = MediaCodec.BufferInfo()
        var consecutiveErrors = 0
        val maxConsecutiveErrors = 10
        // 无输出看门狗：某些设备 configure 成功但实际不支持该尺寸（如 2560x720 对 max 1920x1080 的编码器），
        // 表现是 dequeue 永远不返回有效数据，既不报错也不产出，流会"假推流"挂死。
        // 启动后若长时间没有任何输出（format 变化或编码数据），判定编码器实际不可用并快速失败。
        val watchdogStartNs = System.nanoTime()
        var sawFirstOutput = false
        val watchdogTimeoutMs = 5000L
        // 部分厂商的 Surface 编码器会在画面变化很小时忽略 KEY_I_FRAME_INTERVAL，
        // 导致录屏流长期没有新的随机访问点。录屏启用了重复帧时，按既定的
        // I 帧间隔主动请求同步帧，既限制最坏刷新时间，也方便中途拉流和回放截取。
        val syncFrameIntervalNs = iFrameInterval.coerceAtLeast(1) * 1_000_000_000L
        var nextSyncFrameRequestNs = watchdogStartNs + syncFrameIntervalNs
        
        while (!Thread.interrupted() && isEncoding) {
            try {
                if (repeatPreviousFrameAfterUs != null) {
                    val nowNs = System.nanoTime()
                    if (nowNs >= nextSyncFrameRequestNs) {
                        try {
                            mediaCodecVideo?.setParameters(Bundle().apply {
                                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                            })
                        } catch (_: RuntimeException) {
                            // 厂商编码器不支持动态同步帧，或已在并发停止时，
                            // 继续依赖格式中的 I 帧间隔，不让可选优化中断推流。
                        }
                        nextSyncFrameRequestNs = nowNs + syncFrameIntervalNs
                    }
                }

                // 1. 从编码器获取输出 buffer
                val outputBufferIndex = mediaCodecVideo?.dequeueOutputBuffer(videoBufferInfo!!, 10000)
                    ?: continue
                
                when {
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        sawFirstOutput = true
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
                                val strippedSps = H264Avcc.stripStartCode(sps)
                                val strippedPps = H264Avcc.stripStartCode(pps)
                                                        
//                                Log.d(TAG, "Stripped SPS size: ${strippedSps.size}, Stripped PPS size: ${strippedPps.size}")
//                                Log.d(TAG, "Stripped SPS preview: ${strippedSps.take(10).joinToString(" ") { "%02X".format(it) }}")
//                                Log.d(TAG, "Stripped PPS preview: ${strippedPps.take(10).joinToString(" ") { "%02X".format(it) }}")
                                                        
                                // 合并成 AVCC 格式（使用去除起始码的数据）
                                val avccConfig = H264Avcc.mergeParameterSets(strippedSps, strippedPps)
                                cachedAVCCConfig = avccConfig
//                                Log.d(TAG, "Created AVCC config from output format, size=${avccConfig.size}")
                                                        
                                // 打印 AVCC 前 20 字节验证
                                val hexPreview = avccConfig.take(20).joinToString(" ") { "%02X".format(it) }
//                                Log.d(TAG, "AVCC config preview: $hexPreview")
                                                        
                                // 立即发送视频解码配置
                                if (!spsPpsSent) {
                                    sendVideoConfig(VideoCodecConfig(avccConfig))
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
                            sawFirstOutput = true
                            // 读取编码数据
                            val chunk = ByteArray(videoBufferInfo!!.size)
                            outputBuffer.get(chunk)
                            outputBuffer.clear()
                            
                            // 处理编码数据
                            try {
                                processVideoData(chunk, videoBufferInfo!!)
                                consecutiveErrors = 0
                            } catch (e: VideoOutputException) {
                                if (isEncoding) {
                                    handleEncoderFailure(e.message ?: "视频帧发送失败")
                                }
                                break
                            } catch (e: Exception) {
                                consecutiveErrors++
//                                Log.e(TAG, "Error processing video data ($consecutiveErrors/$maxConsecutiveErrors): ${e.message}")
                                if (consecutiveErrors >= maxConsecutiveErrors) {
//                                    Log.e(TAG, "Too many consecutive errors, stopping encoding")
                                    handleEncoderFailure("视频处理错误过多，停止编码")
                                    break
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
                
                // 无输出看门狗：configure/start 成功但长时间无任何输出 → 编码器实际不可用，
                // 快速失败并交由上层降级（而不是"假推流"一直挂着）
                if (!sawFirstOutput && isEncoding &&
                    System.nanoTime() - watchdogStartNs > watchdogTimeoutMs * 1_000_000L
                ) {
                    handleEncoderFailure(
                        "视频编码器启动后 ${watchdogTimeoutMs / 1000} 秒无输出，" +
                            "设备可能不支持 ${width}x${height}@${effectiveFrameRate}fps，请降低分辨率或帧率"
                    )
                    break
                }
                
            } catch (e: InterruptedException) {
//                Log.d(TAG, "Encode loop interrupted")
                break
            } catch (e: Exception) {
                if (isEncoding) {
//                    Log.e(TAG, "Error in video encoding loop", e)
                    handleEncoderFailure("视频编码器异常退出：${e.message}")
                    break
                }
            }
        }
        
//        Log.d(TAG, "Video encoding loop finished")
    }
    
    /**
     * 处理视频编码数据
     */
    private fun processVideoData(data: ByteArray, bufferInfo: MediaCodec.BufferInfo) {
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
                        sendVideoConfig(VideoCodecConfig(avccConfig))
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
                                val avccFromAnnexB = H264Avcc.mergeParameterSets(sps, pps)
                                sendVideoConfig(VideoCodecConfig(avccFromAnnexB))
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
            val codecMarkedKeyFrame = bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
            
            // Surface 输入帧的 PTS 与 System.nanoTime() 同属单调时钟域。
            // 保留 MediaCodec 的微秒精度，不在编码端伪造递增时间戳。
            val captureTimeNs = bufferInfo.presentationTimeUs * 1_000L
            val presentationTimeUs = (captureTimeNs - mediaTimelineOriginNs) / 1_000L

            val outputTimeNs = System.nanoTime()
            if (videoOutputStartNs == 0L) videoOutputStartNs = outputTimeNs
            videoOutputFrameCount++

            if (frameRateWindowStartNs == 0L) {
                frameRateWindowStartNs = outputTimeNs
                frameRateWindowStartPtsUs = bufferInfo.presentationTimeUs
                frameRateWindowFrameCount = 1
            } else {
                frameRateWindowFrameCount++
            }

            val windowElapsedNs = outputTimeNs - frameRateWindowStartNs
            val windowElapsedPtsUs = bufferInfo.presentationTimeUs - frameRateWindowStartPtsUs
            if (windowElapsedNs >= 1_000_000_000L &&
                windowElapsedPtsUs > 0L &&
                frameRateWindowFrameCount > 1
            ) {
                val frameIntervals = frameRateWindowFrameCount - 1
                val wallFps = frameIntervals * 1_000_000_000.0 / windowElapsedNs
                val ptsFps = frameIntervals * 1_000_000.0 / windowElapsedPtsUs
                Log.i(
                    TAG,
                    "编码输出统计: frames=$videoOutputFrameCount, requested=${frameRate}fps, " +
                        "effective=${effectiveFrameRate}fps, wallFps=${"%.1f".format(wallFps)}, " +
                        "ptsFps=${"%.1f".format(ptsFps)}, lastPts=${presentationTimeUs}us"
                )
                onVideoFrameRateMeasured?.invoke(
                    VideoFrameRateDiagnostics(
                        requestedFps = frameRate,
                        actualFps = ptsFps,
                        wallClockFps = wallFps
                    )
                )
                if (!actualFrameRateWarned &&
                    frameRate > 30 &&
                    outputTimeNs - videoOutputStartNs >= 2_000_000_000L &&
                    ptsFps > 0.0 &&
                    ptsFps < frameRate * 0.75
                ) {
                    actualFrameRateWarned = true
                    val actualFpsText = "%.1f".format(ptsFps)
                    Log.w(TAG, "设备实际摄像头输出帧率偏低: requested=${frameRate}fps, actual=${actualFpsText}fps")
                    onInfo?.invoke("设备实际只输出约 ${actualFpsText}fps，已低于请求的 ${frameRate}fps；请降低帧率或分辨率")
                }

                frameRateWindowStartNs = outputTimeNs
                frameRateWindowStartPtsUs = bufferInfo.presentationTimeUs
                frameRateWindowFrameCount = 1
            }
            
//            Log.d(TAG, "🎬 Video frame: key=$isKeyFrame, pts=${presentationTimeUs}us")
            
            // 发送视频数据（带错误检查和格式转换）
            try {
                // 检测帧数据格式
                val frameData = if (data.size >= 4) {
                    // 🔥 性能优化：移除高频日志，减少 GC 压力
                    // val hex = data.take(4).joinToString(" ") { "%02X".format(it) }
                    // Log.d(TAG, "Frame start bytes: $hex")
                    
                    // 判断是否是 AnnexB 格式
                    if (H264Avcc.isAnnexB(data)) {
                        // 检查是 3 字节还是 4 字节起始码
                        val startCodeSize = if (data[2] == 0x00.toByte()) 4 else 3
//                        Log.w(TAG, "WARNING: Frame is in AnnexB format (${startCodeSize}-byte start code)! Converting to AVCC...")
                        H264Avcc.annexBToAvcc(data)
                    } else {
//                        Log.v(TAG, "Frame appears to be in AVCC format, sending directly")
                        data
                    }
                } else {
                    data
                }
                // 某些厂商编码器的 BufferInfo 标记不完整；真实 IDR 仍须作为
                // 关键帧可靠发送，接收端也会再次按 NAL 类型校验回放边界。
                val isKeyFrame = codecMarkedKeyFrame || H264Avcc.containsIdr(frameData)
                
                try {
                    output.sendVideoFrame(EncodedVideoFrame(
                        data = frameData,
                        presentationTimeUs = presentationTimeUs,
                        isKeyFrame = isKeyFrame,
                        captureTimeNs = captureTimeNs,
                        encodedTimeNs = outputTimeNs
                    ))
                } catch (e: Exception) {
                    throw VideoOutputException(e)
                }
                
                // 🔥 性能优化：移除高频诊断日志，仅在关键帧打印
                if (isKeyFrame) {
//                    Log.d(TAG, "Sent key frame, size=${frameData.size}, pts=${presentationTimeUs}us")
                } else {
                    // Log.v(TAG, "Sent video frame, size=${frameData.size}, pts=${presentationTimeMs}ms")
                }
            } catch (e: VideoOutputException) {
                throw e
            }
            
        } catch (e: Exception) {
            if (e is VideoOutputException) throw e
//            Log.e(TAG, "Error processing video data", e)
        }
    }

    private fun sendVideoConfig(config: VideoCodecConfig) {
        try {
            output.sendVideoConfig(config)
        } catch (e: Exception) {
            throw VideoOutputException(e)
        }
    }
    
    /**
     * 开始音频录制
     */
    private fun startAudioRecording() {
        isAudioRecording = true
        // ASC 会在首次提交 PCM 后由 MediaCodec 自然输出。
    }

    /**
     * 提交外部 PCM 数据到音频编码器
     */
    override fun submitExternalAudioData(pcmData: ByteArray, size: Int, timestampNs: Long) {
        if (!isEncoding || !isAudioRecording || mediaCodecAudio == null || size <= 0) {
            return
        }

        val data = if (size == pcmData.size) {
            pcmData
        } else {
            pcmData.copyOf(size)
        }
        encodeAndSendAudioData(data, timestampNs)
    }
    
    /**
     * 停止音频录制
     */
    private fun stopAudioRecorder() {
        isAudioRecording = false

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
     * 将 PCM 数据编码为 AAC 并交给媒体输出端
     * 
     * 🔥 协议依据：
     * 1. 输出 AudioSpecificConfig + AAC Raw Frame
     * 2. Android MediaCodec 官方文档：CODEC_CONFIG buffer 必须在普通数据之前处理
     * 3. 时间戳规范：音视频必须使用同一系统时间基准
     * 
     * 🔥 关键修复点：
     * - 输入缓冲区满时，主动处理输出来腾出空间
     * - 时间戳基于 PCM 采集时的硬件时间戳推算，而非系统时间
     * - 确保 CODEC_CONFIG 数据被完整处理
     */
    private var audioInputFrameCount = 0L
    private var audioOutputFrameCount = 0L
    private fun encodeAndSendAudioData(pcmData: ByteArray, pcmTimestampNs: Long) {
        if (mediaCodecAudio == null) {
//            Log.w(TAG, "❌ Audio encoder not initialized!")
            return
        }
        
        try {
            // ========== 第一步：处理所有待输出的数据 ==========
            // 这很关键！必须先处理输出，否则输入缓冲区会堆积
            drainAudioEncoderOutput()
            
            // ========== 第二步：输入新的 PCM 数据 ==========
            queueAudioInputData(pcmData, pcmTimestampNs)
            
            // ========== 第三步：再次处理可能生成的新输出 ==========
            // 某些编码器会立即产生输出
            drainAudioEncoderOutput()
            
        } catch (e: Exception) {
//            Log.e(TAG, "❌ Error encoding audio: ${e.message}", e)
            if (isEncoding) {
                handleEncoderFailure("音频编码器异常退出：${e.message}")
            }
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
                                
                                output.sendAudioConfig(AudioCodecConfig(asc))
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
                        
                        // 🔥 关键修复：使用 MediaCodec 输出的 presentationTimeUs（已在输入时设置为 PCM 硬件时间戳）
                        // 这样保证音频时间戳源自采集时的硬件时钟，而非系统时间
                        val presentationTimeUs = bufferInfo.presentationTimeUs
                        
                        // 🔥 诊断日志（降低频率）
                        if (audioOutputFrameCount % 50 == 0L) {
//                            Log.d("AudioEncode", "📤 [Frame #$audioOutputFrameCount] AAC frame: size=${aacData.size}, pts=${presentationTimeUs}us")
                        }

                        val captureTimeNs = if (mediaTimelineOriginNs > 0L) {
                            mediaTimelineOriginNs + bufferInfo.presentationTimeUs * 1_000L
                        } else {
                            System.nanoTime()
                        }
                        output.sendAudioFrame(
                            EncodedAudioFrame(
                                data = aacData,
                                presentationTimeUs = presentationTimeUs,
                                captureTimeNs = captureTimeNs,
                                encodedTimeNs = System.nanoTime()
                            )
                        )
                        audioOutputFrameCount++

                        if (audioOutputFrameCount % 100 == 0L) {
//                            Log.d("AudioEncode", "📤 Sent AAC frame #$audioOutputFrameCount: size=${aacData.size}, pts=${presentationTimeUs}us")
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
     * - 🔥 关键：使用 PCM 采集时的硬件时间戳推算每个 AAC 帧的时间戳
     */
    private var audioPendingBuffer = ByteArrayOutputStream() // 缓存未完成的 PCM 数据
    private val AAC_FRAME_SIZE = 1024 * audioChannelCount * 2 // 4096 bytes @48kHz stereo
    private val AUDIO_SAMPLES_PER_FRAME = 1024 // AAC 每帧 1024 个采样点
    private var audioPendingOffset = 0 // 🔥 待处理缓冲区的起始偏移量
    
    private fun queueAudioInputData(pcmData: ByteArray, pcmTimestampNs: Long) {
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
                handleEncoderFailure("音频编码器异常退出：${e.message}")
                return
            }
        }
        
        val bytesPerPcmFrame = audioChannelCount * 2
        val pendingBytes = audioPendingBuffer.size() - audioPendingOffset
        if (audioPendingCaptureTimeNs < 0L || pendingBytes == 0) {
            audioPendingCaptureTimeNs = pcmTimestampNs
        } else {
            val expectedPacketTimeNs = audioPendingCaptureTimeNs +
                (pendingBytes / bytesPerPcmFrame) * 1_000_000_000L / audioSampleRate
            val clockErrorNs = pcmTimestampNs - expectedPacketTimeNs
            if (clockErrorNs !in -100_000_000L..100_000_000L) {
                // 采集暂停或时钟跳变时丢弃不足一帧的旧 PCM，避免声音拖尾。
                audioPendingBuffer.reset()
                audioPendingOffset = 0
                audioPendingCaptureTimeNs = pcmTimestampNs
            }
        }

        // 🔥 关键：将新数据添加到待处理缓冲区
        audioPendingBuffer.write(pcmData)
        
        // 🔥 循环提交完整的 AAC 帧（使用偏移量避免重复复制）
        while (audioPendingBuffer.size() - audioPendingOffset >= AAC_FRAME_SIZE) {
            // 🔥 性能优化：直接获取内部数组，避免 toByteArray() 复制
            val allData = audioPendingBuffer.toByteArray()
            val frameData = allData.copyOfRange(audioPendingOffset, audioPendingOffset + AAC_FRAME_SIZE)
            
            // 🔥 推算当前 AAC 帧的时间戳
            // 原理：PCM 包中有多个 AAC 帧，每帧间隔固定时间
            val timeIncrementPerFrameNs = (AUDIO_SAMPLES_PER_FRAME * 1_000_000_000L) / audioSampleRate
            val absoluteFrameTimestampNs = audioPendingCaptureTimeNs

            // 音频与视频使用同一个推流时钟原点。
            val relativeFrameTimestampNs = absoluteFrameTimestampNs - mediaTimelineOriginNs
            val frameTimestampUs = relativeFrameTimestampNs / 1_000L
            // 提交单个完整帧到编码器（带时间戳，单位：微秒）
            if (!submitAudioFrameToEncoder(frameData, frameTimestampUs)) break
            // 只有 MediaCodec 真正接收后才消费缓存；输入缓冲区暂时满时下次重试。
            audioPendingOffset += AAC_FRAME_SIZE
            audioPendingCaptureTimeNs += timeIncrementPerFrameNs
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
    private fun submitAudioFrameToEncoder(frameData: ByteArray, frameTimestampUs: Long): Boolean {
        // 尝试获取输入缓冲区
        val inputBufferIndex = mediaCodecAudio!!.dequeueInputBuffer(0)  // 非阻塞
        
        if (inputBufferIndex < 0) {
            // 输入缓冲区满，这是正常现象（输入太快）
            if (audioInputFrameCount % 100 == 0L) {
//                Log.v("AudioEncode", "⚠️ Input buffer full, queued frames: $audioInputFrameCount, output frames: $audioOutputFrameCount, pending: ${audioPendingBuffer.size()} bytes")
            }
            // 🔥 关键：数据已经在 audioPendingBuffer 中，下次会自动重试
            return false
        }
        
        try {
            val inputBuffer = mediaCodecAudio!!.getInputBuffer(inputBufferIndex)
            if (inputBuffer == null) {
//                Log.w("AudioEncode", "⚠️ Failed to get input buffer at index $inputBufferIndex")
                return false
            }
            
            inputBuffer.clear()
            
            // 确保 PCM 数据不超过缓冲区
            if (frameData.size > inputBuffer.capacity()) {
//                Log.w("AudioEncode", "⚠️ Frame data too large! ${frameData.size} > ${inputBuffer.capacity()}, truncating")
                inputBuffer.put(frameData, 0, inputBuffer.capacity())
            } else {
                inputBuffer.put(frameData)
            }
            
            // 🔥 关键修复：使用相对于第一个 PCM 时间戳的时间（已在调用处转换）
            // 这样音频和视频都从 0 开始，起点完全对齐
            val presentationTimeUs = frameTimestampUs
            
            mediaCodecAudio!!.queueInputBuffer(
                inputBufferIndex,
                0,
                frameData.size,
                presentationTimeUs,
                0  // flags = 0 (不是 EOS)
            )
            
            audioInputFrameCount++
            
            if (audioInputFrameCount % 100 == 0L) {
//                Log.d("AudioEncode", "📥 Queued PCM frame #$audioInputFrameCount: size=${frameData.size}, ts=${presentationTimeUs}us (from PCM ts=$frameTimestampNs ns)")
            }
            return true
        } catch (e: Exception) {
//            Log.e("AudioEncode", "❌ Error queuing audio input: ${e.message}")
            return false
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
    override fun isRunning(): Boolean = isEncoding
    
}
