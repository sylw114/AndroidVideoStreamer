package org.dpdns.sylw.videostreamer.udpAudio

import org.dpdns.sylw.videostreamer.quic.XquicConnection
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** LiveSuite 音频可靠 QUIC Stream 发送端。 */
internal class QuicAudioTransport : LowLatencyAudioTransport {
    companion object {
        private const val ALPN = "livesuite-audio-v1"
        private const val VERSION = 1
        private const val CONTROL_HELLO = 0x01
        private const val CONTROL_SYNC_REQUEST = 0x02
        private const val CONTROL_SYNC_RESULT = 0x03
        private const val CONTROL_STOP = 0x04
        private const val CONTROL_HELLO_ACK = 0x81
        private const val CONTROL_SYNC_RESPONSE = 0x82
        private const val CONTROL_STATS = 0x83
        private const val CONTROL_AUDIO = 0x10
        private const val STREAM_PACKET_OVERHEAD = 20
    }

    override var maximumPayloadSize: Int = 1_024
        private set

    private val running = AtomicBoolean(false)
    private val syncSequence = AtomicInteger(0)
    private val outputLock = Any()
    private val sessionId = (SecureRandom().nextLong() and Long.MAX_VALUE).let { if (it == 0L) 1L else it }

    private var endpoint: AudioEndpoint? = null
    private var callbacks: AudioTransportCallbacks? = null
    private var connection: XquicConnection? = null
    private var controlInput: DataInputStream? = null
    private var controlOutput: DataOutputStream? = null
    private var controlThread: Thread? = null
    private var syncExecutor: ScheduledExecutorService? = null

    override fun connect(
        endpoint: AudioEndpoint,
        config: AudioSessionConfig,
        callbacks: AudioTransportCallbacks
    ) {
        check(!running.get()) { "QUIC 音频连接已经启动" }
        this.endpoint = endpoint
        this.callbacks = callbacks

        val nextConnection = XquicConnection.connect(endpoint.host, endpoint.mediaPort, ALPN)

        val control = nextConnection.createStream(true)
        val input = DataInputStream(control.inputStream)
        val output = DataOutputStream(control.outputStream)
        connection = nextConnection
        controlInput = input
        controlOutput = output

        try {
            writeControl(buildHello(config))
            val ack = readControl(input)
            require(ack.size == 11 && unsigned(ack[0]) == CONTROL_HELLO_ACK) { "QUIC 音频握手响应无效" }
            require(readLong(ack, 1) == sessionId) { "QUIC 音频会话编号不匹配" }
            val serverLimit = readUnsignedShort(ack, 9)
            maximumPayloadSize = (serverLimit - STREAM_PACKET_OVERHEAD).coerceAtLeast(256)
        } catch (error: Exception) {
            closeResources()
            throw error
        }

        running.set(true)
        startControlReader()
        startClockSync()
    }

    override fun send(sequence: Int, payload: ByteArray, sentAtEpochMs: Long) {
        if (!running.get() || payload.isEmpty()) return
        connection ?: throw IllegalStateException("QUIC 音频未连接")
        val packet = ByteBuffer.allocate(STREAM_PACKET_OVERHEAD + payload.size).order(ByteOrder.BIG_ENDIAN)
            .put(CONTROL_AUDIO.toByte())
            .putLong(sessionId)
            .put((sequence and 0xff).toByte())
            .putLong(sentAtEpochMs)
            .putShort(payload.size.toShort())
            .put(payload)
        writeControl(packet.array())
    }

    override fun close() {
        val wasRunning = running.getAndSet(false)
        if (wasRunning) {
            try {
                writeControl(byteArrayOf(CONTROL_STOP.toByte()))
            } catch (_: Exception) {
            }
        }
        syncExecutor?.shutdownNow()
        syncExecutor = null
        controlThread?.interrupt()
        controlThread = null
        closeResources()
    }

    private fun buildHello(config: AudioSessionConfig): ByteArray =
        ByteBuffer.allocate(22).order(ByteOrder.BIG_ENDIAN)
            .put(CONTROL_HELLO.toByte())
            .put(VERSION.toByte())
            .putLong(sessionId)
            .putInt(config.sampleRate)
            .put(config.channelCount.toByte())
            .put(config.codec.toByte())
            .put(config.frameMs.toByte())
            .putInt(config.opusBitrate)
            .put(0)
            .array()

