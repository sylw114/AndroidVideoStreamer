// G:/VideoStreamer/app/src/main/java/org/dpdns/sylw/videostreamer/VideoWindow.kt

package org.dpdns.sylw.videostreamer

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.SurfaceTexture
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dpdns.sylw.videostreamer.streaming.StreamManager
import kotlin.math.abs


// 视频窗口
@Composable
fun VideoWindow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    
    // 推流管理器
    var streamManager by remember { mutableStateOf<StreamManager?>(null) }
    var isStreaming by remember { mutableStateOf(false) }
    
    // 从全局配置读取推流 URL（使用可变状态）
    var currentRtmpUrl by remember { mutableStateOf<String?>(null) }
    
    var mediaProjectionService: MediaProjectionService.LocalBinder? by remember {
        mutableStateOf(
            null
        )
    }
    val isAuthorized by MediaProjectionService.isRunning.collectAsState()
    var textureView: TextureView? by remember { mutableStateOf(null) }


    // Service 绑定连接
    val connection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, serviceBinder: IBinder?) {
//                android.util.Log.d("VideoWindow", "onServiceConnected called")
                val binder = serviceBinder as MediaProjectionService.LocalBinder
                mediaProjectionService = binder
                // Service 连接成功后，如果 TextureView 已经准备好，立即设置 Surface
                textureView?.surfaceTexture?.let { surfaceTexture ->
                    val screenSize = binder.getScreenRealSize()
                    val actualWidth = screenSize.x
                    val actualHeight = screenSize.y
                    
                    // 直接使用物理分辨率，不根据方向交换
                    // VirtualDisplay 会自动处理方向
//                    android.util.Log.d("VideoWindow", "onServiceConnected: ${actualWidth}x${actualHeight}")
                    
                    if (actualWidth > 0 && actualHeight > 0) {
                        try {
                            surfaceTexture.setDefaultBufferSize(actualWidth, actualHeight)
                            val surface = Surface(surfaceTexture)
                            // PreviewSurface logic removed - system now uses internal dummy surface
//                            android.util.Log.d(
//                                "VideoWindow",
//                                "SurfaceTexture configured: ${actualWidth}x${actualHeight}"
//                            )
                        } catch (e: Exception) {
//                            android.util.Log.e(
//                                "VideoWindow",
//                                "Error configuring surface texture: ${e.message}"
//                            )
                            e.printStackTrace()
                        }
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
//                android.util.Log.d("VideoWindow", "onServiceDisconnected called")
                mediaProjectionService = null
            }
        }
    }

    val screenCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
