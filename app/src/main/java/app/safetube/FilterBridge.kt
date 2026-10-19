package app.safetube

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import app.safetube.core.Decision
import app.safetube.core.DecisionEngine
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
) {
    private val main = Handler(Looper.getMainLooper())
    private val pool: ExecutorService = Executors.newFixedThreadPool(2)

    /** Host of the page currently loaded, kept up to date by the activity. */
    @Volatile var currentHost: String = ""

    @JavascriptInterface
    fun config(): String = prefs.pageConfigJson()

    @JavascriptInterface
    fun submit(json: String) {
        // Only YouTube pages may ask; the WebView never loads anything else, but be sure.
        if (!isYouTubeHost(currentHost)) return
        val videos = parse(json)
        if (videos.isEmpty()) return
        val settings = prefs.filterSettings()
        pool.execute {
            val decisions = try {
                engine.decide(videos, settings)
            } catch (e: Exception) {
                videos.map { Decision(it.id, Verdict.UNKNOWN, DecisionSource.ERROR, reason = e.toString()) }
            }
            decisions.firstOrNull { it.source == DecisionSource.ERROR }?.let { onAiError(it.reason) }
            val result = JSONObject()
            decisions.forEach { result.put(it.id, it.verdict.wire) }
            main.post {
                webView.evaluateJavascript("window.__safetube && window.__safetube.apply($result)", null)
            }
        }
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
