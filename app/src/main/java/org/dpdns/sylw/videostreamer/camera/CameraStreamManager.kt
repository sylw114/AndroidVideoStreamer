package org.dpdns.sylw.videostreamer.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresPermission
import android.app.Activity
import android.media.MediaRecorder
import android.graphics.ImageFormat
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import androidx.core.content.ContextCompat
import org.dpdns.sylw.videostreamer.R
import org.dpdns.sylw.videostreamer.StreamConfig
import org.dpdns.sylw.videostreamer.streaming.StreamManager
import org.dpdns.sylw.videostreamer.streaming.StreamingConfig

/**
 * Camera 推流管理器
 *
 * 1. 管理 Camera2 API
 * 2. 复用 IStreamingProtocol（编码器 + 推流），由协议层创建编码器输入 Surface
 *    并通过 onSurfaceReady 回调把 Surface 交还本管理器，本管理器再据此
 *    创建 Camera2 CaptureSession，将摄像头帧输出到编码器。
 */
class CameraStreamManager(private val context: Context) {

    companion object {
        private const val TAG = "CameraStreamManager"
    }

    // Camera2 相关
    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    // 🔥 推流协议（仅通过接口访问，复用录屏页同一套编码器/RTMP 逻辑）
    private var streamManager: StreamManager? = null



    // 摄像头状态
    private var isCameraReady: Boolean = false

    // 当前选中的摄像头配置（用于 CaptureRequest / 高速模式判断）
    private var cameraConfig: CameraConfig? = null

