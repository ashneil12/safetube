package app.safetube.core

/** What the filter knows about one video, scraped from the YouTube page. */
data class VideoInfo(
    val id: String,
    val title: String = "",
    val channel: String = "",
    /** Any other text YouTube shows next to the video (byline, badges, description snippet). */
    val context: String = "",
) {
    val thumbnailUrl: String get() = "https://i.ytimg.com/vi/$id/mqdefault.jpg"

    /** All the text we have about the video, used for keyword matching. */
    val allText: String get() = listOf(title, channel, context).joinToString(" ")

    companion object {
        private val ID = Regex("^[A-Za-z0-9_-]{11}$")
        fun isValidId(id: String) = ID.matches(id)
    }
}

enum class Verdict(val wire: String) {
    ALLOW("allow"),
    BLOCK("block"),
    /** Could not decide (AI unreachable, bad key...). The video stays hidden. */
    UNKNOWN("unknown"),
}

/** Where a decision came from, shown to the parent so they can see why something was hidden. */
enum class DecisionSource { PARENT, KEYWORD, AI, NO_RULES, ERROR }

data class Decision(
    val id: String,
    val verdict: Verdict,
    val source: DecisionSource,
    /** Probability (0..1) that the video breaks the parent's rules, when the AI produced one. */
    val score: Double? = null,
    val reason: String = "",
)

/**
 * How cautious to be with the AI's probability.
 * Strict hides anything the model isn't fairly sure is fine.
 */
enum class Strictness(val blockAtOrAbove: Double) {
    STRICT(0.2),
    BALANCED(0.5),
    PERMISSIVE(0.8);

    fun verdictFor(score: Double): Verdict = if (score >= blockAtOrAbove) Verdict.BLOCK else Verdict.ALLOW
}

enum class ProviderType {
    /** Keyword list only; nothing leaves the device. */
    NONE,
    ANTHROPIC,
    /** OpenAI, OpenRouter, Groq, Together, Ollama, LM Studio and anything else that speaks /chat/completions. */
    OPENAI_COMPATIBLE,
}

data class ProviderConfig(
    val type: ProviderType = ProviderType.NONE,
    val apiKey: String = "",
    /** Base URL for OpenAI-compatible providers, e.g. https://openrouter.ai/api/v1 */
    val baseUrl: String = "",
    val model: String = "",
    val sendThumbnails: Boolean = true,
) {
    val isAiEnabled: Boolean
        get() = when (type) {
            ProviderType.NONE -> false
            ProviderType.ANTHROPIC -> apiKey.isNotBlank()
            // Local servers (Ollama etc.) often need no key.
            ProviderType.OPENAI_COMPATIBLE -> baseUrl.isNotBlank() && model.isNotBlank()
        }

    fun modelOrDefault(): String = model.ifBlank {
        when (type) {
            ProviderType.ANTHROPIC -> DEFAULT_ANTHROPIC_MODEL
            ProviderType.OPENAI_COMPATIBLE, ProviderType.NONE -> ""
        }
    }

    companion object {
        const val DEFAULT_ANTHROPIC_MODEL = "claude-haiku-5-5"
    }
}

data class FilterSettings(
    /** The parent's plain-English description of what to hide. */
    val rules: String = "",
    /** Words or phrases that always hide a video, no AI needed. */
    val blockedKeywords: List<String> = emptyList(),
    val strictness: Strictness = Strictness.STRICT,
    val provider: ProviderConfig = ProviderConfig(),
) {
    /**
     * Identifies everything that changes what the AI would say about a video.
     * Cached AI scores are only reused while this stays the same. Strictness is
     * deliberately left out: scores are cached and the threshold is applied on read.
     */
    fun aiConfigHash(): String = Hashing.sha256(
        listOf(
            rules.trim(),
            provider.type.name,
            provider.baseUrl.trim(),
            provider.modelOrDefault(),
            provider.sendThumbnails.toString(),
            PromptBuilder.VERSION.toString(),
        ).joinToString("\u0000")
    ).take(16)
}
