package org.dpdns.sylw.videostreamer.tcpAudio

import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.math.max
import kotlin.math.min

/**
 * 低延迟UDP音频流管理器
 * 
 * 协议设计参考文档: UdpAudioProtocol.md
 */
class UdpAudioManager {

    companion object {
        private const val TAG = "UdpAudioManager"
        private const val DEFAULT_SAMPLE_RATE = 48000
        private const val DEFAULT_CHANNELS = 2
    }

    private var serverIp: String = ""
    private var tcpPort: Int = 0
    private var udpPort: Int = 0
    private var isEnabled: Boolean = false

    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private var isCapturing: Boolean = false

    private var udpSocket: DatagramSocket? = null
    private var tcpSocket: java.net.Socket? = null
    private var tcpOutputStream: java.io.OutputStream? = null
    private var serverAddress: InetAddress? = null
    private var isConnected: Boolean = false
    private var mtu: Int = 1400 // Default value

    var onConnectionStateChanged: ((Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onLatencyUpdated: ((Long, Long) -> Unit)? = null // (min, max) latency

    private var sequenceNumber: Int = 1
    private val packetTimestampMap = LongArray(256) // 存储每个序号的发送时间

    fun updateConfig(ip: String, tcpPort: Int, udpPort: Int, enabled: Boolean) {
        this.serverIp = ip
        this.tcpPort = tcpPort
        this.udpPort = udpPort
        this.isEnabled = enabled
        if (!enabled) stop()
    }

    fun start(audioConfig: AudioPlaybackCaptureConfiguration?) {
        // 🔥 如果之前处于连接状态，强制进行一次探测性清理，防止残留状态
        if (isConnected) {
            val socket = tcpSocket
            if (socket == null || socket.isClosed || !socket.isConnected) {
                Log.w(TAG, "Detected stale connection, forcing disconnect.")
                disconnect()
            } else {
                return // 确实还在连着
            }
        }

        if (!isEnabled || isCapturing) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                serverAddress = InetAddress.getByName(serverIp)
                
                // 1. 建立 TCP 控制连接
                tcpSocket = java.net.Socket(serverAddress, tcpPort)
                tcpSocket?.tcpNoDelay = true // 禁用 Nagle 算法，减小延迟
                tcpOutputStream = tcpSocket?.outputStream
                
                // 2. 初始化 UDP 数据 Socket
                udpSocket = DatagramSocket()
                // 获取当前活动网络的 MTU
                try {
                    val networkInterfaces = java.net.NetworkInterface.getNetworkInterfaces()
                    while (networkInterfaces.hasMoreElements()) {
                        val ni = networkInterfaces.nextElement()
                        if (ni.isUp && !ni.isLoopback) {
                            mtu = ni.mtu
                            Log.d(TAG, "Detected MTU: $mtu for interface: ${ni.displayName}")
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get MTU: ${e.message}")
                }
                
                isConnected = true
                
                // 3. 启动控制循环
                startTcpControlLoop()
                
                // 4. 发送握手包
                sendHandshake()
                
                onConnectionStateChanged?.invoke(true)
                startAudioCapture(audioConfig)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start dual-protocol audio: ${e.message}")
                onError?.invoke("启动失败: ${e.message}")
                disconnect()
            }
        }
    }

    private fun sendHandshake() {
        val sampleRateIndex = if (DEFAULT_SAMPLE_RATE == 48000) 0x01.toByte() else 0x00.toByte()
        val channels = if (DEFAULT_CHANNELS == 2) 0x02.toByte() else 0x01.toByte()
        
        // [SampleRateIdx][Channels]
        val handshake = byteArrayOf(sampleRateIndex, channels)
        tcpOutputStream?.write(handshake)
        tcpOutputStream?.flush()
    }

    fun stop() {
        isCapturing = false
        captureThread?.interrupt()
        captureThread = null
        releaseAudioRecord()
        disconnect()
    }

    fun release() {
        stop()
    }

    private fun disconnect() {
        try {
            tcpOutputStream?.close()
            tcpSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing TCP: ${e.message}")
        }
        tcpSocket = null
        udpSocket?.close()
        udpSocket = null
        isConnected = false
        onConnectionStateChanged?.invoke(false)
    }

    @Suppress("MissingPermission")
    private fun startAudioCapture(audioConfig: AudioPlaybackCaptureConfiguration?) {
        if (audioConfig == null) return

        val sampleRate = DEFAULT_SAMPLE_RATE
        val channelConfig = if (DEFAULT_CHANNELS == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = minBufferSize

        audioRecord = AudioRecord.Builder()
            .setAudioFormat(AudioFormat.Builder().setEncoding(audioFormat).setSampleRate(sampleRate).setChannelMask(channelConfig).build())
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(audioConfig)
            .build()

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            releaseAudioRecord()
            return
        }

        audioRecord?.startRecording()
        isCapturing = true
        sequenceNumber = 1 
        captureThread = Thread({ audioCaptureLoop(bufferSize) }, "UdpAudioCaptureThread")
        captureThread?.start()
    }

    private fun startTcpControlLoop() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val inputStream = tcpSocket?.getInputStream() ?: return@launch
                val buffer = ByteArray(1024)
                var hbSendTimestamp: Long = 0
                // 启动心跳发送协程
                val heartbeatJob = launch {
                    delay(1010) // 1秒一次心跳
                    while (isConnected) {
                        val timestamp = System.currentTimeMillis()
                        hbSendTimestamp = timestamp
                        //发送无要求
                        val hb = java.nio.ByteBuffer.allocate(9)
                            .put(0x02.toByte())
                            .putLong(timestamp)
                            .array()
                        tcpOutputStream?.write(hb)
                        tcpOutputStream?.flush()
                        delay(1000) // 1秒一次心跳
                    }
                }
                var minAdd: Long = Long.MIN_VALUE
                var maxAdd: Long = Long.MAX_VALUE

                while (isConnected) {
                    val bytesRead = inputStream.read(buffer)
                    // 协议回包应包含: [Seq:1][udpPackTimestamp:8][ServerTimestamp:8]
                    if (bytesRead >= 17) {
                        val now = System.currentTimeMillis()
                        val seq = buffer[0].toInt() and 0xFF
                        val serverRecvTime = java.nio.ByteBuffer.wrap(buffer, 9, 8).long
                        val packetTimestamp = java.nio.ByteBuffer.wrap(buffer, 1, 8).long
                        val clientSentTimeOriginal = packetTimestampMap[seq]
                        val temp = clientSentTimeOriginal - packetTimestamp
//                        minAdd = max(minAdd, serverRecvTime - now)
//                        maxAdd = min(maxAdd, serverRecvTime - hbSendTimestamp)

//                        val minLatency = -maxAdd - temp
//                        val maxLatency = -minAdd - temp
                        val minLatency = hbSendTimestamp - serverRecvTime - temp
                        val maxLatency = now - serverRecvTime - temp
                        Log.d(TAG, "Latency Update - Seq: $seq, Min: ${minLatency}ms, Max: ${maxLatency}ms, hbSendTimestamp: $hbSendTimestamp, now: $now, serverRecvTime: $serverRecvTime, packetTimestamp: $packetTimestamp, clientSentTimeOriginal: $clientSentTimeOriginal, minAdd: $minAdd, maxAdd: $maxAdd")
                        
                        onLatencyUpdated?.invoke(maxOf(0, minLatency), maxOf(0, maxLatency))
                    } else if (bytesRead == -1) {
                        break
                    }
                }
                heartbeatJob.cancel()
            } catch (e: Exception) {
                Log.e(TAG, "TCP Control Loop Error: ${e.message}")
            }
            if (isConnected) stop()
        }
    }

    private fun audioCaptureLoop(bufferSize: Int) {
        val buffer = ByteArray(bufferSize)
        val fragmentLimit = mtu - 100
        // 对齐到8字节: 计算最大 payload 长度
        val maxPayloadSize = (fragmentLimit - 1) / 8 * 8

        while (isCapturing && !Thread.interrupted() && isConnected) {
            try {
                val bytesRead = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                if (bytesRead > 0) {
                    var offset = 0
                    while (offset < bytesRead) {
                        val remaining = bytesRead - offset
                        val chunkSize = minOf(remaining, maxPayloadSize)
                        
                        // 每个分片携带 Seq 和 数据
                        val packetData = ByteArray(chunkSize + 1)
                        
                        packetTimestampMap[sequenceNumber] = System.currentTimeMillis()
                        packetData[0] = sequenceNumber.toByte()
                        System.arraycopy(buffer, offset, packetData, 1, chunkSize)

                        // 循环递增序号，范围 0x00 - 0xFF
                        sequenceNumber = (sequenceNumber + 1) % 256

                        repeat(3) {
                            val packet = DatagramPacket(packetData, packetData.size, serverAddress, udpPort)
                            udpSocket?.send(packet)
                        }
                        
                        offset += chunkSize
                    }
                }
                Thread.yield()
            } catch (e: Exception) {
                break
            }
        }
    }

    private fun releaseAudioRecord() {
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }
}
