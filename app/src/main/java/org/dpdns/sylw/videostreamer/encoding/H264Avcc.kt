package org.dpdns.sylw.videostreamer.encoding

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** H.264 Annex-B 与 AVCC 字节布局转换，不包含任何编码器或网络状态。 */
internal object H264Avcc {
    fun stripStartCode(nal: ByteArray): ByteArray = when {
        nal.size >= 4 && nal[0] == ZERO && nal[1] == ZERO && nal[2] == ZERO && nal[3] == ONE ->
            nal.copyOfRange(4, nal.size)
        nal.size >= 3 && nal[0] == ZERO && nal[1] == ZERO && nal[2] == ONE ->
            nal.copyOfRange(3, nal.size)
        else -> nal
    }

    fun isAnnexB(data: ByteArray): Boolean =
        data.size >= 3 && data[0] == ZERO && data[1] == ZERO &&
            (data[2] == ONE || (data.size >= 4 && data[2] == ZERO && data[3] == ONE))

    /** 以码流内容判断访问单元是否包含可随机访问的 IDR NAL。 */
    fun containsIdr(data: ByteArray): Boolean {
        var offset = 0
        while (offset + 4 <= data.size) {
            val nalSize = ((data[offset].toInt() and 0xff) shl 24) or
                ((data[offset + 1].toInt() and 0xff) shl 16) or
                ((data[offset + 2].toInt() and 0xff) shl 8) or
                (data[offset + 3].toInt() and 0xff)
            offset += 4
            if (nalSize <= 0 || nalSize > data.size - offset) return false
            if (data[offset].toInt() and 0x1f == 5) return true
            offset += nalSize
        }
        return false
    }

    fun annexBToAvcc(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(data.size)
        var offset = findStartCode(data, 0)
        while (offset >= 0) {
            val startCodeSize = if (data[offset + 2] == ONE) 3 else 4
            val nalStart = offset + startCodeSize
            val next = findStartCode(data, nalStart)
            val nalEnd = if (next >= 0) next else data.size
            val nalSize = nalEnd - nalStart
            if (nalSize > 0) {
                output.write((nalSize ushr 24) and 0xff)
                output.write((nalSize ushr 16) and 0xff)
                output.write((nalSize ushr 8) and 0xff)
                output.write(nalSize and 0xff)
                output.write(data, nalStart, nalSize)
            }
            offset = next
        }
        return output.toByteArray()
    }

    fun mergeParameterSets(spsWithOptionalStartCode: ByteArray, ppsWithOptionalStartCode: ByteArray): ByteArray {
        val sps = stripStartCode(spsWithOptionalStartCode)
        val pps = stripStartCode(ppsWithOptionalStartCode)
        require(sps.size >= 4) { "SPS 数据过短" }
        require(pps.isNotEmpty()) { "PPS 数据为空" }

        return ByteBuffer.allocate(11 + sps.size + pps.size).order(ByteOrder.BIG_ENDIAN).apply {
            put(0x01.toByte())
            put(sps[1])
            put(sps[2])
            put(sps[3])
            put(0xff.toByte())
            put(0xe1.toByte())
            putShort(sps.size.toShort())
            put(sps)
            put(0x01.toByte())
            putShort(pps.size.toShort())
            put(pps)
        }.array()
    }

    private fun findStartCode(data: ByteArray, fromIndex: Int): Int {
        var index = fromIndex.coerceAtLeast(0)
        while (index <= data.size - 3) {
            if (data[index] == ZERO && data[index + 1] == ZERO) {
                if (data[index + 2] == ONE) return index
                if (index <= data.size - 4 && data[index + 2] == ZERO && data[index + 3] == ONE) {
                    return index
                }
            }
            index++
        }
        return -1
    }

    private val ZERO = 0x00.toByte()
    private val ONE = 0x01.toByte()
}
