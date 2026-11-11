package com.narzalcaide.timelog

import android.content.Context
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

// What the web app reports (same JSON as the iOS shell). The web app stays the only thing that writes logs:
// everything native just shows this.
data class Tile(val id: String, val name: String, val color: String)

data class Total(val id: String, val name: String, val color: String, val ms: Long)

data class Running(
    val id: String,
    val name: String,
    val color: String,
    val start: Long,     // when the whole trip began; what the timer counts from
    val logStart: Long,  // when the current log began; what today's total adds from
    val sub: String?,
) {
    val title: String get() = if (sub.isNullOrBlank()) name else "$name · $sub"
}

data class Snapshot(
    val running: Running?,
    val tiles: List<Tile>,
    val top: List<String>,
    val today: List<Total>,
    val gaps: Int,
    val day: String,
) {
    // Today's total with the running log counted. Totals from an earlier day don't count.
    fun todayTotalMs(now: Long = System.currentTimeMillis()): Long {
        val sameDay = day == dayKey(now)
        var sum = if (sameDay) today.sumOf { it.ms } else 0L
        running?.let { sum += (now - maxOf(it.logStart, midnight(now))).coerceAtLeast(0) }
        return sum
    }

    // Widget shortcuts: most used first, then the rest in tile order.
    fun shortcuts(limit: Int = 4): List<Tile> {
        val byId = tiles.associateBy { it.id }
        val ordered = top.mapNotNull { byId[it] } + tiles.filter { it.id !in top }
        return ordered.distinctBy { it.id }.take(limit)
    }

    companion object {
        // Returns null for anything that isn't a state snapshot, like the {ui:{...}} messages.
        fun parse(text: String): Snapshot? = try {
            val o = JSONObject(text)
            if (o.has("ui") || !o.has("tiles")) null else from(o)
        } catch (e: Exception) {
            null
        }

        private fun from(o: JSONObject): Snapshot {
            val r = o.optJSONObject("running")
            val running = r?.let {
                Running(
                    id = it.getString("id"),
                    name = it.optString("name", it.getString("id")),
                    color = it.optString("color", "#7C8A93"),
                    start = it.getDouble("start").toLong(),
                    logStart = it.optDouble("logStart", it.getDouble("start")).toLong(),
                    sub = if (it.isNull("sub")) null else it.optString("sub").ifBlank { null },
                )
            }
            val tiles = o.optJSONArray("tiles").objects().map {
                Tile(it.getString("id"), it.optString("name", it.getString("id")), it.optString("color", "#7C8A93"))
            }
            val topArr = o.optJSONArray("top")
            val top = if (topArr == null) emptyList() else (0 until topArr.length()).map { topArr.optString(it) }
            val today = o.optJSONArray("today").objects().map {
                Total(
                    it.getString("id"), it.optString("name", it.getString("id")),
                    it.optString("color", "#7C8A93"), it.optDouble("ms", 0.0).toLong(),
                )
            }
            return Snapshot(running, tiles, top, today, o.optInt("gaps", 0), o.optString("day", ""))
        }

        private fun JSONArray?.objects(): List<JSONObject> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    }
}

// Same key as the web app's keyOf(): local date as YYYY-MM-DD.
fun dayKey(t: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = t }
    return String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
}

fun midnight(t: Long): Long = Calendar.getInstance().apply {
    timeInMillis = t
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

fun formatDuration(ms: Long): String {
    val minutes = (ms + 30_000) / 60_000
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

fun parseColor(hex: String): Int = try {
    Color.parseColor(if (hex.startsWith("#")) hex.take(7) else "#" + hex.take(6))
} catch (e: IllegalArgumentException) {
    0xFF7C8A93.toInt()
}

// Same rule as tiles in the web app: light colors get black text, the rest get white.
fun textColorOn(color: Int): Int {
    fun lin(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }
    val l = 0.2126 * lin(Color.red(color)) + 0.7152 * lin(Color.green(color)) + 0.0722 * lin(Color.blue(color))
    return if (l > 0.3) Color.BLACK else Color.WHITE
}

// The last snapshot, for the widget, alarms and reboots.
object Store {
    private const val PREFS = "timelog"
    private const val KEY = "snapshot"

    fun save(context: Context, text: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, text).apply()
    }

    fun load(context: Context): Snapshot? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.let { Snapshot.parse(it) }
}
