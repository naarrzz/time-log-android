package com.narzalcaide.timelog

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private var pageReady = false
    private val queued = mutableListOf<String>()
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) LiveNotification.sync(this, Store.load(this))
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        fileCallback = null
    }

    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (web.canGoBack()) web.goBack()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Keep the page out from under the status bar, nav bar, cutout and keyboard.
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime(),
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        web = findViewById(R.id.web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "TimeLogAndroid")
        web.webViewClient = Client()
        web.webChromeClient = Chrome()
        onBackPressedDispatcher.addCallback(this, back)

        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) web.loadUrl(START_URL)
        if (savedInstanceState == null) handleLink(intent)
        askForNotificationsOnce()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLink(intent)
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        // Timers pause in the background, so ask the page to report again.
        run("refresh", null)
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    // timelog://open, timelog://stop, timelog://tile/<id>
    private fun handleLink(intent: Intent?) {
        if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val uri = intent.data ?: return
        if (uri.scheme != "timelog") return
        when (uri.host) {
            "stop" -> run("stop", null)
            "tile" -> uri.pathSegments.firstOrNull()?.let { run("tile", it) }
        }
    }

    // Calls back into the page. The page holds stop/tile itself until its logs have loaded.
    private fun run(action: String, id: String?) {
        val arg = id?.filter { it.isLetterOrDigit() || it == '-' || it == '_' }?.let { "'$it'" } ?: "null"
        evaluate("if (window.timeLogAction) window.timeLogAction('$action', $arg);")
    }

    private fun evaluate(js: String) {
        if (pageReady) web.evaluateJavascript(js, null) else queued.add(js)
    }

    private fun receive(text: String) {
        val snap = Snapshot.parse(text) ?: return   // {ui:{...}} and anything else: ignored, Android keeps the web nav
        Store.save(this, text)
        LiveNotification.sync(this, snap)
        Alerts.schedule(this, snap)
        TimerWidget.updateAll(this)
    }

    private fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("app", Context.MODE_PRIVATE)
        if (prefs.getBoolean("askedNotifications", false)) return
        prefs.edit().putBoolean("askedNotifications", true).apply()
        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // window.TimeLogAndroid.postMessage(JSON.stringify(state)), same messages as iOS.
    // Called on a background thread; only the Time Log site is listened to.
    inner class Bridge {
        @JavascriptInterface
        fun postMessage(text: String) {
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val host = web.url?.let { Uri.parse(it).host }
                if (host == HOST) receive(text)
            }
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            if (uri.scheme == "timelog") {
                handleLink(Intent(Intent.ACTION_VIEW, uri))
                return true
            }
            if (uri.host == HOST) return false
            // Anything else (help links, mailto) opens outside the app.
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (_: ActivityNotFoundException) {
            }
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            pageReady = false
        }

        override fun onPageFinished(view: WebView, url: String?) {
            pageReady = true
            queued.forEach { view.evaluateJavascript(it, null) }
            queued.clear()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            back.isEnabled = view.canGoBack()
        }
    }

    // File picker for Restore (input type=file).
    private inner class Chrome : WebChromeClient() {
        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = filePathCallback
            return try {
                pickFile.launch(fileChooserParams.createIntent())
                true
            } catch (_: ActivityNotFoundException) {
                fileCallback = null
                false
            }
        }
    }

    companion object {
        const val HOST = "naarrzz.github.io"
        const val START_URL = "https://naarrzz.github.io/time-log/"
    }
}
