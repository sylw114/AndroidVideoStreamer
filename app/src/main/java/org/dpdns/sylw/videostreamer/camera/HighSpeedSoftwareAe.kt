package org.dpdns.sylw.videostreamer.camera

import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range

/**
 * 高速模式（≥120fps）软件自动曝光
 *
 * Camera2 constrained high-speed 会话中 AE 被系统强制开启、禁止手动曝光控制，
 * 部分设备在高速下画面亮度不佳。本类通过一路与编码器同尺寸的 ImageReader
 * 采样 Y 通道平均亮度，以 CONTROL_AE_EXPOSURE_COMPENSATION 为杠杆做软件闭环调节。
 *
 * 注意：曝光补偿在 AE ON（高速会话强制）时有效，但能否在高速会话中生效
 * 依赖具体设备，属于尽力而为的增强。
 */
class HighSpeedSoftwareAe(
    private val width: Int,
    private val height: Int,
    private val compensationRange: Range<Int>,
    private val onExposureChanged: (Int) -> Unit
) {
    companion object {
        private const val TAG = "HighSpeedSoftwareAe"
        /** 目标亮度（Y 通道 0-255 中间值） */
        private const val TARGET_LUMINANCE = 118f
        /** 死区：亮度偏差小于该值不调节 */
        private const val DEAD_ZONE = 12f
        /** 单次最大调节步进 */
        private const val MAX_STEP = 1
        /** 两次调节的最小间隔（毫秒），防抖 */
        private const val MIN_INTERVAL_MS = 500L
    }

    /** 暴露给 Camera2 会话的亮度采样 Surface */
    val surface: android.view.Surface
        get() = imageReader.surface

    /** 当前曝光补偿值（用于初始请求与调试） */
    var currentCompensation: Int = 0
        private set

    private val imageReader: ImageReader =
        ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2)

    // setOnImageAvailableListener 的 Executor 重载需要 API 33+，minSdk 29 用 HandlerThread
    private val handlerThread = HandlerThread("HighSpeedSoftwareAe").apply { start() }
    private val handler = Handler(handlerThread.looper)

    private var lastAdjustTime = 0L

    @Volatile
    private var closed = false

    init {
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val luminance = computeLuminance(image)
                adjust(luminance)
            } catch (e: Exception) {
                Log.w(TAG, "亮度采样失败", e)
            } finally {
                image.close()
            }
        }, handler)
    }

    /** 计算 Y 平面平均亮度（0-255） */
    private fun computeLuminance(image: Image): Float {
        val plane = image.planes[0] // Y 平面
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        var sum = 0L
        var count = 0L
        var row = 0
        while (row < image.height) {
            var rowOffset = row * rowStride
            var col = 0
            while (col < image.width) {
                sum += buffer.get(rowOffset + col * pixelStride).toInt() and 0xFF
                count++
                col += 4 // 采样步长，降低计算量
            }
            row += 4
        }
        return if (count == 0L) 0f else sum.toFloat() / count
    }

    /** 死区 + 步进调节曝光补偿 */
    private fun adjust(luminance: Float) {
        if (closed) return
        val now = System.currentTimeMillis()
        if (now - lastAdjustTime < MIN_INTERVAL_MS) return

        val diff = luminance - TARGET_LUMINANCE
        if (Math.abs(diff) <= DEAD_ZONE) return

        // 偏亮减补偿，偏暗加补偿
        val step = if (diff > 0) -MAX_STEP else MAX_STEP
        val next = (currentCompensation + step)
            .coerceIn(compensationRange.lower, compensationRange.upper)
        if (next == currentCompensation) return

        lastAdjustTime = now
        currentCompensation = next
        Log.d(TAG, "软件AE调节: 亮度=$luminance 补偿=$next")
        onExposureChanged(next)
    }

    fun close() {
        closed = true
        try {
            imageReader.close()
        } catch (e: Exception) {
            Log.w(TAG, "关闭 ImageReader 失败", e)
        }
        handlerThread.quitSafely()
    }
}
