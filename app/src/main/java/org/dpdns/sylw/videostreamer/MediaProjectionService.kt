// G:/VideoStreamer/app/src/main/java/org/dpdns/sylw/videostreamer/MediaProjectionService.kt

package org.dpdns.sylw.videostreamer

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.view.Surface
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow


class MediaProjectionService : Service() {

    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "ScreenCaptureChannel"

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    
    // 音频内录相关
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private var isAudioRecording = false

    // 🛑 必须持有这个引用，否则可能被 GC 回收导致画面停止，且需要在 onDestroy 中释放
    private var dummySurfaceTexture: SurfaceTexture? = null
    private var cachedSurface: Surface? = null

    private var cachedWidth: Int = 0
    private var cachedHeight: Int = 0
    private var cachedDpi: Int = 0
    
    // 屏幕旋转监听（使用 DisplayManager.DisplayListener）
    private var displayManager: DisplayManager? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var lastOrientation: Int = Configuration.ORIENTATION_UNDEFINED

    private val binder = LocalBinder()

    var config: AudioPlaybackCaptureConfiguration? = null
    
    // 音频数据回调接口，用于将内录音频传递给编码器
    var audioDataCallback: (() -> ByteArray?)? = null
    // 🔥 环形缓冲区：8 包（约 5 帧 AAC，~270ms），每包 15360 字节
    // 容量设计：理论最小 2 包，4 倍预留应对并发抖动（GC、线程调度等）
    // 平衡点：足够应对常见卡顿，同时保持低延迟和快速响应
    private val audioRingBuffer = LockFreeRingBuffer(capacity = 8, packetSize = 15360)
    // 复用读取缓冲区，减少 GC
    private val readBuffer = ByteArray(15360)
    
    // 屏幕旋转回调，通知 StreamManager 更新编码器
    var onScreenRotation: ((Int, Int) -> Unit)? = null
    
    // 🔥 MediaProjection 停止回调（系统终止权限时触发）
    var onMediaProjectionStopped: (() -> Unit)? = null

    // 全局状态流，供 UI 观察
    companion object {
        val isRunning = MutableStateFlow(false)
        val isStreaming = MutableStateFlow(false)
    }

