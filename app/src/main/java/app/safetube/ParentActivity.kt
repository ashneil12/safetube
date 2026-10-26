package app.safetube

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import app.safetube.core.ChannelBlocklist
import app.safetube.core.DecisionEngine
import app.safetube.core.InMemoryVerdictStore
import app.safetube.core.Decision
import app.safetube.core.DecisionSource
import app.safetube.core.Classifiers
import app.safetube.core.KeywordFilter
import app.safetube.core.KeywordSuggester
import app.safetube.core.ProviderConfig
import app.safetube.core.ProviderType
import app.safetube.core.Strictness
import app.safetube.core.Verdict
import app.safetube.core.VideoInfo
import app.safetube.core.VideoLinks
import app.safetube.core.YouTubeOEmbed
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * The PIN, first-run setup and parent settings. The screens themselves are the page in
 * assets/parent/, shown in a WebView that can't load anything else; this class is the bridge
 * between that page and the app's settings and decision cache.
 */
class ParentActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var store: SqliteVerdictStore
    private lateinit var webView: WebView
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    /** Set once the PIN has been entered (or first created) in this screen. */
    @Volatile private var unlocked = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep settings (and API keys) out of screenshots and the recent-apps preview.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        prefs = Prefs(this)
        store = SqliteVerdictStore(this)

        val assets = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        webView = WebView(this)
        // The first screen is always the PIN pad, so match its colour while the page loads.
        webView.setBackgroundColor(Color.parseColor("#14182B"))
        webView.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
        }
        webView.addJavascriptInterface(Bridge(), "SafeTubeParent")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assets.shouldInterceptRequest(request.url)

            // The settings page never navigates; links out go through openLink().
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

            @Deprecated("Needed for API < 24")
            override fun shouldOverrideUrlLoading(view: WebView, url: String) = true
        }
        setContentView(webView)
        webView.loadUrl(PAGE_URL)
    }

    override fun onDestroy() {
        io.shutdownNow()
        webView.destroy()
        super.onDestroy()
    }

    private val mode: String
        get() = when {
            intent.hasExtra(EXTRA_BLOCK) -> "block"
            !prefs.hasPin -> "setup"
            else -> "unlock"
        }

    /** Everything the page can ask of the app. Every method runs on a WebView binder thread. */
    private inner class Bridge {
        @JavascriptInterface
        fun state(): String = JSONObject()
            .put("mode", mode)
            .put("pinLength", prefs.pinLength)
            .put("version", appVersion())
            .put("current", currentPage())
            .put("block", intent.getStringExtra(EXTRA_BLOCK)?.let { JSONObject(it) } ?: JSONObject.NULL)
            .put("settings", if (allowed()) settingsJson() else JSONObject.NULL)
            .put(
                "defaults", JSONObject()
                    .put("geminiBaseUrl", Prefs.GEMINI_BASE_URL)
                    .put("geminiModel", Prefs.GEMINI_MODEL)
                    .put(
                        "models", JSONObject()
                            .put("CLEF", ProviderConfig.DEFAULT_CLEF_MODEL)
                            .put("JEV", ProviderConfig.DEFAULT_JEV_MODEL)
                            .put("OPENROUTER_DECISIONS", ProviderConfig.DEFAULT_OPENROUTER_DECISION_MODEL)
                            .put("ANTHROPIC", ProviderConfig.DEFAULT_ANTHROPIC_MODEL)
                    )
            )
            .toString()

        @JavascriptInterface
        fun lists(): String {
            if (!allowed()) return "{}"
            val choices = store.parentChoices()
            val byChild = choices.filter { it.verdict == Verdict.BLOCK && it.by == "child" }.map { it.id }.toSet()
            return JSONObject()
                .put(
                    "stats", JSONObject()
                        .put("checkedToday", prefs.checkedToday())
                        .put("hiddenToday", store.hiddenSince(startOfToday()))
                        .put("watchedMin", prefs.watchedTodaySec / 60)
                        .put("limitMin", if (prefs.dailyLimitMin > 0) prefs.dailyLimitMin + prefs.extraTodayMin else 0)
                        .put("timeUp", prefs.timeUp() ?: JSONObject.NULL)
                )
                .put("history", JSONArray(store.history().map { h ->
                    JSONObject().put("id", h.id).put("title", h.title).put("channel", h.channel)
                        .put("channelId", h.channelId).put("ts", h.ts)
                }))
                .put("hidden", JSONArray(store.recentlyHidden().map { h ->
                    JSONObject().put("id", h.id).put("title", h.title).put("channel", h.channel)
                        .put("reason", if (h.id in byChild) "Set by child" else h.reason)
                }))
                .put("blocked", JSONArray(choices.filter { it.verdict == Verdict.BLOCK }.map { c ->
                    JSONObject().put("id", c.id).put("title", c.title).put("by", c.by)
                }))
                .put("allowed", JSONArray(choices.filter { it.verdict == Verdict.ALLOW }.map { c ->
                    JSONObject().put("id", c.id).put("title", c.title)
                }))
                .put("channels", JSONArray(prefs.blockedChannelList()))
                .toString()
        }

        @JavascriptInterface
        fun checkPin(pin: String): Boolean = prefs.checkPin(pin).also { if (it) unlocked = true }

        @JavascriptInterface
        fun setPin(pin: String) {
            // First-run setup, or a parent who already got past the PIN pad.
            if (!allowed() || pin.length !in 4..8 || !pin.all { it.isDigit() }) return
            prefs.setPin(pin)
            unlocked = true
        }

        @JavascriptInterface
        fun save(json: String) {
            if (!allowed()) return
            val o = JSONObject(json)
            prefs.rules = o.optString("rules").trim()
            prefs.allowedContent = o.optString("allowedContent").trim()
            prefs.exceptions = o.optString("exceptions").trim()
            prefs.blockOnAiFailure = o.optBoolean("blockOnAiFailure", true)
            o.optJSONObject("fallbackProvider")?.let { p ->
                prefs.fallbackProvider = ProviderConfig(enumOr(p.optString("type"), ProviderType.NONE),
                    p.optString("apiKey").trim(), p.optString("baseUrl").trim(), p.optString("model").trim(),
                    p.optString("accountId").trim(), p.optBoolean("sendThumbnails", true))
            }
            val words = o.optJSONArray("keywords") ?: JSONArray()
            prefs.keywords = (0 until words.length()).map { words.optString(it).trim() }.filter { it.isNotEmpty() }
                .distinct().joinToString(", ")
            prefs.strictness = enumOr(o.optString("strictness"), Strictness.STRICT)
            o.optJSONObject("provider")?.let { p ->
                prefs.providerType = enumOr(p.optString("type"), ProviderType.NONE)
                prefs.accountId = p.optString("accountId").trim()
                prefs.apiKey = p.optString("apiKey").trim()
                prefs.baseUrl = p.optString("baseUrl").trim()
                prefs.model = p.optString("model").trim()
                prefs.sendThumbnails = p.optBoolean("sendThumbnails", true)
            }
            o.optJSONObject("helper")?.let { h ->
                prefs.helperType = enumOr(h.optString("type"), ProviderType.NONE)
                prefs.helperKey = h.optString("apiKey").trim()
                prefs.helperBaseUrl = h.optString("baseUrl").trim()
                prefs.helperModel = h.optString("model").trim()
            }
            prefs.hideShorts = o.optBoolean("hideShorts", true)
            prefs.hideComments = o.optBoolean("hideComments", true)
            prefs.kidsCanBlock = o.optBoolean("kidsCanBlock", true)
            prefs.blockAds = o.optBoolean("blockAds", true)
            prefs.noAutoplay = o.optBoolean("noAutoplay", true)
            prefs.restrictedMode = o.optBoolean("restrictedMode", false)
            prefs.keepHistory = o.optBoolean("keepHistory", true)
            prefs.dailyLimitMin = o.optInt("dailyLimitMin", 0)
            prefs.bedtimeOn = o.optBoolean("bedtimeOn", false)
            prefs.bedtimeStart = o.optString("bedtimeStart", "19:30")
            prefs.bedtimeEnd = o.optString("bedtimeEnd", "07:00")
            if (!prefs.keepHistory) store.clearHistory()
            prefs.bumpVersion()
        }

        /** Slow work; the answer goes back to window.__parent.done(id, json). */
        @JavascriptInterface
        fun call(name: String, argsJson: String, id: Int) {
            if (!allowed()) return
            val args = try { JSONObject(argsJson) } catch (e: Exception) { JSONObject() }
            val version = prefs.version
            io.execute {
                val result = try {
                    when (name) {
                        "lookup" -> lookup(args.optString("id"))
                        "suggest" -> suggest(args)
                        "testAi" -> testAi()
                        "blockLink" -> blockLink(args.optString("text"))
                        else -> JSONObject().put("error", "Unknown request")
                    }
                } catch (e: Exception) {
                    JSONObject().put("error", e.message ?: e.toString())
                }
                main.post {
                    if (!allowed() || (name != "blockLink" && version != prefs.version) || isFinishing || isDestroyed) return@post
                    webView.evaluateJavascript("window.__parent.done($id, ${JSONObject.quote(result.toString())})", null)
                }
            }
        }

        @JavascriptInterface
        fun allow(id: String, title: String) = changed { store.setOverride(id, Verdict.ALLOW, title.ifBlank { null }, by = "parent") }

        @JavascriptInterface
        fun undo(id: String) = changed { store.setOverride(id, null) }

        @JavascriptInterface
        fun blockVideo(id: String, title: String) {
            if (VideoInfo.isValidId(id)) changed { store.setOverride(id, Verdict.BLOCK, title.ifBlank { null }, by = "parent") }
        }

        @JavascriptInterface
        fun blockChannel(entriesJson: String) {
            val entries = JSONArray(entriesJson)
            changed { (0 until entries.length()).map { entries.optString(it).trim() }.filter { it.isNotEmpty() }.forEach(prefs::addBlockedChannel) }
        }

        @JavascriptInterface
        fun unblockChannel(entry: String) = changed { prefs.removeBlockedChannel(entry) }

        /** "A few more minutes" from the parent, for the daily limit or bedtime. */
        @JavascriptInterface
        fun addTime(minutes: Int) = changed { prefs.addExtraMinutes(minutes.coerceIn(1, 240)) }

        @JavascriptInterface
        fun clearHistory() = changed { store.clearHistory() }

        @JavascriptInterface
        fun forgetAi() = changed { store.clearDecisions() }

        @JavascriptInterface
        fun openLink(url: String) {
            if (!allowed() || !url.startsWith("https://")) return
            main.post {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    // No browser on the tablet (or it's blocked); nothing to do.
                }
            }
        }

        @JavascriptInterface
        fun finishSetup() = main.post { finish() }

        @JavascriptInterface
        fun close() = main.post { finish() }

        /** The PIN was right for a hide request from the YouTube screen. */
        @JavascriptInterface
        fun confirmBlock() = main.post {
            if (mode == "block") setResult(RESULT_OK, Intent().putExtra(EXTRA_BLOCK, intent.getStringExtra(EXTRA_BLOCK)))
            finish()
        }
    }

    /** Nothing can be read or changed before the PIN is entered, except during first-run setup. */
    private fun allowed() = unlocked || !prefs.hasPin

    /** Applies a change and tells open YouTube pages to re-check what they show. */
    private fun changed(block: () -> Unit) {
        if (!allowed()) return
        block()
        prefs.bumpVersion()
    }

    private fun settingsJson() = JSONObject()
        .put("rules", prefs.rules)
        .put("allowedContent", prefs.allowedContent)
        .put("exceptions", prefs.exceptions)
        .put("blockOnAiFailure", prefs.blockOnAiFailure)
        .put("fallbackProvider", prefs.fallbackProvider.let { p -> JSONObject()
            .put("type", p.type.name).put("apiKey", p.apiKey).put("baseUrl", p.baseUrl)
            .put("model", p.model).put("accountId", p.accountId).put("sendThumbnails", p.sendThumbnails) })
        .put("keywords", JSONArray(KeywordFilter.parseList(prefs.keywords)))
        .put("strictness", prefs.strictness.name)
        .put(
            "provider", JSONObject()
                .put("type", prefs.providerType.name)
                .put("accountId", prefs.accountId)
                .put("apiKey", prefs.apiKey)
                .put("baseUrl", prefs.baseUrl)
                .put("model", prefs.model)
                .put("sendThumbnails", prefs.sendThumbnails)
        )
        .put(
            "helper", JSONObject()
                .put("type", prefs.helperType.name)
                .put("apiKey", prefs.helperKey)
                .put("baseUrl", prefs.helperBaseUrl)
                .put("model", prefs.helperModel)
        )
        .put("hideShorts", prefs.hideShorts)
        .put("hideComments", prefs.hideComments)
        .put("kidsCanBlock", prefs.kidsCanBlock)
        .put("blockAds", prefs.blockAds)
        .put("noAutoplay", prefs.noAutoplay)
        .put("restrictedMode", prefs.restrictedMode)
        .put("keepHistory", prefs.keepHistory)
        .put("dailyLimitMin", prefs.dailyLimitMin)
        .put("bedtimeOn", prefs.bedtimeOn)
        .put("bedtimeStart", prefs.bedtimeStart)
        .put("bedtimeEnd", prefs.bedtimeEnd)

    /** The video or channel that was open when the parent came in, if any. */
    private fun currentPage(): Any {
        val url = intent.getStringExtra(EXTRA_CURRENT_URL) ?: return JSONObject.NULL
        val videoId = VideoLinks.videoId(url)
        val channelRef = if (videoId == null) ChannelBlocklist.normalizeId(Uri.parse(url).path ?: "") else null
        if (videoId == null && channelRef == null) return JSONObject.NULL
        return JSONObject().put("videoId", videoId ?: JSONObject.NULL).put("channelRef", channelRef ?: JSONObject.NULL)
    }

    private fun lookup(id: String): JSONObject {
        if (!allowed() || !VideoInfo.isValidId(id)) return JSONObject().put("error", "Video unavailable")
        val info = YouTubeOEmbed.fetch(id)
        val savedInfo = store.videoInfo(id)
        val hidden = store.recentlyHidden().firstOrNull { it.id == id }
        val watched = store.history().firstOrNull { it.id == id }
        val choice = store.parentChoices().firstOrNull { it.id == id }
        val settings = prefs.filterSettings()
        val score = store.cachedScore(id, settings.aiConfigHash())
        val video = VideoInfo(id, info?.title?.ifBlank { savedInfo?.title ?: "" } ?: savedInfo?.title ?: hidden?.title ?: watched?.title ?: choice?.title ?: "",
            info?.channel?.ifBlank { savedInfo?.channel ?: "" } ?: savedInfo?.channel ?: hidden?.channel ?: watched?.channel ?: "",
            savedInfo?.context ?: "", info?.channelId?.ifBlank { savedInfo?.channelId ?: "" } ?: savedInfo?.channelId ?: watched?.channelId ?: "")
        val channelMatch = ChannelBlocklist(settings.blockedChannels).match(video)
        val keywordMatch = KeywordFilter(settings.blockedKeywords).firstMatch(video.allText)
        val local = when {
            choice != null -> Decision(id, choice.verdict, DecisionSource.PARENT, reason = "Set by ${choice.by}")
            channelMatch != null -> Decision(id, Verdict.BLOCK, DecisionSource.CHANNEL, reason = "Blocked channel: $channelMatch")
            keywordMatch != null -> Decision(id, Verdict.BLOCK, DecisionSource.KEYWORD, reason = "Keyword: $keywordMatch")
            else -> null
        }
        return JSONObject()
            .put("id", id)
            .put("description", savedInfo?.context?.takeIf { it.isNotBlank() } ?: "A full description is not available from YouTube’s public preview. Open the video on YouTube to inspect it.")
            .put("reason", local?.reason ?: score?.reason ?: hidden?.reason?.let { "Last recorded decision: $it" } ?: "No saved decision for these rules")
            .put("source", local?.source?.name ?: if (score != null) "AI" else hidden?.source ?: "")
            .put("score", if (local == null) score?.p ?: JSONObject.NULL else JSONObject.NULL)
            .put("threshold", settings.strictness.blockAtOrAbove)
            .put("verdict", local?.verdict?.wire ?: score?.let { settings.strictness.verdictFor(it.p).wire } ?: hidden?.let { "block" } ?: JSONObject.NULL)
            .put("title", video.title)
            .put("channel", video.channel)
            .put("channelId", video.channelId)
    }

    private fun suggest(args: JSONObject): JSONObject {
        val h = args.optJSONObject("helper") ?: JSONObject()
        val config = ProviderConfig(
            type = enumOr(h.optString("type"), ProviderType.NONE),
            apiKey = h.optString("apiKey").trim(),
            baseUrl = h.optString("baseUrl").trim(),
            model = h.optString("model").trim(),
        )
        if (config.type == ProviderType.NONE) return JSONObject().put("error", "Set up the keyword helper first.")
        val words = args.optJSONArray("keywords") ?: JSONArray()
        val existing = (0 until words.length()).map { words.optString(it) }
        val added = KeywordSuggester(config).suggest(args.optString("rules"), existing)
        return JSONObject().put("keywords", JSONArray(added))
    }

    /** Runs the saved rules against two example videos so the parent can see it working. */
    private fun testAi(): JSONObject {
        val settings = prefs.filterSettings()
        if (!settings.provider.isAiEnabled && !settings.fallbackProvider.isAiEnabled) {
            return JSONObject().put("error", "Choose a checker and enter its API key before testing.")
        }
        val samples = listOf(
            VideoInfo("aaaaaaaaaaa", "Five Little Pumpkins | Halloween Song for Kids", "Sing Along Street"),
            VideoInfo("bbbbbbbbbbb", "Counting to 20 with Building Blocks", "Number Garden"),
        )
        val testSettings = settings.copy(
                rules = if (settings.effectiveRules().isBlank()) "No content restrictions have been set." else settings.rules,
                blockedKeywords = emptyList(), blockedChannels = emptyList(),
                provider = settings.provider.copy(sendThumbnails = false),
                fallbackProvider = settings.fallbackProvider.copy(sendThumbnails = false))
        val decisions = DecisionEngine(InMemoryVerdictStore(), metadata = null).decide(samples, testSettings).associateBy { it.id }
        val error = decisions.values.firstOrNull { it.verdict == Verdict.UNKNOWN }
        if (error != null || decisions.size != samples.size || decisions.values.any { !it.freshAi }) {
            return JSONObject().put("error", error?.reason ?: "The checker did not return an answer for every test video.")
        }
        return JSONObject().put("results", JSONArray(samples.map { v ->
            val decision = decisions[v.id]
            JSONObject()
                .put("title", v.title)
                .put("hidden", decision?.verdict != Verdict.ALLOW)
                .put("reason", decision?.reason ?: "No answer")
        }))
    }

    /** A pasted video link hides that video; a channel link, @handle or name hides the channel. */
    private fun blockLink(text: String): JSONObject {
        val t = text.trim()
        val videoId = if (ChannelBlocklist.isChannelLink(t)) null else VideoLinks.videoId(t)
        if (videoId != null) {
            val title = YouTubeOEmbed.fetch(videoId)?.title
            changed { store.setOverride(videoId, Verdict.BLOCK, title, by = "parent") }
            return JSONObject().put("ok", true).put("message", "${title?.let { "“$it”" } ?: "That video"} is now always hidden.")
        }
        val channel = ChannelBlocklist.parse(t)
            ?: return JSONObject().put("ok", false).put("message", "That doesn’t look like a YouTube video or channel.")
        changed { prefs.addBlockedChannel(channel) }
        return JSONObject().put("ok", true).put("message", "$channel is now hidden.")
    }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun startOfToday(): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    companion object {
        const val EXTRA_SETUP = "setup"
        const val EXTRA_CURRENT_URL = "currentUrl"
        /** JSON of a hide request from the YouTube screen that needs the parent's PIN. */
        const val EXTRA_BLOCK = "block"
        private const val PAGE_URL = "https://appassets.androidplatform.net/assets/parent/index.html"
    }
}