    // 状态回调
    var onCameraReady: ((Boolean) -> Unit)? = null
    var onStreamingStateChanged: ((Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    // 防止递归调用
    private var isHandlingError = false
    
    /**
     * 初始化 Camera 管理器
     */
    fun init() {
        cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    /**
     * 获取可用的摄像头列表
     *
     * 每个摄像头返回一个 CameraInfo，其中包含按帧率索引的分辨率列表。
     * 帧率选项来源于：
     * - CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES（标准帧率 ≤30fps）
     * - getOutputMinFrameDuration（中高帧率 60fps）
     * - getHighSpeedVideoFpsRanges（高速帧率 ≥120fps）
     *
     * 分辨率过滤逻辑：
     * - 标准帧率（≤30fps）：使用全部 MediaRecorder 输出尺寸
     * - 中高帧率（60fps）：仅 minFrameDuration 允许该帧率的分辨率
     * - 高速帧率（≥120fps）：仅 getHighSpeedVideoSizes 中的分辨率
     */
    fun getAvailableCameras(): List<CameraInfo> {
        val cameras = mutableListOf<CameraInfo>()

        try {
            val cameraIdList = cameraManager?.cameraIdList ?: return emptyList()

            for (cameraId in cameraIdList) {
                val characteristics = cameraManager?.getCameraCharacteristics(cameraId)

                // 只使用后置和前置摄像头，忽略外部摄像头
                val lensFacing = characteristics?.get(CameraCharacteristics.LENS_FACING)
                if (lensFacing == CameraCharacteristics.LENS_FACING_BACK ||
                    lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {

                    val info = buildCameraInfo(cameraId, characteristics)
                    cameras.add(info)
                }
            }
        } catch (e: Exception) {
//            Log.e(TAG, "Failed to get available cameras", e)
            onError?.invoke(context.getString(R.string.error_camera_list_failed, e.message ?: ""))
        }

        return cameras
    }

    /**
     * 构建单个摄像头的 CameraInfo（含按帧率索引的分辨率）
     */
    private fun buildCameraInfo(cameraId: String, c: CameraCharacteristics): CameraInfo {
        val isFront = c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        val configMap = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        // 1. 所有 MediaRecorder 输出尺寸（按面积降序）
        val allSizes: List<Size> = configMap?.getOutputSizes(MediaRecorder::class.java)
            ?.toList()
            ?.distinctBy { "${it.width}x${it.height}" }
            ?.sortedByDescending { it.width * it.height }
            ?: emptyList()

        if (allSizes.isEmpty()) {
            return CameraInfo(cameraId = cameraId, isFront = isFront, fpsToSizes = emptyMap(), fpsToMin = emptyMap())
        }

        // 2. 收集所有可用的帧率值
        val fpsSet = mutableSetOf<Int>()

        // 2a. 从 AE 范围收集标准帧率
        val aeRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        aeRanges?.forEach { fpsSet.add(it.upper) }

        // 2b. 从 minFrameDuration 计算中高帧率
        for (size in allSizes) {
            val minDur = configMap?.getOutputMinFrameDuration(ImageFormat.YUV_420_888, size)
            if (minDur != null && minDur > 0) {
                val maxFps = (1_000_000_000L / minDur).toInt()
                if (maxFps in 31..240) {
                    fpsSet.add(maxFps)
                }
            }
        }

        // 2c. 从高速度视频收集
        val highSpeedRanges = configMap?.highSpeedVideoFpsRanges
        if (highSpeedRanges != null) {
            for (range in highSpeedRanges) {
                if (range.upper >= 120) fpsSet.add(range.upper)
            }
        }

        // 收集所有高速度视频尺寸（Set 加速查找）
        val highSpeedSizes = configMap?.highSpeedVideoSizes?.toSet() ?: emptySet()

        // 3. 构建 fps → sizes 映射，并记录每个帧率对应的最小可用帧率（来自 AE ranges / high speed ranges）
        val fpsToSizes = mutableMapOf<Int, List<Size>>()
        val fpsToMin = mutableMapOf<Int, Int>()

        for (fps in fpsSet.filter { it > 0 }.sortedDescending()) {
            val sizes = when {
                // 高速帧率（≥120fps）：仅高速度视频尺寸
                fps >= 120 -> {
                    val candidates = mutableListOf<Size>()
                    val hsRanges = configMap?.highSpeedVideoFpsRanges
                    for (hsSize in highSpeedSizes) {
                        // 检查该尺寸是否支持目标帧率
                        var supported = false
                        if (hsRanges != null) {
                            for (r in hsRanges) {
                                // 如果范围包含 fps 或者范围上界 == fps
                                if (r.lower <= fps && r.upper >= fps) {
                                    supported = true
                                    break
                                }
                            }
                        } else {
                            // 没有具体范围信息，只要在高速尺寸列表就认为支持
                            supported = true
                        }
                        if (supported) {
                            candidates.add(hsSize)
                        }
                    }
                    candidates.sortedByDescending { it.width * it.height }
                }
                // 高帧率（60fps）：minFrameDuration 允许的尺寸
                fps > 30 -> {
                    allSizes.filter { size ->
                        val minDur = configMap?.getOutputMinFrameDuration(ImageFormat.YUV_420_888, size)
                        minDur != null && minDur > 0 && (1_000_000_000L / minDur) >= fps
                    }
                }
                // 标准帧率（≤30fps）：全部尺寸
                else -> allSizes
            }
            if (sizes.isNotEmpty()) {
                fpsToSizes[fps] = sizes

                // 计算该 fps 的最小帧率
                // 优先使用 AE 范围中包含该 fps 的最小下界；其次使用 highSpeedRanges 的下界；否则将最小值设为 fps 自身
                var minForFps: Int? = null
                if (aeRanges != null) {
                    for (r in aeRanges) {
                        if (r.lower <= fps && r.upper >= fps) {
                            if (minForFps == null || r.lower < minForFps) minForFps = r.lower
                        }
                    }
                }
                if (minForFps == null && highSpeedRanges != null) {
                    for (r in highSpeedRanges) {
                        if (r.lower <= fps && r.upper >= fps) {
                            if (minForFps == null || r.lower < minForFps) minForFps = r.lower
                        }
                    }
                }
                if (minForFps == null) minForFps = fps
                fpsToMin[fps] = minForFps
            }
        }

        return CameraInfo(
            cameraId = cameraId,
            isFront = isFront,
            fpsToSizes = fpsToSizes,
            fpsToMin = fpsToMin
        )
    }

    /**
     * 打开摄像头（仅打开 Camera2 设备，不创建编码器，也不创建 CaptureSession）
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    fun openCamera(config: CameraConfig, targetSurface: Surface) {
        Log.d(TAG, "Opening camera: ${config.cameraId}, resolution: ${config.width}x${config.height}, fps: ${config.frameRate}")

        cameraConfig = config

        // 若已有残留的 camera 资源（异常路径），先关闭
        if (cameraDevice != null) {
            Log.d(TAG, "Closing previous camera before opening new one")
            releaseCameraResources()
        }

        try {
            cameraManager?.openCamera(config.cameraId, ContextCompat.getMainExecutor(context), object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
//                    Log.d(TAG, "Camera opened successfully")
                    cameraDevice = camera
                    // 立即创建 CaptureSession（根据是否高速模式选择不同会话类型）
                    createCameraSession(camera, config, targetSurface)
                    isCameraReady = true
                    onCameraReady?.invoke(true)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera disconnected")
                    releaseCameraResources()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    val errorMsg = when (error) {
                        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "摄像头已被占用"
                        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "已达到最大摄像头数量"
                        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "摄像头已被禁用"
                        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "摄像头设备错误"
                        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "摄像头服务错误"
                        else -> "未知错误: $error"
                    }
                    Log.e(TAG, "Camera error: $errorMsg")
                    // 摄像头出错也要停掉推流，避免编码器无输入却一直占用 RTMP
                    stopStreaming()
                    releaseCameraResources()
                    onError?.invoke(errorMsg)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera", e)
            onError?.invoke("打开摄像头失败: ${e.message}")
        }
    }

    /**
     * 创建 Camera2 CaptureSession —— 高速模式用 ConstrainedHighSpeed，否则用普通会话
     */
    private fun createCameraSession(camera: CameraDevice, config: CameraConfig, targetSurface: Surface) {
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                Log.d(TAG, "Camera capture session configured")
                captureSession = session
                startCameraPreview(targetSurface)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(TAG, "Failed to configure camera capture session")
                onError?.invoke("配置摄像头会话失败")
                releaseCameraResources()
            }
        }

        try {
            val sessionConfig = SessionConfiguration(
                if(!config.isHighSpeed)SessionConfiguration.SESSION_REGULAR else SessionConfiguration.SESSION_HIGH_SPEED,
                listOf(OutputConfiguration(targetSurface)),
                ContextCompat.getMainExecutor(context),
                callback
            )
            camera.createCaptureSession(sessionConfig)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create camera capture session", e)
            onError?.invoke("创建摄像头会话失败: ${e.message}")
        }
    }

    /**
     * 开始 Camera 预览（持续输出帧到 Surface）
     * - 普通模式：setRepeatingRequest
     * - 高速模式：createHighSpeedRequestList + setRepeatingBurst
     */
    private fun startCameraPreview(targetSurface: Surface) {
        val config = cameraConfig ?: return
        val session = captureSession ?: return
        val device = cameraDevice ?: return

        try {
            Log.d(TAG, "Starting camera preview, target fps=${config.frameRate}, highSpeed=${config.isHighSpeed}")

            val captureRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)

            captureRequestBuilder.addTarget(targetSurface)

            captureRequestBuilder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            )
            captureRequestBuilder.set(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON
            )

            // 使用存储的最小帧率作为 AE 范围下界，确保设备可以在该范围内调节（例如 15-30fps）
            captureRequestBuilder.set(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(config.minFrameRateForSelected, config.frameRate)
            )

            val request = captureRequestBuilder.build()

            if (config.isHighSpeed && session is CameraConstrainedHighSpeedCaptureSession) {
                Log.d(TAG, "Using High Speed configuration...")
                val highSpeedRequestList = session.createHighSpeedRequestList(request)
                session.setRepeatingBurst(highSpeedRequestList, null, null)
            } else {
                Log.d(TAG, "Using Normal Speed configuration...")
                session.setRepeatingRequest(request, null, null)
            }

            Log.d(TAG, "Camera preview started successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start camera preview", e)
            onError?.invoke("启动预览失败: ${e.message}")
        }
    }

