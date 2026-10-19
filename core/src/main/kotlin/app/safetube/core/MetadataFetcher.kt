package app.safetube.core

import org.json.JSONObject
import java.net.URLEncoder

/** Looks up a video's title and channel when the page didn't show them (direct links, autoplay). */
fun interface MetadataFetcher {
    fun fetch(id: String): VideoInfo?
}

/** YouTube's public oEmbed endpoint: no API key, returns title and channel name. */
object YouTubeOEmbed : MetadataFetcher {
    override fun fetch(id: String): VideoInfo? {
        val watch = URLEncoder.encode("https://www.youtube.com/watch?v=$id", "UTF-8")
        return try {
            val r = Http.request("https://www.youtube.com/oembed?format=json&url=$watch", timeoutMs = 10_000)
            if (r.code != 200) return null
            val json = JSONObject(r.body)
            VideoInfo(id = id, title = json.optString("title"), channel = json.optString("author_name"))
        } catch (e: Exception) {
            null
        }
    }
}