    inner class LocalBinder : Binder() {
        fun getService(): MediaProjectionService = this@MediaProjectionService

        // 获取 MediaProjection 实例（用于传递给 SurfaceTextureEncoder）
        fun getMediaProjection(): MediaProjection? = this@MediaProjectionService.mediaProjection

        // 获取屏幕实际物理分辨率（考虑了屏幕方向）
        fun getScreenRealSize() = this@MediaProjectionService.getScreenRealSize()
        
        // 设置/获取屏幕旋转回调
        var onScreenRotation: ((Int, Int) -> Unit)? 
            get() = this@MediaProjectionService.onScreenRotation
            set(value) { this@MediaProjectionService.onScreenRotation = value }
        
        // 🔥 设置/获取 MediaProjection 停止回调
        var onMediaProjectionStopped: (() -> Unit)?
            get() = this@MediaProjectionService.onMediaProjectionStopped
            set(value) { this@MediaProjectionService.onMediaProjectionStopped = value }


        // 供 SurfaceTextureEncoder 设置它的 input surface 到 VirtualDisplay
        fun updateVirtualDisplaySurface(encoderSurface: Surface, width: Int, height: Int) {
//            android.util.Log.d("MediaProj", "updateVirtualDisplaySurface called")
            if (cachedWidth > 0 && cachedHeight > 0) {
                try {
                    virtualDisplay?.surface = null
                    virtualDisplay?.resize(width, height, cachedDpi)
                    virtualDisplay?.surface = encoderSurface
//                    android.util.Log.d("MediaProj", "VirtualDisplay surface updated to encoder surface")
                } catch (e: Exception) {
//                    android.util.Log.e("MediaProj", "Failed to update VirtualDisplay surface: ${e.message}")
                    e.printStackTrace()
                }
            } else {
//                android.util.Log.w("MediaProj", "Cannot update VirtualDisplay surface: invalid dimensions")
            }
        }
        
        // 获取当前的 cachedSurface（用于调试）

        fun toggleStreaming(enable: Boolean) {
            if (enable) {
                // 检测屏幕旋转
                val currentOrientation = getCurrentOrientation()
                if (currentOrientation != lastOrientation && currentOrientation != Configuration.ORIENTATION_UNDEFINED) {
//                    android.util.Log.d("MediaProj", "Screen rotation detected in toggleStreaming: $lastOrientation -> $currentOrientation")
                    handleRotationChange(currentOrientation)
                }
                        
                // 不再创建 VirtualDisplay，由 SurfaceTextureEncoder 自己创建
//                android.util.Log.d("MediaProj", "toggleStreaming: config is ${if (config == null) "null" else "valid"}, starting audio capture")
                startAudioCapture()
                isStreaming.value = true
            } else {
                stopAudioCapture()
                isStreaming.value = false
            }
        }

        fun stopAudioCapture() = this@MediaProjectionService.stopAudioCapture()
        
        // 提供给外部获取音频数据的接口 - 使用环形缓冲区
        // 🔥 性能优化：提供直接读取到目标缓冲区的方法，避免 copyOf
        fun getAudioDataInto(targetBuffer: ByteArray): Int {
            return audioRingBuffer.read(targetBuffer)
        }
            
        // 🔥 性能优化：使用对象池减少 ByteArray 分配
        private val audioDataPool = ArrayDeque<ByteArray>(4).apply {
            repeat(4) { addLast(ByteArray(15360)) }
        }
            
        // 保留旧方法以兼容（但标记为 deprecated）
        @Deprecated("Use getAudioDataInto() for better performance", ReplaceWith("getAudioDataInto(targetBuffer)"))
        fun getAudioData(): ByteArray? {
            val readSize = audioRingBuffer.read(readBuffer)
                    
            if (readSize <= 0) {
                Thread.sleep(1)
//                android.util.Log.w("AudioCapture", "🔍 [getAudioData] Ring buffer is EMPTY! Size: ${audioRingBuffer.size()}, capacity: ${audioRingBuffer.getCapacity()}")
                return null
            } else {
                val remaining = audioRingBuffer.size()
                val capacity = audioRingBuffer.getCapacity()
                val usagePercent = ((capacity - remaining) * 100) / capacity
//                android.util.Log.d("AudioCapture", "🔍 [getAudioData] Got data: size=$readSize bytes, remaining=$remaining/$capacity, usage=$usagePercent%")
                    
                // 🔥 从对象池获取缓冲区，避免频繁 GC
                val recycledBuffer = synchronized(audioDataPool) {
                    if (audioDataPool.isNotEmpty()) {
                        audioDataPool.removeFirst()
                    } else {
                        ByteArray(15360) // 池为空时创建新的
                    }
                }
                    
                System.arraycopy(readBuffer, 0, recycledBuffer, 0, readSize)
                return recycledBuffer.copyOf(readSize)
            }
        }
            
        // 🔥 回收音频数据缓冲区到对象池
        fun recycleAudioData(buffer: ByteArray) {
            synchronized(audioDataPool) {
                if (audioDataPool.size < 4 && buffer.size >= 15360) {
                    audioDataPool.addLast(buffer)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        // 初始化 DisplayManager（用于监听屏幕旋转）
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    fun getScreenRealSize(): android.graphics.Point {
        val point = android.graphics.Point()
        // Android 11+ API (minSdk=29, always available)
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        display.getRealSize(point)
//        android.util.Log.d("MediaProj", "Screen real size: ${point.x}x${point.y}")
        return point
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
//        android.util.Log.d("MediaProj", "onStartCommand called, extras: ${intent.extras != null}")

        if (intent.extras == null) {
//            android.util.Log.e("MediaProj", "No extras in intent, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED)
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("DATA")
        }

//        android.util.Log.d("MediaProj", "Result code: $resultCode, Data: ${data != null}")

        if (resultCode == Activity.RESULT_OK && data != null) {
            setupProjection(resultCode, data)
        } else {
//            android.util.Log.e("MediaProj", "Invalid result code or data, stopping")
            stopSelf()
        }

        return START_NOT_STICKY
    }

    @SuppressLint("Recycle", "NewApi")
    private fun createDummySurfaceIfNeeded() {
        if (cachedSurface == null) {
            // 获取屏幕实际物理分辨率
            val screenSize = LocalBinder().getScreenRealSize()
            val width = screenSize.x
            val height = screenSize.y

//            android.util.Log.d("MediaProj", "Screen real size: ${width}x${height}")

            // 创建 SurfaceTexture (ID 0 是随意的，只要唯一就行)
            dummySurfaceTexture = SurfaceTexture(0).apply {
                setDefaultBufferSize(width, height)
            }

            cachedSurface = Surface(dummySurfaceTexture)
            cachedWidth = width
            cachedHeight = height
            cachedDpi = resources.displayMetrics.densityDpi

//            android.util.Log.d("MediaProj", "Created Dummy Surface: ${width}x${height}")
        } else {
//            android.util.Log.d("MediaProj", "Surface already exists, skipping dummy creation")
        }
    }


    private fun releaseDummySurface() {
        dummySurfaceTexture?.release()
        dummySurfaceTexture = null
        cachedSurface?.release()
        cachedSurface = null
//        android.util.Log.d("MediaProj", "Dummy Surface released")
    }

    private fun setupProjection(resultCode: Int, data: Intent) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("录屏服务运行中")
            .setContentText("正在捕获屏幕...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, data) ?: return
        
        // Android 10+ 音频内录配置 (minSdk=29, always available)
        config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
//        android.util.d("AudioCapture", "AudioPlaybackCaptureConfiguration created successfully")
        
        isRunning.value = true
        
//        android.util.Log.d("AudioCapture", "setupProjection completed, config is ${if (config == null) "null" else "valid"}, mediaProjection is ${if (mediaProjection == null) "null" else "valid"}")
        
        // 初始化屏幕方向
        lastOrientation = getCurrentOrientation()
//        android.util.Log.d("MediaProj", "Initial screen orientation: $lastOrientation")

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
//                android.util.Log.d("MediaProj", "Projection stopped by system.")
                isRunning.value = false
                
                // 🔥 通知上层停止推流
                onMediaProjectionStopped?.invoke()
//                android.util.Log.d("MediaProj", "Notified onMediaProjectionStopped callback")
                
                stopSelf()
            }
        }, null)

        // 🚀 创建初始的 Surface 和 VirtualDisplay
        createInitialSurfaceAndVirtualDisplay()
        
        // 启动屏幕旋转监听（使用 DisplayManager.DisplayListener）
        startScreenRotationMonitoring()
    }
    
    private fun getCurrentOrientation(): Int {
        return resources.configuration.orientation
    }
    

    /**
     * 启动屏幕旋转监听（使用 DisplayManager.DisplayListener）
     */
    private fun startScreenRotationMonitoring() {
        displayManager?.let { dm ->
            displayListener = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {
                    // 不处理
                }

                override fun onDisplayRemoved(displayId: Int) {
                    // 不处理
                }

                override fun onDisplayChanged(displayId: Int) {
                    // 只在主显示器发生变化时处理
                    if (displayId == android.view.Display.DEFAULT_DISPLAY) {
                        val display = dm.getDisplay(displayId)
                        val rotation = display.rotation
                        
//                        android.util.Log.d("MediaProj", "Display rotation changed: $rotation")
                        
                        // 获取新的屏幕尺寸并处理旋转
                        handleRotationByDisplayChange()
                    }
                }
            }
            
            // 注册监听器（使用 Handler 确保在主线程执行）
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            dm.registerDisplayListener(displayListener!!, handler)
//            android.util.Log.d("MediaProj", "DisplayListener registered successfully")
        }
    }
    
