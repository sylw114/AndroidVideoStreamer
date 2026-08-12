package org.dpdns.sylw.videostreamer.camera

import android.hardware.camera2.CaptureResult
import android.util.Log
import android.util.Range

/**
 * 高速模式（≥120fps）软件自动曝光
 *
 * Camera2 constrained high-speed 会话中 AE 被系统强制开启、禁止手动曝光控制，
 * 部分设备在高速下画面亮度不佳。
 *
 * 设计要点：高速会话一旦存在第二个输出 surface（如 ImageReader），
 * createHighSpeedRequestList 会生成交错请求模式，导致编码器帧率暴跌
 * （实测 120fps 只剩 ~7.5fps）。因此本类**不占用额外输出流**，而是通过
 * CaptureCallback 读取 SENSOR_EXPOSURE_TIME / SENSOR_SENSITIVITY 估算画面明暗，
 * 以 CONTROL_AE_EXPOSURE_COMPENSATION 为杠杆做软件闭环微调。
 *
 * 注意：曝光补偿在 AE ON（高速会话强制）时有效，但能否在高速会话中生效
 * 依赖具体设备，属于尽力而为的增强。
 */
class HighSpeedSoftwareAe(
    /** 单帧时长（ns）= 1e9 / fps，用于归一化曝光时间占比 */
    private val frameDurationNs: Long,
    private val compensationRange: Range<Int>,
    private val onExposureChanged: (Int) -> Unit
) {
    companion object {
        private const val TAG = "HighSpeedSoftwareAe"
        /** 曝光时间占比上限：超过且增益较高视为画面偏暗（AE 已接近拉满曝光） */
        private const val DARK_EXPOSURE_RATIO = 0.85f
        /** 曝光时间占比下限：低于视为画面偏亮 */
        private const val BRIGHT_EXPOSURE_RATIO = 0.15f
        /** 增益阈值：曝光接近拉满且增益不低于该值时判定为暗 */
        private const val DARK_SENSITIVITY = 400
        /** 单次最大调节步进 */
        private const val MAX_STEP = 1
        /** 两次调节的最小间隔（毫秒），防抖 */
        private const val MIN_INTERVAL_MS = 800L
    }

    /** 当前曝光补偿值（用于初始请求与调试） */
    var currentCompensation: Int = 0
        private set

    private var lastAdjustTime = 0L

    @Volatile
    private var closed = false

    /**
     * 由 CaptureCallback.onCaptureCompleted 喂入，内部估算画面明暗并调节曝光补偿。
     * 任何时刻仅允许最新一次调节生效（防抖 + 步进 + 限幅）。
     */
    fun onCaptureResult(result: CaptureResult) {
        if (closed) return
        val exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
        val sensitivity = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
        if (frameDurationNs <= 0) return

        val now = System.currentTimeMillis()
        if (now - lastAdjustTime < MIN_INTERVAL_MS) return

        val exposureRatio = exposureTimeNs.toFloat() / frameDurationNs
        val isDark = exposureRatio >= DARK_EXPOSURE_RATIO && sensitivity >= DARK_SENSITIVITY
        val isBright = exposureRatio <= BRIGHT_EXPOSURE_RATIO
        if (!isDark && !isBright) return

        // 偏暗加补偿（更亮），偏亮减补偿
        val step = if (isDark) MAX_STEP else -MAX_STEP
        val next = (currentCompensation + step)
            .coerceIn(compensationRange.lower, compensationRange.upper)
        if (next == currentCompensation) return

        lastAdjustTime = now
        currentCompensation = next
        Log.d(TAG, "软件AE调节: 曝光比=$exposureRatio 增益=$sensitivity 补偿=$next")
        onExposureChanged(next)
    }

    fun close() {
        closed = true
    }
}
