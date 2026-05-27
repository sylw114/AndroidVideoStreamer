package org.dpdns.sylw.videostreamer.tcpAudio

import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.util.Log
import kotlinx.coroutines.*
import java.io.OutputStream
import java.net.Socket

/**
 * 低延迟TCP音频流管理器
 * 
 * 特性：
 * - 独立于视频推流的音频采集和传输
 * - 屏幕旋转时不中断音频流
 * - 使用纯PCM数据传输，零额外开销
 * - 配置信息在握手阶段协商一次
 */
class TcpAudioManager {
    
    companion object {
        private const val TAG = "TcpAudioManager"
        
        // 协议常量
        private const val MSG_TYPE_CONFIG = 0x01.toByte()
        private const val SAMPLE_RATE_44100 = 0x00.toByte()
        private const val SAMPLE_RATE_48000 = 0x01.toByte()
        private const val CHANNELS_MONO = 1
        private const val CHANNELS_STEREO = 2
        
        // 默认配置
        private const val DEFAULT_SAMPLE_RATE = 48000
        private const val DEFAULT_CHANNELS = 2
    }
    
    // 配置参数
    private var serverIp: String = ""
    private var serverPort: Int = 0
    private var isEnabled: Boolean = false
    
    // 音频采集相关
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private var isCapturing: Boolean = false
    
    // TCP连接相关
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var isConnected: Boolean = false
    
    // 回调
    var onConnectionStateChanged: ((Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    
    /**
     * 更新配置
     */
    fun updateConfig(ip: String, port: Int, enabled: Boolean) {
        Log.d(TAG, "Updating config: ip=$ip, port=$port, enabled=$enabled")
        this.serverIp = ip
        this.serverPort = port
        this.isEnabled = enabled
        
        // 如果禁用，停止传输
        if (!enabled) {
            stop()
        }
    }
    
    /**
     * 启动TCP音频流
     */
    fun start(audioConfig: AudioPlaybackCaptureConfiguration?) {
        if (!isEnabled) {
            Log.w(TAG, "TCP audio is disabled")
            return
        }
        
        if (serverIp.isEmpty() || serverPort <= 0) {
            Log.e(TAG, "Invalid server configuration: ip=$serverIp, port=$serverPort")
            onError?.invoke("无效的服务器配置")
            return
        }
        
        if (isConnected || isCapturing) {
            Log.w(TAG, "Already connected or capturing")
            return
        }
        
        Log.d(TAG, "Starting TCP audio stream to $serverIp:$serverPort")
        
        // 在后台线程中执行
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. 建立TCP连接
                connectToServer()
                
                // 2. 发送配置握手包
                sendConfigHandshake()
                
                // 3. 启动音频采集
                startAudioCapture(audioConfig)
                
                Log.d(TAG, "TCP audio stream started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start TCP audio: ${e.message}", e)
                onError?.invoke("启动失败: ${e.message}")
                disconnect()
            }
        }
    }
    
    /**
     * 停止TCP音频流
     */
    fun stop() {
        Log.d(TAG, "Stopping TCP audio stream")
        
        // 🔥 先设置标志，让采集线程自然退出
        isCapturing = false
        
        // 🔥 立即断开TCP连接（会发送FIN包给服务器）
        // 这样服务器能立即知道连接已关闭
        disconnect()
        
        // 中断采集线程
        captureThread?.interrupt()
        // 🔥 优化：只等待200ms，而不是1秒
        captureThread?.join(200)
        captureThread = null
        
        // 释放AudioRecord
        releaseAudioRecord()
        
        Log.d(TAG, "TCP audio stream stopped")
    }
    
    /**
     * 释放资源
     */
    fun release() {
        stop()
        Log.d(TAG, "TcpAudioManager released")
    }
    
    // ==================== 私有方法 ====================
    
    /**
     * 连接到TCP服务器
     */
    @OptIn(ExperimentalStdlibApi::class)
    private fun connectToServer() {
        Log.d(TAG, "Connecting to $serverIp:$serverPort...")
        
        socket = Socket().apply {
            tcpNoDelay = true // 禁用Nagle算法，降低延迟
            soTimeout = 5000  // 5秒超时
        }
        
        socket?.connect(java.net.InetSocketAddress(serverIp, serverPort), 5000)
        outputStream = socket?.outputStream
        
        isConnected = true
        onConnectionStateChanged?.invoke(true)
        
        Log.d(TAG, "TCP connection established")
    }
    
    /**
     * 断开TCP连接
     */
    private fun disconnect() {
        try {
            // 🔥 先flush确保所有缓冲数据立即发送
            outputStream?.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Error flushing output stream: ${e.message}")
        }
        
        try {
            // 🔥 优雅关闭输出流（发送FIN包）
            outputStream?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing output stream: ${e.message}")
        }
        
        try {
            // 🔥 关闭Socket，这会向对端发送断开信号
            socket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing socket: ${e.message}")
        }
        
        outputStream = null
        socket = null
        isConnected = false
        onConnectionStateChanged?.invoke(false)
        
        Log.d(TAG, "TCP connection closed gracefully")
    }
    