//        android.util.Log.d(
//            "VideoWindow",
//            "ActivityResult: resultCode=${result.resultCode}, hasData=${result.data != null}"
//        )

        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(context, MediaProjectionService::class.java).apply {
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("DATA", result.data)
            }
            context.startForegroundService(intent)
            try {
//                android.util.Log.d("VideoWindow", "Attempting to bind service...")
                context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            } catch (e: Exception) {
//                android.util.Log.e("VideoWindow", "Failed to bind service: ${e.message}")
                e.printStackTrace()
            }
        } else {
//            android.util.Log.w("VideoWindow", "Screen capture authorization failed or cancelled")
        }
    }

    // 页面销毁时解绑
    DisposableEffect(Unit) {
        onDispose {
            if (isAuthorized) {
                try {
                    context.unbindService(connection)
                } catch (e: Exception) {
                }
            }
//            // 释放 TextureView 引用
            textureView = null
        }
    }
    
    // 初始化推流管理器并从配置加载参数
    DisposableEffect(Unit) {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
        
        // 🔥 关键修复：设置全局 streaming 引用，让 SettingWindow 可以访问
        streamManager = StreamManager(context as Activity).apply {
            streaming = this
            
            onStreamingStateChanged = { streaming ->
                isStreaming = streaming
//                android.util.Log.d("VideoWindow", "Streaming state changed: $streaming")
            }
            
            onError = { error ->
//                android.util.Log.e("VideoWindow", "Stream error: $error")
            }
        }
        
        // 异步加载保存的配置并初始化协议
        scope.launch {
            val savedUrl = loadUrl(context)
            StreamConfig.setCurrentUrl(savedUrl)
            currentRtmpUrl = savedUrl  // 更新本地状态
//            android.util.Log.d("VideoWindow", "Loaded saved RTMP URL: $savedUrl")
            
            val savedBitrate = loadBitrate(context)
//            android.util.Log.d("VideoWindow", "Loaded saved bitrate: $savedBitrate")
            
            val savedFrameRate = loadFrameRate(context)
//            android.util.Log.d("VideoWindow", "Loaded saved frame rate: $savedFrameRate")
            
            val savedProtocol = loadProtocol(context)
//            android.util.Log.d("VideoWindow", "Loaded saved protocol: $savedProtocol")
            
            // 初始化协议（使用保存的协议）
            streamManager?.init(savedProtocol)
            
            // 设置帧率到 StreamManager
            streamManager?.setVideoParams(
                width = 1920,  // 默认宽度，后续会根据屏幕方向调整
                height = 1080, // 默认高度
                bitrate = savedBitrate,
                frameRate = savedFrameRate
            )
        }
        
        onDispose {
            streamManager?.release()
            streamManager = null
        }
    }
    
    // 当绑定到 Service 时，设置外部音频源和 MediaProjection
    LaunchedEffect(mediaProjectionService) {
        mediaProjectionService?.let { binder ->
            // 1. 传递 MediaProjection 给 StreamManager（关键！）
            val projection = binder.getMediaProjection()
            if (projection != null) {
                streamManager?.setMediaProjection(projection)
//                android.util.Log.d("VideoWindow", "MediaProjection passed to StreamManager")
            } else {
//                android.util.Log.e("VideoWindow", "MediaProjection is null in Service!")
            }
            
            // 2. 将 StreamManager 的旋转回调注册到 MediaProjectionService
            binder.onScreenRotation = { newWidth, newHeight ->
//                android.util.Log.d("MediaProj", "Screen rotation detected: ${newWidth}x${newHeight}")
                // 直接调用 StreamManager 的 updateResolution
                streamManager?.updateResolution(newWidth, newHeight)
            }
            
            // 🔥 注册 MediaProjection 停止回调（系统终止权限时自动停止推流）
            binder.onMediaProjectionStopped = {
//                android.util.Log.w("VideoWindow", "⚠️ MediaProjection stopped by system!")
                // 通过 StreamManager 停止推流（业务逻辑层）
                streamManager?.stopStreaming()
                // isStreaming 会通过 StateFlow 自动更新 UI
            }
//            android.util.Log.d("VideoWindow", "Screen rotation callback registered in Service")
            
            // 3. 设置外部 PCM 音频源（从 MediaProjectionService 获取）
            // 🔥 性能优化：使用 getAudioDataInto() + 预分配缓冲区，减少 GC
            val pcmReadBuffer = ByteArray(15360) // 预分配缓冲区
            streamManager?.setExternalAudioSource {
                val readSize = binder.getAudioDataInto(pcmReadBuffer)
                if (readSize > 0) {
                    pcmReadBuffer.copyOf(readSize)
                } else {
                    null
                }
            }
//            android.util.Log.d("AudioCapture", "External audio source set from MediaProjectionService (optimized)")
            
            // 4. 设置 MediaProjectionService binder 到 StreamManager（供协议层使用）
            streamManager?.setMediaProjectionServiceBinder(binder)
//            android.util.Log.d("VideoWindow", "MediaProjectionService binder configured in StreamManager")
        }
    }
    
    // 屏幕旋转现在由 MediaProjectionService 在后台持续监听，不需要在前台检测
    
    // 🔥 关键修复：页面恢复时重新绑定服务（从设置页返回时）
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (isAuthorized && mediaProjectionService == null) {
//                android.util.Log.d("VideoWindow", "🔄 Page resumed, re-binding to existing service...")
                try {
                    val intent = Intent(context, MediaProjectionService::class.java)
                    context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                } catch (e: Exception) {
//                    android.util.Log.e("VideoWindow", "Failed to re-bind service: ${e.message}")
                    e.printStackTrace()
                }
            }
        }
    }
    
    // 当已授权时，自动绑定服务
    DisposableEffect(isAuthorized) {
        if (isAuthorized && mediaProjectionService == null) {
            try {
//                android.util.Log.d("VideoWindow", "Auto-binding to existing service...")
                val intent = Intent(context, MediaProjectionService::class.java)
                context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            } catch (e: Exception) {
//                android.util.Log.e("VideoWindow", "Failed to auto-bind service: ${e.message}")
                e.printStackTrace()
            }
        }
        onDispose {
            // isAuthorized 变为 false 时不需要处理，上面的 Unit dispose 会处理
        }
    }

    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val dm = DisplayMetrics()


    windowManager.defaultDisplay.getRealMetrics(dm)
    var width by remember {
        mutableIntStateOf(dm.widthPixels)
    }
    var height by remember {
        mutableIntStateOf(dm.heightPixels)
    }
    var aspectRatio by remember {
        mutableFloatStateOf(width.toFloat() / height.toFloat())
    }
//    val dpi = context.resources.displayMetrics.densityDpi
//    val aspectRatio = width.toFloat() / height.toFloat()


    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
