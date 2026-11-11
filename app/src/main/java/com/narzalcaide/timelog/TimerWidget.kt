package com.narzalcaide.timelog

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews

// Home screen widget: running tile with a live timer, today's total, and up to 4 tile shortcuts.
class TimerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, build(context, manager.getAppWidgetOptions(it))) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        manager.updateAppWidget(id, build(context, options))
    }

    companion object {
        private val slots = listOf(
            Triple(R.id.slot0, R.id.slot0_bg, R.id.slot0_text),
            Triple(R.id.slot1, R.id.slot1_bg, R.id.slot1_text),
            Triple(R.id.slot2, R.id.slot2_bg, R.id.slot2_text),
            Triple(R.id.slot3, R.id.slot3_bg, R.id.slot3_text),
        )

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TimerWidget::class.java))
            ids.forEach { manager.updateAppWidget(it, build(context, manager.getAppWidgetOptions(it))) }
        }

        private fun build(context: Context, options: Bundle?): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_timer)
            val snap = Store.load(context)
            val now = System.currentTimeMillis()
            views.setOnClickPendingIntent(R.id.widget_root, Notifs.link(context, "timelog://open", 0))

            val running = snap?.running
            if (running != null) {
                views.setTextViewText(R.id.name, running.title)
                views.setInt(R.id.dot, "setColorFilter", parseColor(running.color))
                views.setViewVisibility(R.id.dot, View.VISIBLE)
                views.setChronometer(R.id.chrono, SystemClock.elapsedRealtime() - (now - running.start), null, true)
                views.setViewVisibility(R.id.chrono, View.VISIBLE)
            } else {
                views.setTextViewText(R.id.name, context.getString(R.string.widget_idle))
                views.setViewVisibility(R.id.dot, View.GONE)
                views.setChronometer(R.id.chrono, SystemClock.elapsedRealtime(), null, false)
                views.setViewVisibility(R.id.chrono, View.GONE)
            }
            val total = snap?.todayTotalMs(now) ?: 0L
            views.setTextViewText(R.id.total, context.getString(R.string.widget_today, formatDuration(total)))

            // Short widgets skip the shortcuts row.
            val minHeight = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
            val tiles = if (minHeight in 1..99) emptyList() else snap?.shortcuts().orEmpty()
            views.setViewVisibility(R.id.shortcuts, if (tiles.isEmpty()) View.GONE else View.VISIBLE)
            slots.forEachIndexed { i, (slot, bg, text) ->
                val tile = tiles.getOrNull(i)
                if (tile == null) {
                    views.setViewVisibility(slot, View.INVISIBLE)
                } else {
                    val color = parseColor(tile.color)
                    views.setViewVisibility(slot, View.VISIBLE)
                    views.setInt(bg, "setColorFilter", color)
                    views.setTextViewText(text, tile.name)
                    views.setTextColor(text, textColorOn(color))
                    views.setOnClickPendingIntent(slot, Notifs.link(context, "timelog://tile/${tile.id}", 100 + i))
                }
            }
            return views
        }
    }
}