    private fun startControlReader() {
        controlThread = thread(name = "QuicAudio-control", isDaemon = true) {
            try {
                val input = controlInput ?: return@thread
                while (running.get()) handleControl(readControl(input))
                if (running.getAndSet(false)) callbacks?.onDisconnected?.invoke()
            } catch (error: Exception) {
                if (running.getAndSet(false)) {
                    callbacks?.onError?.invoke("QUIC 控制连接失败：${error.message}")
                    callbacks?.onDisconnected?.invoke()
                }
            } finally {
                closeResources()
            }
        }
    }

    private fun startClockSync() {
        syncExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "QuicAudio-clock-sync").apply { isDaemon = true }
        }.also { executor ->
            executor.scheduleWithFixedDelay({
                if (!running.get()) return@scheduleWithFixedDelay
                try {
                    val sequence = syncSequence.incrementAndGet()
                    val timestamp = System.currentTimeMillis()
                    writeControl(
                        ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN)
                            .put(CONTROL_SYNC_REQUEST.toByte())
                            .putInt(sequence)
                            .putLong(timestamp)
                            .array()
                    )
                } catch (error: Exception) {
                    fail("QUIC 时钟同步失败：${error.message}")
                }
            }, 0L, 500L, TimeUnit.MILLISECONDS)
        }
    }

    private fun handleControl(data: ByteArray) {
        if (data.isEmpty()) return
        when (unsigned(data[0])) {
            CONTROL_SYNC_RESPONSE -> handleSyncResponse(data)
            CONTROL_STATS -> handleStats(data)
        }
    }

    private fun handleSyncResponse(data: ByteArray) {
        if (data.size != 29) return
        val sequence = readInt(data, 1)
        val t0 = readLong(data, 5)
        val t1 = readLong(data, 13)
        val t2 = readLong(data, 21)
        val t3 = System.currentTimeMillis()
        val offsetMinimum = minOf(t2 - t3, t1 - t0)
        val offsetMaximum = maxOf(t2 - t3, t1 - t0)
        val roundTrip = ((t3 - t0) - (t2 - t1)).coerceIn(0L, 0xffff_ffffL)
        writeControl(
            ByteBuffer.allocate(25).order(ByteOrder.BIG_ENDIAN)
                .put(CONTROL_SYNC_RESULT.toByte())
                .putInt(sequence)
                .putLong(offsetMinimum)
                .putLong(offsetMaximum)
                .putInt(roundTrip.toInt())
                .array()
        )
    }

    private fun handleStats(data: ByteArray) {
        if (data.size != 26) return
        val sequence = unsigned(data[1])
        val minimum = readInt(data, 2)
        val maximum = readInt(data, 6)
        if (minimum >= 0 && maximum >= minimum) {
            callbacks?.onLatency?.invoke(
                AudioLatencyRange(sequence, minimum.toLong(), maximum.toLong())
            )
        }
    }

    private fun writeControl(data: ByteArray) {
        synchronized(outputLock) {
            val output = controlOutput ?: throw IllegalStateException("QUIC 控制流未连接")
            output.writeInt(data.size)
            output.write(data)
            output.flush()
        }
    }

    private fun readControl(input: DataInputStream): ByteArray {
        val size = input.readInt()
        require(size in 1..65_536) { "QUIC 控制消息长度无效" }
        return ByteArray(size).also { input.readFully(it) }
    }

    private fun fail(message: String) {
        if (!running.getAndSet(false)) return
        callbacks?.onError?.invoke(message)
        callbacks?.onDisconnected?.invoke()
        closeResources()
    }

    private fun closeResources() {
        try {
            controlInput?.close()
        } catch (_: Exception) {
        }
        try {
            controlOutput?.close()
        } catch (_: Exception) {
        }
        try {
            connection?.close()
        } catch (_: Exception) {
        }
        controlInput = null
        controlOutput = null
        connection = null
    }

    private fun unsigned(value: Byte): Int = value.toInt() and 0xff
    private fun readUnsignedShort(data: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(data, offset, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff
    private fun readInt(data: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(data, offset, 4).order(ByteOrder.BIG_ENDIAN).int
    private fun readLong(data: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(data, offset, 8).order(ByteOrder.BIG_ENDIAN).long
}
