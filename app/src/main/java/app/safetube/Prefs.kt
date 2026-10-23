package app.safetube

import android.content.Context
import android.content.SharedPreferences
import app.safetube.core.FilterSettings
import app.safetube.core.Hashing
import app.safetube.core.KeywordFilter
import app.safetube.core.ProviderConfig
import app.safetube.core.ProviderType
import app.safetube.core.Strictness
import org.json.JSONObject

/** Everything the parent configures, stored privately on the device. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("safetube", Context.MODE_PRIVATE)

    var rules: String
        get() = sp.getString("rules", DEFAULT_RULES) ?: DEFAULT_RULES
        set(v) = sp.edit().putString("rules", v).apply()

    var keywords: String
        get() = sp.getString("keywords", DEFAULT_KEYWORDS) ?: DEFAULT_KEYWORDS
        set(v) = sp.edit().putString("keywords", v).apply()

    var strictness: Strictness
        get() = enumOr(sp.getString("strictness", null), Strictness.STRICT)
        set(v) = sp.edit().putString("strictness", v.name).apply()

    var providerType: ProviderType
        get() = enumOr(sp.getString("provider", null), ProviderType.NONE)
        set(v) = sp.edit().putString("provider", v.name).apply()

    var apiKey: String
        get() = sp.getString("apiKey", "") ?: ""
        set(v) = sp.edit().putString("apiKey", v).apply()

    var baseUrl: String
        get() = sp.getString("baseUrl", "https://openrouter.ai/api/v1") ?: ""
        set(v) = sp.edit().putString("baseUrl", v).apply()

    var model: String
        get() = sp.getString("model", "") ?: ""
        set(v) = sp.edit().putString("model", v).apply()

    var accountId: String
        get() = sp.getString("accountId", "") ?: ""
        set(v) = sp.edit().putString("accountId", v).apply()

    /** One entry per line: "@handle", "UC..." or a channel name. */
    var blockedChannels: String
        get() = sp.getString("blockedChannels", "") ?: ""
        set(v) = sp.edit().putString("blockedChannels", v).apply()

    fun blockedChannelList(): List<String> = blockedChannels.lines().map { it.trim() }.filter { it.isNotEmpty() }

    fun addBlockedChannel(entry: String) {
        val list = blockedChannelList()
        if (list.none { it.equals(entry, ignoreCase = true) }) blockedChannels = (list + entry).joinToString("\n")
    }

    fun removeBlockedChannel(entry: String) {
        blockedChannels = blockedChannelList().filterNot { it.equals(entry, ignoreCase = true) }.joinToString("\n")
    }

    // ---- the chat model that suggests keywords from the rules (can differ from the video checker)

    var helperType: ProviderType
        get() = enumOr(sp.getString("helperType", null), ProviderType.NONE)
        set(v) = sp.edit().putString("helperType", v.name).apply()

    var helperKey: String
        get() = sp.getString("helperKey", "") ?: ""
        set(v) = sp.edit().putString("helperKey", v).apply()

    var helperBaseUrl: String
        get() = sp.getString("helperBaseUrl", GEMINI_BASE_URL) ?: ""
        set(v) = sp.edit().putString("helperBaseUrl", v).apply()

    var helperModel: String
        get() = sp.getString("helperModel", GEMINI_MODEL) ?: ""
        set(v) = sp.edit().putString("helperModel", v).apply()

    fun helperConfig() = ProviderConfig(
        type = helperType, apiKey = helperKey, baseUrl = helperBaseUrl, model = helperModel,
    )

    var sendThumbnails: Boolean
        get() = sp.getBoolean("thumbs", true)
        set(v) = sp.edit().putBoolean("thumbs", v).apply()

    var hideShorts: Boolean
        get() = sp.getBoolean("hideShorts", true)
        set(v) = sp.edit().putBoolean("hideShorts", v).apply()

    /** Let the child hide videos and channels from the tablet themselves. Only the parent can undo it. */
    var kidsCanBlock: Boolean
        get() = sp.getBoolean("kidsCanBlock", true)
        set(v) = sp.edit().putBoolean("kidsCanBlock", v).apply()

    var blockAds: Boolean
        get() = sp.getBoolean("blockAds", true)
        set(v) = sp.edit().putBoolean("blockAds", v).apply()

    var hideComments: Boolean
        get() = sp.getBoolean("hideComments", true)
        set(v) = sp.edit().putBoolean("hideComments", v).apply()

    /** Stop YouTube moving on to another video by itself when one ends. */
    var noAutoplay: Boolean
        get() = sp.getBoolean("noAutoplay", true)
        set(v) = sp.edit().putBoolean("noAutoplay", v).apply()

    /** YouTube's Restricted Mode, set only in this app's own browser: never on the Google account. */
    var restrictedMode: Boolean
        get() = sp.getBoolean("restrictedMode", false)
        set(v) = sp.edit().putBoolean("restrictedMode", v).apply()

    /** Keep a list of watched videos for the parent. Stays on this device. */
    var keepHistory: Boolean
        get() = sp.getBoolean("keepHistory", true)
        set(v) = sp.edit().putBoolean("keepHistory", v).apply()

    // ---- screen time

    /** Minutes of YouTube a day; 0 means no limit. */
    var dailyLimitMin: Int
        get() = sp.getInt("dailyLimitMin", 0)
        set(v) = sp.edit().putInt("dailyLimitMin", v.coerceIn(0, 24 * 60)).apply()

    var bedtimeOn: Boolean
        get() = sp.getBoolean("bedtimeOn", false)
        set(v) = sp.edit().putBoolean("bedtimeOn", v).apply()

    /** "HH:MM", 24-hour. YouTube is off from bedtimeStart until bedtimeEnd. */
    var bedtimeStart: String
        get() = sp.getString("bedtimeStart", "19:30") ?: "19:30"
        set(v) = sp.edit().putString("bedtimeStart", validTime(v, "19:30")).apply()

    var bedtimeEnd: String
        get() = sp.getString("bedtimeEnd", "07:00") ?: "07:00"
        set(v) = sp.edit().putString("bedtimeEnd", validTime(v, "07:00")).apply()

    /** Seconds watched today. */
    val watchedTodaySec: Int get() = if (sp.getInt("watchedDay", 0) == dayKey()) sp.getInt("watchedSec", 0) else 0

    @Synchronized
    fun addWatched(seconds: Int) {
        sp.edit().putInt("watchedDay", dayKey()).putInt("watchedSec", watchedTodaySec + seconds).apply()
    }

    /** Extra minutes a parent granted today on top of the daily limit. */
    val extraTodayMin: Int get() = if (sp.getInt("extraDay", 0) == dayKey()) sp.getInt("extraMin", 0) else 0

    /** A parent's "a few more minutes": works for both the daily limit and bedtime. */
    fun addExtraMinutes(minutes: Int) {
        val now = System.currentTimeMillis()
        sp.edit().putInt("extraDay", dayKey()).putInt("extraMin", extraTodayMin + minutes)
            .putLong("bedtimeExtendedUntil", maxOf(now, bedtimeExtendedUntil) + minutes * 60_000L).apply()
    }

    /** Bedtime is paused until this time (ms) after a parent gives a few more minutes. */
    var bedtimeExtendedUntil: Long
        get() = sp.getLong("bedtimeExtendedUntil", 0)
        set(v) = sp.edit().putLong("bedtimeExtendedUntil", v).apply()

    /** Why YouTube should be closed right now, or null if it's fine to watch. */
    fun timeUp(now: java.util.Calendar = java.util.Calendar.getInstance()): String? {
        if (bedtimeOn && now.timeInMillis > bedtimeExtendedUntil && inBedtime(now)) return "bedtime"
        val limit = dailyLimitMin
        if (limit > 0 && watchedTodaySec >= (limit + extraTodayMin) * 60) return "limit"
        return null
    }

    private fun inBedtime(now: java.util.Calendar): Boolean {
        val m = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        val start = minutes(bedtimeStart)
        val end = minutes(bedtimeEnd)
        return if (start <= end) m in start until end else m >= start || m < end
    }

    private fun minutes(hhmm: String): Int = hhmm.split(":").let { it[0].toInt() * 60 + it[1].toInt() }

    private fun validTime(v: String, fallback: String): String =
        if (Regex("([01]\\d|2[0-3]):[0-5]\\d").matches(v.trim())) v.trim() else fallback

    /** The page the child was on, so a relaunch carries on instead of starting over. */
    val lastUrl: String? get() = sp.getString("lastUrl", null)
    val lastUrlAt: Long get() = sp.getLong("lastUrlAt", 0)

    fun rememberPage(url: String) {
        sp.edit().putString("lastUrl", url).putLong("lastUrlAt", System.currentTimeMillis()).apply()
    }

    /** Bumped whenever settings change so open pages re-check what they show. */
    var version: Int
        get() = sp.getInt("version", 0)
        private set(v) = sp.edit().putInt("version", v).apply()

    fun bumpVersion() {
        version += 1
    }

    // ---- PIN

    val hasPin: Boolean get() = sp.getString("pinHash", null) != null

    fun setPin(pin: String) {
        val salt = Hashing.newSalt()
        sp.edit().putString("pinSalt", salt).putString("pinHash", Hashing.pinHash(pin, salt))
            .putInt("pinLength", pin.length).apply()
    }

    /** Length of the PIN, so the keypad knows when to say it's wrong. 0 for PINs set before this was kept. */
    val pinLength: Int get() = sp.getInt("pinLength", 0)

    fun checkPin(pin: String): Boolean {
        val salt = sp.getString("pinSalt", null) ?: return false
        return sp.getString("pinHash", null) == Hashing.pinHash(pin, salt)
    }

    // ---- the overview's "videos checked today" count

    @Synchronized
    fun recordChecked(count: Int) {
        val today = dayKey()
        val sofar = if (sp.getInt("checkedDay", 0) == today) sp.getInt("checkedCount", 0) else 0
        sp.edit().putInt("checkedDay", today).putInt("checkedCount", sofar + count).apply()
    }

    fun checkedToday(): Int = if (sp.getInt("checkedDay", 0) == dayKey()) sp.getInt("checkedCount", 0) else 0

    private fun dayKey(): Int = java.util.Calendar.getInstance().let {
        it.get(java.util.Calendar.YEAR) * 1000 + it.get(java.util.Calendar.DAY_OF_YEAR)
    }

    // ---- what the engine and the page need

    fun filterSettings() = FilterSettings(
        rules = rules,
        blockedKeywords = KeywordFilter.parseList(keywords),
        blockedChannels = blockedChannelList(),
        strictness = strictness,
        provider = ProviderConfig(
            type = providerType,
            apiKey = apiKey,
            baseUrl = baseUrl,
            model = model,
            accountId = accountId,
            sendThumbnails = sendThumbnails,
        ),
    )

    fun pageConfigJson(): String = JSONObject()
        .put("hideShorts", hideShorts)
        .put("hideComments", hideComments)
        .put("blockMenu", true)
        .put("kidsCanBlock", kidsCanBlock)
        .put("blockAds", blockAds)
        .put("noAutoplay", noAutoplay)
        .put("keepHistory", keepHistory)
        .put("debug", BuildConfigFlags.DEBUG)
        .toString()

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: default

    companion object {
        const val DEFAULT_RULES =
            "Nothing involving witches, witchcraft, spells or potions, Halloween, ghosts, demons, " +
                "zombies, skeletons, monsters meant to be scary, or anything occult or designed to frighten " +
                "children. Ordinary fairy tales and silly cartoon fun are fine."
        const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai"
        const val GEMINI_MODEL = "gemini-2.5-flash"
        const val DEFAULT_KEYWORDS = "halloween, witch, witchcraft, ghost, zombie, demon, spooky, trick or treat"
    }
}

/** Debug logging in the page script; off for release builds. */
internal object BuildConfigFlags {
    const val DEBUG = false
}
