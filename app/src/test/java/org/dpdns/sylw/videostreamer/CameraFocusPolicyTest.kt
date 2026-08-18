package org.dpdns.sylw.videostreamer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraFocusPolicyTest {
    @Test
    fun `focus tap is rejected before streaming starts`() {
        assertFalse(
            canHandleCameraFocusTap(
                isStreaming = false,
                isCameraReady = false,
                isPreviewAvailable = false
            )
        )
    }

    @Test
    fun `focus tap is rejected while camera is still opening`() {
        assertFalse(
            canHandleCameraFocusTap(
                isStreaming = true,
                isCameraReady = false,
                isPreviewAvailable = true
            )
        )
    }

    @Test
    fun `focus tap is rejected after transport stops`() {
        assertFalse(
            canHandleCameraFocusTap(
                isStreaming = false,
                isCameraReady = true,
                isPreviewAvailable = true
            )
        )
    }

    @Test
    fun `focus tap is rejected before preview produces a frame`() {
        assertFalse(
            canHandleCameraFocusTap(
                isStreaming = true,
                isCameraReady = true,
                isPreviewAvailable = false
            )
        )
    }

    @Test
    fun `focus tap is accepted only for a running ready camera`() {
        assertTrue(
            canHandleCameraFocusTap(
                isStreaming = true,
                isCameraReady = true,
                isPreviewAvailable = true
            )
        )
    }
}
