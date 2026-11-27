package com.narzalcaide.timelog

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Checks GitHub Releases for a newer TimeLog.apk and offers it in one tap. The page itself is the live site, so this
 * is only for changes to the app around it (notification, widget, alerts).
 *
 * An app from outside the Play Store can't install itself silently: Android always shows its own Install screen,
 * and the first time it asks to allow installs from Time Log. So the flow is Update, then Install.
 * Every release has to be signed with the same key as the installed one, or Android refuses the update.
 */
object Updater {
    private const val LATEST = "https://github.com/naarrzz/time-log-android/releases/latest"
    private const val APK_NAME = "TimeLog.apk"
    private const val CHECK_EVERY = 6 * 60 * 60 * 1000L
    private const val SNOOZE = 24 * 60 * 60 * 1000L
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var busy = false

    private fun prefs(c: Context) = c.getSharedPreferences("updates", Context.MODE_PRIVATE)

    private fun installedVersion(c: Context): String =
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "0"

    /** "v0.3.1" > "0.3.0", number by number. */
    fun isNewer(tag: String, current: String): Boolean {
        val a = tag.trimStart('v', 'V').split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val b = current.trimStart('v', 'V').split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Quietly looks for an update, at most every 6 hours, and asks only when there is one. */
    fun checkInBackground(activity: Activity) {
        val p = prefs(activity)
        val now = System.currentTimeMillis()
        if (busy || now - p.getLong("checked", 0) < CHECK_EVERY) return
        busy = true
        thread(name = "update-check") {
            try {
                val release = fetchLatest() ?: return@thread
                p.edit().putLong("checked", now).apply()
                val (tag, apkUrl, notes) = release
                if (!isNewer(tag, installedVersion(activity))) return@thread
                if (p.getString("snoozedTag", null) == tag && now - p.getLong("snoozedAt", 0) < SNOOZE) return@thread
                main.post { if (!activity.isFinishing) ask(activity, tag, apkUrl, notes) }
            } catch (_: Exception) {
                // No internet or GitHub is busy: try again next time.
            } finally {
                busy = false
            }
        }
    }

    // github.com/.../releases/latest redirects to .../releases/tag/<tag>. Unlike the GitHub API, this isn't
    // rate limited per network, so a shared Wi-Fi can't block the check.
    private fun fetchLatest(): Triple<String, String, String>? {
        val conn = (URL(LATEST).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "TimeLog-Android")
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        try {
            if (conn.responseCode !in 300..399) return null
            val tag = conn.getHeaderField("Location")?.substringAfter("/releases/tag/", "")?.substringBefore('?') ?: return null
            if (tag.isBlank()) return null
            return Triple(tag, "https://github.com/naarrzz/time-log-android/releases/download/$tag/$APK_NAME", "")
        } finally {
            conn.disconnect()
        }
    }

    private fun ask(activity: Activity, tag: String, apkUrl: String, notes: String) {
        val text = buildString {
            append("May bagong Time Log app: $tag. Nasa ${installedVersion(activity)} ka pa.")
            if (notes.isNotBlank()) append("\n\n").append(notes.trim().take(600))
            append("\n\nI-tap ang Update, tapos Install. Hindi mabubura ang logs mo.")
        }
        AlertDialog.Builder(activity)
            .setTitle("May update!")
            .setMessage(text)
            .setPositiveButton("Update") { _, _ -> download(activity, tag, apkUrl) }
            .setNegativeButton("Mamaya") { _, _ ->
                prefs(activity).edit().putString("snoozedTag", tag).putLong("snoozedAt", System.currentTimeMillis()).apply()
            }
            .show()
    }

    private fun download(activity: Activity, tag: String, apkUrl: String) {
        Toast.makeText(activity, "Dina-download ang $tag…", Toast.LENGTH_SHORT).show()
        val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, APK_NAME)
        thread(name = "update-download") {
            try {
                var url = URL(apkUrl)
                // GitHub sends the file from another host, so follow the redirects by hand.
                repeat(5) {
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = false
                        setRequestProperty("User-Agent", "TimeLog-Android")
                        connectTimeout = 15_000
                        readTimeout = 60_000
                    }
                    try {
                        val code = conn.responseCode
                        if (code in 300..399) {
                            url = URL(url, conn.getHeaderField("Location"))
                            return@repeat
                        }
                        if (code != 200) throw IllegalStateException("HTTP $code")
                        conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
                        main.post { install(activity, out) }
                        return@thread
                    } finally {
                        conn.disconnect()
                    }
                }
                throw IllegalStateException("too many redirects")
            } catch (_: Exception) {
                main.post { Toast.makeText(activity, "Di na-download ang update. Try ulit mamaya.", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun install(activity: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(activity, activity.packageName + ".files", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, "Di mabuksan ang installer.", Toast.LENGTH_LONG).show()
        }
    }
}
