// G:/VideoStreamer/app/src/main/java/org/dpdns/sylw/videostreamer/VideoWindow.kt

package org.dpdns.sylw.videostreamer

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dpdns.sylw.videostreamer.streaming.StreamManager
import org.dpdns.sylw.videostreamer.udpAudio.UdpAudioManager
import org.dpdns.sylw.videostreamer.ui.components.SafeButton
import org.dpdns.sylw.videostreamer.ui.components.SafeButtonState


// 视频窗口
@Composable
fun VideoWindow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val noLatencyRecordsText = stringResource(R.string.latency_no_records)
    val latencyLogHeaderText = stringResource(R.string.latency_log_header)

    // 推流管理器
    var streamManager by remember { mutableStateOf<StreamManager?>(null) }
    var isStreaming by remember { mutableStateOf(false) }
    var isPending by remember { mutableStateOf(false) }
    
    // 🔥 TCP音频管理器 (已移除)
    // var tcpAudioManager by remember { mutableStateOf<TcpAudioManager?>(null) }
    var udpAudioManager by remember { mutableStateOf<UdpAudioManager?>(null) }
    var isUdpAudioStreaming by remember { mutableStateOf(false) }
    var isUdpAudioPending by remember { mutableStateOf(false) }
    var currentLatency by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    
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

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val logFile = udpAudioManager?.getLatencyLogFile()
                if (logFile != null && logFile.exists()) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        logFile.inputStream().use { it.copyTo(out) }
                    }
                } else {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(noLatencyRecordsText.toByteArray())
                    }
                }
            } catch (_: Exception) {}
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
//            android.util.Log.w("VideoWindow", "Screen capture authorization failed or canceled")
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
    DisposableEffect(activity) {
        if (activity == null) {
            onDispose {}
        } else {
            // 关键修复：设置全局 streaming 引用，让 SettingWindow 可以访问
            streamManager = StreamManager(activity, { surface ->
                mediaProjectionService?.let { binder ->
                    try {
                        val screenSize = binder.getScreenRealSize()
                        binder.updateVirtualDisplaySurface(surface, screenSize.x, screenSize.y)
                    } catch (e: Exception) {
                    }
                }
            }).apply {
                onStreamingStateChanged = { streaming ->
                    isStreaming = streaming
                    isPending = false  // 连接完成或断开，清除 pending
                }

                onError = { error ->
//                android.util.Log.e("VideoWindow", "Stream error: $error")
                    // 🔥 确保在主线程显示 Toast，避免后台线程崩溃
                    isPending = false
                    kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                        Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                    }
                }

                onInfo = { message ->
                    kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                }
            }

            // 异步加载保存的配置并初始化协议

            val savedUrl = StreamConfig.getCurrentUrl()!!
            currentRtmpUrl = savedUrl  // 更新本地状态
//            android.util.Log.d("VideoWindow", "Loaded saved RTMP URL: $savedUrl")

            val savedBitrate = StreamConfig.getVideoBitrate()!!
//            android.util.Log.d("VideoWindow", "Loaded saved bitrate: $savedBitrate")

            val savedFrameRate = StreamConfig.getFrameRate()!!
//            android.util.Log.d("VideoWindow", "Loaded saved frame rate: $savedFrameRate")

            val savedProtocol = StreamConfig.getStreamingProtocol()!!
            val savedMode = StreamConfig.getRateMode() ?: "CBR"
            val savedQuality = StreamConfig.getCqQuality() ?: 70

            // 初始化协议（使用保存的协议）
            streamManager?.init(savedProtocol)

            // 设置帧率到 StreamManager
            streamManager?.setVideoParams(
                width = 1920,  // 默认宽度，后续会根据屏幕方向调整
                height = 1080, // 默认高度
                bitrate = savedBitrate,
                frameRate = savedFrameRate,
                videoMode = savedMode,
                videoQuality = savedQuality
            )
        
            // 🔥 初始化TCP音频管理器 (改为使用新的双重协议 UDPAudioManager，TCPManager 已移除)
            // tcpAudioManager = TcpAudioManager().apply { ... }
        
            // 🔥 初始化UDP音频管理器
            udpAudioManager = UdpAudioManager().apply {
                onConnectionStateChanged = { connected ->
                    isUdpAudioStreaming = connected
                    isUdpAudioPending = false  // 连接完成或断开，清除 pending
                    if (!connected) currentLatency = null
                }
                onLatencyUpdated = { min, max ->
                    currentLatency = Pair(min, max)
                }
                onError = { error ->
                    isUdpAudioPending = false
                    // 🔥 确保在主线程显示 Toast，避免后台线程崩溃
                    kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                        Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                    }
                }
            }
        
            onDispose {
                streamManager?.release()
                streamManager = null
            
                mediaProjectionService?.stop()

                // tcpAudioManager?.release()
                // tcpAudioManager = null
                udpAudioManager?.release()
                udpAudioManager = null
            }
        }
    }

    // 当绑定到 Service 时，设置外部音频源和 MediaProjection
    LaunchedEffect(mediaProjectionService) {
        mediaProjectionService?.let { binder ->
            // 1. 将 StreamManager 的旋转回调注册到 MediaProjectionService
            binder.onScreenRotation = { newWidth, newHeight ->
//                android.util.Log.d("MediaProj", "Screen rotation detected: ${newWidth}x${newHeight}")
                // 直接调用 StreamManager 的 updateResolution
                streamManager?.updateResolution(newWidth, newHeight)
            }

            binder.onMediaProjectionStopped = {
//                android.util.Log.w("VideoWindow", "⚠️ MediaProjection stopped by system!")
                streamManager?.stopStreaming()
                udpAudioManager?.stop()
            }
//            android.util.Log.d("VideoWindow", "Screen rotation callback registered in Service")

            // 3. 设置外部 PCM 音频源（从 MediaProjectionService 获取）
            // 🔥 性能优化：使用 getAudioDataInto() + 预分配缓冲区，减少 GC
            // 🔥 关键修复：返回 Pair<ByteArray, Long>，包含 PCM 数据和采集时间戳
            // 🔥 关键修复：使用动态的 audioPacketSize，避免硬编码
            val audioPacketSize = binder.getAudioPacketSize().takeIf { it > 0 } ?: 15360
            val pcmReadBuffer = ByteArray(audioPacketSize) // 预分配缓冲区
            val timestampArray = LongArray(1) // 🔥 用于接收时间戳
            streamManager?.setExternalAudioSource {
                val readSize = binder.getAudioDataInto(pcmReadBuffer, timestampArray)
                if (readSize > 0) {
                    val timestampNs = timestampArray[0]
                    Pair(pcmReadBuffer.copyOf(readSize), timestampNs)
                } else {
                    null
                }
            }
//            android.util.Log.d("AudioCapture", "External audio source set from MediaProjectionService (with timestamp)")

            // 5. 注入音频采集回调（协议层自动管理音频生命周期）
            streamManager?.setAudioCaptureCallbacks(
                onStart = {
                    binder.setAudioCaptureMode(isVideoPush = true)
                    binder.toggleStreaming(true)
                },
                onStop = {
                    binder.stopAudioCapture()
                }
            )

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
                                    iFrameInterval = 5,
                                    videoMode = StreamConfig.getRateMode() ?: "CBR",
                                    videoQuality = StreamConfig.getCqQuality() ?: 70
                                )

//                                android.util.Log.d("VideoWindow", "准备调用 toggleStreaming(true)")
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
                Text(
                    if (isAuthorized) {
                        stringResource(R.string.video_screen_service_running)
                    } else {
                        stringResource(R.string.video_authorize_and_start_screen)
                    }
                )
            }
            SafeButton(
                isPending = isPending,
                isActive = isStreaming,
                enabled = isAuthorized,
                onClick = {
                    // 使用新的推流管理器进行推流
                    streamManager?.let { manager ->
                        if (manager.isStreaming()) {
                            // 停止推流 - 在后台线程执行
                            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                manager.stopStreaming()
                            }
                        } else if (!isPending) {
                            // 开始推流
                            val rtmpUrl = currentRtmpUrl
                            if (rtmpUrl.isNullOrEmpty()) {
                                Toast.makeText(context, context.getString(R.string.error_rtmp_url_required), Toast.LENGTH_LONG).show()
                                return@let
                            }
                            // 验证 URL 格式
                            if (!rtmpUrl.startsWith("rtmp://")) {
                                Toast.makeText(context, context.getString(R.string.error_invalid_rtmp_url, rtmpUrl), Toast.LENGTH_LONG).show()
                                return@let
                            }

                            // 获取实际屏幕分辨率（物理尺寸）
                            mediaProjectionService?.let { binder ->
                                val screenSize = binder.getScreenRealSize()
                                val actualWidth = screenSize.x
                                val actualHeight = screenSize.y

                                isPending = true  // 进入连接中状态

                                // 在后台线程启动推流，避免 NetworkOnMainThreadException
                                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    try {
                                        // 加载保存的码率和帧率
                                        val savedBitrate = loadBitrate(context)
                                        val savedFrameRate = loadFrameRate(context)

                                        // 设置视频参数（使用实际分辨率和保存的帧率）
                                        manager.setVideoParams(
                                            width = actualWidth,
                                            height = actualHeight,
                                            bitrate = savedBitrate,
                                            frameRate = savedFrameRate,
                                            iFrameInterval = 5,
                                            videoMode = StreamConfig.getRateMode() ?: "CBR",
                                            videoQuality = StreamConfig.getCqQuality() ?: 70
                                        )

                                        manager.startStreaming(rtmpUrl)
                                    } catch (e: Exception) {
                                        // 在主线程显示错误
                                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            manager.onError?.invoke(context.getString(R.string.error_start_stream_failed, e.message ?: ""))
                                        }
                                    }
                                }
                            } ?: run {
                                isPending = false
                            }
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                activeContainerColor = Color(0xFFF44336),
            ) { state ->
                Text(
                    when (state) {
                        SafeButtonState.IDLE -> stringResource(R.string.video_start_streaming)
                        SafeButtonState.PENDING -> stringResource(R.string.video_connecting)
                        SafeButtonState.ACTIVE -> stringResource(R.string.video_stop_streaming)
                    }
                )
            }
        }
        
        // 🔥 音频控制按钮
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SafeButton(
                isPending = isUdpAudioPending,
                isActive = isUdpAudioStreaming,
                enabled = isAuthorized,
                onClick = {
                    if (isUdpAudioStreaming) {
                        udpAudioManager?.stop()
                    } else if (!isUdpAudioPending) {
                        scope.launch {
                            val ip = loadUdpAudioIp(context)
                            // 🔥 前置校验 IP 有效性
                            if (ip.isBlank()) {
                                Toast.makeText(context, context.getString(R.string.error_udp_audio_ip_invalid), Toast.LENGTH_LONG).show()
                                return@launch
                            }
                            // 简单的 IP 格式检查（避免无效 IP 导致 InetAddress.getByName 异常闪退）
                            val ipPattern = Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")
                            if (!ipPattern.matches(ip.trim())) {
                                Toast.makeText(context, context.getString(R.string.error_udp_audio_ip_invalid), Toast.LENGTH_LONG).show()
                                return@launch
                            }
                            val tcpPort = loadTcpControlPort(context)
                            val udpPort = loadUdpAudioUdpPort(context)
                            mediaProjectionService?.let { binder ->
                                val config = binder.getService().config
                                binder.setAudioCaptureMode(isVideoPush = false)
                                udpAudioManager?.updateConfig(ip, tcpPort, udpPort, true)

                                isUdpAudioPending = true  // 进入连接中状态

                                val recordEnabled = StreamConfig.getLatencyRecordingEnabled() ?: false
                                val logFile = if (recordEnabled) java.io.File(context.filesDir, "latency_log.txt") else null
                                udpAudioManager?.start(context, config, logFile, recordEnabled, latencyLogHeaderText)
                            }
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                activeContainerColor = Color(0xFF2196F3),
            ) { state ->
                val latText = currentLatency?.let { "${it.first}-${it.second}ms" } ?: "--"
                Text(
                    when (state) {
                        SafeButtonState.IDLE -> stringResource(R.string.video_start_audio)
                        SafeButtonState.PENDING -> stringResource(R.string.video_connecting)
                        SafeButtonState.ACTIVE -> stringResource(R.string.video_stop_audio_with_latency, latText)
                    }
                )
            }
            Button(
                onClick = {
                    exportLauncher.launch("latency_${System.currentTimeMillis()}.txt")
                },
                enabled = (StreamConfig.getLatencyRecordingEnabled() == true) &&
                        (isUdpAudioStreaming || (udpAudioManager?.getLatencyLogFile()?.exists() == true))
            ) {
                Text(stringResource(R.string.video_export_latency))
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
