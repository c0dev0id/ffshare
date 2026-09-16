package com.caydey.ffshare

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.caydey.ffshare.utils.CompressionState
import com.caydey.ffshare.utils.Utils

/**
 * The notifications a compression run posts while it is not on screen.
 *
 * Two channels, because the two have different urgency: progress is silent and
 * ongoing, the finished-run notification should be able to make a sound while the
 * user is elsewhere.
 */
class CompressionNotifications(private val context: Context) {
    private val utils: Utils by lazy { Utils(context) }

    private val manager: NotificationManager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun createChannels() {
        val progress = NotificationChannel(
            CHANNEL_PROGRESS,
            context.getString(R.string.notification_channel_progress),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_channel_progress_summary)
            setShowBadge(false)
        }
        val result = NotificationChannel(
            CHANNEL_RESULT,
            context.getString(R.string.notification_channel_result),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notification_channel_result_summary)
        }
        manager.createNotificationChannels(listOf(progress, result))
    }

    /** The ongoing notification the foreground service is tied to. */
    fun progress(state: CompressionState.Running?): Notification {
        val title = if (state != null && state.total > 1) {
            context.getString(R.string.notification_compressing_x_of_y, state.position, state.total)
        } else {
            context.getString(R.string.notification_compressing)
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(state?.outputName)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(0, context.getString(R.string.cancel_ffmpeg), cancelIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (state != null && state.hasProgress) {
            builder.setProgress(100, state.percent.toInt(), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    fun updateProgress(state: CompressionState.Running) {
        if (!canPost()) return
        manager.notify(ID_PROGRESS, progress(state))
    }

    /**
     * Posted when a run ends. Tapping opens the app so the user can review stats
     * and explicitly share or dismiss — the notification itself never launches the chooser.
     */
    fun postResult(state: CompressionState.Finished) {
        if (!canPost()) return

        val builder = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openAppIntent())

        if (state.succeeded && state.outputs.isNotEmpty()) {
            builder
                .setContentTitle(context.getString(R.string.notification_ready_title))
                .setContentText(
                    context.getString(
                        R.string.media_reduction_message,
                        utils.bytesToHuman(state.totalOutputSize),
                        state.reductionPercent
                    )
                )
                .setSubText(context.getString(R.string.notification_ready_text))
        } else {
            builder.setContentTitle(context.getString(state.errorRes ?: R.string.ffmpeg_error))
        }

        manager.notify(ID_RESULT, builder.build())
    }

    /**
     * Drops the finished-run notification. The progress one belongs to the foreground
     * service and goes with stopForeground, so cancelling it here would only race.
     */
    fun cancelResult() {
        manager.cancel(ID_RESULT)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context, REQUEST_OPEN,
        Intent(context, HandleMediaActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun cancelIntent(): PendingIntent = PendingIntent.getService(
        context, REQUEST_CANCEL,
        Intent(context, CompressionService::class.java).setAction(CompressionService.ACTION_CANCEL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /**
     * A foreground service may run without the notification permission, its notification
     * is simply never shown. Everything else has to check first.
     */
    private fun canPost(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

    companion object {
        const val ID_PROGRESS = 1
        const val ID_RESULT = 2

        private const val CHANNEL_PROGRESS = "compression_progress"
        private const val CHANNEL_RESULT = "compression_result"

        private const val REQUEST_OPEN = 0
        private const val REQUEST_CANCEL = 1
    }
}
