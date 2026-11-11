package com.narzalcaide.timelog

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.widget.RemoteViews

object Notifs {
    const val CHANNEL_RUNNING = "running_live"
    private const val CHANNEL_RUNNING_OLD = "running"   // v0.1.0, default importance; importance can't be raised in place
    const val CHANNEL_ALERTS = "alerts"
    const val ID_RUNNING = 1
    const val ID_LONG = 2
    const val ID_GAPS = 3

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        // High importance so it sorts to the top and shows on the lock screen, but silent.
        val running = NotificationChannel(CHANNEL_RUNNING, "Running timer", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "The tile that's running now, with a timer and Stop."
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val alerts = NotificationChannel(CHANNEL_ALERTS, "Reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "A tile still running after 4 hours, and gaps left at 9 PM."
        }
        nm.createNotificationChannels(listOf(running, alerts))
        if (nm.getNotificationChannel(CHANNEL_RUNNING_OLD) != null) nm.deleteNotificationChannel(CHANNEL_RUNNING_OLD)
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

// The Android version of the iOS Live Activity: the running tile with a ticking timer and Stop,
// held by a foreground service so it stays pinned at the top and can't be swiped away.
object LiveNotification {
    // Android 16 reads this extra (Notification.EXTRA_REQUEST_PROMOTED_ONGOING) to ask for a Live Update chip.
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    fun sync(context: Context, snap: Snapshot?) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val running = snap?.running
        if (running == null) {
            context.stopService(Intent(context, RunningService::class.java))
            nm.cancel(Notifs.ID_RUNNING)
            return
        }
        if (!Notifs.canPost(context)) return
        try {
            // The service reads the saved snapshot and calls startForeground.
            context.startForegroundService(Intent(context, RunningService::class.java))
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (Android 12+) is an IllegalStateException:
            // fall back to a plain ongoing notification.
            nm.notify(Notifs.ID_RUNNING, build(context, running, foreground = false))
        } catch (e: SecurityException) {
            nm.notify(Notifs.ID_RUNNING, build(context, running, foreground = false))
        }
    }

    fun build(context: Context, running: Running, foreground: Boolean): Notification {
        Notifs.ensureChannels(context)
        val color = parseColor(running.color)
        val onColor = textColorOn(color)

        // A real ticking timer in the content, since some phones (ColorOS) show the start time in the header
        // instead of honoring setUsesChronometer. The tile-colored pill keeps the text readable even where the
        // phone ignores setColorized.
        val views = RemoteViews(context.packageName, R.layout.notif_running).apply {
            setInt(R.id.notif_bg, "setColorFilter", color)
            setTextViewText(R.id.notif_title, running.title)
            setTextColor(R.id.notif_title, onColor)
            setChronometer(
                R.id.notif_chrono,
                SystemClock.elapsedRealtime() - (System.currentTimeMillis() - running.start), null, true,
            )
            setTextColor(R.id.notif_chrono, onColor)
        }

        val stop = Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_stop), "Stop",
            Notifs.link(context, "timelog://stop", 1),
        ).build()

        @Suppress("DEPRECATION")
        val builder = Notification.Builder(context, Notifs.CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(running.title)
            .setContentText("Running")
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(views)
            .setWhen(running.start)
            .setShowWhen(true)
            .setUsesChronometer(true)   // for phones that honor it
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(color)
            .setColorized(foreground)   // only allowed for foreground-service notifications
            .setPriority(Notification.PRIORITY_MAX)   // older OEM sorting; channel importance rules on 8.0+
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(Notifs.link(context, "timelog://open", 0))
            .addAction(stop)
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setCategory(Notification.CATEGORY_STOPWATCH)
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        if (Build.VERSION.SDK_INT >= 36) {
            builder.addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
        }
        return builder.build()
    }

    // Only for the moment the service starts after the log was already stopped.
    fun placeholder(context: Context): Notification {
        Notifs.ensureChannels(context)
        return Notification.Builder(context, Notifs.CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(context.getString(R.string.app_name))
            .build()
    }
}

class RunningService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val running = Store.load(this)?.running
        val notification = if (running != null) LiveNotification.build(this, running, foreground = true)
        else LiveNotification.placeholder(this)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Notifs.ID_RUNNING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(Notifs.ID_RUNNING, notification)
            }
        } catch (e: RuntimeException) {
            // Not allowed to go foreground right now (say, restarted in the background): plain notification instead.
            if (running != null && Notifs.canPost(this)) {
                getSystemService(NotificationManager::class.java)
                    .notify(Notifs.ID_RUNNING, LiveNotification.build(this, running, foreground = false))
            }
            stopSelf()
            return START_NOT_STICKY
        }
        if (running == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // If the system kills the process, bring the timer back from the saved snapshot.
        return START_STICKY
    }
}
