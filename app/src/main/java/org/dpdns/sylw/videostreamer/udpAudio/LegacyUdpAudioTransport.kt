package org.dpdns.sylw.videostreamer.udpAudio

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** 兼容原有的 TCP 控制 + UDP 媒体协议。 */
internal class LegacyUdpAudioTransport : LowLatencyAudioTransport {
    companion object {
        private const val TAG = "LegacyUdpAudio"
        private const val LATENCY_INVALID_THRESHOLD_MS = -20L
    }

    override var maximumPayloadSize: Int = 1300
        private set

    private val running = AtomicBoolean(false)
    private val outputLock = Any()
    private val heartbeatSentAt = AtomicLong(0L)
    private val packetSentAt = LongArray(256)

    private var endpoint: AudioEndpoint? = null
    private var callbacks: AudioTransportCallbacks? = null
    private var serverAddress: InetAddress? = null
    private var tcpSocket: Socket? = null
    private var tcpInput: DataInputStream? = null
    private var tcpOutput: DataOutputStream? = null
    private var udpSocket: DatagramSocket? = null
    private var readerThread: Thread? = null
    private var heartbeatThread: Thread? = null

    override fun connect(
        endpoint: AudioEndpoint,
        config: AudioSessionConfig,
        callbacks: AudioTransportCallbacks
    ) {
        check(!running.get()) { "UDP 音频连接已经启动" }
        this.endpoint = endpoint
        this.callbacks = callbacks
        serverAddress = InetAddress.getByName(endpoint.host)

        val controlSocket = Socket(serverAddress, endpoint.controlPort).apply {
            tcpNoDelay = true
            keepAlive = true
        }
        val input = DataInputStream(controlSocket.getInputStream())
        val output = DataOutputStream(controlSocket.getOutputStream())
        val mediaSocket = DatagramSocket()

        tcpSocket = controlSocket
        tcpInput = input
        tcpOutput = output
        udpSocket = mediaSocket
        maximumPayloadSize = detectPayloadLimit()

        try {
            output.write(
                byteArrayOf(
                    0x01,
                    if (config.sampleRate == 48_000) 0x01 else 0x00,
                    config.channelCount.toByte(),
                    config.codec.toByte(),
                    config.frameMs.toByte(),
                    (config.opusBitrate / 1_000).coerceIn(0, 255).toByte()
                )
            )
            output.flush()
            require(input.readUnsignedByte() == 0) { "UDP 音频服务端拒绝了配置" }
        } catch (error: Exception) {
            closeResources()
            throw error
        }

        running.set(true)
        startControlReader()
        startHeartbeat()
    }

    override fun send(sequence: Int, payload: ByteArray, sentAtEpochMs: Long) {
        if (!running.get()) return
        val socket = udpSocket ?: throw IllegalStateException("UDP 音频未连接")
        val address = serverAddress ?: throw IllegalStateException("UDP 音频地址无效")
        val activeEndpoint = endpoint ?: throw IllegalStateException("UDP 音频端点无效")
        val normalizedSequence = sequence and 0xff
        val data = ByteArray(payload.size + 1)
        data[0] = normalizedSequence.toByte()
        payload.copyInto(data, destinationOffset = 1)
        packetSentAt[normalizedSequence] = sentAtEpochMs

        val repeatCount = if (activeEndpoint.redundantTransmission) 3 else 1
        repeat(repeatCount) {
            socket.send(DatagramPacket(data, data.size, address, activeEndpoint.mediaPort))
        }
    }

    override fun close() {
        running.set(false)
        readerThread?.interrupt()
        heartbeatThread?.interrupt()
        closeResources()
        readerThread = null
        heartbeatThread = null
    }

