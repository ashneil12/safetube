package app.safetube

import android.net.Uri

/**
 * Ad and tracking addresses the YouTube screen never needs. Video ads themselves are removed
 * by filter.js; this stops the ad servers, ad reporting and trackers from loading at all.
 */
object AdBlocklist {
    private val HOSTS = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com",
        "googletagmanager.com", "googletagservices.com", "adservice.google.com", "imasdk.googleapis.com",
        "ads.youtube.com",
    )

    // Paths on YouTube's own servers that only serve or report ads.
    private val YOUTUBE_PATHS = listOf(
        "/api/stats/ads", "/pagead/", "/get_midroll_info", "/ptracking", "/youtubei/v1/log_event",
    )

    fun blocks(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        if (HOSTS.any { host == it || host.endsWith(".$it") }) return true
        if (FilterBridge.isYouTubeHost(host)) {
            val path = uri.path ?: return false
            return YOUTUBE_PATHS.any { path.startsWith(it) }
        }
        return false
    }
}
