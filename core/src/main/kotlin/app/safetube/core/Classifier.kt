package app.safetube.core

/** The AI's opinion of one video: probability [p] that it breaks the parent's rules. */
data class Score(val id: String, val p: Double, val reason: String)

class ClassifierException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The one thing every AI provider has to do. Swap providers by swapping implementations;
 * nothing else in the app knows which model is behind it.
 */
interface VideoClassifier {
    /** Scores a batch of videos. May return fewer scores than videos if the model skipped some. */
    @Throws(ClassifierException::class)
    fun classify(videos: List<VideoInfo>, rules: String): List<Score>
}

object Classifiers {
    fun create(config: ProviderConfig): VideoClassifier? = when (config.type) {
        ProviderType.NONE -> null
        ProviderType.CLEF, ProviderType.JEV -> DecisionModelClassifier(config)
        ProviderType.ANTHROPIC -> AnthropicClassifier(config)
        ProviderType.OPENAI_COMPATIBLE -> OpenAiCompatibleClassifier(config)
    }
}
