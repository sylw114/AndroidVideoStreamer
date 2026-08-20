package org.dpdns.sylw.videostreamer.quic

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * xquic 客户端连接的阻塞式 Kotlin 适配器。
 *
 * xquic 引擎本身只在 JNI 的事件线程中运行；这里提供的流接口用于兼容现有的
 * DataInputStream/DataOutputStream 协议代码。
 */
internal class XquicConnection private constructor(
    private val nativeHandle: Long
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val streams = Collections.synchronizedSet(mutableSetOf<XquicStream>())

    companion object {
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000
        private const val DEFAULT_IDLE_TIMEOUT_MS = 15_000

        @Throws(IOException::class)
        fun connect(
            host: String,
            port: Int,
            applicationProtocol: String,
            connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
            idleTimeoutMs: Int = DEFAULT_IDLE_TIMEOUT_MS
        ): XquicConnection {
            require(host.isNotBlank()) { "QUIC 主机不能为空" }
            require(port in 1..65_535) { "QUIC 端口无效" }
            require(applicationProtocol.isNotBlank()) { "QUIC ALPN 不能为空" }
            require(connectTimeoutMs > 0) { "QUIC 连接超时必须大于 0" }
            require(idleTimeoutMs > 0) { "QUIC 空闲超时必须大于 0" }
            val handle = XquicNative.nativeConnect(
                host,
                port,
                applicationProtocol,
                connectTimeoutMs,
                idleTimeoutMs
            )
            if (handle == 0L) throw IOException("xquic 未返回有效连接")
            return XquicConnection(handle)
        }
    }

    @Throws(IOException::class)
    fun createStream(bidirectional: Boolean): XquicStream {
        ensureOpen()
        val streamHandle = XquicNative.nativeOpenStream(nativeHandle, bidirectional)
        if (streamHandle == 0L) throw IOException("xquic 未返回有效流")
        return XquicStream(this, streamHandle, bidirectional).also(streams::add)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val snapshot = synchronized(streams) { streams.toList() }
        snapshot.forEach(XquicStream::connectionClosed)
        streams.clear()
        XquicNative.nativeCloseConnection(nativeHandle)
    }

    internal fun ensureOpen() {
        if (closed.get()) throw IOException("QUIC 连接已关闭")
    }

    internal fun forget(stream: XquicStream) {
        streams.remove(stream)
    }
}

internal class XquicStream(
    private val connection: XquicConnection,
    private val nativeHandle: Long,
    bidirectional: Boolean
) {
    private val connectionIsClosed = AtomicBoolean(false)
    val inputStream: InputStream = if (bidirectional) XquicInputStream() else ClosedInputStream
    val outputStream: OutputStream = XquicOutputStream()

    fun connectionClosed() {
        connectionIsClosed.set(true)
    }

    private fun ensureOpen() {
        connection.ensureOpen()
        if (connectionIsClosed.get()) throw IOException("QUIC 流已关闭")
    }

    private inner class XquicInputStream : InputStream() {
        private val closed = AtomicBoolean(false)
        private val oneByte = ByteArray(1)

        override fun read(): Int {
            val count = read(oneByte, 0, 1)
            return if (count < 0) -1 else oneByte[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            checkBounds(buffer, offset, length)
            if (length == 0) return 0
            if (closed.get()) throw IOException("QUIC 输入流已关闭")
            ensureOpen()
            return XquicNative.nativeRead(nativeHandle, buffer, offset, length)
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            if (!connectionIsClosed.get()) XquicNative.nativeCancelRead(nativeHandle)
        }
    }

    private inner class XquicOutputStream : OutputStream() {
        private val closed = AtomicBoolean(false)
        private val oneByte = ByteArray(1)

        override fun write(value: Int) {
            oneByte[0] = value.toByte()
            write(oneByte, 0, 1)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            checkBounds(buffer, offset, length)
            if (length == 0) return
            if (closed.get()) throw IOException("QUIC 输出流已关闭")
            ensureOpen()
            XquicNative.nativeWrite(nativeHandle, buffer, offset, length)
        }

        override fun flush() {
            if (closed.get()) throw IOException("QUIC 输出流已关闭")
            ensureOpen()
            XquicNative.nativeFlush(nativeHandle)
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try {
                if (!connectionIsClosed.get()) XquicNative.nativeFinishStream(nativeHandle)
            } finally {
                connection.forget(this@XquicStream)
            }
        }
    }

    private fun checkBounds(buffer: ByteArray, offset: Int, length: Int) {
        if (offset < 0 || length < 0 || offset > buffer.size - length) {
            throw IndexOutOfBoundsException("offset=$offset, length=$length, size=${buffer.size}")
        }
    }

    private object ClosedInputStream : InputStream() {
        override fun read(): Int = -1
    }
}

internal object XquicNative {
    init {
        System.loadLibrary("xquicbridge")
    }

    external fun nativeConnect(
        host: String,
        port: Int,
        applicationProtocol: String,
        connectTimeoutMs: Int,
        idleTimeoutMs: Int
    ): Long

    external fun nativeOpenStream(connectionHandle: Long, bidirectional: Boolean): Long
    external fun nativeRead(streamHandle: Long, buffer: ByteArray, offset: Int, length: Int): Int
    external fun nativeWrite(streamHandle: Long, buffer: ByteArray, offset: Int, length: Int)
    external fun nativeFlush(streamHandle: Long)
    external fun nativeFinishStream(streamHandle: Long)
    external fun nativeCancelRead(streamHandle: Long)
    external fun nativeCloseConnection(connectionHandle: Long)
}
