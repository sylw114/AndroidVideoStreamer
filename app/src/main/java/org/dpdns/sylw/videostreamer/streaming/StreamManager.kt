package org.dpdns.sylw.videostreamer.streaming

import android.app.Activity

/**
 * 推流管理器（重构版）
 * 
 * 🔥 核心改进：
 * 1. 使用 IStreamingProtocol 接口，解耦具体协议实现
 * 2. UI 层只关心“开始/停止推流”，不关心底层协议
 * 3. 支持动态切换协议
 * 
 * 架构分层：
 * ```
 * VideoWindow (UI)
 *     ↓ 调用
 * StreamManager (业务逻辑)
 *     ↓ 使用接口
 * IStreamingProtocol (协议抽象)
 *     ↓ 实现
 * RtmpStreamingProtocol
 * ```
 */
class StreamManager(private val activity: Activity, val onSurfaceReady: ((android.view.Surface) -> Unit), private var currentConfig: StreamingConfig = StreamingConfig()) {
    
    companion object {
        private const val TAG = "StreamManager"
        const val REQUEST_CODE_SCREEN_CAPTURE = 1001
    }
    
    // 🔥 协议实例（通过接口抽象）
    private var protocol: IStreamingProtocol? = null
    
    // 状态回调
    var onStreamingStateChanged: ((Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onInfo: ((String) -> Unit)? = null
    var onVideoFrameRateMeasured: ((VideoFrameRateDiagnostics) -> Unit)? = null
    
    /**
     * 初始化推流管理器（默认使用 RTMP 协议）
     */
    fun init(protocol: String = "RTMP") {
//        Log.d(TAG, "StreamManager initialized with $protocol protocol")
        
        // 根据协议名称创建对应的协议实例
        this.protocol = when (protocol.uppercase()) {
            "RTMP" -> RtmpStreamingProtocol{surface ->
//                Log.d(TAG, "Encoder surface ready, forwarding to UI layer")
                this@StreamManager.onSurfaceReady(surface)}
            else -> {
//                Log.w(TAG, "Unknown protocol: $protocol, fallback to RTMP")
                RtmpStreamingProtocol{surface ->
//                Log.d(TAG, "Encoder surface ready, forwarding to UI layer")
                    this@StreamManager.onSurfaceReady(surface)}
            }
        }.apply {
            onStreamingStateChanged = { isStreaming ->
//                Log.d(TAG, "Streaming state changed: $isStreaming")
                this@StreamManager.onStreamingStateChanged?.invoke(isStreaming)
            }
            
            onError = { error ->
//                Log.e(TAG, "Protocol error: $error")
                this@StreamManager.onError?.invoke(error)
            }

            onInfo = { message ->
                this@StreamManager.onInfo?.invoke(message)
            }

            onVideoFrameRateMeasured = { diagnostics ->
                this@StreamManager.onVideoFrameRateMeasured?.invoke(diagnostics)
            }
        }

    }
    
    /**
     * 设置视频参数（便捷方法）
     */
    fun setVideoParams(
        width: Int,
        height: Int,
        bitrate: Int,
        frameRate: Int = 30,
        iFrameInterval: Int = 5,
        videoMode: String = "CBR",
        videoQuality: Int = 70
    ) {
        currentConfig = currentConfig.copy(
            width = width,
            height = height,
            videoBitrate = bitrate,
            frameRate = frameRate,
            iFrameInterval = iFrameInterval,
            videoMode = videoMode,
            videoQuality = videoQuality
        )
        
//        Log.d(TAG, "Video params updated: ${width}x${height}, bitrate=$bitrate, fps=$frameRate, mode=$videoMode, quality=$videoQuality")
    }
    
    /**
     * 提交外部 PCM 音频数据
     */
    fun submitExternalAudioData(pcmData: ByteArray, size: Int, timestampNs: Long) {
        protocol?.submitExternalAudioData(pcmData, size, timestampNs)
    }

    /**
     * 设置音频捕获回调
     */
    fun setAudioCaptureCallbacks(onStart: () -> Unit, onStop: () -> Unit) {
        currentConfig = currentConfig.copy(
            onAudioCaptureStart = onStart,
            onAudioCaptureStop = onStop
        )
    }
    
    /**
     * 开始推流
     * 
     * @param url 推流地址（RTMP 等）
     */
    fun startStreaming(url: String) {
        if (protocol == null) {
            val error = "推流协议未初始化"
//            Log.e(TAG, error)
            onError?.invoke(error)
            return
        }
        
//        Log.d(TAG, "Starting streaming with protocol: ${protocol?.javaClass?.simpleName}")
        protocol?.start(url, currentConfig)
    }
    
    /**
     * 停止推流
     */
    fun stopStreaming() {
//        Log.d(TAG, "Stopping streaming...")
        protocol?.stop()
    }
    
    /**
     * 检查是否正在推流
     */
    fun isStreaming(): Boolean {
        return protocol?.isStreaming() ?: false
    }

    /**
     * 更新分辨率（屏幕旋转时调用）
     */
    fun updateResolution(newWidth: Int, newHeight: Int) {
        if (!isStreaming()) {
//            Log.w(TAG, "Not streaming, skipping resolution update")
            return
        }
        
        val currentUrl = getCurrentUrl()
        if (currentUrl.isNullOrBlank()) {
//            Log.e(TAG, "Cannot get current URL, rotation update failed")
            return
        }
        
        // 停止推流
        stopStreaming()
        Thread.sleep(500)  // 等待编码器完全停止
        
        // 更新配置
        currentConfig = currentConfig.copy(width = newWidth, height = newHeight)
//        Log.d(TAG, "Resolution updated: ${newWidth}x${newHeight}")
        
        // 重新推流
        startStreaming(currentUrl)
//        Log.d(TAG, "Resolution update completed")
    }

    /**
     * 获取当前推流地址
     */
    fun getCurrentUrl(): String? {
        return protocol?.getCurrentUrl()
    }
    
    /**
     * 释放资源（只在彻底退出时调用）
     */
     fun release() {
//         Log.d(TAG, "Releasing StreamManager...")

         stopStreaming()
         protocol?.release()
         protocol = null

         //Log.d(TAG, "StreamManager released")
     }
}
