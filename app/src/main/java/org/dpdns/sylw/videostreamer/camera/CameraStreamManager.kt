package org.dpdns.sylw.videostreamer.camera

import android.Manifest
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresPermission
import org.dpdns.sylw.videostreamer.rtmpStreamer.SurfaceTextureEncoder
import java.util.*

/**
 * Camera 推流管理器
 * 
 * 🔥 核心功能：
 * 1. 管理 Camera2 API
 * 2. 将摄像头数据直接编码为 H.264
 * 3. 通过 RTMP 推流（无音频）
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
    
    // 编码器相关
    private var encoder: SurfaceTextureEncoder? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    
    // 当前配置
    private var currentCameraId: String? = null
    private var currentWidth: Int = 1920
    private var currentHeight: Int = 1080
    private var currentFrameRate: Int = 30
    private var videoBitrate: Int = 2500_000  // 从全局配置读取
    
    // 摄像头状态
    private var isCameraReady: Boolean = false
    
    // 状态回调
    var onCameraReady: ((Boolean) -> Unit)? = null  // 🔥 摄像头就绪状态
    var onStreamingStateChanged: ((Boolean) -> Unit)? = null  // 推流状态
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
        
//        Log.d(TAG, "CameraStreamManager initialized")
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
            val sizes = configMap?.getOutputSizes(SurfaceTexture::class.java)
            sizes?.toList()?.sortedByDescending { it.width * it.height } ?: emptyList()
        } catch (e: Exception) {
//            Log.e(TAG, "Failed to get supported sizes", e)
            emptyList()
        }
    }
    
    /**
     * 获取支持的帧率范围
     */
    private fun getSupportedFrameRates(characteristics: CameraCharacteristics?): List<Int> {
        return try {
            val fpsRanges = characteristics?.get(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
            )
            // 提取常见的固定帧率
            val commonFrameRates = setOf(15, 24, 30, 60)
            fpsRanges?.flatMap { range ->
                commonFrameRates.filter { it in range.lower..range.upper }
            }?.distinct()?.sorted() ?: listOf(30)
        } catch (e: Exception) {
//            Log.e(TAG, "Failed to get supported frame rates", e)
            listOf(30)
        }
    }
    
    /**
     * 打开摄像头（仅初始化 Camera，不创建编码器）
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    fun openCamera(cameraId: String, width: Int, height: Int, frameRate: Int, bitrate: Int) {
//        Log.d(TAG, "Opening camera: $cameraId, resolution: ${width}x${height}, fps: $frameRate")
        
        currentCameraId = cameraId
        currentWidth = width
        currentHeight = height
        currentFrameRate = frameRate
        videoBitrate = bitrate
        
        try {
            // 🔥 只有在摄像头已打开时才关闭（避免首次打开时的无效调用）
            if (cameraDevice != null || encoder != null) {
//                Log.d(TAG, "Closing previous camera before opening new one")
                closeCamera()
            }
            
            // 打开摄像头
            cameraManager?.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
//                    Log.d(TAG, "Camera opened successfully")
                    cameraDevice = camera
                    // 🔥 注意：这里不创建预览会话，等待推流时再创建
                    isCameraReady = true
                    onCameraReady?.invoke(true)  // 🔥 通知 UI 摄像头已就绪
                }
                
                override fun onDisconnected(camera: CameraDevice) {
//                    Log.w(TAG, "Camera disconnected")
                    closeCamera()
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
                    onError?.invoke(errorMsg)
                    closeCamera()
                }
            }, cameraHandler)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera", e)
            onError?.invoke("打开摄像头失败: ${e.message}")
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
     * 开始 RTMP 推流
     */
    fun startStreaming(rtmpUrl: String) {
        Log.d(TAG, "Starting RTMP streaming to: $rtmpUrl")
        
        if (cameraDevice == null) {
            Log.e(TAG, "Camera not opened")
            onError?.invoke("摄像头未启动，请先启动摄像头")
            return
        }
        
        try {
            // 🔥 关键流程：先创建编码器，获取 Surface，再创建 Camera 预览会话
            createEncoderAndStartPreview(rtmpUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start RTMP streaming", e)
            onError?.invoke("启动推流失败: ${e.message}")
        }
    }
    
    /**
     * 创建编码器并启动预览（内部方法）
     */
    private fun createEncoderAndStartPreview(rtmpUrl: String) {
        Log.d(TAG, "Creating encoder and starting preview...")
        
        try {
            // 1. 创建编码器
            encoder = SurfaceTextureEncoder(
                width = currentWidth,
                height = currentHeight,
                dpi = 320,
                videoBitrate = videoBitrate,
                frameRate = currentFrameRate,
                iFrameInterval = 5,
                useAudio = false,
                externalAudioSource = null,
                isCameraMode = true
            ).apply {
                onStreamStateChanged = { isStreaming ->
                    Log.d(TAG, "Streaming state changed: $isStreaming")
                    onStreamingStateChanged?.invoke(isStreaming)
                }
                
                onError = label@{ error ->
                    if (isHandlingError) {
                        Log.w(TAG, "Preventing recursive error callback: $error")
                        return@label
                    }
                    try {
                        isHandlingError = true
                        Log.e(TAG, "Encoder error: $error")
                        this@CameraStreamManager.onError?.invoke(error)
                    } finally {
                        isHandlingError = false
                    }
                }
            }
            
            // 2. 🔥 关键：先启动编码器（初始化 RTMP 和 MediaCodec）
            Log.d(TAG, "Starting encoder with URL: $rtmpUrl")
            encoder?.start(rtmpUrl)
            Log.d(TAG, "Encoder started successfully")
            
            // 3. 获取编码器的 Input Surface
            val encoderInputSurface = encoder?.getInputSurface()
            if (encoderInputSurface == null) {
                Log.e(TAG, "Failed to get encoder input surface")
                onError?.invoke("获取编码器输入表面失败")
                encoder?.stop()
                encoder = null
                return
            }
            
            Log.d(TAG, "Encoder input surface obtained: $encoderInputSurface")
            
            // 4. 创建 Camera Capture Session
            val surfaces = listOf(encoderInputSurface)
            
            cameraDevice?.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    Log.d(TAG, "Camera capture session configured")
                    captureSession = session
                    
                    // 5. 启动 Camera 预览（摄像头数据输出到编码器）
                    startCameraPreview(encoderInputSurface)
                    
                    Log.d(TAG, "Camera preview and RTMP streaming started")
                }
                
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Failed to configure camera capture session")
                    onError?.invoke("配置摄像头会话失败")
                    closeCamera()
                }
            }, cameraHandler)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create encoder and start preview", e)
            // 🔥 清理资源
            encoder?.let {
                try {
                    it.stop()
                } catch (stopEx: Exception) {
                    Log.w(TAG, "Error stopping encoder during cleanup", stopEx)
                }
            }
            encoder = null
            onError?.invoke("启动推流失败: ${e.message}")
        }
    }
    
    /**
     * 停止推流
     */
    fun stopStreaming() {
        Log.d(TAG, "Stopping RTMP streaming...")
        
        try {
            encoder?.stop()
            Log.d(TAG, "RTMP streaming stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping RTMP streaming", e)
        }
    }
    
    /**
     * 检查是否正在推流
     */
    fun isStreaming(): Boolean {
        return encoder?.isRunning() ?: false
    }
    
    /**
     * 关闭摄像头
     */
    fun closeCamera() {
        Log.d(TAG, "Closing camera... (stack trace below)")
        Log.d(TAG, android.util.Log.getStackTraceString(Exception()))
        
        // 停止推流
        stopStreaming()
        
        // 关闭 Capture Session
        captureSession?.close()
        captureSession = null
        
        // 关闭 Camera Device
        cameraDevice?.close()
        cameraDevice = null
        
        // 释放 Surface
        cameraSurface?.release()
        cameraSurface = null
        
        // 释放 SurfaceTexture
        surfaceTexture?.release()
        surfaceTexture = null
        
        // 释放编码器
        encoder = null
        
        // 🔥 更新状态
        isCameraReady = false
        onCameraReady?.invoke(false)
        
        Log.d(TAG, "Camera closed")
    }
    
    /**
     * 释放所有资源
     */
    fun release() {
        Log.d(TAG, "Releasing CameraStreamManager...")
        
        closeCamera()
        
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
        val displayName: String
            get() = if (isFront) "前置摄像头" else "后置摄像头"
    }
}