//                    android.util.Log.d("VideoWindow", "Button clicked! isAuthorized=$isAuthorized, mediaProjectionService=${if (mediaProjectionService == null) "null" else "valid"}")
                    if (!isAuthorized) {
                        // 未授权时，启动录屏授权
                        val mpManager =
                            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        screenCaptureLauncher.launch(mpManager.createScreenCaptureIntent())
                    } else {
//                        android.util.Log.d("VideoWindow", "Entering authorized branch")
                        mediaProjectionService?.let { binder ->
//                            android.util.Log.d("VideoWindow", "binder is valid, calling toggleStreaming")
                            // 获取实际屏幕分辨率（物理尺寸）
                            val screenSize = binder.getScreenRealSize()
                            val actualWidth = screenSize.x
                            val actualHeight = screenSize.y
                            
                            // 获取保存的码率
                            scope.launch {
                                val savedBitrate = loadBitrate(context)
                                
//                                android.util.Log.d("VideoWindow", "=== 开始配置 ===")
//                                android.util.Log.d("VideoWindow", "binder: ${if (binder == null) "null" else "valid"}")
//                                android.util.Log.d("VideoWindow", "streamManager: ${if (streamManager == null) "null" else "valid"}")

                                // 获取实际屏幕分辨率（物理尺寸）
                                val screenSize = binder.getScreenRealSize()
                                val actualWidth = screenSize.x
                                val actualHeight = screenSize.y
                                
//                                android.util.Log.d("VideoWindow", "推流分辨率：${actualWidth}x${actualHeight}")
//                                android.util.Log.d("VideoWindow", "码率：$savedBitrate bps")
//                                android.util.Log.d("VideoWindow", "=== 配置结束 ===")

                                // 设置视频参数（使用物理分辨率）
                                streamManager?.setVideoParams(
                                    width = actualWidth,
                                    height = actualHeight,
                                    bitrate = savedBitrate,
                                    frameRate = 30,
                                    iFrameInterval = 5
                                )
                                
//                                android.util.Log.d("VideoWindow", "准备调用 toggleStreaming(true)")
                                // 开始录制
                                binder.toggleStreaming(true)
//                                android.util.Log.d("VideoWindow", "toggleStreaming(true) 调用完成")
                            }
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                colors = if (isAuthorized) ButtonDefaults.buttonColors(
                    containerColor = Color(
                        0xFF4CAF50
                    )
                ) else ButtonDefaults.buttonColors()
            ) {
                Text(if (isAuthorized) "录屏服务正在运行" else "授权并开启录屏")
            }
            Button(
                onClick = {
                    // 使用新的推流管理器进行推流
                    streamManager?.let { manager ->
                        if (manager.isStreaming()) {
                            // 停止推流 - 在后台线程执行
                            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                manager.stopStreaming()
//                                android.util.Log.d("VideoWindow", "Stopped streaming")
                            }
                        } else {
                            // 开始推流
                            val rtmpUrl = currentRtmpUrl
                            if (!rtmpUrl.isNullOrEmpty()) {
                                
                                // 获取实际屏幕分辨率（物理尺寸）
                                mediaProjectionService?.let { binder ->
                                    val screenSize = binder.getScreenRealSize()
                                    val actualWidth = screenSize.x
                                    val actualHeight = screenSize.y
                                    
//                                    android.util.Log.d("VideoWindow", "推流分辨率：${actualWidth}x${actualHeight}")
//                                    android.util.Log.d("VideoWindow", "=== 配置结束 ===")
                                    
                                    // 在后台线程启动推流，避免 NetworkOnMainThreadException
                                    kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        try {
                                            // 加载保存的码率和帧率
                                            val savedBitrate = loadBitrate(context)
                                            val savedFrameRate = loadFrameRate(context)
                                            
                                            // 使用 MediaProjectionService 的实际分辨率
                                            val screenSize = binder.getScreenRealSize()
                                            val actualWidth = screenSize.x
                                            val actualHeight = screenSize.y
//                                            android.util.Log.d("VideoWindow", "Starting RTMP streaming: ${actualWidth}x${actualHeight}, bitrate=$savedBitrate, fps=$savedFrameRate to: $rtmpUrl")
                                
                                // 设置视频参数（使用实际分辨率和保存的帧率）
                                manager.setVideoParams(
                                    width = actualWidth,
                                    height = actualHeight,
                                    bitrate = savedBitrate,
                                    frameRate = savedFrameRate,
                                    iFrameInterval = 5
                                )
                                            
                                            manager.startStreaming(rtmpUrl)
//                                            android.util.Log.d("VideoWindow", "Started streaming to: $rtmpUrl")
                                        } catch (e: Exception) {
//                                            android.util.Log.e("VideoWindow", "Failed to start streaming", e)
                                            // 在主线程显示错误
                                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                manager.onError?.invoke("启动推流失败：${e.message}")
                                            }
                                        }
                                    }
                                } ?: run {
//                                    android.util.Log.e("VideoWindow", "MediaProjectionService not bound!")
                                }
                            } else {
                                // 如果没有设置 RTMP URL，提示用户去设置页面配置
//                                android.util.Log.w("VideoWindow", "RTMP URL not set, please configure in Settings")
                                // TODO: 可以显示一个 Toast 或对话框提示用户
                            }
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = isAuthorized,
                colors = if (isStreaming) {
                    ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Text(if (isStreaming) "停止推流" else "开始推流")
            }
        }
    }
}

// 预览

@Preview(showBackground = true)
@Composable
fun VideoWindowPreview() {
    VideoWindow()
}