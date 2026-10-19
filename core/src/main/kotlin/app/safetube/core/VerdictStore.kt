package app.safetube.core

/**
 * Local memory of decisions, so a video is only ever sent to the AI once per set of rules.
 * The Android app backs this with SQLite; tests use [InMemoryVerdictStore].
 */
interface VerdictStore {
    /** A cached AI score for [id] made under the rules identified by [configHash]. */
    fun cachedScore(id: String, configHash: String): Score?
    fun saveScore(score: Score, configHash: String, video: VideoInfo)

    /** A parent's manual allow/block for [id], which beats everything else. */
    fun override(id: String): Verdict?
    fun setOverride(id: String, verdict: Verdict?)

    /** Remembers hidden videos so the parent can review them. */
    fun recordHidden(video: VideoInfo, decision: Decision)
}

class InMemoryVerdictStore : VerdictStore {
    val scores = HashMap<String, Score>()
    val overrides = HashMap<String, Verdict>()
    val hidden = ArrayList<Pair<VideoInfo, Decision>>()

    override fun cachedScore(id: String, configHash: String) = scores["$configHash/$id"]
    override fun saveScore(score: Score, configHash: String, video: VideoInfo) {
        scores["$configHash/${score.id}"] = score
    }
    override fun override(id: String) = overrides[id]
    override fun setOverride(id: String, verdict: Verdict?) {
        if (verdict == null) overrides.remove(id) else overrides[id] = verdict
    }
    override fun recordHidden(video: VideoInfo, decision: Decision) {
        hidden += video to decision
    }
}
