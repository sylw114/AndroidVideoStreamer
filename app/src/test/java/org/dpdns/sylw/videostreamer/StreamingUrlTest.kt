package org.dpdns.sylw.videostreamer

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingUrlTest {
    @Test
    fun `protocol switch updates only the URL scheme`() {
        assertEquals(
            "udp://192.168.137.1:1935/live/camera?token=local",
            switchStreamingUrlProtocol(
                "quic://192.168.137.1:1935/live/camera?token=local",
                "UDP"
            )
        )
    }

    @Test
    fun `protocol switch normalizes full width URI punctuation`() {
        assertEquals(
            "rtmp://192.168.137.1:1935/live/camera",
            switchStreamingUrlProtocol(
                "QUIC：／／192.168.137.1：1935／live／camera",
                "RTMP"
            )
        )
    }

    @Test
    fun `protocol switch leaves incomplete address untouched`() {
        assertEquals("192.168.137.1", switchStreamingUrlProtocol("192.168.137.1", "UDP"))
    }
}
