package org.dpdns.sylw.videostreamer.streaming

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.Log

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
class StreamManager(private val activity: Activity) {
    
    companion object {
        private const val TAG = "StreamManager"
        const val REQUEST_CODE_SCREEN_CAPTURE = 1001
    }
    
    // 🔥 协议实例（通过接口抽象）
    private var protocol: IStreamingProtocol? = null
    
    // MediaProjection
    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionManager: MediaProjectionManager? = null
    
    // 当前配置
    private var currentConfig: StreamingConfig = StreamingConfig()
    
    // 状态回调
    var onStreamingStateChanged: ((Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    
    /**
     * 初始化推流管理器（默认使用 RTMP 协议）
     */
    fun init(protocol: String = "RTMP") {
//        Log.d(TAG, "StreamManager initialized with $protocol protocol")
        
        // 根据协议名称创建对应的协议实例
        this.protocol = when (protocol.uppercase()) {
            "RTMP" -> RtmpStreamingProtocol()
            else -> {
//                Log.w(TAG, "Unknown protocol: $protocol, fallback to RTMP")
                RtmpStreamingProtocol()
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
        }
        
        // 初始化 MediaProjectionManager
        mediaProjectionManager = activity.getSystemService(Activity.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    
    /**
     * 开始录屏授权（必须在调用 startStreaming 之前）
     */
    fun requestScreenCapturePermission() {
        if (mediaProjectionManager == null) {
            init()
        }
        
        val intent = mediaProjectionManager?.createScreenCaptureIntent()
        activity.startActivityForResult(intent, REQUEST_CODE_SCREEN_CAPTURE)
        
//        Log.d(TAG, "Screen capture permission requested")
    }
    
    /**
     * 处理授权结果（在 onActivityResult 中调用）
     */
    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_CODE_SCREEN_CAPTURE) {
            return false
        }
        
        if (resultCode == Activity.RESULT_OK && data != null) {
            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)
            
//            Log.d(TAG, "Screen capture permission granted")
            return true
        } else {
//            Log.e(TAG, "Screen capture permission denied")
            onError?.invoke("录屏授权被拒绝")
            return false
        }
    }
    
    /**
     * 设置 MediaProjection（从 MediaProjectionService 获取）
     */
    fun setMediaProjection(projection: MediaProjection) {
        this.mediaProjection = projection
//        Log.d(TAG, "MediaProjection set successfully")
    }
    
    /**
     * 设置视频参数（便捷方法）
     */
    fun setVideoParams(
        width: Int,
        height: Int,
        bitrate: Int,
        frameRate: Int = 30,
        iFrameInterval: Int = 5
    ) {
        currentConfig = currentConfig.copy(
            width = width,
            height = height,
            videoBitrate = bitrate,
            frameRate = frameRate,
            iFrameInterval = iFrameInterval
        )
        
//        Log.d(TAG, "Video params updated: ${width}x${height}, bitrate=$bitrate, fps=$frameRate")
    }
    
    /**
     * 设置音频参数（便捷方法）
     */
    fun setAudioParams(
        useAudio: Boolean,
        sampleRate: Int = 48000,
        channelCount: Int = 2,
        bitrate: Int = 128000
    ) {
        currentConfig = currentConfig.copy(
            useAudio = useAudio,
            audioSampleRate = sampleRate,
            audioChannelCount = channelCount,
            audioBitrate = bitrate
        )
        
//        Log.d(TAG, "Audio params updated: enabled=$useAudio, sampleRate=$sampleRate")
    }
    
    /**
     * 设置外部音频源
     */
    fun setExternalAudioSource(audioSource: (() -> ByteArray?)?) {
        currentConfig = currentConfig.copy(externalAudioSource = audioSource)
//        Log.d(TAG, "External audio source ${if (audioSource != null) "set" else "cleared"}")
    }
    
    /**
     * 设置 MediaProjectionService binder（供协议层使用）
     */
    fun setMediaProjectionServiceBinder(binder: org.dpdns.sylw.videostreamer.MediaProjectionService.LocalBinder) {
        currentConfig = currentConfig.copy(mediaProjectionServiceBinder = binder)
//        Log.d(TAG, "MediaProjectionService binder set")
    }
    
    /**
     * 开始推流
     * 
     * @param url 推流地址（RTMP 等）
     */
    fun startStreaming(url: String) {
        if (mediaProjection == null) {
            val error = "MediaProjection 未初始化，请先请求授权"
//            Log.e(TAG, error)
            onError?.invoke(error)
            return
        }
        
        if (protocol == null) {
            val error = "推流协议未初始化"
//            Log.e(TAG, error)
            onError?.invoke(error)
            return
        }
        
        // 构建完整配置
        val config = currentConfig.copy(
            mediaProjection = mediaProjection,
            mediaProjectionServiceBinder = currentConfig.mediaProjectionServiceBinder,
            externalAudioSource = currentConfig.externalAudioSource
        )
        
//        Log.d(TAG, "Starting streaming with protocol: ${protocol?.javaClass?.simpleName}")
        protocol?.start(url, config)
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
     * 切换推流地址
     */
    fun switchUrl(newUrl: String) {
//        Log.d(TAG, "Switching URL to: $newUrl")
        protocol?.switchUrl(newUrl)
    }
    
    /**
     * 动态更新码率
     */
    fun updateBitrate(bitrate: Int) {
//        Log.d(TAG, "Updating bitrate to: $bitrate")
        protocol?.updateBitrate(bitrate)
        currentConfig = currentConfig.copy(videoBitrate = bitrate)
    }
    
    /**
     * 动态更新帧率
     */
    fun updateFrameRate(frameRate: Int) {
//        Log.d(TAG, "Updating frame rate to: $frameRate")
        protocol?.updateFrameRate(frameRate)
        currentConfig = currentConfig.copy(frameRate = frameRate)
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
     * 切换推流协议
     * 
     * @param newProtocol 新协议名称（目前仅支持 RTMP）
     */
    fun switchProtocol(newProtocol: String) {
        if (newProtocol.uppercase() != "RTMP") {
//            Log.w(TAG, "Only RTMP protocol is supported, ignoring switch to: $newProtocol")
            return
        }
        
//        Log.d(TAG, "Protocol remains RTMP (no switch needed)")
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

         // 释放 MediaProjection
         mediaProjection?.stop()
         mediaProjection = null
         mediaProjectionManager = null

         //Log.d(TAG, "StreamManager released")
     }
}
