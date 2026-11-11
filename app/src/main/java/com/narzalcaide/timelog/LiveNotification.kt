package com.narzalcaide.timelog

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle

object Notifs {
    const val CHANNEL_RUNNING = "running"
    const val CHANNEL_ALERTS = "alerts"
    const val ID_RUNNING = 1
    const val ID_LONG = 2
    const val ID_GAPS = 3

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val running = NotificationChannel(CHANNEL_RUNNING, "Running timer", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "The tile that's running now, with a timer and Stop."
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val alerts = NotificationChannel(CHANNEL_ALERTS, "Reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "A tile still running after 4 hours, and gaps left at 9 PM."
        }
        nm.createNotificationChannels(listOf(running, alerts))
    }

    // Opens the app on a timelog:// link, which MainActivity passes on to the web app.
    fun link(context: Context, uri: String, requestCode: Int): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

// The Android version of the iOS Live Activity: an ongoing notification with a running timer and Stop.
object LiveNotification {
    // Android 16 reads this extra (Notification.EXTRA_REQUEST_PROMOTED_ONGOING) to show a Live Update chip.
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    fun sync(context: Context, snap: Snapshot?) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val running = snap?.running
        if (running == null) {
            nm.cancel(Notifs.ID_RUNNING)
            return
        }
        if (!Notifs.canPost(context)) return
        Notifs.ensureChannels(context)

        val stop = Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_stop), "Stop",
            Notifs.link(context, "timelog://stop", 1),
        ).build()

        val builder = Notification.Builder(context, Notifs.CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(running.title)
            .setContentText("Running · tap to open Time Log")
            .setWhen(running.start)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(parseColor(running.color))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(Notifs.link(context, "timelog://open", 0))
            .addAction(stop)
        if (Build.VERSION.SDK_INT >= 31) builder.setCategory(Notification.CATEGORY_STOPWATCH)
        if (Build.VERSION.SDK_INT >= 36) {
            builder.addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
        }
        nm.notify(Notifs.ID_RUNNING, builder.build())
    }
}
