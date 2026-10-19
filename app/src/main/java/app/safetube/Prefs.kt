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

    var sendThumbnails: Boolean
        get() = sp.getBoolean("thumbs", true)
        set(v) = sp.edit().putBoolean("thumbs", v).apply()

    var hideShorts: Boolean
        get() = sp.getBoolean("hideShorts", true)
        set(v) = sp.edit().putBoolean("hideShorts", v).apply()

    var hideComments: Boolean
        get() = sp.getBoolean("hideComments", true)
        set(v) = sp.edit().putBoolean("hideComments", v).apply()

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
        sp.edit().putString("pinSalt", salt).putString("pinHash", Hashing.pinHash(pin, salt)).apply()
    }

    fun checkPin(pin: String): Boolean {
        val salt = sp.getString("pinSalt", null) ?: return false
        return sp.getString("pinHash", null) == Hashing.pinHash(pin, salt)
    }

    // ---- what the engine and the page need

    fun filterSettings() = FilterSettings(
        rules = rules,
        blockedKeywords = KeywordFilter.parseList(keywords),
        strictness = strictness,
        provider = ProviderConfig(
            type = providerType,
            apiKey = apiKey,
            baseUrl = baseUrl,
            model = model,
            sendThumbnails = sendThumbnails,
        ),
    )

    fun pageConfigJson(): String = JSONObject()
        .put("hideShorts", hideShorts)
        .put("hideComments", hideComments)
        .put("debug", BuildConfigFlags.DEBUG)
        .toString()

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: default

    companion object {
        const val DEFAULT_RULES =
            "Nothing involving witches, witchcraft, spells or potions, Halloween, ghosts, demons, " +
                "zombies, skeletons, monsters meant to be scary, or anything occult or designed to frighten " +
                "children. Ordinary fairy tales and silly cartoon fun are fine."
        const val DEFAULT_KEYWORDS = "halloween, witch, witchcraft, ghost, zombie, demon, spooky, trick or treat"
    }
}

/** Debug logging in the page script; off for release builds. */
internal object BuildConfigFlags {
    const val DEBUG = false
}