    /**
     * 创建初始 Surface 和 VirtualDisplay（使用 dummy surface）
     */
    @SuppressLint("MissingPermission")
    private fun createInitialSurfaceAndVirtualDisplay() {
        // 1. 获取屏幕尺寸
        val screenSize = getScreenRealSize()
        val width = screenSize.x
        val height = screenSize.y
        val dpi = resources.displayMetrics.densityDpi
        
//        android.util.Log.d("MediaProj", "Creating initial Surface and VirtualDisplay: ${width}x${height}")
//        android.util.Log.d("MediaProj", "Initial orientation: $lastOrientation")
        
        // 2. 创建 dummy SurfaceTexture 和 Surface
        dummySurfaceTexture = SurfaceTexture(0).apply {
            setDefaultBufferSize(width, height)
        }
        
        cachedSurface = Surface(dummySurfaceTexture)
        cachedWidth = width
        cachedHeight = height
        cachedDpi = dpi
        
//        android.util.Log.d("MediaProj", "Dummy Surface created")
        
        // 3. 创建 VirtualDisplay (使用 dummy surface)
        try {
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCaptureStream",
                width,
                height,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                cachedSurface,
                null,
                null
            )
            
            if (virtualDisplay != null) {
//                android.util.Log.d("MediaProj", "✓ VirtualDisplay created successfully with dummy surface: ${width}x${height}, DPI=$dpi")
            } else {
//                android.util.Log.e("MediaProj", "✗ VirtualDisplay creation failed (returned null)")
            }
        } catch (e: Exception) {
//            android.util.Log.e("MediaProj", "✗ Failed to create VirtualDisplay: ${e.message}", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioCapture() {
//        android.util.Log.d("AudioCapture", "========================================")
//        android.util.Log.d("AudioCapture", "startAudioCapture called")
//        android.util.Log.d("AudioCapture", "- config is ${if (config == null) "null" else "valid"}")
//        android.util.Log.d("AudioCapture", "- isAudioRecording=$isAudioRecording")
//        android.util.Log.d("AudioCapture", "- Android version: ${Build.VERSION.SDK_INT}")
        
        if (config == null) {
//            android.util.Log.e("AudioCapture", "❌ Cannot start audio: AudioPlaybackCaptureConfiguration is null. Did you wait for service to initialize?")
            // 延迟 500ms 后重试
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
//                android.util.Log.d("AudioCapture", "Retrying startAudioCapture after delay...")
                startAudioCapture()
            }, 500)
            return
        }
        
        if (isAudioRecording) {
//            android.util.Log.w("AudioCapture", "⚠️ Audio already recording")
            return
        }

        // Android AudioPlaybackCapture API 推荐的采样率
        val sampleRate = 48000
        val channelConfig = AudioFormat.CHANNEL_IN_STEREO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        
        // 获取最小缓冲区大小，并设置为 2 倍以保证稳定性
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
//            android.util.Log.e("MediaProj", "❌ Invalid buffer size: $minBufferSize")
            return
        }
        
        val bufferSize = minBufferSize * 2