    /**
     * 开始推流
     *
     * 1. 调用 protocol.startCameraMode，由协议层创建编码器并暴露输入 Surface
     * 2. 协议层通过 onSurfaceReady 回调把 Surface 交还本管理器
     * 3. 本管理器使用该 Surface 打开摄像头，启动 CaptureSession
     */
    @SuppressLint("MissingPermission")
    suspend fun startStreaming(rtmpUrl: String, cameraConfig: CameraConfig, activity: Activity) {
        Log.d(TAG, "Starting RTMP streaming to: $rtmpUrl")

        fun onSurfaceReady(surface: Surface) {
            openCamera(cameraConfig, surface)
        }
        streamManager = StreamManager(activity, {
            onSurfaceReady(it)
        }, StreamingConfig(
            width = cameraConfig.width,
            height = cameraConfig.height,
            videoBitrate = StreamConfig.getVideoBitrate()!!,
            frameRate = cameraConfig.frameRate,
            iFrameInterval = 5,
            videoMode = StreamConfig.getRateMode()!!,
            videoQuality = StreamConfig.getCqQuality()!!,
            useAudio = false        // 摄像头模式不采集音频
        )
        ).apply {
            onStreamingStateChanged = { isStreaming ->
                Log.d(TAG, "Streaming state changed: $isStreaming")
                this@CameraStreamManager.onStreamingStateChanged?.invoke(isStreaming)
            }

            onError = label@{ error ->
                if (isHandlingError) {
                    Log.w(TAG, "Preventing recursive error callback: $error")
                    return@label
                }
                try {
                    isHandlingError = true
                    Log.e(TAG, "Protocol error: $error")
                    releaseCameraResources()
                    this@CameraStreamManager.onError?.invoke(error)
                } finally {
                    isHandlingError = false
                }
            }
        }
        if (streamManager == null) {
            Log.e(TAG, "Protocol not initialized")
            onError?.invoke("推流协议未初始化，请先调用 init()")
            return
        }

        if (!rtmpUrl.startsWith("rtmp://")) {
            onError?.invoke("无效的 RTMP 地址：$rtmpUrl")
            return
        }

        try {
            streamManager!!.init(StreamConfig.getStreamingProtocol()!!)

            streamManager!!.startStreaming(rtmpUrl)
            Log.d(TAG, "Camera streaming requested via protocol")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start RTMP streaming", e)
            onError?.invoke("启动推流失败: ${e.message}")
        }
    }

