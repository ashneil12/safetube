package app.safetube

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import app.safetube.core.Decision
import app.safetube.core.DecisionEngine
import app.safetube.core.ChannelBlocklist
import app.safetube.core.DecisionSource
import app.safetube.core.Verdict
import app.safetube.core.VideoInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The page script's only way to reach the app. It sends batches of videos it found on the page;
 * the app decides off the main thread and answers with allow/block/unknown per video id.
 */
class FilterBridge(
    private val webView: WebView,
    private val prefs: Prefs,
    private val engine: DecisionEngine,
    private val onAiError: (String) -> Unit,
    /** Called on the main thread when someone uses the hold-to-block menu. */
    private val onBlockRequest: (BlockRequest) -> Unit,
    /** Called on the main thread when the YouTube logo is held long enough. */
    private val onOpenParent: () -> Unit,
    /** Called (off the main thread) when the child has been watching a video for a little while. */
    private val onWatched: (SqliteVerdictStore.Watched) -> Unit,
) {
    data class BlockRequest(val channel: Boolean, val video: VideoInfo)

    private val main = Handler(Looper.getMainLooper())
    private val pool: ExecutorService = Executors.newFixedThreadPool(2)

    /** Host of the page currently loaded, kept up to date by the activity. */
    @Volatile var currentHost: String = ""

    private val stateLock = Any()
    private var settingsVersion = -1
    private var failureRevision = 0L
    private var aiUnavailable = false
    @Volatile private var pageGeneration = 0L

    fun pageStarted() { pageGeneration++ }

    private fun refreshSettings() = synchronized(stateLock) {
        if (settingsVersion != prefs.version) {
            settingsVersion = prefs.version
            aiUnavailable = prefs.aiUnavailable
            failureRevision++
        }
    }

    @JavascriptInterface
    fun config(): String {
        refreshSettings()
        val settings = prefs.filterSettings()
        val requiresAi = settings.allowedContent.isNotBlank() ||
            (settings.provider.type != app.safetube.core.ProviderType.NONE && !settings.provider.isAiEnabled) ||
            (settings.effectiveRules().isNotBlank() && (settings.provider.type != app.safetube.core.ProviderType.NONE || settings.fallbackProvider.type != app.safetube.core.ProviderType.NONE))
        if (requiresAi && !settings.provider.isAiEnabled && !settings.fallbackProvider.isAiEnabled && settings.blockOnAiFailure) {
            synchronized(stateLock) { aiUnavailable = true; prefs.aiUnavailable = true }
        }
        return JSONObject(prefs.pageConfigJson()).put("aiUnavailable", synchronized(stateLock) { aiUnavailable }).toString()
    }

    fun settingsChanged() { refreshSettings() }

    fun reapplyAiLock() {
        refreshSettings()
        val locked = synchronized(stateLock) { aiUnavailable }
        webView.evaluateJavascript("window.__safetube && window.__safetube.setAiUnavailable($locked)", null)
    }

    @JavascriptInterface
    fun watched(json: String) {
        if (!isYouTubeHost(currentHost) || !prefs.keepHistory) return
        val o = try { JSONObject(json) } catch (e: Exception) { return }
        val id = o.optString("id")
        if (!VideoInfo.isValidId(id)) return
        onWatched(
            SqliteVerdictStore.Watched(
                id, o.optString("title").take(300), o.optString("channel").take(120),
                ChannelBlocklist.normalizeId(o.optString("channelId")) ?: "", System.currentTimeMillis(),
            )
        )
    }

    @JavascriptInterface
    fun openParent() {
        if (!isYouTubeHost(currentHost)) return
        main.post(onOpenParent)
    }

    @JavascriptInterface
    fun submit(json: String) {
        // Only YouTube pages may ask; the WebView never loads anything else, but be sure.
        if (!isYouTubeHost(currentHost)) return
        val videos = parse(json)
        if (videos.isEmpty()) return
        refreshSettings()
        val version = prefs.version
        val generation = pageGeneration
        val settings = prefs.filterSettings()
        val startFailure = synchronized(stateLock) { failureRevision }
        val retry = synchronized(stateLock) { aiUnavailable }
        pool.execute {
            var hadFailure = false
            var hadFreshAi = false
            val send: (List<Decision>) -> Unit = { decisions ->
                synchronized(stateLock) {
                    if (version == prefs.version && generation == pageGeneration) {
                        hadFreshAi = hadFreshAi || decisions.any { it.freshAi }
                        // Only a checker that is down pauses YouTube; one unreadable video just stays hidden.
                        if (decisions.any { it.checkerFailed }) {
                            hadFailure = true
                            if (settings.blockOnAiFailure) {
                                aiUnavailable = true
                                prefs.aiUnavailable = true
                                failureRevision++
                            }
                        }
                    }
                }
                if (version == prefs.version && generation == pageGeneration) {
                    decisions.firstOrNull { it.checkerFailed }?.let { onAiError(it.reason) }
                    prefs.recordChecked(decisions.count { it.verdict != Verdict.UNKNOWN })
                    val result = JSONObject()
                    decisions.forEach { result.put(it.id, it.verdict.wire) }
                    main.post {
                        if (version == prefs.version && generation == pageGeneration) {
                            reapplyAiLock()
                            webView.evaluateJavascript("window.__safetube && window.__safetube.apply($result)", null)
                        }
                    }
                }
            }
            try {
                // During an outage, retry fresh checks; cached/local allowances cannot restore access.
                engine.decide(videos, settings, bypassCache = retry, emit = send)
            } catch (e: Exception) {
                send(videos.map { Decision(it.id, Verdict.UNKNOWN, DecisionSource.ERROR, reason = e.toString(), checkerFailed = true) })
            }
            if (retry && !hadFailure && !hadFreshAi && version == prefs.version && generation == pageGeneration) {
                hadFreshAi = engine.probeAiAvailability(videos, settings)
                if (!hadFreshAi) {
                    synchronized(stateLock) {
                        if (version == prefs.version && generation == pageGeneration) {
                            hadFailure = true
                            aiUnavailable = true
                            prefs.aiUnavailable = true
                            failureRevision++
                        }
                    }
                }
            }
            synchronized(stateLock) {
                if (version == prefs.version && generation == pageGeneration && !hadFailure && hadFreshAi && failureRevision == startFailure) {
                    aiUnavailable = false
                    prefs.aiUnavailable = false
                }
            }
            main.post { if (version == prefs.version && generation == pageGeneration) reapplyAiLock() }
        }
    }

    @JavascriptInterface
    fun block(json: String) {
        if (!isYouTubeHost(currentHost)) return
        val o = try { JSONObject(json) } catch (e: Exception) { return }
        val video = VideoInfo(
            id = o.optString("id"),
            title = o.optString("title"),
            channel = o.optString("channel"),
            channelId = ChannelBlocklist.normalizeId(o.optString("channelId")) ?: "",
        )
        if (!VideoInfo.isValidId(video.id)) return
        val request = BlockRequest(channel = o.optString("kind") == "channel", video = video)
        main.post { onBlockRequest(request) }
    }

    /** Hides one video on the open page straight away. */
    fun hideNow(id: String) {
        webView.evaluateJavascript("window.__safetube && window.__safetube.apply({\"$id\":\"block\"})", null)
    }

    fun shutdown() = pool.shutdownNow()

    private fun parse(json: String): List<VideoInfo> = try {
        val arr = JSONArray(json)
        (0 until minOf(arr.length(), 200)).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            VideoInfo(
                id = o.optString("id"),
                title = o.optString("title"),
                channel = o.optString("channel"),
                context = o.optString("context"),
                channelId = ChannelBlocklist.normalizeId(o.optString("channelId")) ?: "",
            ).takeIf { VideoInfo.isValidId(it.id) }
        }
    } catch (e: Exception) {
        emptyList()
    }

    companion object {
        fun isYouTubeHost(host: String?): Boolean {
            val h = host?.lowercase() ?: return false
            return h == "youtube.com" || h.endsWith(".youtube.com")
        }

        /** YouTube's built-in games live under /playables. */
        fun isGamePath(path: String?): Boolean = path?.lowercase()?.startsWith("/playables") == true

        fun hostOf(url: String?): String = url?.let { Uri.parse(it).host } ?: ""
    }
}
