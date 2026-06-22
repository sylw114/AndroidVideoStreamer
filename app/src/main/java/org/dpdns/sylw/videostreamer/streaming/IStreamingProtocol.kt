package org.dpdns.sylw.videostreamer.streaming

import android.view.Surface

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
     * 开始摄像头模式推流（不需要 MediaProjection）
     *
     * @param url 推流地址
     * @param config 推流配置
     */
    fun startCameraMode(url: String, config: StreamingConfig)

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
     * 编码器异常退出后的资源清理
     *
     * 协议实现需要释放编码器、网络连接和协议持有的运行状态，
     * 并确保上层收到停止推流状态。
     */
    fun cleanupAfterEncoderExit()

    /**
     * 设置状态回调
     */
    var onStreamingStateChanged: ((Boolean) -> Unit)?

    /**
     * 设置错误回调
     */
    var onError: ((String) -> Unit)?

    /**
     * 设置非致命提示回调
     */
    var onInfo: ((String) -> Unit)?

    /**
     * 编码器输入 Surface 就绪回调
     * 当协议层创建好编码器的输入 Surface 后触发，UI 层可用该 Surface
     * 连接 Camera2（摄像头模式）或更新 VirtualDisplay（录屏模式）。
     */
    val onSurfaceReady: (Surface) -> Unit
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
    val videoMode: String = "CBR",    // 🔥 新增：CBR 或 CQ
    val videoQuality: Int = 70,       // 🔥 新增：0-100
    val useAudio: Boolean = true,
    val onAudioCaptureStart: (() -> Unit)? = null,
    val onAudioCaptureStop: (() -> Unit)? = null,
    val audioSampleRate: Int = 48000,
    val audioChannelCount: Int = 2,
    val audioBitrate: Int = 128_000,   // 128 kbps
    val externalAudioSource: (() -> Pair<ByteArray, Long>?)? = null,  // 🔥 返回 PCM 数据和采集时间戳
)
