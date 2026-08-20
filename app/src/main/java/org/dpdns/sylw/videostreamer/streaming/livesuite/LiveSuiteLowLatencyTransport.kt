package org.dpdns.sylw.videostreamer.streaming.livesuite

import android.os.Handler
import android.os.Looper
import org.dpdns.sylw.videostreamer.encoding.AudioCodecConfig
import org.dpdns.sylw.videostreamer.encoding.EncodedAudioFrame
import org.dpdns.sylw.videostreamer.encoding.EncodedVideoFrame
import org.dpdns.sylw.videostreamer.encoding.VideoBitstreamFormat
import org.dpdns.sylw.videostreamer.encoding.VideoCodecConfig
import org.dpdns.sylw.videostreamer.streaming.StreamDescription
import org.dpdns.sylw.videostreamer.streaming.StreamingLatencyDiagnostics
import org.dpdns.sylw.videostreamer.streaming.StreamingTransport
import org.dpdns.sylw.videostreamer.streaming.TransportCapabilities
import org.dpdns.sylw.videostreamer.quic.XquicConnection
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.URI
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.min

/**
 * LiveSuite 专属低延迟视频发送端。
 *
 * QUIC 模式使用持续的可靠单向视频流，所有视频帧按编码顺序写入并由 QUIC
 * 负责重传；音频使用独立的可靠单向流。UDP 模式保留原有的分片和 XOR 校验片。
 */
