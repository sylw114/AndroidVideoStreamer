package org.dpdns.sylw.videostreamer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import org.dpdns.sylw.videostreamer.camera.CameraStreamManager
import org.dpdns.sylw.videostreamer.ui.theme.VideoStreamerTheme

/**
 * Camera 推流页面
 * 
 * 🔥 核心特性：
 * 1. 无预览界面（降低性能开销）
 * 2. 强制横屏
 * 3. 启动录制后屏幕常亮
 * 4. 智能省电模式（2分钟后黑屏，点击恢复）
 * 5. 使用抽象层 StreamManager
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraWindow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // Camera 管理器
    var cameraManager by remember { mutableStateOf<CameraStreamManager?>(null) }
    
    // 状态管理
    var isCameraReady by remember { mutableStateOf(false) }
    var isStreaming by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    // 配置选项
    var selectedCameraId by remember { mutableStateOf<String?>(null) }
    var selectedResolution by remember { mutableStateOf("1920x1080") }
    var selectedFrameRate by remember { mutableIntStateOf(30) }
    var videoBitrate by remember { mutableIntStateOf(2500_000) }  // 从全局配置读取
    
    // 下拉菜单展开状态
    var cameraMenuExpanded by remember { mutableStateOf(false) }
    var resolutionMenuExpanded by remember { mutableStateOf(false) }
    var frameRateMenuExpanded by remember { mutableStateOf(false) }
    
    // 省电模式状态
    var isBlackScreenMode by remember { mutableStateOf(false) }
    
    // 摄像头列表
    var availableCameras by remember { mutableStateOf(listOf<CameraStreamManager.CameraInfo>()) }
    
    // 黑屏计时器 Job（用于取消）
    var blackScreenJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    // 权限请求
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // 权限已授予，初始化 Camera 管理器
            cameraManager?.init()
            cameraManager?.let {
                availableCameras = it.getAvailableCameras()
                if (availableCameras.isNotEmpty()) {
                    selectedCameraId = availableCameras.first().cameraId
                }
            }
        } else {
            errorMessage = "需要摄像头权限才能使用此功能"
        }
    }
    
    // 初始化 Camera 管理器
    fun initCameraManager() {
        cameraManager = CameraStreamManager(context).apply {
            init()
            
            // 获取可用摄像头
            availableCameras = getAvailableCameras()
            if (availableCameras.isNotEmpty()) {
                selectedCameraId = availableCameras.first().cameraId
            }
            
            // 🔥 摄像头就绪状态
            onCameraReady = { ready ->
                isCameraReady = ready
            }
            
            // 🔥 推流状态
            onStreamingStateChanged = { streaming ->
                isStreaming = streaming
            }
            
            onError = { error ->
                errorMessage = error
                isCameraReady = false
                isStreaming = false
            }
        }
    }
    
    // 启动/停止摄像头
    fun toggleCamera() {
//        android.util.Log.d("CameraWindow", "toggleCamera called, isCameraReady=$isCameraReady")
        
        if (isCameraReady) {
            // 停止摄像头
//            android.util.Log.d("CameraWindow", "Stopping camera...")
            cameraManager?.closeCamera()
            isCameraReady = false
        } else {
            // 启动摄像头（权限已在 LaunchedEffect 中检查）
            selectedCameraId?.let { cameraId ->
                val (width, height) = selectedResolution.split("x").map { it.toInt() }
//                android.util.Log.d("CameraWindow", "Starting camera: $cameraId, ${width}x${height}, ${selectedFrameRate}fps")
                // 安全检查：确保有权限
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    cameraManager?.openCamera(cameraId, width, height, selectedFrameRate, videoBitrate)
                } else {
                    errorMessage = "摄像头权限未授予"
                }
            }
        }
    }
    
    // 开始/停止推流
    fun toggleStreaming() {
        if (isStreaming) {
            cameraManager?.stopStreaming()
            // 取消屏幕常亮
            (context as? Activity)?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // 取消黑屏计时器
            blackScreenJob?.cancel()
            isBlackScreenMode = false
        } else {
            // 从全局配置加载 RTMP URL
            scope.launch {
                val rtmpUrl = loadUrl(context)
                if (!rtmpUrl.isNullOrEmpty()) {
                    // 设置屏幕常亮
                    (context as? Activity)?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    
                    cameraManager?.startStreaming(rtmpUrl)
                    
                    // 启动黑屏计时器
                    blackScreenJob?.cancel()
                    isBlackScreenMode = false
                    blackScreenJob = scope.launch {
                        kotlinx.coroutines.delay(120_000)
                        if (isStreaming) {
                            isBlackScreenMode = true
                        }
                    }
                } else {
                    errorMessage = "请先在设置页面配置 RTMP 地址"
                }
            }
        }
    }
    
    // 点击屏幕恢复显示
    fun handleScreenClick() {
        if (isBlackScreenMode && isStreaming) {
            isBlackScreenMode = false
            // 重新计时
            blackScreenJob?.cancel()
            blackScreenJob = scope.launch {
                kotlinx.coroutines.delay(120_000)
                if (isStreaming) {
                    isBlackScreenMode = true
                }
            }
        }
    }

    // 初始化
    LaunchedEffect(Unit) {
        // 检查摄像头权限
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            initCameraManager()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
        
        // 加载全局码率配置
        videoBitrate = loadBitrate(context)
    }
    
    // 🔥 当摄像头切换时，更新默认分辨率和帧率
    LaunchedEffect(selectedCameraId) {
        val currentCamera = availableCameras.find { it.cameraId == selectedCameraId }
        if (currentCamera != null) {
            // 设置默认分辨率为第一个（通常是最大的）
            if (currentCamera.supportedSizes.isNotEmpty()) {
                val defaultSize = currentCamera.supportedSizes.first()
                selectedResolution = "${defaultSize.width}x${defaultSize.height}"
            }
            
            // 设置默认帧率为第一个（通常是最高的）
            if (currentCamera.supportedFrameRates.isNotEmpty()) {
                selectedFrameRate = currentCamera.supportedFrameRates.first()
            }
        }
    }
    
    // 页面销毁时释放资源
    DisposableEffect(Unit) {
        onDispose {
            cameraManager?.release()
            // 清除屏幕常亮
            (context as? Activity)?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    
    // UI 布局
    Box(
        modifier = modifier
            .fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 顶部：标题和状态
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Camera Streaming",
                    style = MaterialTheme.typography.headlineMedium,
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // 状态指示
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(
                                if (isStreaming) Color.Green else Color.Gray,
                                shape = androidx.compose.foundation.shape.CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isStreaming) "推流中" else if (isCameraReady) "摄像头就绪" else "未启动",
                    )
                }
                
                // 错误信息
                errorMessage?.let { error ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = error,
                        color = Color.Red,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            
            // 中间：配置信息
            Column(
                horizontalAlignment = Alignment.Start,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "当前配置：",
                    style = MaterialTheme.typography.titleMedium,
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "📷 ${availableCameras.find { it.cameraId == selectedCameraId }?.displayName ?: "未选择"}",
                )
                Text(
                    text = "📐 $selectedResolution @ ${selectedFrameRate}fps",
                )
                Text(
                    text = "🎬 码率: ${videoBitrate / 1000} kbps",
                )
                Text(
                    text = "🔇 无音频",
                )
                
                if (isStreaming) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "⚠️ 请保持此页面开启",
                        color = Color.Yellow,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "💡 2分钟后将自动黑屏省电",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            
            // 底部：控制按钮
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 配置选择（仅在未推流时显示）
                if (!isCameraReady && !isStreaming && availableCameras.isNotEmpty()) {
                    // 摄像头选择
                    ExposedDropdownMenuBox(
                        expanded = cameraMenuExpanded,
                        onExpandedChange = { cameraMenuExpanded = !cameraMenuExpanded }
                    ) {
                        OutlinedTextField(
                            value = availableCameras.find { it.cameraId == selectedCameraId }?.displayName ?: "选择摄像头",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("📷 摄像头") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = cameraMenuExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = cameraMenuExpanded,
                            onDismissRequest = { cameraMenuExpanded = false }
                        ) {
                            availableCameras.forEach { camera ->
                                DropdownMenuItem(
                                    text = { Text(camera.displayName) },
                                    onClick = {
                                        selectedCameraId = camera.cameraId
                                        cameraMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                                    
                    // 分辨率选择
                    val currentCamera = availableCameras.find { it.cameraId == selectedCameraId }
                    val supportedSizes = currentCamera?.supportedSizes ?: emptyList()
                                        
                    if (supportedSizes.isNotEmpty()) {
                        ExposedDropdownMenuBox(
                            expanded = resolutionMenuExpanded,
                            onExpandedChange = { resolutionMenuExpanded = !resolutionMenuExpanded }
                        ) {
                            OutlinedTextField(
                                value = selectedResolution,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("📐 分辨率") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = resolutionMenuExpanded) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor()
                            )
                            ExposedDropdownMenu(
                                expanded = resolutionMenuExpanded,
                                onDismissRequest = { resolutionMenuExpanded = false }
                            ) {
                                // 🔥 显示所有支持的分辨率（按从大到小排序）
                                supportedSizes.forEach { size ->
                                    val sizeStr = "${size.width}x${size.height}"
                                    DropdownMenuItem(
                                        text = { Text(sizeStr) },
                                        onClick = {
                                            selectedResolution = sizeStr
                                            resolutionMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                                    
                    // 帧率选择
                    val supportedFrameRates = currentCamera?.supportedFrameRates ?: listOf(30)
                                    
                    ExposedDropdownMenuBox(
                        expanded = frameRateMenuExpanded,
                        onExpandedChange = { frameRateMenuExpanded = !frameRateMenuExpanded }
                    ) {
                        OutlinedTextField(
                            value = "$selectedFrameRate fps",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("🎬 帧率") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = frameRateMenuExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = frameRateMenuExpanded,
                            onDismissRequest = { frameRateMenuExpanded = false }
                        ) {
                            supportedFrameRates.forEach { fps ->
                                DropdownMenuItem(
                                    text = { Text("$fps fps") },
                                    onClick = {
                                        selectedFrameRate = fps
                                        frameRateMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                
                // 启动/停止摄像头按钮
                Button(
                    onClick = ::toggleCamera,
                    enabled = !isStreaming,
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (isCameraReady) ButtonDefaults.buttonColors(
                        containerColor =  Color.Red
                    ) else ButtonDefaults.buttonColors()
                ) {
                    Text(if (isCameraReady) "停止摄像头" else "启动摄像头")
                }
                
                // 开始/停止推流按钮
                Button(
                    onClick = ::toggleStreaming,
                    enabled = isCameraReady,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isStreaming) Color.Red else Color.Green
                    )
                ) {
                    Text(if (isStreaming) "停止推流" else "开始推流")
                }
            }
        }
        
        // 🔥 黑屏模式：使用 Popup 覆盖整个屏幕（包括状态栏和导航栏）
        if (isBlackScreenMode) {
            Popup(
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false
                ),
                onDismissRequest = {
                    // 点击黑屏区域恢复显示
                    handleScreenClick()
                }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .clickable(onClick = ::handleScreenClick)
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun CameraWindowPreview() {
    VideoStreamerTheme {
        CameraWindow()
    }
}
