package org.dpdns.sylw.videostreamer.encoding

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class H264AvccTest {
    @Test
    fun annexBToAvcc_handlesMixedStartCodes() {
        val annexB = bytes(
            0, 0, 0, 1, 0x65, 0x11, 0x22,
            0, 0, 1, 0x41, 0x33
        )

        assertArrayEquals(
            bytes(
                0, 0, 0, 3, 0x65, 0x11, 0x22,
                0, 0, 0, 2, 0x41, 0x33
            ),
            H264Avcc.annexBToAvcc(annexB)
        )
    }

    @Test
    fun stripStartCode_acceptsThreeAndFourBytePrefixes() {
        assertArrayEquals(bytes(0x67, 0x64), H264Avcc.stripStartCode(bytes(0, 0, 1, 0x67, 0x64)))
        assertArrayEquals(bytes(0x68, 0xee), H264Avcc.stripStartCode(bytes(0, 0, 0, 1, 0x68, 0xee)))
        assertArrayEquals(bytes(0x65, 0x01), H264Avcc.stripStartCode(bytes(0x65, 0x01)))
    }

    @Test
    fun mergeParameterSets_buildsAvcDecoderConfigurationRecord() {
        val sps = bytes(0, 0, 0, 1, 0x67, 0x64, 0, 0x2a, 0x01)
        val pps = bytes(0, 0, 1, 0x68, 0xee, 0x3c)

        val config = H264Avcc.mergeParameterSets(sps, pps)

        assertEquals(1, config[0].toInt())
        assertEquals(0x64, config[1].toInt() and 0xff)
        assertEquals(0x2a, config[3].toInt() and 0xff)
        assertEquals(0xff, config[4].toInt() and 0xff)
        assertEquals(0xe1, config[5].toInt() and 0xff)
        assertTrue(config.size > sps.size + pps.size)
    }

    @Test
    fun containsIdr_checksEveryAvccNalUnit() {
        val sample = bytes(
            0, 0, 0, 2, 0x06, 0x01,
            0, 0, 0, 3, 0x65, 0x11, 0x22
        )

        assertTrue(H264Avcc.containsIdr(sample))
        assertEquals(false, H264Avcc.containsIdr(bytes(0, 0, 0, 2, 0x41, 0x01)))
        assertEquals(false, H264Avcc.containsIdr(bytes(0, 0, 0, 9, 0x65)))
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
