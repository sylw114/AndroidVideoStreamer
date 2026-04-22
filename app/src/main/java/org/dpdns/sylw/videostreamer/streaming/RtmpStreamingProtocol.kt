package org.dpdns.sylw.videostreamer.streaming

import android.annotation.SuppressLint
import android.util.Log
import org.dpdns.sylw.videostreamer.rtmpStreamer.SurfaceTextureEncoder

/**
 * RTMP 推流协议实现
 * 
 * 🔥 封装 SurfaceTextureEncoder，实现 IStreamingProtocol 接口
 */
class RtmpStreamingProtocol : IStreamingProtocol {
    
    companion object {
        private const val TAG = "RtmpProtocol"
    }
    
    private var encoder: SurfaceTextureEncoder? = null
    private var currentUrl: String? = null
    private var currentConfig: StreamingConfig? = null
    
    override var onStreamingStateChanged: ((Boolean) -> Unit)? = null
    override var onError: ((String) -> Unit)? = null
    
    // 防止 onError 递归调用
    private var isHandlingError = false
    
    @SuppressLint("MissingPermission")
    override fun start(url: String, config: StreamingConfig) {
//        Log.d(TAG, "Starting RTMP streaming to: $url")
//        Log.d(TAG, "Config: ${config.width}x${config.height}, bitrate=${config.videoBitrate}, fps=${config.frameRate}")
        
        if (config.mediaProjection == null) {
            val error = "MediaProjection 未初始化"
//            Log.e(TAG, error)
            onError?.invoke(error)
            return
        }
        
        try {
            currentUrl = url
            currentConfig = config
            
            encoder = SurfaceTextureEncoder(
                width = config.width,
                height = config.height,
                dpi = config.dpi,
                videoBitrate = config.videoBitrate,
                frameRate = config.frameRate,
                iFrameInterval = config.iFrameInterval,
                useAudio = config.useAudio,
                audioSampleRate = config.audioSampleRate,
                audioChannelCount = config.audioChannelCount,
                audioBitrate = config.audioBitrate,
                externalAudioSource = config.externalAudioSource
            ).apply {
                setMediaProjection(config.mediaProjection)
                
                // 设置 MediaProjectionService binder（从配置中获取）
                config.mediaProjectionServiceBinder?.let { binder ->
                    setMediaProjectionServiceBinder(binder)
                }
                
                // 转发状态回调
                onStreamStateChanged = { isStreaming ->
//                    Log.d(TAG, "Streaming state changed: $isStreaming")
                    onStreamingStateChanged?.invoke(isStreaming)
                }
                
                // 转发错误回调（防止递归）
                onError = label@{ error ->
                    if (isHandlingError) {
//                        Log.w(TAG, "Preventing recursive error callback: $error")
                        return@label
                    }
                    try {
                        isHandlingError = true
//                        Log.e(TAG, "Encoder error: $error")
                        this@RtmpStreamingProtocol.onError?.invoke(error)
                    } finally {
                        isHandlingError = false
                    }
                }
                
                // 启动编码器
                start(url)
//                Log.d(TAG, "RTMP encoder started successfully")
                
                // 启动音频采集
                if (config.useAudio) {
                    config.mediaProjectionServiceBinder?.let { binder ->
                        binder.toggleStreaming(true)
//                        Log.d(TAG, "🔊 Audio capture started")
                    }
                }
            }
            
        } catch (e: Exception) {
//            Log.e(TAG, "Failed to start RTMP streaming", e)
            // 🔥 关键修复：启动失败时清理 encoder 对象
            encoder?.let {
                try {
                    it.stop()
                } catch (stopEx: Exception) {
//                    Log.w(TAG, "Error stopping encoder during cleanup", stopEx)
                }
            }
            encoder = null
            currentUrl = null
            currentConfig = null
            onError?.invoke("启动推流失败: ${e.message}")
        }
    }
    
    override fun stop() {
//        Log.d(TAG, "Stopping RTMP streaming...")
        
        try {
            // 停止音频采集
            currentConfig?.let { config ->
                if (config.useAudio) {
                    config.mediaProjectionServiceBinder?.let { binder ->
                        binder.stopAudioCapture()
//                        Log.d(TAG, "🔇 Audio capture stopped")
                    }
                }
            }
            
            // 停止编码器
            encoder?.stop()
            encoder = null
            
            currentUrl = null
            currentConfig = null
            
//            Log.d(TAG, "RTMP streaming stopped")
            
        } catch (e: Exception) {
//            Log.e(TAG, "Error stopping RTMP streaming", e)
        }
    }
    
    override fun isStreaming(): Boolean {
        return encoder?.let {
            try {
                it.isRunning()
            } catch (e: Exception) {
//                Log.w(TAG, "Error checking encoder state", e)
                false
            }
        } ?: false
    }
    
    override fun switchUrl(newUrl: String) {
//        Log.d(TAG, "Switching URL from $currentUrl to $newUrl")
        
        // 先停止当前推流
        stop()
        
        // 用新 URL 重新启动
        currentConfig?.let { config ->
            start(newUrl, config)
        } ?: run {
//            Log.e(TAG, "Cannot switch URL: config is null")
            onError?.invoke("无法切换地址：配置为空")
        }
    }
    
    override fun updateBitrate(bitrate: Int) {
//        Log.d(TAG, "Updating bitrate to $bitrate (requires restart)")
        // 🔥 RTMP 协议需要重启推流才能更新码率
        val currentUrl = getCurrentUrl()
        if (currentUrl != null && isStreaming()) {
            stop()
            currentConfig = currentConfig?.copy(videoBitrate = bitrate)
            Thread.sleep(500)
            start(currentUrl, currentConfig!!)
        } else {
            currentConfig = currentConfig?.copy(videoBitrate = bitrate)
        }
    }
    
    override fun updateFrameRate(frameRate: Int) {
//        Log.d(TAG, "Updating frame rate to $frameRate (requires restart)")
        // 🔥 RTMP 协议需要重启推流才能更新帧率
        val currentUrl = getCurrentUrl()
        if (currentUrl != null && isStreaming()) {
            stop()
            currentConfig = currentConfig?.copy(frameRate = frameRate)
            Thread.sleep(500)
            start(currentUrl, currentConfig!!)
        } else {
            currentConfig = currentConfig?.copy(frameRate = frameRate)
        }
    }
    
    override fun getCurrentUrl(): String? {
        return currentUrl
    }
    
    override fun release() {
//        Log.d(TAG, "Releasing RTMP protocol resources")
        stop()
        onStreamingStateChanged = null
        onError = null
    }
}
