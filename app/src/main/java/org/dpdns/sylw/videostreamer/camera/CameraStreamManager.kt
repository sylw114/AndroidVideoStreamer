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
import android.graphics.SurfaceTexture
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.FocusMeteringResult
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.TimeUnit
import org.dpdns.sylw.videostreamer.R
import org.dpdns.sylw.videostreamer.StreamConfig
import org.dpdns.sylw.videostreamer.streaming.StreamManager
import org.dpdns.sylw.videostreamer.streaming.StreamingConfig
import org.dpdns.sylw.videostreamer.streaming.VideoFrameRateDiagnostics
import kotlin.math.roundToInt

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
        private const val HIGH_FRAME_RATE_THRESHOLD = 60
        private const val EXPOSURE_WARM_UP_NS = 750_000_000L
        private const val EXPOSURE_MAX_WAIT_NS = 1_500_000_000L
        private const val MAX_EXPOSURE_FRAME_RATIO = 0.9
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

    // 🔥 CameraX 相关（普通模式 ≤60fps）
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraX: Camera? = null
    private var displayPreview: Preview? = null
    private var encoderPreview: Preview? = null
    private var previewSurfaceProvider: Preview.SurfaceProvider? = null
    private var previewSurfaceTexture: SurfaceTexture? = null
    private var activePreviewSurface: Surface? = null
    private var lifecycleActivity: Activity? = null

    // 🔥 高速模式软件 AE（≥120fps，Camera2 路径）
    private var softwareAe: HighSpeedSoftwareAe? = null

    // 高帧率曝光锁定状态。每次打开摄像头只尝试一次，失败时维持系统 AE。
    private var highFrameRateExposureAttempted = false
    private var highFrameRateExposureStartNs = 0L
    private var highFrameRateExposureResultCount = 0

    // 状态回调
    var onCameraReady: ((Boolean) -> Unit)? = null
    var onStreamingStateChanged: ((Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onInfo: ((String) -> Unit)? = null
    var onVideoFrameRateMeasured: ((VideoFrameRateDiagnostics) -> Unit)? = null

    // 防止递归调用
    private var isHandlingError = false

    // 高速模式降级标记：是否已尝试过回退到普通帧率
    private var hasAttemptedFallback = false

    private val mainHandler = Handler(Looper.getMainLooper())
    
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
            return CameraInfo(
                cameraId = cameraId,
                isFront = isFront,
                fpsToSizes = emptyMap(),
                fpsToMin = emptyMap(),
                highSpeedFpsToSizes = emptyMap()
            )
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
        val highSpeedFpsToSizes = mutableMapOf<Int, List<Size>>()

        for (fps in fpsSet.filter { it > 0 }.sortedDescending()) {
            val highSpeedCandidatesForFps = if (fps > 30) {
                val candidates = mutableListOf<Size>()
                for (hsSize in highSpeedSizes) {
                    val hsRangesForSize = try {
                        configMap?.getHighSpeedVideoFpsRangesFor(hsSize)
                    } catch (e: IllegalArgumentException) {
                        Log.w(TAG, "无法读取高速尺寸 ${hsSize.width}x${hsSize.height} 的帧率范围", e)
                        null
                    }
                    val supported = hsRangesForSize?.any { it.upper == fps || (it.lower <= fps && it.upper >= fps) }
                        ?: false
                    if (supported) {
                        candidates.add(hsSize)
                    }
                }
                candidates.sortedByDescending { it.width * it.height }
            } else {
                emptyList()
            }
            if (highSpeedCandidatesForFps.isNotEmpty()) {
                highSpeedFpsToSizes[fps] = highSpeedCandidatesForFps
            }

            val sizes = when {
                // 高速帧率（≥120fps）：仅高速度视频尺寸
                fps >= 120 -> highSpeedCandidatesForFps
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
                    minForFps = if (highSpeedRanges.any { r -> r.lower == fps && r.upper == fps }) {
                        fps
                    } else {
                        var minFromHighSpeed: Int? = null
                        for (r in highSpeedRanges) {
                            if (r.lower <= fps && r.upper >= fps) {
                                if (minFromHighSpeed == null || r.lower < minFromHighSpeed) minFromHighSpeed = r.lower
                            }
                        }
                        minFromHighSpeed
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
            fpsToMin = fpsToMin,
            highSpeedFpsToSizes = highSpeedFpsToSizes
        )
    }

    /**
     * CameraX 绑定（普通模式 ≤60fps）
     *
     * 绑定两个 Preview 用例：
     * 1. 编码器 Preview：通过自定义 SurfaceProvider 把编码器输入 Surface 提供给 CameraX
     * 2. 显示 Preview（可选）：绑定 UI 的 PreviewView SurfaceProvider（预览 + 点击对焦）
     * 60fps 通过 Camera2Interop 设置 CONTROL_AE_TARGET_FPS_RANGE。
     */
    @OptIn(ExperimentalCamera2Interop::class)
    @SuppressLint("MissingPermission")
    private fun bindCameraX(config: CameraConfig, targetSurface: Surface) {
        val lifecycleOwner = lifecycleActivity as? LifecycleOwner
        if (lifecycleOwner == null) {
            Log.e(TAG, "Activity 未实现 LifecycleOwner，无法绑定 CameraX")
            onError?.invoke("Activity 未实现 LifecycleOwner")
            return
        }

        // ProcessCameraProvider.getInstance() 可能阻塞，在调用线程（IO）获取
        val provider = try {
            ProcessCameraProvider.getInstance(context).get()
        } catch (e: Exception) {
            Log.e(TAG, "获取 CameraProvider 失败", e)
            onError?.invoke("CameraX 初始化失败: ${e.message}")
            return
        }
        cameraProvider = provider

        // CameraX 的绑定操作必须在主线程执行
        mainHandler.post {
            try {
                provider.unbindAll()

                val isFront = try {
                    cameraManager?.getCameraCharacteristics(config.cameraId)
                        ?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
                } catch (e: Exception) {
                    false
                }
                val cameraSelector =
                    if (isFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

                // 1. 编码器 Preview：帧直接输出到编码器输入 Surface
                val encoderBuilder = Preview.Builder()
                    .setTargetResolution(Size(config.width, config.height))
                Camera2Interop.Extender(encoderBuilder)
                    .setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        Range(config.minFrameRateForSelected, config.frameRate)
                    )
                val encoderPreview = encoderBuilder.build()
                encoderPreview.setSurfaceProvider(object : Preview.SurfaceProvider {
                    override fun onSurfaceRequested(request: SurfaceRequest) {
                        request.provideSurface(targetSurface, ContextCompat.getMainExecutor(context)) { result ->
                            if (result.resultCode != SurfaceRequest.Result.RESULT_SURFACE_USED_SUCCESSFULLY) {
                                Log.w(TAG, "编码器 Surface 未成功使用: ${result.resultCode}")
                            }
                        }
                    }
                })
                this.encoderPreview = encoderPreview

                // 2. 显示 Preview：绑定 UI 传入的 PreviewView SurfaceProvider
                val useCases = mutableListOf<androidx.camera.core.UseCase>(encoderPreview)
                val displayProvider = previewSurfaceProvider
                if (displayProvider != null) {
                    val displayBuilder = Preview.Builder()
                        .setTargetResolution(Size(config.width, config.height))
                    Camera2Interop.Extender(displayBuilder)
                        .setCaptureRequestOption(
                            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                            Range(config.minFrameRateForSelected, config.frameRate)
                        )
                    val displayPreview = displayBuilder.build()
                    displayPreview.setSurfaceProvider(displayProvider)
                    this.displayPreview = displayPreview
                    useCases.add(displayPreview)
                }

                cameraX = provider.bindToLifecycle(lifecycleOwner, cameraSelector, *useCases.toTypedArray())
                isCameraReady = true
                onCameraReady?.invoke(true)
                Log.d(TAG, "CameraX 绑定成功, useCases=${useCases.size}")
            } catch (e: Exception) {
                Log.e(TAG, "CameraX 绑定失败", e)
                onError?.invoke("CameraX 绑定失败: ${e.message}")
            }
        }
    }

    /**
     * 设置预览 SurfaceProvider（UI 层把 PreviewView 的 SurfaceProvider 传入）
     */
    fun setPreviewSurfaceProvider(provider: Preview.SurfaceProvider?) {
        previewSurfaceProvider = provider
        // 若显示预览已绑定，实时更新
        displayPreview?.setSurfaceProvider(provider)
    }

    /**
     * 设置 Camera2 预览 SurfaceTexture。高速/60fps 路径需要真实 preview Surface。
     */
    fun setPreviewSurfaceTexture(surfaceTexture: SurfaceTexture?) {
        if (previewSurfaceTexture === surfaceTexture) return
        activePreviewSurface?.release()
        activePreviewSurface = null
        previewSurfaceTexture = surfaceTexture
    }

    /**
     * 手动对焦 + 测光（点击预览区域触发）
     *
     * @param meteringPoint 由 UI 层通过 PreviewView.meteringPointFactory.createPoint(x, y) 生成
     * @return 对焦结果 Future（可监听判断对焦是否成功），摄像头未就绪时返回 null
     */
    fun focusAt(meteringPoint: MeteringPoint): ListenableFuture<FocusMeteringResult>? {
        val camera = cameraX ?: return null
        val action = FocusMeteringAction.Builder(
            meteringPoint,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        )
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        return try {
            camera.cameraControl.startFocusAndMetering(action)
        } catch (e: Exception) {
            Log.w(TAG, "手动对焦失败", e)
            null
        }
    }

    /**
     * 打开摄像头（仅打开 Camera2 设备，不创建编码器，也不创建 CaptureSession）
     */
    // 保存最近一次传给 openCamera 的 Surface，用于降级重试
    private var lastTargetSurface: Surface? = null

    @RequiresPermission(Manifest.permission.CAMERA)
    fun openCamera(config: CameraConfig, targetSurface: Surface) {
        Log.d(TAG, "Opening camera: ${config.cameraId}, resolution: ${config.width}x${config.height}, fps: ${config.frameRate}")

        cameraConfig = config
        lastTargetSurface = targetSurface
        resetHighFrameRateExposureState()

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

                    // 高速模式失败时自动降级到普通帧率
                    val currentConfig = cameraConfig
                    if (currentConfig != null && currentConfig.isHighSpeed && !hasAttemptedFallback) {
                        Log.w(TAG, "高速摄像头会话失败，尝试降级到普通帧率")
                        // 设备已进入错误态时，主动关闭高速 session 会触发 framework 在 stopRepeating() 打 E 级栈。
                        // 这里只关闭 camera device，保留编码器 Surface，稍后再尝试普通帧率重开。
                        releaseCameraResources(closeCaptureSession = false)
                        hasAttemptedFallback = true
                        tryFallbackToNormalSpeed(currentConfig)
                        return
                    }

                    // 摄像头出错也要停掉推流，避免编码器无输入却一直占用 RTMP
                    stopStreaming()
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
        val previewSurface = if (config.isHighSpeed) {
            // 部分厂商的高速会话虽然声明支持 preview+recording，但实际会把编码输出降到 30fps。
            // 高速推流优先保证编码帧率；普通帧率仍保留预览 Surface。
            null
        } else {
            getOrCreatePreviewSurface(config)
        }
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                Log.d(TAG, "Camera capture session configured")
                captureSession = session
                startCameraPreview(targetSurface, previewSurface)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(TAG, "Failed to configure camera capture session")

                // 高速模式配置失败时自动降级
                val currentConfig = cameraConfig
                if (currentConfig != null && currentConfig.isHighSpeed && !hasAttemptedFallback) {
                    Log.w(TAG, "高速会话配置失败，尝试降级到普通帧率")
                    releaseCameraResources()
                    hasAttemptedFallback = true
                    tryFallbackToNormalSpeed(currentConfig)
                    return
                }

                onError?.invoke("配置摄像头会话失败")
                releaseCameraResources()
            }
        }

        try {
            if (!isCameraConfigSupported(config)) {
                Log.w(TAG, "摄像头配置不受支持: ${config.width}x${config.height}@${config.frameRate}, highSpeed=${config.isHighSpeed}")
                if (config.isHighSpeed && !hasAttemptedFallback) {
                    releaseCameraResources()
                    hasAttemptedFallback = true
                    tryFallbackToNormalSpeed(config)
                    return
                }
                onError?.invoke("摄像头不支持当前配置：${config.width}x${config.height}@${config.frameRate}fps")
                return
            }

            // 高速会话对 repeating burst 很敏感，避免软件 AE 重复提交请求导致厂商管线退回普通帧率。
            if (config.isHighSpeed) {
                softwareAe?.close()
                softwareAe = null
            }

            val outputConfigurations = mutableListOf(OutputConfiguration(targetSurface))
            previewSurface?.let { outputConfigurations.add(OutputConfiguration(it)) }
            val sessionParameters = buildBaseCaptureRequest(camera, config, targetSurface, previewSurface)
                .build()
            Log.i(
                TAG,
                "创建摄像头会话: ${config.width}x${config.height}@${config.frameRate}fps, " +
                    "highSpeed=${config.isHighSpeed}, outputs=${outputConfigurations.size}, " +
                    "fpsRange=${selectCaptureFpsRange(config)}"
            )
            if (config.isHighSpeed) {
                @Suppress("DEPRECATION")
                camera.createConstrainedHighSpeedCaptureSession(
                    listOfNotNull(targetSurface, previewSurface),
                    callback,
                    mainHandler
                )
                return
            }
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputConfigurations,
                ContextCompat.getMainExecutor(context),
                callback
            ).apply {
                setSessionParameters(sessionParameters)
            }
            camera.createCaptureSession(sessionConfig)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create camera capture session", e)
            onError?.invoke("创建摄像头会话失败: ${e.message}")
        }
    }

    private fun getOrCreatePreviewSurface(config: CameraConfig): Surface? {
        val texture = previewSurfaceTexture ?: return null
        texture.setDefaultBufferSize(config.width, config.height)
        return activePreviewSurface ?: Surface(texture).also {
            activePreviewSurface = it
        }
    }

    private fun buildBaseCaptureRequest(
        device: CameraDevice,
        config: CameraConfig,
        targetSurface: Surface,
        previewSurface: Surface?
    ): CaptureRequest.Builder {
        return device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(targetSurface)
            previewSurface?.let { addTarget(it) }
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, selectCaptureFpsRange(config))
        }
    }

    private fun selectCaptureFpsRange(config: CameraConfig): Range<Int> {
        if (!config.isHighSpeed) {
            val fallbackRange = Range(config.minFrameRateForSelected, config.frameRate)
            val selectedRange = try {
                val characteristics = cameraManager?.getCameraCharacteristics(config.cameraId)
                val ranges = characteristics
                    ?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    .orEmpty()
                ranges.firstOrNull { it.lower == config.frameRate && it.upper == config.frameRate }
                    ?: ranges.firstOrNull { it.lower <= config.frameRate && it.upper >= config.frameRate }
                    ?: fallbackRange
            } catch (e: Exception) {
                Log.w(TAG, "选择普通帧率范围失败，使用配置默认范围", e)
                fallbackRange
            }
            Log.i(TAG, "普通帧率范围: requested=${config.frameRate}fps, selected=$selectedRange")
            return selectedRange
        }

        val selectedSize = Size(config.width, config.height)
        return try {
            val characteristics = cameraManager?.getCameraCharacteristics(config.cameraId)
            val configMap = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val ranges = configMap?.getHighSpeedVideoFpsRangesFor(selectedSize).orEmpty()
            val selectedRange = ranges.firstOrNull { it.lower == config.frameRate && it.upper == config.frameRate }
                ?: ranges.firstOrNull { it.lower <= config.frameRate && it.upper >= config.frameRate }
                ?: Range(config.frameRate, config.frameRate)
            Log.i(TAG, "高速帧率范围: size=$selectedSize, supported=${ranges.joinToString()}, selected=$selectedRange")
            selectedRange
        } catch (e: Exception) {
            Log.w(TAG, "选择高速帧率范围失败，使用固定帧率", e)
            Range(config.frameRate, config.frameRate)
        }
    }

    private fun isCameraConfigSupported(config: CameraConfig): Boolean {
        val selectedSize = Size(config.width, config.height)
        val supportedSizes = config.fpsToSizes[config.frameRate]
        if (supportedSizes != null && selectedSize !in supportedSizes) {
            return false
        }

        if (!config.isHighSpeed) {
            return true
        }

        val manager = cameraManager ?: return false
        return try {
            val characteristics = manager.getCameraCharacteristics(config.cameraId)
            val configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val ranges = configMap?.getHighSpeedVideoFpsRangesFor(selectedSize) ?: return false
            ranges.any { it.upper == config.frameRate || (it.lower <= config.frameRate && it.upper >= config.frameRate) }
        } catch (e: Exception) {
            Log.w(TAG, "校验高速摄像头配置失败", e)
            false
        }
    }

    /**
     * 开始 Camera 预览（持续输出帧到 Surface）
     * - 普通模式：setRepeatingRequest
     * - 高速模式：createHighSpeedRequestList + setRepeatingBurst
     */
    private fun startCameraPreview(targetSurface: Surface, previewSurface: Surface?) {
        val config = cameraConfig ?: return
        val session = captureSession ?: return
        val device = cameraDevice ?: return

        try {
            Log.d(TAG, "Starting camera preview, target fps=${config.frameRate}, highSpeed=${config.isHighSpeed}")

            val captureRequestBuilder = buildBaseCaptureRequest(device, config, targetSurface, previewSurface)
            // 高速模式：应用软件 AE 当前曝光补偿
            val ae = softwareAe
            if (ae != null) {
                captureRequestBuilder.set(
                    CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                    ae.currentCompensation
                )
            }

            val request = captureRequestBuilder.build()
            val captureCallback = createExposureCaptureCallback(
                config = config,
                targetSurface = targetSurface,
                previewSurface = previewSurface,
                softwareAe = ae
            )
            submitRepeatingRequest(session, config, request, captureCallback)
            scheduleHighFrameRateExposureFallback(session, config, targetSurface, previewSurface)

            Log.d(TAG, "Camera preview started successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start camera preview", e)

            // 高速模式启动预览失败时也尝试降级
            val currentConfig = cameraConfig
            if (currentConfig != null && currentConfig.isHighSpeed && !hasAttemptedFallback) {
                Log.w(TAG, "高速预览启动失败，尝试降级到普通帧率")
                releaseCameraResources()
                hasAttemptedFallback = true
                tryFallbackToNormalSpeed(currentConfig)
                return
            }

            onError?.invoke("启动预览失败: ${e.message}")
        }
    }

    private fun submitRepeatingRequest(
        session: CameraCaptureSession,
        config: CameraConfig,
        request: CaptureRequest,
        callback: CameraCaptureSession.CaptureCallback?
    ) {
        if (config.isHighSpeed && session is CameraConstrainedHighSpeedCaptureSession) {
            Log.d(TAG, "Using High Speed configuration...")
            val requestList = session.createHighSpeedRequestList(request)
            session.setRepeatingBurst(requestList, callback, mainHandler)
        } else {
            Log.d(TAG, "Using Normal Speed configuration...")
            session.setRepeatingRequest(request, callback, mainHandler)
        }
    }

    private fun createExposureCaptureCallback(
        config: CameraConfig,
        targetSurface: Surface,
        previewSurface: Surface?,
        softwareAe: HighSpeedSoftwareAe?
    ): CameraCaptureSession.CaptureCallback? {
        if (config.frameRate < HIGH_FRAME_RATE_THRESHOLD && softwareAe == null) return null

        highFrameRateExposureStartNs = System.nanoTime()
        return object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                softwareAe?.onCaptureResult(result)
                maybeLockHighFrameRateExposure(
                    session = session,
                    config = config,
                    targetSurface = targetSurface,
                    previewSurface = previewSurface,
                    result = result
                )
            }
        }
    }

    private fun maybeLockHighFrameRateExposure(
        session: CameraCaptureSession,
        config: CameraConfig,
        targetSurface: Surface,
        previewSurface: Surface?,
        result: TotalCaptureResult
    ) {
        if (config.frameRate < HIGH_FRAME_RATE_THRESHOLD || highFrameRateExposureAttempted) return
        if (captureSession !== session || cameraConfig != config) return

        highFrameRateExposureResultCount++
        val elapsedNs = System.nanoTime() - highFrameRateExposureStartNs
        if (elapsedNs < EXPOSURE_WARM_UP_NS || highFrameRateExposureResultCount < 10) return

        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
        val aeSettled = aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
            aeState == CaptureResult.CONTROL_AE_STATE_LOCKED ||
            aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
        if (!aeSettled && elapsedNs < EXPOSURE_MAX_WAIT_NS) return

        val exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
        val sensitivity = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
        val reportedFrameDurationNs = result.get(CaptureResult.SENSOR_FRAME_DURATION)
        highFrameRateExposureAttempted = true

        Log.i(
            TAG,
            "高帧率曝光采样: requested=${config.frameRate}fps, exposure=${exposureTimeNs}ns, " +
                "sensitivity=$sensitivity, frameDuration=${reportedFrameDurationNs}ns, aeState=$aeState"
        )

        if (tryApplyManualHighFrameRateExposure(
                session,
                config,
                targetSurface,
                previewSurface,
                exposureTimeNs,
                sensitivity
            )
        ) {
            return
        }

        if (tryApplyAeLock(
                session,
                config,
                targetSurface,
                previewSurface,
                exposureTimeNs
            )
        ) {
            return
        }

        Log.w(TAG, "设备未能应用高帧率短曝光锁定，继续使用系统自动曝光")
        onInfo?.invoke("设备未能锁定高帧率短曝光，将继续使用自动曝光")
    }

    /**
     * 部分厂商的 constrained high-speed 会话不回传曝光元数据。
     * 等待 AE 采样超时后，使用目标帧周期和保守的高 ISO 直接尝试手动短曝光。
     */
    private fun scheduleHighFrameRateExposureFallback(
        session: CameraCaptureSession,
        config: CameraConfig,
        targetSurface: Surface,
        previewSurface: Surface?
    ) {
        if (config.frameRate < HIGH_FRAME_RATE_THRESHOLD) return

        mainHandler.postDelayed({
            if (highFrameRateExposureAttempted) return@postDelayed
            if (captureSession !== session || cameraConfig != config) return@postDelayed

            val characteristics = try {
                cameraManager?.getCameraCharacteristics(config.cameraId)
            } catch (e: Exception) {
                Log.w(TAG, "读取高帧率曝光兜底参数失败", e)
                null
            } ?: return@postDelayed
            val sensitivityRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                ?: return@postDelayed
            val targetFrameDurationNs = 1_000_000_000L / config.frameRate
            val fallbackExposureTimeNs = (targetFrameDurationNs * MAX_EXPOSURE_FRAME_RATIO).toLong()
            val fallbackSensitivity = (sensitivityRange.upper / 2)
                .coerceAtLeast(800)
                .coerceIn(sensitivityRange.lower, sensitivityRange.upper)

            highFrameRateExposureAttempted = true
            Log.i(
                TAG,
                "高速会话未返回曝光元数据，尝试兜底短曝光: exposure=${fallbackExposureTimeNs}ns, " +
                    "ISO=$fallbackSensitivity"
            )
            if (!tryApplyManualHighFrameRateExposure(
                    session,
                    config,
                    targetSurface,
                    previewSurface,
                    fallbackExposureTimeNs,
                    fallbackSensitivity
                )
            ) {
                onInfo?.invoke("高速会话不接受手动短曝光，将继续使用自动曝光")
            }
        }, EXPOSURE_MAX_WAIT_NS / 1_000_000L)
    }

    private fun tryApplyManualHighFrameRateExposure(
        session: CameraCaptureSession,
        config: CameraConfig,
        targetSurface: Surface,
        previewSurface: Surface?,
        measuredExposureTimeNs: Long,
        measuredSensitivity: Int
    ): Boolean {
        return try {
            val characteristics = cameraManager?.getCameraCharacteristics(config.cameraId) ?: return false
            val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            if (capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) != true) {
                return false
            }

            val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                ?: return false
            val sensitivityRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                ?: return false
            val targetFrameDurationNs = 1_000_000_000L / config.frameRate
            val exposureLimitNs = (targetFrameDurationNs * MAX_EXPOSURE_FRAME_RATIO).toLong()
            if (exposureRange.lower > exposureLimitNs) return false

            val lockedExposureTimeNs = measuredExposureTimeNs
                .coerceAtMost(exposureLimitNs)
                .coerceIn(exposureRange.lower, exposureRange.upper)
            val brightnessScale = measuredExposureTimeNs.toDouble() / lockedExposureTimeNs
            val lockedSensitivity = (measuredSensitivity * brightnessScale)
                .roundToInt()
                .coerceIn(sensitivityRange.lower, sensitivityRange.upper)

            val builder = buildBaseCaptureRequest(
                cameraDevice ?: return false,
                config,
                targetSurface,
                previewSurface
            ).apply {
                set(CaptureRequest.CONTROL_AE_LOCK, false)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, lockedExposureTimeNs)
                set(CaptureRequest.SENSOR_SENSITIVITY, lockedSensitivity)
                set(CaptureRequest.SENSOR_FRAME_DURATION, targetFrameDurationNs)
            }
            submitRepeatingRequest(session, config, builder.build(), null)

            val exposureMs = lockedExposureTimeNs / 1_000_000.0
            Log.i(
                TAG,
                "已应用高帧率手动曝光: ${"%.2f".format(exposureMs)}ms, ISO=$lockedSensitivity, " +
                    "frameDuration=${targetFrameDurationNs}ns"
            )
            onInfo?.invoke(
                "已锁定短曝光 ${"%.2f".format(exposureMs)} ms / ISO $lockedSensitivity，优先保证 ${config.frameRate}fps"
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "高帧率手动曝光请求被设备拒绝，尝试 AE 锁定", e)
            false
        }
    }

    private fun tryApplyAeLock(
        session: CameraCaptureSession,
        config: CameraConfig,
        targetSurface: Surface,
        previewSurface: Surface?,
        measuredExposureTimeNs: Long
    ): Boolean {
        return try {
            val characteristics = cameraManager?.getCameraCharacteristics(config.cameraId) ?: return false
            if (characteristics.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) != true) return false

            val targetFrameDurationNs = 1_000_000_000L / config.frameRate
            val exposureLimitNs = (targetFrameDurationNs * MAX_EXPOSURE_FRAME_RATIO).toLong()
            if (measuredExposureTimeNs > exposureLimitNs) return false

            val builder = buildBaseCaptureRequest(
                cameraDevice ?: return false,
                config,
                targetSurface,
                previewSurface
            ).apply {
                set(CaptureRequest.CONTROL_AE_LOCK, true)
            }
            submitRepeatingRequest(session, config, builder.build(), null)

            val exposureMs = measuredExposureTimeNs / 1_000_000.0
            Log.i(TAG, "已应用高帧率 AE 锁定: ${"%.2f".format(exposureMs)}ms")
            onInfo?.invoke("已锁定自动曝光 ${"%.2f".format(exposureMs)} ms，正在检测实际帧率")
            true
        } catch (e: Exception) {
            Log.w(TAG, "高帧率 AE 锁定请求被设备拒绝", e)
            false
        }
    }

    private fun resetHighFrameRateExposureState() {
        highFrameRateExposureAttempted = false
        highFrameRateExposureStartNs = 0L
        highFrameRateExposureResultCount = 0
    }

    /**
     * 尝试创建高速模式软件 AE（基于 CaptureResult 曝光参数闭环）
     *
     * 仅当设备支持曝光补偿（CONTROL_AE_COMPENSATION_RANGE 非 [0,0]）时创建，
     * 否则返回 null（维持原行为）。
     */
    private fun tryCreateSoftwareAe(config: CameraConfig): HighSpeedSoftwareAe? {
        return try {
            val characteristics = cameraManager?.getCameraCharacteristics(config.cameraId)
            val compRange = characteristics?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            if (compRange == null || (compRange.lower == 0 && compRange.upper == 0)) {
                Log.w(TAG, "设备不支持曝光补偿，软件 AE 不可用")
                return null
            }
            HighSpeedSoftwareAe(
                frameDurationNs = 1_000_000_000L / config.frameRate,
                compensationRange = compRange,
                onExposureChanged = { compensation ->
                    updateHighSpeedExposure(compensation)
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "创建软件 AE 失败", e)
            null
        }
    }

    /**
     * 高速模式：应用新的曝光补偿并重新提交高速请求列表
     */
    private fun updateHighSpeedExposure(compensation: Int) {
        val config = cameraConfig ?: return
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val targetSurface = lastTargetSurface ?: return
        val previewSurface = activePreviewSurface
        if (session !is CameraConstrainedHighSpeedCaptureSession) return

        try {
            val builder = buildBaseCaptureRequest(device, config, targetSurface, previewSurface)
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, compensation)
            val requestList = session.createHighSpeedRequestList(builder.build())
            session.setRepeatingBurst(requestList, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "更新高速曝光补偿失败", e)
        }
    }

    /**
     * 高速模式失败后降级到普通帧率（60fps → 30fps）
     *
     * 某些设备的高速摄像头会话（SESSION_HIGH_SPEED）因硬件/驱动兼容性问题
     * 无法正常工作（表现为 C2AllocatorGralloc 不兼容、摄像头设备错误等）。
     * 此方法将帧率降级到设备支持的普通帧率，使用常规会话重新打开摄像头。
     */
    @SuppressLint("MissingPermission")
    private fun tryFallbackToNormalSpeed(originalConfig: CameraConfig) {
        // 从 fpsToSizes 中找到最高的非高速帧率（<120fps）
        val fallbackFps = originalConfig.fpsToSizes.keys
            .filter { fps ->
                fps < 120 && originalConfig.fpsToSizes[fps]?.any {
                    it.width == originalConfig.width && it.height == originalConfig.height
                } == true
            }
            .maxOrNull()

        if (fallbackFps == null || fallbackFps <= 0) {
            Log.e(TAG, "无法找到可用的普通帧率进行降级")
            onError?.invoke("高速摄像头模式不兼容，且当前分辨率无可用的普通帧率，请降低分辨率后重试")
            return
        }

        Log.d(TAG, "降级到普通帧率: ${originalConfig.frameRate}fps → ${fallbackFps}fps")

        // 编码器 Surface 已按原分辨率创建，降级只能改帧率，不能在同一次推流中改分辨率。
        val fallbackConfig = CameraConfig(
            cameraId = originalConfig.cameraId,
            width = originalConfig.width,
            height = originalConfig.height,
            frameRate = fallbackFps,
            fpsToSizes = originalConfig.fpsToSizes,
            fpsToMin = originalConfig.fpsToMin
        )

        try {
            val surface = lastTargetSurface
            if (surface == null) {
                Log.e(TAG, "无法获取编码器 Surface 进行降级")
                onError?.invoke("高速摄像头模式降级失败：编码器 Surface 不可用")
                return
            }
            mainHandler.postDelayed({
                openCamera(fallbackConfig, surface)
            }, 500L)
        } catch (e: Exception) {
            Log.e(TAG, "降级到普通帧率失败", e)
            onError?.invoke("降级到普通帧率失败: ${e.message}")
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

        // 重置降级标记，每次新推流都允许先尝试高速模式
        hasAttemptedFallback = false
        lifecycleActivity = activity

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
            useAudio = false,       // 摄像头模式不采集音频
            requireHardwareVideoEncoder = true
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

            onInfo = { message ->
                this@CameraStreamManager.onInfo?.invoke(message)
            }

            onVideoFrameRateMeasured = { diagnostics ->
                this@CameraStreamManager.onVideoFrameRateMeasured?.invoke(diagnostics)
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
    private fun releaseCameraResources(closeCaptureSession: Boolean = true) {
        Log.d(TAG, "Releasing camera resources...")

        // 解绑 CameraX（普通模式）
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "Ignored exception while unbinding CameraX", e)
        }
        cameraProvider = null
        cameraX = null
        displayPreview = null
        encoderPreview = null

        // 关闭软件 AE（高速模式）
        try {
            softwareAe?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Ignored exception while closing software AE", e)
        }
        softwareAe = null
        resetHighFrameRateExposureState()

        // Closing a CameraCaptureSession may internally call stopRepeating() which can
        // throw CameraAccessException (CAMERA_ERROR) if the device is already in an
        // error/closed state. Guard these calls to avoid uncaught exceptions coming
        // from camera framework threads (see logs: "Exception while stopping repeating").
        if (closeCaptureSession) {
            try {
                captureSession?.close()
            } catch (e: Exception) {
                // Catch CameraAccessException, IllegalStateException and any runtime
                // exception coming from the framework and log it. Do not rethrow.
                Log.w(TAG, "Ignored exception while closing captureSession", e)
            }
        } else {
            Log.w(TAG, "摄像头已进入错误态，跳过 captureSession.close()")
        }
        if (!closeCaptureSession || captureSession != null) {
            captureSession = null
        }

        try {
            activePreviewSurface?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Ignored exception while releasing preview surface", e)
        } finally {
            activePreviewSurface = null
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
        lastTargetSurface = null
        hasAttemptedFallback = false

        cameraManager = null

        onCameraReady = null
        onStreamingStateChanged = null
        onError = null
        onInfo = null
        onVideoFrameRateMeasured = null

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
        val fpsToMin: Map<Int, Int>,
        /** 来自 Camera2 high-speed 表的帧率->分辨率映射，用于决定是否需要受限高速会话 */
        val highSpeedFpsToSizes: Map<Int, List<Size>> = emptyMap()
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
        val fpsToMin: Map<Int, Int> = emptyMap(),
        /** 来自 Camera2 high-speed 表的帧率->分辨率映射 */
        val highSpeedFpsToSizes: Map<Int, List<Size>> = emptyMap()
    ) {
        /** 是否需要受限高速会话（所选尺寸必须出现在 Camera2 high-speed 表中） */
        val isHighSpeed: Boolean
            get() = frameRate >= 120 && highSpeedFpsToSizes[frameRate]?.any {
                it.width == width && it.height == height
            } == true

        /** 当前选中帧率对应的最小帧率（用于设置 CONTROL_AE_TARGET_FPS_RANGE 的下界） */
        val minFrameRateForSelected: Int get() = fpsToMin[frameRate] ?: frameRate
    }
}
