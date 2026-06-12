package org.dpdns.sylw.videostreamer.rtmpStreamer

import java.io.FilterInputStream
import java.io.InputStream
import java.net.SocketException

/**
 * 包装 InputStream，在 read 操作返回 -1（EOF）时置位 serverEof 标志。
 *
 * 使用场景：RTMP 推流过程中，发送线程只写不读，无法感知服务端已发送 TCP FIN。
 * 通过本类包装 inputStream，可在任何线程调用 read 时检测到 FIN（返回 -1），
 * 发送线程通过轮询 serverEof 标志即可及时退出，避免在已断连的 socket 上持续写入。
 *
 * 同时捕获 SocketException（如 "Socket closed"、"Connection reset"），
 * 这类异常也表明连接已断开，同样置位 serverEof。
 */
class EofDetectingInputStream(
    source: InputStream
) : FilterInputStream(source) {

    @Volatile
    var serverEof: Boolean = false
        private set

    override fun read(): Int {
        val r = try {
            super.read()
        } catch (e: Exception) {
            -1
        }
        if (r == -1) serverEof = true
        return r
    }

    override fun read(b: ByteArray): Int {
        val r = try {
            super.read(b)
        } catch (e: Exception) {
            -1
        }
        if (r == -1) serverEof = true
        return r
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        try {
            val r = try {
                super.read(b, off, len)
            } catch (e: Exception) {
                -1
            }
            if (r == -1) serverEof = true
            return r
        } catch (e: SocketException) {
            serverEof = true
            throw e
        }
    }

    fun markEof() {
        serverEof = true
    }
}
