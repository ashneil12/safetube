package app.safetube

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.safetube.core.DecisionEngine
import app.safetube.core.Verdict
import app.safetube.core.YouTubeOEmbed

/**
 * The child's screen: normal YouTube in a WebView, with the filter script running on every page.
 * Only YouTube (and Google sign-in) can load; every other link is swallowed.
 */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var store: SqliteVerdictStore
    private lateinit var webView: WebView
    private lateinit var bridge: FilterBridge
    private lateinit var cover: View
    private lateinit var root: FrameLayout
    private var filterScript: String = ""
    private var documentStartSupported = false
    private var seenVersion = -1
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var lastErrorToast = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        store = SqliteVerdictStore(this)
        filterScript = assets.open("filter.js").bufferedReader().use { it.readText() }

        root = FrameLayout(this)
        webView = WebView(this)
        root.addView(webView, FrameLayout.LayoutParams(MATCH, MATCH))
        cover = buildCover()
        root.addView(cover, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(buildParentButton(), FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END))
        setContentView(root)

        CookieManager.getInstance().setAcceptCookie(true)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }

        bridge = FilterBridge(webView, prefs, DecisionEngine(store), ::showAiError, ::onBlockRequest)
        webView.addJavascriptInterface(bridge, "SafeTubeAndroid")

        // Best case: the filter is in place before YouTube's own scripts run, so nothing unchecked
        // can ever paint. Older WebViews fall back to injecting on page start behind a cover.
        documentStartSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (documentStartSupported) {
            WebViewCompat.addDocumentStartJavaScript(
                webView, filterScript, setOf("https://youtube.com", "https://*.youtube.com")
            )
        }

        webView.webViewClient = Client()
        webView.webChromeClient = Chrome()
        seenVersion = prefs.version

        if (!prefs.hasPin) {
            startActivity(Intent(this, ParentActivity::class.java).putExtra(ParentActivity.EXTRA_SETUP, true))
        }
        if (savedInstanceState == null) webView.loadUrl(START_URL) else webView.restoreState(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        if (prefs.version != seenVersion) {
            // Settings changed: the page forgets its decisions and re-checks what's on screen.
            seenVersion = prefs.version
            webView.evaluateJavascript(
                "window.__safetube && window.__safetube.reset(${prefs.pageConfigJson()})", null
            )
        }
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        bridge.shutdown()
        webView.destroy()
        super.onDestroy()
    }

    @Deprecated("Still the simplest back handling across Fire OS versions")
    override fun onBackPressed() {
        when {
            fullscreenView != null -> exitFullscreen()
            webView.canGoBack() -> webView.goBack()
            // Don't let back drop the child out to the launcher; Home still works.
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ navigation

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            return !allowNavigation(request.url)
        }

        @Deprecated("Needed for API < 24")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
            !allowNavigation(Uri.parse(url))

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            bridge.currentHost = FilterBridge.hostOf(url)
            if (!documentStartSupported) {
                cover.visibility = View.VISIBLE
                injectScript(view)
            }
        }

        override fun onPageCommitVisible(view: WebView, url: String?) {
            if (!documentStartSupported) injectScript(view)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            bridge.currentHost = FilterBridge.hostOf(url)
            if (!documentStartSupported) {
                injectScript(view)
                // Give the script a moment to hide everything before the child sees the page.
                cover.postDelayed({ cover.visibility = View.GONE }, 400)
            } else {
                cover.visibility = View.GONE
            }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            bridge.currentHost = FilterBridge.hostOf(url)
        }
    }

    private fun injectScript(view: WebView) {
        if (FilterBridge.isYouTubeHost(FilterBridge.hostOf(view.url))) view.evaluateJavascript(filterScript, null)
    }

    /** YouTube, plus the Google pages needed to sign in or accept cookies. Nothing else. */
    private fun allowNavigation(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase() ?: return false
        if (scheme != "https" && scheme != "http") return false
        if (host == "youtu.be" || host == "www.youtu.be") {
            val id = uri.path?.trimStart('/')?.take(11) ?: ""
            // Short links open the same video on YouTube itself, where the filter checks it.
            webView.loadUrl("https://m.youtube.com/watch?v=$id")
            return false
        }
        if (FilterBridge.isYouTubeHost(host)) return true
        if (host in SIGN_IN_HOSTS) return true
        toast("Links outside YouTube are turned off")
        return false
    }

    // ------------------------------------------------------------------ fullscreen video

    private inner class Chrome : WebChromeClient() {
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (fullscreenView != null) {
                callback.onCustomViewHidden()
                return
            }
            fullscreenView = view
            fullscreenCallback = callback
            root.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        override fun onHideCustomView() = exitFullscreen()
    }

    private fun exitFullscreen() {
        val view = fullscreenView ?: return
        root.removeView(view)
        fullscreenView = null
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ------------------------------------------------------------------ chrome around the page

    private fun buildCover(): View = FrameLayout(this).apply {
        setBackgroundColor(Color.WHITE)
        addView(ProgressBar(this@MainActivity), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        isClickable = true
    }

    /** A faint lock in the corner. Hold it for two seconds to reach the parent settings. */
    @SuppressLint("ClickableViewAccessibility")
    private fun buildParentButton(): View = TextView(this).apply {
        text = "🔒"
        textSize = 16f
        gravity = Gravity.CENTER
        alpha = 0.35f
        contentDescription = "Parent settings (hold)"
        val open = Runnable {
            // Pass the page that's open so the parent can block that video or channel in one tap.
            startActivity(
                Intent(this@MainActivity, ParentActivity::class.java)
                    .putExtra(ParentActivity.EXTRA_CURRENT_URL, webView.url)
            )
        }
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.postDelayed(open, 2000)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.removeCallbacks(open)
            }
            true
        }
    }

    // ------------------------------------------------------------------ hold-to-block

    private fun onBlockRequest(req: FilterBridge.BlockRequest) {
        if (prefs.kidsCanBlock) {
            applyBlock(req)
            return
        }
        // Blocking is parent-only in this mode: ask for the PIN first.
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Parent PIN"
        }
        AlertDialog.Builder(this)
            .setTitle(if (req.channel) "Block this channel?" else "Block this video?")
            .setView(FrameLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input) })
            .setPositiveButton("Block") { _, _ ->
                if (prefs.checkPin(input.text.toString())) applyBlock(req) else toast("Wrong PIN")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyBlock(req: FilterBridge.BlockRequest) {
        val v = req.video
        if (!req.channel) {
            store.setOverride(v.id, Verdict.BLOCK, v.title.ifBlank { null })
            bridge.hideNow(v.id)
            syncVersion()
            toast("Video blocked. A grown-up can unblock it in parent settings.")
            return
        }
        Thread {
            // The page may not show which channel a video is from; YouTube's oEmbed always does.
            val looked = if (v.channelId.isBlank() || v.channel.isBlank()) YouTubeOEmbed.fetch(v.id) else null
            val handle = v.channelId.ifBlank { looked?.channelId ?: "" }
            val name = v.channel.ifBlank { looked?.channel ?: "" }
            runOnUiThread {
                if (handle.isBlank() && name.isBlank()) {
                    toast("Couldn't tell which channel this is. Try again on the video's page.")
                    return@runOnUiThread
                }
                listOf(handle, name).filter { it.isNotBlank() }.forEach { prefs.addBlockedChannel(it) }
                syncVersion()
                // Re-check everything on screen against the new channel list.
                webView.evaluateJavascript("window.__safetube && window.__safetube.reset(${prefs.pageConfigJson()})", null)
                toast("${name.ifBlank { handle }} blocked. A grown-up can unblock it in parent settings.")
            }
        }.start()
    }

    /** Record a settings change made from this screen so onResume doesn't redo it. */
    private fun syncVersion() {
        prefs.bumpVersion()
        seenVersion = prefs.version
    }

    private fun showAiError(reason: String) {
        runOnUiThread {
            val now = SystemClock.uptimeMillis()
            if (now - lastErrorToast < 60_000) return@runOnUiThread
            lastErrorToast = now
            toast("Some videos are hidden because the AI check failed: ${reason.take(120)}")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val START_URL = "https://m.youtube.com/"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private val SIGN_IN_HOSTS = setOf(
            "accounts.google.com", "accounts.youtube.com", "consent.youtube.com", "consent.google.com",
            "myaccount.google.com", "accounts.google.co.uk",
        )
    }
}
