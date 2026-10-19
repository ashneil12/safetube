package app.safetube.core

/**
 * Channels the parent never wants shown, whatever the rules say.
 * Each entry is a handle ("@SuperSimpleSongs"), a channel id ("UC..."), or a plain channel name.
 */
class ChannelBlocklist(entries: List<String>) {
    private val ids = HashSet<String>()
    private val names = ArrayList<Pair<String, Regex>>()

    init {
        for (raw in entries) {
            val ref = parse(raw) ?: continue
            if (HANDLE.matches(ref) || CHANNEL_ID.matches(ref)) ids += ref.lowercase()
            else names += ref to Regex(
                "(?<![\\p{L}\\p{N}])" + ref.split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it) } + "(?![\\p{L}\\p{N}])",
                RegexOption.IGNORE_CASE,
            )
        }
    }

    /** Returns the matching entry if [video]'s channel is blocked. */
    fun match(video: VideoInfo): String? {
        val cid = normalizeId(video.channelId)
        if (cid != null && cid.lowercase() in ids) return cid
        // Names are matched against the channel line only, never the title.
        return names.firstOrNull { it.second.containsMatchIn(video.channel) }?.first
    }

    val isEmpty: Boolean get() = ids.isEmpty() && names.isEmpty()

    companion object {
        private val HANDLE = Regex("@[A-Za-z0-9._-]{1,100}")
        private val CHANNEL_ID = Regex("UC[A-Za-z0-9_-]{22}")

        /**
         * Turns whatever a parent pastes (a channel link, a handle, an id or a name) into the stored
         * form: "@handle", "UC..." or the trimmed name. Returns null for blank input.
         */
        fun parse(input: String): String? {
            val s = input.trim()
            if (s.isEmpty()) return null
            Regex("/channel/(UC[A-Za-z0-9_-]{22})").find(s)?.let { return it.groupValues[1] }
            Regex("youtube\\.com/(@[A-Za-z0-9._-]+)").find(s)?.let { return it.groupValues[1] }
            return s
        }

        /** "@handle" or "UC..." from a page link like "/@handle/videos" or "/channel/UC.../featured". */
        fun normalizeId(link: String): String? {
            if (link.isBlank()) return null
            Regex("(?:^|/)(@[A-Za-z0-9._-]+)").find(link)?.let { return it.groupValues[1] }
            Regex("(?:^|/)(UC[A-Za-z0-9_-]{22})").find(link)?.let { return it.groupValues[1] }
            return null
        }

        /** True if [input] looks like a link or id rather than a plain channel name. */
        fun isChannelLink(input: String): Boolean {
            val s = input.trim()
            return s.contains("/channel/") || s.contains("youtube.com/@") || HANDLE.matches(s) || CHANNEL_ID.matches(s)
        }
    }
}

/** Pulls a video id out of anything a parent might paste: a watch link, a short link, a Shorts link or a bare id. */
object VideoLinks {
    private val PATTERNS = listOf(
        Regex("[?&]v=([A-Za-z0-9_-]{11})"),
        Regex("youtu\\.be/([A-Za-z0-9_-]{11})"),
        Regex("/(?:shorts|live|embed)/([A-Za-z0-9_-]{11})"),
    )

    fun videoId(input: String): String? {
        val s = input.trim()
        if (VideoInfo.isValidId(s)) return s
        return PATTERNS.firstNotNullOfOrNull { it.find(s)?.groupValues?.get(1) }
    }
}
