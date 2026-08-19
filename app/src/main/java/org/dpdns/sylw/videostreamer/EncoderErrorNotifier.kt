package org.dpdns.sylw.videostreamer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * 将编码器错误单独显示在系统通知栏，方便应用退到后台后仍能发现推流故障。
 */
object EncoderErrorNotifier {
    private const val TAG = "EncoderErrorNotifier"
    private const val CHANNEL_ID = "encoder-errors"
    private const val NOTIFICATION_ID = 2002

    fun notifyIfEncoderError(context: Context, message: String) {
        val isEncoderError = message.contains("编码器") ||
            message.contains("encoder", ignoreCase = true) ||
            message.contains("视频帧发送失败") ||
            message.contains("视频处理错误过多")
        if (!isEncoderError) {
            return
        }

        val appContext = context.applicationContext
        createNotificationChannel(appContext)

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(appContext.getString(R.string.notification_encoder_error_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Android 13+ 未授予通知权限时不能发送通知，但不能影响原有错误处理。
            Log.w(TAG, "无法发送编码器错误通知，可能未授予通知权限", e)
        }
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_encoder_errors),
            NotificationManager.IMPORTANCE_HIGH
        )
        manager.createNotificationChannel(channel)
    }
}
