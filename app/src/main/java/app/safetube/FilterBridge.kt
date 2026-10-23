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

    @JavascriptInterface
    fun config(): String = prefs.pageConfigJson()

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
        val settings = prefs.filterSettings()
        pool.execute {
            val send: (List<Decision>) -> Unit = { decisions ->
                decisions.firstOrNull { it.source == DecisionSource.ERROR }?.let { onAiError(it.reason) }
                prefs.recordChecked(decisions.count { it.verdict != Verdict.UNKNOWN })
                val result = JSONObject()
                decisions.forEach { result.put(it.id, it.verdict.wire) }
                main.post {
                    webView.evaluateJavascript("window.__safetube && window.__safetube.apply($result)", null)
                }
            }
            try {
                // Keyword and cached answers reach the page at once; AI answers follow batch by batch.
                engine.decide(videos, settings, send)
            } catch (e: Exception) {
                send(videos.map { Decision(it.id, Verdict.UNKNOWN, DecisionSource.ERROR, reason = e.toString()) })
            }
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

        fun hostOf(url: String?): String = url?.let { Uri.parse(it).host } ?: ""
    }
}