    /**
     * 发送配置握手包（5字节）
     * 格式：[Type][SR_IDX][CHANS][RSV][RSV]
     */
    private fun sendConfigHandshake() {
        Log.d(TAG, "Sending config handshake")
        
        val sampleRateIndex = when (DEFAULT_SAMPLE_RATE) {
            44100 -> SAMPLE_RATE_44100
            48000 -> SAMPLE_RATE_48000
            else -> {
                Log.e(TAG, "Unsupported sample rate: $DEFAULT_SAMPLE_RATE")
                0xFF.toByte()
            }
        }
        
        val channels = when (DEFAULT_CHANNELS) {
            1 -> CHANNELS_MONO.toByte()
            2 -> CHANNELS_STEREO.toByte()
            else -> {
                Log.e(TAG, "Unsupported channel count: $DEFAULT_CHANNELS")
                1.toByte()
            }
        }
        
        val configPacket = byteArrayOf(
            MSG_TYPE_CONFIG,      // Type: Config
            sampleRateIndex,      // Sample Rate Index
            channels,             // Channels
            0x00,                 // Reserved
            0x00                  // Reserved
        )
        
        outputStream?.write(configPacket)
        
//        Log.d(TAG, "Config sent: sampleRate=${DEFAULT_SAMPLE_RATE}Hz, channels=$DEFAULT_CHANNELS")
    }
    
    /**
     * 启动音频采集
     */
    @Suppress("MissingPermission")
    private fun startAudioCapture(audioConfig: AudioPlaybackCaptureConfiguration?) {
        if (audioConfig == null) {
//            Log.e(TAG, "AudioPlaybackCaptureConfiguration is null")
            onError?.invoke("音频配置为空")
            return
        }
        
        val sampleRate = DEFAULT_SAMPLE_RATE
        val channelConfig = if (DEFAULT_CHANNELS == 2) {
            AudioFormat.CHANNEL_IN_STEREO
        } else {
            AudioFormat.CHANNEL_IN_MONO
        }
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        
        // 🔥 关键优化：使用最小缓冲区以降低延迟
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBufferSize <= 0) {
            onError?.invoke("无效的缓冲区大小")
            return
        }
        
        // 🔥 使用最小缓冲区（而不是2倍），降低采集延迟
        // 对于48kHz立体声16bit，minBufferSize通常是4096-8192字节
        // 对应约42-85ms的音频数据
        val bufferSize = minBufferSize
        
//        Log.d(TAG, "Audio config: sampleRate=$sampleRate, channels=$DEFAULT_CHANNELS, bufferSize=${bufferSize}B (${bufferSize * 1000L / (sampleRate * DEFAULT_CHANNELS * 2)}ms)")
        
        try {
            // 🔥 关键：创建独立的AudioRecord实例，不依赖MediaProjectionService的共享实例
            audioRecord = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(audioConfig)
                .build()
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                onError?.invoke("音频录制初始化失败")
                releaseAudioRecord()
                return
            }
            
            // 启动录音（需要RECORD_AUDIO权限）
            audioRecord?.startRecording()
            isCapturing = true
            
            // 启动采集线程
            captureThread = Thread({
                audioCaptureLoop(bufferSize)
            }, "TcpAudioCaptureThread")
            captureThread?.start()
            
            Log.d(TAG, "Audio capture started")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio capture: ${e.message}", e)
            onError?.invoke("音频采集启动失败: ${e.message}")
            releaseAudioRecord()
        }
    }
    
    /**
     * 音频采集循环
     */
    private fun audioCaptureLoop(bufferSize: Int) {
        val buffer = ByteArray(bufferSize)
        var totalBytesSent = 0L
        var packetCount = 0L

        
        while (isCapturing && !Thread.interrupted() && isConnected) {
            try {
                val bytesRead = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                
                if (bytesRead > 0) {
                    // 🔥 关键修复：立即复制PCM数据，避免与录屏侧争夺缓冲区
                    val pcmData = buffer.copyOf(bytesRead)

                    // 🔥 直接发送PCM数据到TCP流（无包头）
                    outputStream?.write(pcmData)
                    outputStream?.flush()
                    
                    totalBytesSent += bytesRead
                    packetCount++

                } else if (bytesRead < 0) {
//                    Log.e(TAG, "AudioRecord read error: $bytesRead")
                    break
                } else {
                    // 🔥 优化：bytesRead == 0时不休眠，立即重试以降低延迟
                    // Thread.yield() 让出CPU但不休眠
                    Thread.yield()
                }

            } catch (e: InterruptedException) {
                Log.d(TAG, "Capture thread interrupted")
                break
            } catch (e: java.net.SocketException) {
                // 🔥 TCP连接断开，优雅退出
                Log.w(TAG, "TCP connection closed: ${e.message}")
                isConnected = false
                break
            } catch (e: Exception) {
                Log.e(TAG, "Error in capture loop: ${e.message}", e)
                onError?.invoke("采集错误: ${e.message}")
                break
            }
        }
        
    }
    
    /**
     * 释放AudioRecord
     */
    private fun releaseAudioRecord() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioRecord: ${e.message}")
        }
        audioRecord = null
    }
}