    private fun startControlReader() {
        readerThread = thread(name = "UdpAudio-control", isDaemon = true) {
            val rawBuffer = ByteArray(4096)
            val accumulator = ByteArrayOutputStream()
            try {
                val input = tcpInput ?: return@thread
                while (running.get()) {
                    val count = input.read(rawBuffer)
                    if (count < 0) break
                    accumulator.write(rawBuffer, 0, count)
                    parseLatencyFrames(accumulator)
                }
                if (running.getAndSet(false)) callbacks?.onDisconnected?.invoke()
            } catch (error: Exception) {
                if (running.getAndSet(false)) {
                    callbacks?.onError?.invoke("UDP 控制连接失败：${error.message}")
                    callbacks?.onDisconnected?.invoke()
                }
            } finally {
                closeResources()
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatThread = thread(name = "UdpAudio-heartbeat", isDaemon = true) {
            try {
                Thread.sleep(250)
                while (running.get()) {
                    val timestamp = System.currentTimeMillis()
                    heartbeatSentAt.set(timestamp)
                    val heartbeat = ByteBuffer.allocate(9)
                        .put(0x02)
                        .putLong(timestamp)
                        .array()
                    synchronized(outputLock) {
                        tcpOutput?.write(heartbeat)
                        tcpOutput?.flush()
                    }
                    Thread.sleep(1_000)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                if (running.getAndSet(false)) {
                    callbacks?.onError?.invoke("UDP 心跳失败：${error.message}")
                    callbacks?.onDisconnected?.invoke()
                    closeResources()
                }
            }
        }
    }

    private fun parseLatencyFrames(accumulator: ByteArrayOutputStream) {
        val data = accumulator.toByteArray()
        var position = 0
        while (position + 2 <= data.size) {
            val frameLength = ((data[position].toInt() and 0xff) shl 8) or
                (data[position + 1].toInt() and 0xff)
            if (frameLength < 10) {
                position++
                continue
            }
            if (position + frameLength > data.size) break

            val now = System.currentTimeMillis()
            val serverSentAt = ByteBuffer.wrap(data, position + frameLength - 8, 8).long
            val entryCount = (frameLength - 10) / 9
            var latest: AudioLatencyRange? = null
            repeat(entryCount) { index ->
                val entryOffset = position + 2 + index * 9
                val sequence = data[entryOffset].toInt() and 0xff
                val receivedAt = ByteBuffer.wrap(data, entryOffset + 1, 8).long
                val sentAt = packetSentAt[sequence]
                if (sentAt <= 0L || receivedAt <= 0L) return@repeat
                val clockDelta = sentAt - receivedAt
                val minimum = heartbeatSentAt.get() - serverSentAt - clockDelta
                val maximum = now - serverSentAt - clockDelta
                if (minimum >= LATENCY_INVALID_THRESHOLD_MS) {
                    latest = AudioLatencyRange(
                        sequence = sequence,
                        minimumMs = minimum.coerceAtLeast(0L),
                        maximumMs = maximum.coerceAtLeast(0L)
                    )
                }
            }
            latest?.let { callbacks?.onLatency?.invoke(it) }
            position += frameLength
        }

        accumulator.reset()
        if (position < data.size) accumulator.write(data, position, data.size - position)
    }

    private fun detectPayloadLimit(): Int {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            var mtu = 1_400
            while (interfaces.hasMoreElements()) {
                val network = interfaces.nextElement()
                if (network.isUp && !network.isLoopback && network.mtu > 0) {
                    mtu = network.mtu
                    break
                }
            }
            ((mtu - 101).coerceAtLeast(512) / 8) * 8
        } catch (error: Exception) {
            Log.w(TAG, "无法读取 MTU，使用安全值：${error.message}")
            1_296
        }
    }

    private fun closeResources() {
        try {
            tcpInput?.close()
        } catch (_: Exception) {
        }
        try {
            tcpOutput?.close()
        } catch (_: Exception) {
        }
        try {
            tcpSocket?.close()
        } catch (_: Exception) {
        }
        udpSocket?.close()
        tcpInput = null
        tcpOutput = null
        tcpSocket = null
        udpSocket = null
    }
}
