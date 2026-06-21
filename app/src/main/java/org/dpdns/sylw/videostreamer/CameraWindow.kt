package org.dpdns.sylw.videostreamer

import android.Manifest
import android.content.pm.PackageManager
import android.view.WindowManager
import android.widget.Toast
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import org.dpdns.sylw.videostreamer.camera.CameraStreamManager
import org.dpdns.sylw.videostreamer.ui.theme.VideoStreamerTheme
import org.dpdns.sylw.videostreamer.ui.components.SafeButton
import org.dpdns.sylw.videostreamer.ui.components.SafeButtonState

/**
 * Camera 推流页面
 * 
 * 🔥 核心特性：
 * 1. 无预览界面（降低性能开销）
 * 2. 强制横屏
 * 3. 启动录制后屏幕常亮
 * 4. 智能省电模式（30s钟后黑屏，点击恢复）
 * 5. 使用抽象层 StreamManager
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraWindow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val scope = rememberCoroutineScope()
    val cameraPermissionRequiredText = stringResource(R.string.error_camera_permission_required)
    val rtmpUrlRequiredText = stringResource(R.string.error_rtmp_url_required)
    
    // Camera 管理器
    var cameraManager by remember { mutableStateOf<CameraStreamManager?>(null) }

    // 状态管理
    var isStreaming by remember { mutableStateOf(false) }
    var isPending by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    // 配置选项
    var selectedCameraId by remember { mutableStateOf<String?>(null) }
    var selectedResolution by remember { mutableStateOf("1920x1080") }
    var selectedFrameRate by remember { mutableIntStateOf(30) }
    var videoBitrate by remember { mutableIntStateOf(2500_000) }  // 从全局配置读取
    var videoMode by remember { mutableStateOf("CBR") }
    var videoQuality by remember { mutableIntStateOf(70) }
    
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

    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    
    // 权限请求
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            pendingAction?.invoke()
            pendingAction = null
        } else {
            errorMessage = cameraPermissionRequiredText
            // 授权窗口关闭后未授权，Toast 提示
            Toast.makeText(context, cameraPermissionRequiredText, Toast.LENGTH_LONG).show()
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

            // 🔥 推流状态
            onStreamingStateChanged = { streaming ->
                isStreaming = streaming
                isPending = false  // 连接完成或断开，清除 pending
            }

            onError = { error ->
                isPending = false
                errorMessage = error
                // 🔥 确保在主线程显示 Toast，避免后台线程崩溃
                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // 开始/停止推流（同时打开/关闭摄像头）
    fun toggleStreaming() {
        if (isStreaming) {
//            cameraManager?.stopStreaming()
            cameraManager?.closeCamera()

            // 取消屏幕常亮
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else if (!isPending) {
            fun startStreaming() {
                isPending = true  // 进入连接中状态
                scope.launch {
                    val rtmpUrl = loadUrl(context)
                    if (rtmpUrl.isNullOrEmpty()) {
                        isPending = false
                        errorMessage = rtmpUrlRequiredText
                        Toast.makeText(context, rtmpUrlRequiredText, Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    // 验证 URL 格式
                    if (!rtmpUrl.startsWith("rtmp://")) {
                        isPending = false
                        val error = context.getString(R.string.error_invalid_rtmp_url, rtmpUrl)
                        errorMessage = error
                        Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    // 设置屏幕常亮
                    activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    if (selectedCameraId != null) {
                        val (width, height) = selectedResolution.split("x").map { it.toInt() }
                        val currentCameraInfo = availableCameras.find { it.cameraId == selectedCameraId }
                        cameraManager?.startStreaming(
                            rtmpUrl,
                            CameraStreamManager.CameraConfig(
                                cameraId = selectedCameraId!!,
                                width = width,
                                height = height,
                                frameRate = selectedFrameRate,
                                fpsToSizes = currentCameraInfo?.fpsToSizes ?: emptyMap(),
                                fpsToMin = currentCameraInfo?.fpsToMin ?: emptyMap()
                            ),
                            activity!!
                        )
                    } else {
                        isPending = false
                    }

                    // 启动黑屏计时器
                    blackScreenJob?.cancel()
                    isBlackScreenMode = false
                    blackScreenJob = scope.launch {
                        kotlinx.coroutines.delay(30_000)
                        if (isStreaming) {
                            isBlackScreenMode = true
                        }
                    }
                }
            }
            // 检查摄像头权限，未授权则弹出授权窗口并停止后续操作
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                pendingAction = ::startStreaming
                permissionLauncher.launch(Manifest.permission.CAMERA)
            } else startStreaming()
        }
    }
    
    // 点击屏幕恢复显示
    fun handleScreenClick() {
        blackScreenJob?.cancel()
        isBlackScreenMode = false
        // 重新计时
        if(isStreaming){
            blackScreenJob = scope.launch {
                kotlinx.coroutines.delay(30_000)
                if (isStreaming) {
                    isBlackScreenMode = true
                }
            }
        }
    }

    // 初始化
    LaunchedEffect(Unit) {
        // 🔥 仅初始化 Camera 管理器并获取摄像头列表（不需要权限）
        initCameraManager()
        
        // 加载全局配置
        videoBitrate = StreamConfig.getVideoBitrate()!!
        videoMode = StreamConfig.getRateMode()!!
        videoQuality = StreamConfig.getCqQuality()!!
    }
    
    // 当摄像头切换时，更新默认帧率和分辨率
    LaunchedEffect(selectedCameraId) {
        val currentCamera = availableCameras.find { it.cameraId == selectedCameraId }
        if (currentCamera != null) {
            // 默认选最大帧率
            selectedFrameRate = currentCamera.maxFrameRate
            
            // 选该帧率下的第一个分辨率
            val sizesForFps = currentCamera.getSizesForFps(selectedFrameRate)
            if (sizesForFps.isNotEmpty()) {
                val defaultSize = sizesForFps.first()
                selectedResolution = "${defaultSize.width}x${defaultSize.height}"
            }
        }
    }
    
    // 当帧率切换时，自动选择合适的默认分辨率
    LaunchedEffect(selectedFrameRate, selectedCameraId) {
        val currentCamera = availableCameras.find { it.cameraId == selectedCameraId }
        if (currentCamera != null) {
            val sizesForFps = currentCamera.getSizesForFps(selectedFrameRate)
            if (sizesForFps.isNotEmpty()) {
                // 如果当前分辨率不在新帧率支持列表中，切换到第一个
                val currentSizeStr = selectedResolution
                val stillSupported = sizesForFps.any { "${it.width}x${it.height}" == currentSizeStr }
                if (!stillSupported) {
                    val defaultSize = sizesForFps.first()
                    selectedResolution = "${defaultSize.width}x${defaultSize.height}"
                }
            }
        }
    }
    
    // 页面销毁时释放资源
    DisposableEffect(activity) {
        onDispose {
            cameraManager?.release()
            // 清除屏幕常亮
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
            // 中间：配置信息
            Column(
                horizontalAlignment = Alignment.Start,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.camera_current_config),
                    style = MaterialTheme.typography.titleMedium,
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = availableCameras.find { it.cameraId == selectedCameraId }?.displayName(context) ?: stringResource(R.string.common_unselected),
                )
                Text(
                    text = "$selectedResolution @ ${stringResource(R.string.fps_value, selectedFrameRate)}",
                )
                Text(
                    text = if (videoMode == "CBR") {
                        stringResource(R.string.camera_bitrate_value, videoBitrate / 1000)
                    } else {
                        stringResource(R.string.camera_quality_value, videoQuality)
                    },
                )
                Text(
                    text = stringResource(R.string.camera_no_audio),
                )
                
                if (isStreaming) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.camera_keep_page_open),
                        color = Color.Yellow,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = stringResource(R.string.camera_black_screen_hint),
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
                if (!isStreaming && availableCameras.isNotEmpty()) {
                    // 摄像头选择
                    ExposedDropdownMenuBox(
                        expanded = cameraMenuExpanded,
                        onExpandedChange = { cameraMenuExpanded = !cameraMenuExpanded }
                    ) {
                        OutlinedTextField(
                            value = availableCameras.find { it.cameraId == selectedCameraId }?.displayName(context) ?: stringResource(R.string.camera_choose_camera),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.camera_camera)) },
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
                                    text = { Text(camera.displayName(context)) },
                                    onClick = {
                                        selectedCameraId = camera.cameraId
                                        cameraMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                                    
                    // 🔥 帧率选择（放在第二顺位，先选帧率再选分辨率）
                    val currentCamera = availableCameras.find { it.cameraId == selectedCameraId }
                    val allFrameRates = currentCamera?.allFrameRates ?: listOf(30)
                                        
                    if (allFrameRates.isNotEmpty()) {
                        ExposedDropdownMenuBox(
                            expanded = frameRateMenuExpanded,
                            onExpandedChange = { frameRateMenuExpanded = !frameRateMenuExpanded }
                        ) {
                            OutlinedTextField(
                                value = stringResource(R.string.fps_value, selectedFrameRate),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.camera_frame_rate)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = frameRateMenuExpanded) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor()
                            )
                            ExposedDropdownMenu(
                                expanded = frameRateMenuExpanded,
                                onDismissRequest = { frameRateMenuExpanded = false }
                            ) {
                                allFrameRates.forEach { fps ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.fps_value, fps)) },
                                        onClick = {
                                            selectedFrameRate = fps
                                            frameRateMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                                    
                    // 🔥 分辨率选择（放在第三顺位，根据选中的帧率过滤显示）
                    val supportedSizes = currentCamera?.getSizesForFps(selectedFrameRate) ?: emptyList()
                                        
                    if (supportedSizes.isNotEmpty()) {
                        ExposedDropdownMenuBox(
                            expanded = resolutionMenuExpanded,
                            onExpandedChange = { resolutionMenuExpanded = !resolutionMenuExpanded }
                        ) {
                            OutlinedTextField(
                                value = selectedResolution,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.camera_resolution)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = resolutionMenuExpanded) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor()
                            )
                            ExposedDropdownMenu(
                                expanded = resolutionMenuExpanded,
                                onDismissRequest = { resolutionMenuExpanded = false }
                            ) {
                                // 🔥 只显示当前帧率支持的分辨率
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
                }
                
                // 开始/停止推流（点击时一并启动摄像头，或停止时一并关闭摄像头）
                SafeButton(
                    isPending = isPending,
                    isActive = isStreaming,
                    enabled = true,
                    onClick = ::toggleStreaming,
                    modifier = Modifier.fillMaxWidth(),
                    activeContainerColor = Color.Red,
                    inactiveContainerColor = Color.Green,
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
        }
        
        // 🔥 黑屏模式：使用 Popup 覆盖整个屏幕（包括状态栏和导航栏）
        if (isBlackScreenMode && isStreaming) {
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