    /**
     * 停止推流（同时关闭摄像头）
     */
    fun stopStreaming() {
        Log.d(TAG, "Stopping RTMP streaming...")

        try {
            // 先关闭摄像头，停止帧流入 Surface
            releaseCameraResources()

            // 再停止协议层（编码器 + RTMP）
            streamManager?.release()
            Log.d(TAG, "RTMP streaming stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping RTMP streaming", e)
        }
    }

    /**
     * 检查是否正在推流
     */
    fun isStreaming(): Boolean {
        return streamManager?.isStreaming() ?: false
    }

    /**
     * 释放摄像头资源（不停止协议层）
     */
    private fun releaseCameraResources() {
        Log.d(TAG, "Releasing camera resources...")

        // Closing a CameraCaptureSession may internally call stopRepeating() which can
        // throw CameraAccessException (CAMERA_ERROR) if the device is already in an
        // error/closed state. Guard these calls to avoid uncaught exceptions coming
        // from camera framework threads (see logs: "Exception while stopping repeating").
        try {
            captureSession?.close()
        } catch (e: Exception) {
            // Catch CameraAccessException, IllegalStateException and any runtime
            // exception coming from the framework and log it. Do not rethrow.
            Log.w(TAG, "Ignored exception while closing captureSession", e)
        } finally {
            captureSession = null
        }

        try {
            cameraDevice?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Ignored exception while closing cameraDevice", e)
        } finally {
            cameraDevice = null
        }

        if (isCameraReady) {
            isCameraReady = false
            onCameraReady?.invoke(false)
        }

        Log.d(TAG, "Camera resources released")
    }

    /**
     * 兼容旧 API 的关闭摄像头方法（同时停止推流）
     */
    fun closeCamera() {
        stopStreaming()
    }

    /**
     * 释放所有资源
     */
    fun release() {
        Log.d(TAG, "Releasing CameraStreamManager...")

        stopStreaming()
        streamManager = null

        cameraManager = null

        onCameraReady = null
        onStreamingStateChanged = null
        onError = null

        Log.d(TAG, "CameraStreamManager released")
    }

    /**
     * 摄像头信息数据类
     *
     * fpsToSizes 是一个从帧率到分辨率列表的映射，例如：
     *   { 60 → [3840x2160], 30 → [全部尺寸], ... }
     */
    data class CameraInfo(
        val cameraId: String,
        val isFront: Boolean,
        val fpsToSizes: Map<Int, List<Size>>,
        /** 每个帧率对应的最小帧率（下界） */
        val fpsToMin: Map<Int, Int>
    ) {
        /** 所有可选的帧率（从高到低） */
        val allFrameRates: List<Int>
            get() = fpsToSizes.keys.sortedDescending()

        /** 所有分辨率（去重并按面积降序） */
        val allSizes: List<Size>
            get() = fpsToSizes.values.flatten().distinct()
                .sortedByDescending { it.width * it.height }

        /** 获取某个帧率下支持的分辨率列表 */
        fun getSizesForFps(fps: Int): List<Size> = fpsToSizes[fps] ?: emptyList()

        /** 获取某个帧率下的最小帧率（用于设置 AE 范围的下界） */
        fun getMinForFps(fps: Int): Int = fpsToMin[fps] ?: fps

        /** 最大帧率（用于默认选中） */
        val maxFrameRate: Int get() = allFrameRates.firstOrNull() ?: 30

        fun displayName(context: Context): String {
            val nameResId = if (isFront) R.string.camera_front_camera else R.string.camera_back_camera
            return context.getString(nameResId)
        }
    }

    /**
     * 摄像头启动配置
     */
    data class CameraConfig(
        val cameraId: String,
        val width: Int,
        val height: Int,
        val frameRate: Int,
        /** 完整的帧率->分辨率映射（来源于 CameraInfo），用于后续打开摄像头时参考可用最小帧率 */
        val fpsToSizes: Map<Int, List<Size>> = emptyMap(),
        /** 每个帧率对应的最小帧率（下界），用于设置 AE 范围 */
        val fpsToMin: Map<Int, Int> = emptyMap()
    ) {
        /** 是否高速模式（≥120fps 需要用 ConstrainedHighSpeedCaptureSession） */
        val isHighSpeed: Boolean get() = frameRate >= 120

        /** 当前选中帧率对应的最小帧率（用于设置 CONTROL_AE_TARGET_FPS_RANGE 的下界） */
        val minFrameRateForSelected: Int get() = fpsToMin[frameRate] ?: frameRate
    }
}