class LiveSuiteLowLatencyTransport(
    private val requestedTransport: Transport
) : StreamingTransport {

    enum class Transport { QUIC, UDP }

    companion object {
        private val MAGIC = byteArrayOf('L'.code.toByte(), 'S'.code.toByte(), 'Q'.code.toByte(), '1'.code.toByte())
        private val VIDEO_STREAM_MAGIC = byteArrayOf('L'.code.toByte(), 'S'.code.toByte(), 'V'.code.toByte(), 'S'.code.toByte())
        private val AUDIO_STREAM_MAGIC = byteArrayOf('L'.code.toByte(), 'S'.code.toByte(), 'A'.code.toByte(), '2'.code.toByte())
        private const val ALPN = "livesuite-quic-reliable"
        private const val PROTOCOL_VERSION = 1
        private const val HEADER_SIZE = 60
        private const val DEFAULT_DATAGRAM_SIZE = 1200
        private const val FEC_GROUP_SIZE = 8
        private const val MAX_QUEUE_DEPTH = 32
        private const val MAX_AUDIO_QUEUE_DEPTH = 512

        private const val CONTROL_HELLO = 0x01
        private const val CONTROL_SYNC_REQUEST = 0x02
        private const val CONTROL_SYNC_RESULT = 0x03
        private const val CONTROL_STOP = 0x04
        private const val CONTROL_HELLO_ACK = 0x81
        private const val CONTROL_SYNC_RESPONSE = 0x82
        private const val CONTROL_STATS = 0x83

        private const val PACKET_MEDIA = 0x10
        private const val PACKET_PARITY = 0x11
        private const val PACKET_UDP_HELLO = 0x12
        private const val PACKET_UDP_SYNC_REQUEST = 0x13
        private const val PACKET_UDP_SYNC_RESULT = 0x14
        private const val PACKET_UDP_STOP = 0x15
        private const val PACKET_UDP_HELLO_ACK = 0x92
        private const val PACKET_UDP_SYNC_RESPONSE = 0x93
        private const val PACKET_UDP_STATS = 0x94

        private const val FLAG_KEYFRAME = 0x01
        private const val FLAG_CONFIG = 0x02
        private const val FLAG_RELIABLE_COPY = 0x04
        private const val FLAG_AUDIO = 0x08
    }

    private data class OutboundFrame(
        val id: Int,
        val data: ByteArray,
        val flags: Int,
        val captureEpochMs: Long,
        val encodeEpochMs: Long,
        val ptsUs: Long
    ) {
        val isCritical: Boolean get() = flags and (FLAG_KEYFRAME or FLAG_CONFIG) != 0
    }

    override val capabilities = TransportCapabilities(
        displayName = "LiveSuite ${requestedTransport.name}",
        urlScheme = requestedTransport.name.lowercase(),
        supportsAudio = requestedTransport == Transport.QUIC
    )

    override var onConnectionStateChanged: ((Boolean) -> Unit)? = null
    override var onDiagnostics: ((StreamingLatencyDiagnostics) -> Unit)? = null
    override var onError: ((String) -> Unit)? = null

    private var width = 0
    private var height = 0
    private var bitrate = 0
    private var frameRate = 0
    private var audioEnabled = false
    private var audioSampleRate = 0
    private var audioChannelCount = 0
    private var audioBitrate = 0
    private var audioGroupDurationUs = 0L
    private var currentUrl: String? = null
    private var streamPath = "/live/camera"
    private var remoteHost = ""
    private var remotePort = 0
    private val sessionId = (SecureRandom().nextLong() and Long.MAX_VALUE).let { if (it == 0L) 1L else it }

    private val running = AtomicBoolean(false)
    private val frameSequence = AtomicInteger(0)
    private val audioSequence = AtomicInteger(0)
    private val syncSequence = AtomicInteger(0)
    private val locallyDroppedFrames = AtomicLong(0)
    private val queue = LinkedBlockingDeque<OutboundFrame>(MAX_QUEUE_DEPTH)
    private val audioQueue = LinkedBlockingDeque<OutboundFrame>(MAX_AUDIO_QUEUE_DEPTH)
    private val pendingSync = ConcurrentHashMap<Int, Long>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var quicConnection: XquicConnection? = null
    private var controlInput: DataInputStream? = null
    private var controlOutput: DataOutputStream? = null
    private val controlWriteLock = Any()
    private var udpSocket: DatagramSocket? = null
    private var senderThread: Thread? = null
    private var audioSenderThread: Thread? = null
    private var receiverThread: Thread? = null
    private var syncExecutor: ScheduledExecutorService? = null
    @Volatile private var maxDatagramSize = DEFAULT_DATAGRAM_SIZE

    override fun connect(url: String, description: StreamDescription) {
        check(!running.get()) { "连接已经启动" }
        width = description.width
        height = description.height
        bitrate = description.videoBitrate
        frameRate = description.frameRate
        audioEnabled = description.audioEnabled && requestedTransport == Transport.QUIC
        audioSampleRate = description.audioSampleRate
        audioChannelCount = description.audioChannelCount
        audioBitrate = description.audioBitrate
        audioGroupDurationUs = description.audioGroupDurationUs
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase() ?: throw IllegalArgumentException("推流地址缺少协议")
        val expectedScheme = if (requestedTransport == Transport.QUIC) "quic" else "udp"
        require(scheme == expectedScheme) { "已选择 ${requestedTransport.name}，地址必须以 $expectedScheme:// 开头" }
        remoteHost = uri.host ?: throw IllegalArgumentException("推流地址缺少主机")
        remotePort = if (uri.port > 0) uri.port else throw IllegalArgumentException("推流地址缺少端口")
        streamPath = uri.rawPath?.takeIf { it.isNotBlank() } ?: "/live/camera"
        currentUrl = url

        try {
            if (requestedTransport == Transport.QUIC) connectQuic() else connectUdp()
            running.set(true)
            startWorkers()
            notifyConnection(true)
        } catch (error: Exception) {
            closeTransports()
            currentUrl = null
            throw error
        }
    }

    private fun connectQuic() {
        val connection = XquicConnection.connect(remoteHost, remotePort, ALPN)
        val control = connection.createStream(true)
        val input = DataInputStream(control.inputStream)
        val output = DataOutputStream(control.outputStream)
        quicConnection = connection
        controlInput = input
        controlOutput = output
        writeControl(buildHello(CONTROL_HELLO))
        val ack = readControl(input)
        require(ack.size == 20 && unsigned(ack[0]) == CONTROL_HELLO_ACK) { "QUIC 握手响应无效" }
        require(readLong(ack, 1) == sessionId) { "QUIC 会话编号不匹配" }
    }

    private fun connectUdp() {
        val socket = DatagramSocket()
        socket.connect(InetSocketAddress(remoteHost, remotePort))
        socket.soTimeout = 900
        val hello = withMagic(buildHello(PACKET_UDP_HELLO))
        var acknowledged = false
        for (attempt in 0 until 3) {
            socket.send(DatagramPacket(hello, hello.size))
            try {
                val response = receiveUdp(socket, 256)
                if (response.size == 23 && startsWithMagic(response) &&
                    unsigned(response[4]) == PACKET_UDP_HELLO_ACK && readLong(response, 5) == sessionId
                ) {
                    maxDatagramSize = readUnsignedShort(response, 21).coerceAtLeast(HEADER_SIZE + 256)
                    acknowledged = true
                    break
                }
            } catch (_: SocketTimeoutException) {
                // 重发握手
            }
        }
        if (!acknowledged) {
            socket.close()
            throw SocketTimeoutException("UDP 回退握手超时")
        }
        socket.soTimeout = 1000
        udpSocket = socket
    }

    private fun startWorkers() {
        senderThread = thread(name = "LiveSuite-media-sender", isDaemon = true) {
            try {
                if (requestedTransport == Transport.QUIC) {
                    val connection = quicConnection ?: throw IllegalStateException("QUIC 未连接")
                    val stream = connection.createStream(false)
                    DataOutputStream(stream.outputStream).use { output ->
                        output.write(VIDEO_STREAM_MAGIC)
                        output.flush()
                        while (running.get() || queue.isNotEmpty()) {
                            val frame = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                            sendReliableFrame(output, frame)
                        }
                    }
                } else {
                    while (running.get() || queue.isNotEmpty()) {
                        val frame = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                        sendDatagramFrame(frame)
                    }
                }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                failTransport("媒体发送失败：${error.message}")
            }
        }
        if (requestedTransport == Transport.QUIC && audioEnabled) {
            audioSenderThread = thread(name = "LiveSuite-audio-sender", isDaemon = true) {
                try {
                    val stream = quicConnection?.createStream(false)
                        ?: throw IllegalStateException("QUIC 未连接")
                    DataOutputStream(stream.outputStream).use { output ->
                        output.write(AUDIO_STREAM_MAGIC)
                        output.flush()
                        while (running.get() || audioQueue.isNotEmpty()) {
                            val frame = audioQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                            val packet = buildMediaPacket(
                                packetType = PACKET_MEDIA,
                                frame = frame,
                                fragmentIndex = 0,
                                fragmentCount = 1,
                                groupStart = 0,
                                groupSize = 1,
                                shardSize = frame.data.size.coerceIn(1, 0xffff),
                                payload = frame.data,
                                reliable = true
                            )
                            output.writeInt(packet.size)
                            output.write(packet)
                            output.flush()
                        }
                    }
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (error: Exception) {
                    if (running.get()) failTransport("音频发送失败：${error.message}")
                }
            }
        }
        receiverThread = thread(name = "LiveSuite-control-reader", isDaemon = true) {
            try {
                if (requestedTransport == Transport.QUIC) readQuicControlLoop() else readUdpControlLoop()
            } catch (_: EOFException) {
                if (running.get()) failTransport("服务端关闭了控制连接")
            } catch (error: Exception) {
                if (running.get()) failTransport("控制连接失败：${error.message}")
            }
        }
        syncExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "LiveSuite-clock-sync").apply { isDaemon = true }
        }.also { executor ->
            executor.scheduleWithFixedDelay({
                if (running.get()) {
                    try {
                        sendSyncRequest()
                    } catch (error: Exception) {
                        failTransport("时钟同步失败：${error.message}")
                    }
                }
            }, 0, 2, TimeUnit.SECONDS)
        }
    }

    override fun disconnect() {
        val wasRunning = running.getAndSet(false)
        syncExecutor?.shutdownNow()
        syncExecutor = null
        if (wasRunning) {
            try { senderThread?.join(2_000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            try { audioSenderThread?.join(2_000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        if (wasRunning) {
            try {
                if (requestedTransport == Transport.QUIC) {
                    writeControl(byteArrayOf(CONTROL_STOP.toByte()))
                } else {
                    sendUdpControl(PACKET_UDP_STOP, ByteBuffer.allocate(8).putLong(sessionId).array())
                }
            } catch (_: Exception) {
                // 关闭路径不再上报发送错误
            }
        }
        senderThread?.interrupt()
        audioSenderThread?.interrupt()
        receiverThread?.interrupt()
        senderThread = null
        audioSenderThread = null
        receiverThread = null
        queue.clear()
        audioQueue.clear()
        pendingSync.clear()
        closeTransports()
        currentUrl = null
        if (wasRunning) notifyConnection(false)
    }

    private fun closeTransports() {
        try { controlInput?.close() } catch (_: Exception) {}
        try { controlOutput?.close() } catch (_: Exception) {}
        try { quicConnection?.close() } catch (_: Exception) {}
        try { udpSocket?.close() } catch (_: Exception) {}
        controlInput = null
        controlOutput = null
        quicConnection = null
        udpSocket = null
    }

    override fun sendVideoConfig(config: VideoCodecConfig) {
        require(config.format == VideoBitstreamFormat.AVCC) { "LiveSuite 仅接受 AVCC 视频配置" }
        enqueueFrame(config.data, FLAG_CONFIG, 0L, System.nanoTime(), 0L)
    }

    override fun sendVideoFrame(frame: EncodedVideoFrame) {
        require(frame.format == VideoBitstreamFormat.AVCC) { "LiveSuite 仅接受 AVCC 视频帧" }
        enqueueFrame(
            data = frame.data,
            flags = if (frame.isKeyFrame) FLAG_KEYFRAME else 0,
            captureTimeNs = frame.captureTimeNs,
            encodedTimeNs = frame.encodedTimeNs,
            ptsUs = frame.presentationTimeUs
        )
    }

    override fun sendAudioConfig(config: AudioCodecConfig) {
        enqueueAudio(config.data, FLAG_AUDIO or FLAG_CONFIG, 0L, System.nanoTime(), 0L)
    }

    override fun sendAudioFrame(frame: EncodedAudioFrame) {
        enqueueAudio(
            frame.data,
            FLAG_AUDIO,
            frame.captureTimeNs,
            frame.encodedTimeNs,
            frame.presentationTimeUs
        )
    }

    private fun enqueueAudio(
        data: ByteArray,
        flags: Int,
        captureTimeNs: Long,
        encodedTimeNs: Long,
        ptsUs: Long
    ) {
        if (!running.get() || !audioEnabled || data.isEmpty()) return
        val frame = createOutboundFrame(
            id = audioSequence.getAndIncrement(),
            data = data,
            flags = flags,
            captureTimeNs = captureTimeNs,
            encodedTimeNs = encodedTimeNs,
            ptsUs = ptsUs
        )
        if (!audioQueue.offerLast(frame)) {
            failTransport("音频可靠队列已满，停止推流以避免录像音频缺失")
        }
    }

    private fun enqueueFrame(data: ByteArray, flags: Int, captureTimeNs: Long, encodedTimeNs: Long, ptsUs: Long) {
        if (!running.get() || data.isEmpty()) return
        val frame = createOutboundFrame(
            id = frameSequence.getAndIncrement(),
            data = data,
            flags = flags,
            captureTimeNs = captureTimeNs,
            encodedTimeNs = encodedTimeNs,
            ptsUs = ptsUs
        )
        if (requestedTransport == Transport.QUIC) {
            try {
                // 可靠视频流不能丢弃普通帧；队列满时反压编码器，保持编码顺序和完整性。
                while (running.get()) {
                    if (queue.offerLast(frame, 200, TimeUnit.MILLISECONDS)) return
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return
        }
        if (queue.offerLast(frame)) return

        val removable = queue.firstOrNull { !it.isCritical }
        if (removable != null) {
            queue.remove(removable)
            locallyDroppedFrames.incrementAndGet()
            if (!queue.offerLast(frame)) locallyDroppedFrames.incrementAndGet()
        } else {
            locallyDroppedFrames.incrementAndGet()
            if (frame.isCritical) {
                queue.pollFirst()
                queue.offerLast(frame)
            }
        }
    }

    private fun createOutboundFrame(
        id: Int,
        data: ByteArray,
        flags: Int,
        captureTimeNs: Long,
        encodedTimeNs: Long,
        ptsUs: Long
    ): OutboundFrame {
        val encodeNs = encodedTimeNs.takeIf { it > 0L } ?: System.nanoTime()
        val nowNs = System.nanoTime()
        val nowEpochMs = System.currentTimeMillis()
        val encodeAgeNs = (nowNs - encodeNs).coerceIn(0L, 5_000_000_000L)
        val encodeEpochMs = nowEpochMs - encodeAgeNs / 1_000_000L
        val encodeDurationNs = encodeNs - captureTimeNs
        val captureEpochMs = if (captureTimeNs > 0L && encodeDurationNs in 0L..5_000_000_000L) {
            encodeEpochMs - encodeDurationNs / 1_000_000L
        } else {
            encodeEpochMs
        }
        return OutboundFrame(
            id = id,
            data = data,
            flags = flags,
            captureEpochMs = captureEpochMs,
            encodeEpochMs = encodeEpochMs,
            ptsUs = ptsUs
        )
    }

    private fun sendDatagramFrame(frame: OutboundFrame) {
        val payloadCapacity = (maxDatagramSize - HEADER_SIZE).coerceAtLeast(256)
        val fragmentCount = ((frame.data.size + payloadCapacity - 1) / payloadCapacity).coerceAtLeast(1)
        require(fragmentCount <= 0xffff) { "视频帧过大，无法分片" }
        var groupStart = 0
        while (groupStart < fragmentCount) {
            val groupSize = min(FEC_GROUP_SIZE, fragmentCount - groupStart)
            val parity = ByteArray(payloadCapacity)
            for (offset in 0 until groupSize) {
                val fragmentIndex = groupStart + offset
                val dataStart = fragmentIndex * payloadCapacity
                val dataEnd = min(frame.data.size, dataStart + payloadCapacity)
                val payload = frame.data.copyOfRange(dataStart, dataEnd)
                payload.forEachIndexed { index, value -> parity[index] = (parity[index].toInt() xor value.toInt()).toByte() }
                sendDatagram(buildMediaPacket(
                    packetType = PACKET_MEDIA,
                    frame = frame,
                    fragmentIndex = fragmentIndex,
                    fragmentCount = fragmentCount,
                    groupStart = groupStart,
                    groupSize = groupSize,
                    shardSize = payloadCapacity,
                    payload = payload,
                    reliable = false
                ))
            }
            sendDatagram(buildMediaPacket(
                packetType = PACKET_PARITY,
                frame = frame,
                fragmentIndex = 0xffff,
                fragmentCount = fragmentCount,
                groupStart = groupStart,
                groupSize = groupSize,
                shardSize = payloadCapacity,
                payload = parity,
                reliable = false
            ))
            groupStart += groupSize
        }

        if (frame.isCritical) {
            // 原生 UDP 没有可靠流，关键数据再发一遍，用时间换可恢复性。
            sendUdpCriticalDuplicate(frame, payloadCapacity, fragmentCount)
        }
    }

    private fun sendUdpCriticalDuplicate(frame: OutboundFrame, payloadCapacity: Int, fragmentCount: Int) {
        for (fragmentIndex in 0 until fragmentCount) {
            val dataStart = fragmentIndex * payloadCapacity
            val dataEnd = min(frame.data.size, dataStart + payloadCapacity)
            val groupStart = fragmentIndex / FEC_GROUP_SIZE * FEC_GROUP_SIZE
            sendDatagram(buildMediaPacket(
                packetType = PACKET_MEDIA,
                frame = frame,
                fragmentIndex = fragmentIndex,
                fragmentCount = fragmentCount,
                groupStart = groupStart,
                groupSize = min(FEC_GROUP_SIZE, fragmentCount - groupStart),
                shardSize = payloadCapacity,
                payload = frame.data.copyOfRange(dataStart, dataEnd),
                reliable = false
            ))
        }
    }

    private fun sendReliableFrame(output: DataOutputStream, frame: OutboundFrame) {
        val packet = buildMediaPacket(
            packetType = PACKET_MEDIA,
            frame = frame,
            fragmentIndex = 0,
            fragmentCount = 1,
            groupStart = 0,
            groupSize = 1,
            shardSize = frame.data.size.coerceIn(1, 0xffff),
            payload = frame.data,
            reliable = true
        )
        output.writeInt(packet.size)
        output.write(packet)
        output.flush()
    }

    private fun buildMediaPacket(
        packetType: Int,
        frame: OutboundFrame,
        fragmentIndex: Int,
        fragmentCount: Int,
        groupStart: Int,
        groupSize: Int,
        shardSize: Int,
        payload: ByteArray,
        reliable: Boolean
    ): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(MAGIC)
        buffer.put(packetType.toByte())
        buffer.put((frame.flags or if (reliable) FLAG_RELIABLE_COPY else 0).toByte())
        buffer.putShort(HEADER_SIZE.toShort())
        buffer.putLong(sessionId)
        buffer.putInt(frame.id)
        buffer.putLong(frame.captureEpochMs)
        buffer.putLong(frame.encodeEpochMs)
        buffer.putLong(frame.ptsUs)
        buffer.putInt(frame.data.size)
        buffer.putShort(fragmentIndex.toShort())
        buffer.putShort(fragmentCount.toShort())
        buffer.putShort(groupStart.toShort())
        buffer.put(groupSize.toByte())
        buffer.put(0)
        buffer.putShort(shardSize.coerceIn(1, 0xffff).toShort())
        buffer.putShort(payload.size.coerceAtMost(0xffff).toShort())
        buffer.put(payload)
        return buffer.array()
    }

    private fun sendDatagram(data: ByteArray) {
        val socket = udpSocket ?: throw IllegalStateException("UDP 未连接")
        socket.send(DatagramPacket(data, data.size))
    }

    private fun readQuicControlLoop() {
        val input = controlInput ?: throw IllegalStateException("QUIC 控制流未连接")
        while (running.get()) {
            handleControl(readControl(input), false)
        }
    }

    private fun readUdpControlLoop() {
        val socket = udpSocket ?: throw IllegalStateException("UDP 未连接")
        while (running.get()) {
            try {
                handleControl(receiveUdp(socket, 65_507), true)
            } catch (_: SocketTimeoutException) {
                // 周期性检查 running
            }
        }
    }

    private fun handleControl(data: ByteArray, udp: Boolean) {
        if (udp) {
            if (!startsWithMagic(data) || data.size < 13 || readLong(data, 5) != sessionId) return
            when (unsigned(data[4])) {
                PACKET_UDP_SYNC_RESPONSE -> handleSyncResponse(data, 13, true)
                PACKET_UDP_STATS -> handleStats(data, 13)
            }
        } else if (data.isNotEmpty()) {
            when (unsigned(data[0])) {
                CONTROL_SYNC_RESPONSE -> handleSyncResponse(data, 1, false)
                CONTROL_STATS -> handleStats(data, 1)
            }
        }
    }

    private fun sendSyncRequest() {
        val sequence = syncSequence.incrementAndGet()
        val t0 = System.currentTimeMillis()
        pendingSync[sequence] = t0
        if (requestedTransport == Transport.QUIC) {
            val data = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN)
                .put(CONTROL_SYNC_REQUEST.toByte()).putInt(sequence).putLong(t0).array()
            writeControl(data)
        } else {
            val payload = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
                .putLong(sessionId).putInt(sequence).putLong(t0).array()
            sendUdpControl(PACKET_UDP_SYNC_REQUEST, payload)
        }
        if (pendingSync.size > 8) {
            pendingSync.entries.sortedBy { it.value }.take(pendingSync.size - 8).forEach { pendingSync.remove(it.key) }
        }
    }

    private fun handleSyncResponse(data: ByteArray, offset: Int, udp: Boolean) {
        if (data.size < offset + 28) return
        val sequence = readInt(data, offset)
        val t0 = readLong(data, offset + 4)
        if (pendingSync.remove(sequence) == null) return
        val t1 = readLong(data, offset + 12)
        val t2 = readLong(data, offset + 20)
        val t3 = System.currentTimeMillis()
        val firstBound = t2 - t3
        val secondBound = t1 - t0
        val offsetMin = minOf(firstBound, secondBound)
        val offsetMax = maxOf(firstBound, secondBound)
        val rtt = ((t3 - t0) - (t2 - t1)).coerceIn(0L, 0xffff_ffffL)
        if (udp) {
            val payload = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
                .putLong(sessionId).putInt(sequence).putLong(offsetMin).putLong(offsetMax).putInt(rtt.toInt()).array()
            sendUdpControl(PACKET_UDP_SYNC_RESULT, payload)
        } else {
            val result = ByteBuffer.allocate(25).order(ByteOrder.BIG_ENDIAN)
                .put(CONTROL_SYNC_RESULT.toByte()).putInt(sequence).putLong(offsetMin).putLong(offsetMax)
                .putInt(rtt.toInt()).array()
            writeControl(result)
        }
    }

    private fun handleStats(data: ByteArray, offset: Int) {
        if (data.size < offset + 46) return
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        buffer.position(offset)
        buffer.long // server frame count (currently only displayed on desktop)
        val dropped = buffer.int.toLong() and 0xffff_ffffL
        val latencyMin = buffer.int.takeIf { it >= 0 }?.toDouble()
        val latencyMax = buffer.int.takeIf { it >= 0 }?.toDouble()
        val loss = buffer.float.toDouble().coerceIn(0.0, 1.0)
        val recovered = buffer.int.toLong() and 0xffff_ffffL
        val bitrateKbps = buffer.int
        val fps = (buffer.short.toInt() and 0xffff) / 100.0
        val encodeMin = buffer.int.takeIf { it >= 0 }?.toDouble()
        val encodeMax = buffer.int.takeIf { it >= 0 }?.toDouble()
        val rawRtt = buffer.int.toLong() and 0xffff_ffffL
        val clockRtt = rawRtt.takeIf { it != 0xffff_ffffL }?.toDouble()
        val diagnostics = StreamingLatencyDiagnostics(
            protocol = "LiveSuite",
            transport = requestedTransport.name,
            latencyMinMs = latencyMin,
            latencyMaxMs = latencyMax,
            encodeMinMs = encodeMin,
            encodeMaxMs = encodeMax,
            clockRttMs = clockRtt,
            packetLossRatio = loss,
            recoveredFragments = recovered,
            droppedFrames = dropped + locallyDroppedFrames.get(),
            bitrateKbps = bitrateKbps,
            framesPerSecond = fps
        )
        mainHandler.post { onDiagnostics?.invoke(diagnostics) }
    }

    private fun buildHello(messageType: Int): ByteArray {
        val path = streamPath.toByteArray(Charsets.UTF_8)
        require(path.isNotEmpty() && path.size <= 1024) { "推流路径无效" }
        return ByteBuffer.allocate(13 + path.size + 24).order(ByteOrder.BIG_ENDIAN)
            .put(messageType.toByte())
            .put(PROTOCOL_VERSION.toByte())
            .putLong(sessionId)
            .put((if (requestedTransport == Transport.QUIC) 0 else 1).toByte())
            .putShort(path.size.toShort())
            .put(path)
            .putShort(width.coerceIn(0, 0xffff).toShort())
            .putShort(height.coerceIn(0, 0xffff).toShort())
            .putShort(frameRate.coerceIn(0, 0xffff).toShort())
            .putInt(bitrate.coerceAtLeast(0))
            .put((if (audioEnabled) 1 else 0).toByte())
            .putInt(audioSampleRate.coerceAtLeast(0))
            .put(audioChannelCount.coerceIn(0, 255).toByte())
            .putInt(audioBitrate.coerceAtLeast(0))
            .putInt(audioGroupDurationUs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
            .array()
    }

    private fun writeControl(data: ByteArray) {
        synchronized(controlWriteLock) {
            val output = controlOutput ?: throw IllegalStateException("QUIC 控制流未连接")
            output.writeInt(data.size)
            output.write(data)
            output.flush()
        }
    }

    private fun readControl(input: DataInputStream): ByteArray {
        val size = input.readInt()
        require(size in 1..65_536) { "控制消息长度无效" }
        return ByteArray(size).also { input.readFully(it) }
    }

    private fun sendUdpControl(type: Int, payload: ByteArray) {
        val data = ByteBuffer.allocate(5 + payload.size).put(MAGIC).put(type.toByte()).put(payload).array()
        val socket = udpSocket ?: throw IllegalStateException("UDP 未连接")
        socket.send(DatagramPacket(data, data.size))
    }

    private fun withMagic(data: ByteArray): ByteArray = ByteBuffer.allocate(4 + data.size).put(MAGIC).put(data).array()

    private fun receiveUdp(socket: DatagramSocket, capacity: Int): ByteArray {
        val buffer = ByteArray(capacity)
        val packet = DatagramPacket(buffer, buffer.size)
        socket.receive(packet)
        return buffer.copyOf(packet.length)
    }

    private fun failTransport(message: String) {
        if (!running.getAndSet(false)) return
        mainHandler.post {
            onError?.invoke(message)
            onConnectionStateChanged?.invoke(false)
        }
        syncExecutor?.shutdownNow()
        closeTransports()
    }

    private fun notifyConnection(connected: Boolean) {
        mainHandler.post { onConnectionStateChanged?.invoke(connected) }
    }

    private fun startsWithMagic(data: ByteArray): Boolean =
        data.size >= 4 && data[0] == MAGIC[0] && data[1] == MAGIC[1] && data[2] == MAGIC[2] && data[3] == MAGIC[3]

    private fun unsigned(value: Byte): Int = value.toInt() and 0xff
    private fun readUnsignedShort(data: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(data, offset, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff
    private fun readInt(data: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(data, offset, 4).order(ByteOrder.BIG_ENDIAN).int
    private fun readLong(data: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(data, offset, 8).order(ByteOrder.BIG_ENDIAN).long
}
