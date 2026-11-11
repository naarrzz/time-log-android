package com.narzalcaide.timelog

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Calendar

// Same reminders as iOS: a tile still running after 4 hours, and gaps left at 9 PM.
// Inexact alarms (setAndAllowWhileIdle), so no exact-alarm permission. They can arrive a few minutes late.
object Alerts {
    const val LONG_RUNNING = 10
    const val GAPS = 11
    const val LONG_AFTER_MS = 4L * 3600 * 1000
    private const val GAPS_HOUR = 21

    fun schedule(context: Context, snap: Snapshot?) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(intent(context, LONG_RUNNING))
        am.cancel(intent(context, GAPS))
        if (snap == null) return
        val now = System.currentTimeMillis()

        snap.running?.let {
            val at = it.start + LONG_AFTER_MS
            if (at > now) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(context, LONG_RUNNING))
        }

        if (snap.gaps > 0 && snap.day == dayKey(now)) {
            val at = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, GAPS_HOUR); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            if (at > now) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(context, GAPS))
        }
    }

    private fun intent(context: Context, kind: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, kind,
            Intent(context, AlertReceiver::class.java).setAction("com.narzalcaide.timelog.ALERT").putExtra("kind", kind),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

class AlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Notifs.canPost(context)) return
        val snap = Store.load(context) ?: return
        val now = System.currentTimeMillis()
        when (intent.getIntExtra("kind", 0)) {
            Alerts.LONG_RUNNING -> {
                // Only if the same log is still the one running.
                val r = snap.running ?: return
                if (now - r.start < Alerts.LONG_AFTER_MS - 60_000) return
                post(context, Notifs.ID_LONG, "Still on ${r.name}?", "It's been over 4 hours. Did you forget to stop it?")
            }
            Alerts.GAPS -> {
                if (snap.gaps <= 0 || snap.day != dayKey(now)) return
                val title = if (snap.gaps == 1) "You have a gap today" else "You have ${snap.gaps} gaps today"
                post(context, Notifs.ID_GAPS, title, "What happened? Tap to fill them in.")
            }
        }
    }

    private fun post(context: Context, id: Int, title: String, text: String) {
        Notifs.ensureChannels(context)
        val n = Notification.Builder(context, Notifs.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(Notifs.link(context, "timelog://open", 2 + id))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, n)
    }
}

// Alarms and posted notifications don't survive a reboot or an update, so bring them back from the last snapshot.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val snap = Store.load(context)
        LiveNotification.sync(context, snap)
        Alerts.schedule(context, snap)
        TimerWidget.updateAll(context)
    }
}
