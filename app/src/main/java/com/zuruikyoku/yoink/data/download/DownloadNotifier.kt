package com.zuruikyoku.yoink.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.zuruikyoku.yoink.R

object DownloadNotifier {

    const val CHANNEL_ID = "downloads"
    const val PROGRESS_NOTIFICATION_ID = 1001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_downloads),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notif_channel_downloads_desc)
        }
        manager.createNotificationChannel(channel)
    }

    fun progressNotification(
        context: Context,
        percent: Int,
        indeterminate: Boolean,
        queueIndex: Int = 1,
        queueTotal: Int = 1
    ): android.app.Notification {
        ensureChannel(context)
        val title = if (queueTotal > 1) {
            context.getString(R.string.notif_downloading_title_indexed, queueIndex, queueTotal)
        } else {
            context.getString(R.string.notif_downloading_title)
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, indeterminate)
            .build()
    }

    fun showResultNotification(context: Context, notificationId: Int, success: Boolean) {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(
                context.getString(
                    if (success) R.string.notif_download_success else R.string.notif_download_failure
                )
            )
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()
        manager.notify(notificationId, notification)
        manager.cancel(PROGRESS_NOTIFICATION_ID)
    }
}