//        android.util.Log.d("AudioCapture", "📊 Audio config: ${sampleRate}Hz, stereo, 16-bit")
//        android.util.Log.d("AudioCapture", "   Min buffer: $minBufferSize, Using buffer: $bufferSize bytes")

        try {
            audioRecord = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(config!!)
                .build()

            // 检查 AudioRecord 是否成功创建
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
//                android.util.Log.e("AudioCapture", "❌ AudioRecord failed to initialize (state=${audioRecord?.state})")
                audioRecord?.release()
                audioRecord = null
                return
            }
        
            audioRecord?.startRecording()
            isAudioRecording = true
//            android.util.Log.d("AudioCapture", "✅ Audio capture started successfully")
//            android.util.Log.d("AudioCapture", "   Sample rate: ${sampleRate}Hz")
//            android.util.Log.d("AudioCapture", "   Channel config: $channelConfig")
//            android.util.Log.d("AudioCapture", "   Audio format: $audioFormat")
//            android.util.Log.d("AudioCapture", "   Buffer size: $bufferSize bytes")
//            android.util.Log.d("AudioCapture", "   AudioRecord state: ${audioRecord?.state}")
//            android.util.Log.d("AudioCapture", "   AudioRecord recording state: ${audioRecord?.recordingState}")
//            android.util.Log.d("AudioCapture", "========================================")
        
            audioThread = Thread({
                val data = ByteArray(bufferSize)
//                android.util.Log.e("AudioCapture", "🎤 [THREAD] AudioCaptureThread STARTED")
                var readCount = 0
                var emptyReadCount = 0
                
                while (isAudioRecording && !Thread.interrupted()) {
                    try {
                        // 🔥 关键修复：检查 recordingState，如果是 STOPPED 立即退出
                        val currentRecordingState = audioRecord?.recordingState ?: AudioRecord.RECORDSTATE_STOPPED
                        if (currentRecordingState == AudioRecord.RECORDSTATE_STOPPED) {
//                            android.util.Log.e("AudioCapture", "❌ [FATAL] AudioRecord.recordingState = STOPPED! Exiting loop.")
                            break
                        }
                        
                        val read = audioRecord?.read(data, 0, bufferSize) ?: 0
                        
                        when {
                            read > 0 -> {
                                readCount++
                                emptyReadCount = 0
                                if (readCount <= 3 || readCount % 100 == 0) {
//                                    android.util.Log.d("AudioCapture", "✅ Read #$readCount: $read bytes")
                                }
                                val success = audioRingBuffer.write(data, read)
                                if (!success && (readCount <= 3 || readCount % 100 == 0)) {
//                                    android.util.Log.w("AudioCapture", "⚠️ Ring buffer FULL")
                                }
                            }
                            read == 0 -> {
                                emptyReadCount++
                                if (emptyReadCount == 1 || emptyReadCount == 100) {
                                    val state = audioRecord?.recordingState ?: -1
//                                    android.util.Log.w("AudioCapture", "⚠️ Read returned 0 (count=$emptyReadCount), recordingState=$state")
                                }
                                Thread.sleep(5)
                            }
                            read < 0 -> {
//                                android.util.Log.e("AudioCapture", "❌ FATAL ERROR: $read")
                                break
                            }
                        }
                    } catch (e: Exception) {
//                        android.util.Log.e("AudioCapture", "❌ EXCEPTION: ${e.message}")
                        break
                    }
                }
                
//                android.util.Log.e("AudioCapture", "🏁 [THREAD] AudioCaptureThread FINISHED - total=$readCount, empty=$emptyReadCount")
            }, "AudioCaptureThread")
            audioThread?.start()
                
        } catch (e: SecurityException) {
//            android.util.Log.e("AudioCapture", "RECORD_AUDIO permission not granted: ${e.message}")
        } catch (e: Exception) {
//            android.util.Log.e("AudioCapture", "Failed to start audio capture: ${e.message}", e)
        }
    }

    private fun stopAudioCapture() {
        isAudioRecording = false
        audioThread?.join(500)
        audioThread = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        
        // 🔥 关键修复：清空音频环形缓冲区，防止旧数据累积导致音画不同步
        audioRingBuffer.clear()
//        android.util.Log.d("AudioCapture", "🔍 Audio ring buffer cleared")
    }
    
    /**
     * 处理屏幕旋转 - 使用 VirtualDisplay.resize() 动态调整大小
     */
    private fun handleRotationChange(newOrientation: Int) {
//        android.util.Log.d("MediaProj", "Handling rotation change to: $newOrientation")
        
        // 1. 获取新的屏幕尺寸（物理分辨率）
        val screenSize = getScreenRealSize()
        val newWidth = screenSize.x
        val newHeight = screenSize.y

//        android.util.Log.d("MediaProj", "New screen size after rotation: ${newWidth}x${newHeight}")
//        android.util.Log.d("MediaProj", "New orientation: $newOrientation, last orientation: $lastOrientation")

        // 2. 检查是否需要调整大小
        if (newWidth == cachedWidth && newHeight == cachedHeight) {
//            android.util.Log.d("MediaProj", "Dimensions unchanged, skipping resize")
            lastOrientation = newOrientation
            return
        }
        
        // 3. 🔥 关键修复：先停止音频采集并清空缓冲区，防止旧数据累积
        stopAudioCapture()
        
        // 4. 更新缓存的尺寸
        cachedWidth = newWidth
        cachedHeight = newHeight
        cachedDpi = resources.displayMetrics.densityDpi
        
        // 5. 更新 SurfaceTexture 的缓冲区大小
        dummySurfaceTexture?.let { st ->
            try {
                st.setDefaultBufferSize(newWidth, newHeight)
//                android.util.Log.d("MediaProj", "SurfaceTexture buffer size updated: ${newWidth}x${newHeight}")
            } catch (e: Exception) {
//                android.util.Log.w("MediaProj", "Failed to update SurfaceTexture buffer: ${e.message}")
            }
        }
        
        // 6. 使用 VirtualDisplay.resize() 动态调整大小 (关键！)
        virtualDisplay?.let { vd ->
            try {
                vd.resize(newWidth, newHeight, cachedDpi)
//                android.util.Log.d("MediaProj", "✓ VirtualDisplay resized successfully: ${newWidth}x${newHeight}, DPI=$cachedDpi")
                updateNotification("正在推流：${cachedWidth}x${cachedHeight}, DPI=$cachedDpi")
            } catch (e: Exception) {
//                android.util.Log.e("MediaProj", "✗ Failed to resize VirtualDisplay: ${e.message}", e)
            }
        } ?: run {
//            android.util.Log.w("MediaProj", "VirtualDisplay is null, cannot resize")
        }
        
        // 7. 通知 StreamManager 更新编码器分辨率
        onScreenRotation?.invoke(newWidth, newHeight)
//        android.util.Log.d("MediaProj", "Notified StreamManager of new resolution: ${newWidth}x${newHeight}")
        
        // 8. 更新方向记录
        lastOrientation = newOrientation
        
//        android.util.Log.d("MediaProj", "Rotation handling completed")
    }
    
    /**
     * 当 DisplayListener 检测到显示变化时调用此方法
     */
    private fun handleRotationByDisplayChange() {
        val currentOrientation = getCurrentOrientation()
        if (currentOrientation != lastOrientation && currentOrientation != Configuration.ORIENTATION_UNDEFINED) {
            handleRotationChange(currentOrientation)
        }
    }

    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("录屏直播中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
//        android.util.Log.d("MediaProj", "Service Destroying...")
        
        // 注销 DisplayListener（屏幕旋转监听）
        try {
            displayManager?.let { dm ->
                displayListener?.let { listener ->
                    dm.unregisterDisplayListener(listener)
//                    android.util.Log.d("MediaProj", "DisplayListener unregistered successfully")
                }
            }
        } catch (e: Exception) {
//            android.util.Log.w("MediaProj", "Failed to unregister DisplayListener: ${e.message}")
        }
        displayListener = null
        displayManager = null
        
        stopAudioCapture()

        // 清理我们自己创建的 Dummy Surface
        releaseDummySurface()

        mediaProjection?.stop()
        mediaProjection = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen Capture",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}

