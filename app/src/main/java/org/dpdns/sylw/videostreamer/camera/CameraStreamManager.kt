package org.dpdns.sylw.videostreamer.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresPermission
import android.app.Activity
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
    private var cameraHandlerThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    // 🔥 推流协议（仅通过接口访问，复用录屏页同一套编码器/RTMP 逻辑）
    private var streamManager: StreamManager? = null

    // 当前配置
    private var currentCameraId: String? = null
    private var currentWidth: Int = 1920
    private var currentHeight: Int = 1080
    private var currentFrameRate: Int = 30
    private var videoBitrate: Int = 2500_000
    private var videoMode: String = "CBR"
    private var videoQuality: Int = 70

    // 摄像头状态
    private var isCameraReady: Boolean = false

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

        // 创建后台线程处理 Camera 回调
        cameraHandlerThread = HandlerThread("CameraHandler").apply {
            start()
        }
        cameraHandler = Handler(cameraHandlerThread!!.looper)
    }

    /**
     * 获取可用的摄像头列表
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

                    val info = CameraInfo(
                        cameraId = cameraId,
                        isFront = lensFacing == CameraCharacteristics.LENS_FACING_FRONT,
                        supportedSizes = getSupportedSizes(characteristics),
                        supportedFrameRates = getSupportedFrameRates(characteristics)
                    )
                    cameras.add(info)
                }
            }
        } catch (e: Exception) {
//            Log.e(TAG, "Failed to get available cameras", e)
            onError?.invoke("获取摄像头列表失败: ${e.message}")
        }

        return cameras
    }

    /**
     * 获取支持的分辨率列表
     */
    private fun getSupportedSizes(characteristics: CameraCharacteristics?): List<Size> {
        return try {
            val configMap = characteristics?.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            )
            val sizes = configMap?.getOutputSizes(Surface::class.java)
            sizes?.toList()?.sortedByDescending { it.width * it.height } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 获取支持的帧率范围
     */
    private fun getSupportedFrameRates(characteristics: CameraCharacteristics?): List<Int> {
        if (characteristics == null) return listOf(30)

        return try {
            val fpsRanges = characteristics.get(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
            ) ?: return listOf(30)

            fpsRanges.map { it.upper }
                .distinct()
                .sorted()
        } catch (e: Exception) {
            listOf(30)
        }
    }

    /**
     * 打开摄像头（仅打开 Camera2 设备，不创建编码器，也不创建 CaptureSession）
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    fun openCamera(cameraId: String, width: Int, height: Int, frameRate: Int, targetSurface: Surface) {
//        Log.d(TAG, "Opening camera: $cameraId, resolution: ${width}x${height}, fps: $frameRate, mode=$mode, quality=$quality")

        // 若已有残留的 camera 资源（异常路径），先关闭
        if (cameraDevice != null) {
            Log.d(TAG, "Closing previous camera before opening new one")
            releaseCameraResources()
        }

        try {
            cameraManager?.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
//                    Log.d(TAG, "Camera opened successfully")
                    cameraDevice = camera
                    // 立即创建 CaptureSession，让摄像头帧流向编码器 Surface
                    createCameraSessionAndPreview(camera, targetSurface)
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
            }, cameraHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera", e)
            onError?.invoke("打开摄像头失败: ${e.message}")
        }
    }

    /**
     * 创建 Camera2 CaptureSession 并启动持续预览（输出到编码器 Surface）
     */
    private fun createCameraSessionAndPreview(camera: CameraDevice, targetSurface: Surface) {
        try {
            camera.createCaptureSession(
                listOf(targetSurface),
                object : CameraCaptureSession.StateCallback() {
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
                },
                cameraHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create camera capture session", e)
            onError?.invoke("创建摄像头会话失败: ${e.message}")
        }
    }

    /**
     * 开始 Camera 预览（持续输出到 Surface）
     */
    private fun startCameraPreview(targetSurface: Surface) {
        try {
            Log.d(TAG, "Starting camera preview to surface")

            val captureRequestBuilder = cameraDevice?.createCaptureRequest(
                CameraDevice.TEMPLATE_RECORD
            )

            captureRequestBuilder?.addTarget(targetSurface)

            // 设置自动对焦和自动曝光
            captureRequestBuilder?.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            )
            captureRequestBuilder?.set(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON
            )

            val captureRequest = captureRequestBuilder?.build()

            // 设置重复请求，持续输出帧
            captureSession?.setRepeatingRequest(captureRequest!!, null, cameraHandler)
            
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
    // TODO: 协议切换
    @SuppressLint("MissingPermission")
    suspend fun startStreaming(rtmpUrl: String, cameraId: String, width: Int, height: Int, selectedFrameRate: Int, activity: Activity) {
        Log.d(TAG, "Starting RTMP streaming to: $rtmpUrl")

        fun onSurfaceReady(surface: Surface) {
            openCamera(cameraId, width, height, selectedFrameRate, surface)
        }
        streamManager = StreamManager(activity, {
                onSurfaceReady(it)
            }, StreamingConfig(
                width = currentWidth,
                height = currentHeight,
                videoBitrate = videoBitrate,
                frameRate = currentFrameRate,
                iFrameInterval = 5,
                videoMode = videoMode,
                videoQuality = videoQuality,
                useAudio = false,        // 摄像头模式不采集音频
                isCameraMode = true
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
                    // 🔥 推流出错时一并关闭摄像头，避免摄像头仍在占用而推流已断
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

        captureSession?.close()
        captureSession = null

        cameraDevice?.close()
        cameraDevice = null

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

        // 释放协议层
        streamManager?.release()
        streamManager = null

        // 停止后台线程
        cameraHandlerThread?.quitSafely()
        try {
            cameraHandlerThread?.join()
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error joining handler thread", e)
        }
        cameraHandlerThread = null
        cameraHandler = null

        cameraManager = null

        onCameraReady = null
        onStreamingStateChanged = null
        onError = null

        Log.d(TAG, "CameraStreamManager released")
    }

    /**
     * 摄像头信息数据类
     */
    data class CameraInfo(
        val cameraId: String,
        val isFront: Boolean,
        val supportedSizes: List<Size>,
        val supportedFrameRates: List<Int>
    ) {
        fun displayName(context: Context): String {
            val nameResId = if (isFront) {
                R.string.camera_front_camera
            } else {
                R.string.camera_back_camera
            }
            return context.getString(nameResId)
        }
    }
}
