package app.safetube

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.safetube.core.DecisionEngine
import app.safetube.core.Verdict
import app.safetube.core.YouTubeOEmbed
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors

/**
 * The child's screen: normal YouTube in a WebView, with the filter script running on every page.
 * Only YouTube (and Google sign-in) can load; every other link is swallowed.
 */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var store: SqliteVerdictStore
    private lateinit var webView: WebView
    private lateinit var bridge: FilterBridge
    /** AI batches and title look-ups run side by side here so a page fills in faster. */
    private val aiPool = Executors.newFixedThreadPool(4)
    private lateinit var cover: View
    private lateinit var root: FrameLayout
    private var filterScript: String = ""
    private var documentStartSupported = false
    private var seenVersion = -1
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var lastErrorToast = 0L
    private var pendingBlock: FilterBridge.BlockRequest? = null
    /** Read on WebView's network thread, so kept in a field rather than read from prefs each time. */
    @Volatile private var blockAds = true
    private var blockGames = true
    private lateinit var closeGame: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        store = SqliteVerdictStore(this)
        filterScript = assets.open("filter.js").bufferedReader().use { it.readText() }

        root = FrameLayout(this)
        cover = buildCover()
        setContentView(root)
        setUpWebView()
        seenVersion = prefs.version
        blockAds = prefs.blockAds
        blockGames = prefs.blockGames
        closeGame = buildCloseGame()
        root.addView(closeGame, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { setMargins(0, 24, 24, 0) })

        if (!prefs.hasPin) {
            startActivity(Intent(this, ParentActivity::class.java).putExtra(ParentActivity.EXTRA_SETUP, true))
        } else if (intent?.action == ACTION_OPEN_PARENT) {
            openParent()
        }
        applyRestrictedMode()
        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) webView.loadUrl(startUrl())
    }

    /** Builds the YouTube screen. Called again if the tablet runs low on memory and kills the page. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun setUpWebView() {
        webView = WebView(this)
        root.addView(webView, 0, FrameLayout.LayoutParams(MATCH, MATCH))
        if (cover.parent == null) root.addView(cover, FrameLayout.LayoutParams(MATCH, MATCH))
        if (Build.VERSION.SDK_INT >= 26) {
            // Keep YouTube's page alive while the child is in another app, so coming back
            // carries on where they were instead of reloading.
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            // Google sign-in hands the login from accounts.google.com to youtube.com through
            // cross-site cookies. Without these it spins forever and never signs in.
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.settings.apply {
            // Google stalls or refuses sign-in when it sees an embedded app browser, so send the
            // user agent Chrome on this device would.
            userAgentString = userAgentString.replace("; wv)", ")").replace(Regex("Version/[\\d.]+ "), "")
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            // The X-Requested-With header names this app, which also trips Google's check.
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(webView.settings, emptySet())
        }

        bridge = FilterBridge(webView, prefs, DecisionEngine(store, batchSize = 8, aiPool = aiPool), ::showAiError, ::onBlockRequest, ::openParent, store::recordWatched)
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
    }

    /** Where the child was, if they left recently; otherwise YouTube's home page. */
    private fun startUrl(): String {
        val url = prefs.lastUrl
        val fresh = System.currentTimeMillis() - prefs.lastUrlAt < RESUME_WITHIN_MS
        return if (fresh && url != null && FilterBridge.isYouTubeHost(FilterBridge.hostOf(url))) url else START_URL
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The app icon's "Parent settings" shortcut while the app is already open.
        if (intent.action == ACTION_OPEN_PARENT) openParent()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        blockAds = prefs.blockAds
        blockGames = prefs.blockGames
        if (prefs.version != seenVersion) {
            // Settings changed: the page forgets its decisions and re-checks what's on screen.
            seenVersion = prefs.version
            bridge.settingsChanged()
            if (applyRestrictedMode()) {
                webView.reload()
            } else {
                webView.evaluateJavascript(
                    "window.__safetube && window.__safetube.reset(${bridge.config()})", null
                )
                bridge.reapplyAiLock()
            }
        }
        lastTick = SystemClock.elapsedRealtime()
        ticker.removeCallbacks(tick)
        ticker.post(tick)
    }

    // ------------------------------------------------------------------ screen time

    private val ticker = Handler(Looper.getMainLooper())
    private var lastTick = 0L
    private var timeUpView: View? = null

    /** Counts viewing time while the app is on screen and closes YouTube when time is up. */
    private val tick = object : Runnable {
        override fun run() {
            countTime()
            showTimeUp(prefs.timeUp())
            ticker.postDelayed(this, 15_000)
        }
    }

    private fun countTime() {
        val now = SystemClock.elapsedRealtime()
        val sec = ((now - lastTick) / 1000).toInt().coerceIn(0, 60)
        lastTick = now
        if (sec > 0 && timeUpView == null) prefs.addWatched(sec)
    }

    private fun showTimeUp(reason: String?) {
        if (reason == null) {
            timeUpView?.let { root.removeView(it) }
            timeUpView = null
            return
        }
        if (timeUpView != null) return
        webView.evaluateJavascript("document.querySelectorAll('video').forEach(function(v){v.pause()})", null)
        if (fullscreenView != null) exitFullscreen()
        val v = layoutInflater.inflate(R.layout.time_up, root, false)
        v.findViewById<TextView>(R.id.time_up_title).text =
            getString(if (reason == "bedtime") R.string.time_up_bedtime else R.string.time_up_limit)
        v.findViewById<View>(R.id.time_up_parent).setOnClickListener { openParent() }
        root.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        timeUpView = v
    }

    /**
     * YouTube's Restricted Mode lives in its PREF cookie (the f2 flag). Setting it here changes only
     * this app's browser on this device; the Google account and other devices are untouched.
     * Returns true if the cookie changed and the page needs a reload.
     */
    private fun applyRestrictedMode(): Boolean {
        val cookies = CookieManager.getInstance()
        val current = cookies.getCookie("https://www.youtube.com")
            ?.split(";")?.map { it.trim() }?.firstOrNull { it.startsWith("PREF=") }?.removePrefix("PREF=") ?: ""
        val parts = current.split("&").filter { it.isNotBlank() && !it.startsWith("f2=") }.toMutableList()
        if (prefs.restrictedMode) parts += "f2=8000000"
        val wanted = parts.joinToString("&")
        if (wanted == current) return false
        cookies.setCookie("https://www.youtube.com", "PREF=$wanted; Domain=.youtube.com; Path=/; Secure; Max-Age=63072000")
        cookies.flush()
        return true
    }

    override fun onPause() {
        countTime()
        ticker.removeCallbacks(tick)
        // A game page is never resumed: coming back always starts at the home page.
        webView.url?.let { prefs.rememberPage(if (FilterBridge.isGamePath(Uri.parse(it).path)) START_URL else it) }
        webView.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!FilterBridge.isGamePath(webView.url?.let { Uri.parse(it).path })) webView.saveState(outState)
    }

    override fun onDestroy() {
        bridge.shutdown()
        aiPool.shutdownNow()
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

        /** Ad and tracking requests get an empty answer, so they never load. */
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (!blockAds || !AdBlocklist.blocks(request.url)) return null
            return WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }

        /** Also fires for YouTube's in-page navigation, which never goes through shouldOverrideUrlLoading. */
        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            bridge.currentHost = FilterBridge.hostOf(url)
            val onGame = FilterBridge.isGamePath(url?.let { Uri.parse(it).path })
            if (onGame && blockGames) {
                toast("Games are turned off")
                view.loadUrl(START_URL)
                return
            }
            closeGame.visibility = if (onGame) View.VISIBLE else View.GONE
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            bridge.pageStarted()
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
            bridge.reapplyAiLock()
            bridge.currentHost = FilterBridge.hostOf(url)
            if (!documentStartSupported) {
                injectScript(view)
                // Give the script a moment to hide everything before the child sees the page.
                cover.postDelayed({ cover.visibility = View.GONE }, 400)
            } else {
                cover.visibility = View.GONE
            }
        }

        /**
         * Fire tablets with little memory sometimes kill the page's renderer. Without this the
         * whole app would crash; instead build a fresh YouTube screen at the same page.
         */
        @android.annotation.TargetApi(26)
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            val url = view.url
            if (fullscreenView != null) exitFullscreen()
            root.removeView(view)
            bridge.shutdown()
            view.destroy()
            cover.visibility = View.VISIBLE
            setUpWebView()
            webView.loadUrl(url?.takeIf { FilterBridge.isYouTubeHost(FilterBridge.hostOf(it)) } ?: START_URL)
            return true
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
        if (FilterBridge.isYouTubeHost(host)) {
            if (blockGames && FilterBridge.isGamePath(uri.path)) {
                toast("Games are turned off")
                return false
            }
            return true
        }
        if (isSignInPage(host, uri.path ?: "")) return true
        toast("Links outside YouTube are turned off")
        return false
    }

    /** Google's sign-in hops across several hosts, including country domains like google.co.uk. */
    private fun isSignInPage(host: String, path: String): Boolean {
        if (host in SIGN_IN_HOSTS) return true
        if (host.startsWith("accounts.") && GOOGLE.matches(host.removePrefix("accounts."))) return true
        return GOOGLE.matches(host.removePrefix("www.")) && SIGN_IN_PATHS.any { path.startsWith(it) }
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

    /**
     * YouTube's own close button for a game doesn't always work inside an app, so a game always
     * gets one more way out: this button takes the child back to the YouTube home page.
     */
    private fun buildCloseGame(): TextView = TextView(this).apply {
        text = "✕  Close game"
        setTextColor(Color.WHITE)
        textSize = 16f
        setPadding(40, 24, 40, 24)
        setBackgroundColor(0xCC000000.toInt())
        visibility = View.GONE
        setOnClickListener {
            visibility = View.GONE
            webView.loadUrl(START_URL)
        }
    }

    private fun buildCover(): View = FrameLayout(this).apply {
        setBackgroundColor(Color.WHITE)
        addView(ProgressBar(this@MainActivity), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        isClickable = true
    }

    /** Holding the YouTube logo for three seconds lands here (see filter.js). */
    private fun openParent() {
        // Pass the page that's open so the parent can hide that video or channel in one tap.
        startActivity(
            Intent(this, ParentActivity::class.java).putExtra(ParentActivity.EXTRA_CURRENT_URL, webView.url)
        )
    }

    // ------------------------------------------------------------------ hold-to-block

    private fun onBlockRequest(req: FilterBridge.BlockRequest) {
        if (prefs.kidsCanBlock) {
            applyBlock(req, by = "child")
            return
        }
        // Hiding is parent-only in this mode: show the PIN pad, then hide it if the PIN was right.
        pendingBlock = req
        val v = req.video
        val json = JSONObject()
            .put("kind", if (req.channel) "channel" else "video")
            .put("title", v.title)
            .put("channel", v.channel)
            .toString()
        startActivityForResult(Intent(this, ParentActivity::class.java).putExtra(ParentActivity.EXTRA_BLOCK, json), REQ_BLOCK)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_BLOCK) return
        val req = pendingBlock
        pendingBlock = null
        if (resultCode == RESULT_OK && req != null) applyBlock(req, by = "parent")
    }

    private fun applyBlock(req: FilterBridge.BlockRequest, by: String) {
        val v = req.video
        if (!req.channel) {
            store.setOverride(v.id, Verdict.BLOCK, v.title.ifBlank { null }, by)
            bridge.hideNow(v.id)
            syncVersion()
            toast(if (by == "child") "Hidden. Only a grown-up can bring it back." else "Hidden.")
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
                webView.evaluateJavascript("window.__safetube && window.__safetube.reset(${bridge.config()})", null)
                toast("Everything from ${name.ifBlank { handle }} is hidden." +
                    if (by == "child") " Only a grown-up can bring it back." else "")
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
        const val ACTION_OPEN_PARENT = "app.safetube.OPEN_PARENT"
        private const val RESUME_WITHIN_MS = 2 * 60 * 60 * 1000L
        private const val REQ_BLOCK = 1
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private val SIGN_IN_HOSTS = setOf(
            "accounts.google.com", "accounts.youtube.com", "consent.youtube.com", "consent.google.com",
            "myaccount.google.com", "gds.google.com",
        )
        private val SIGN_IN_PATHS = listOf("/accounts", "/signin", "/ServiceLogin", "/sorry")
        private val GOOGLE = Regex("google\\.(com?\\.)?[a-z]{2,3}")
    }
}
