package org.dpdns.sylw.videostreamer.streaming

import android.media.projection.MediaProjection
import org.dpdns.sylw.videostreamer.MediaProjectionService

/**
 * 推流协议抽象接口
 * 
 * 🔥 设计目标：解耦界面层和协议层
 */
interface IStreamingProtocol {
    
    /**
     * 开始推流
     * 
     * @param url 推流地址（RTMP 等）
     * @param config 推流配置
     */
    fun start(url: String, config: StreamingConfig)
    
    /**
     * 停止推流
     */
    fun stop()
    
    /**
     * 检查是否正在推流
     */
    fun isStreaming(): Boolean
    
    /**
     * 动态切换推流地址
     * 
     * @param newUrl 新的推流地址
     */
    fun switchUrl(newUrl: String)
    
    /**
     * 动态更新码率
     * 
     * @param bitrate 新码率（bps）
     */
    fun updateBitrate(bitrate: Int)
    
    /**
     * 动态更新帧率
     * 
     * @param frameRate 新帧率（fps）
     */
    fun updateFrameRate(frameRate: Int)
    
    /**
     * 获取当前推流地址
     */
    fun getCurrentUrl(): String?
    
    /**
     * 释放资源
     */
    fun release()
    
    /**
     * 设置状态回调
     */
    var onStreamingStateChanged: ((Boolean) -> Unit)?
    
    /**
     * 设置错误回调
     */
    var onError: ((String) -> Unit)?
}

/**
 * 推流配置数据类
 */
data class StreamingConfig(
    val width: Int = 1920,
    val height: Int = 1080,
    val dpi: Int = 320,
    val videoBitrate: Int = 2500_000,  // 2.5 Mbps
    val frameRate: Int = 30,
    val iFrameInterval: Int = 5,
    val useAudio: Boolean = true,
    val audioSampleRate: Int = 48000,
    val audioChannelCount: Int = 2,
    val audioBitrate: Int = 128_000,   // 128 kbps
    val mediaProjection: MediaProjection? = null,
    val mediaProjectionServiceBinder: MediaProjectionService.LocalBinder? = null,
    val externalAudioSource: (() -> ByteArray?)? = null
)
