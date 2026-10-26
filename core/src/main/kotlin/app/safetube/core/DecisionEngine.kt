package app.safetube.core

import java.util.concurrent.ExecutorService

/**
 * Decides, for each video, whether the child may see it. In order:
 *
 * 1. A parent's manual allow/block for this exact video always wins.
 * 2. Videos from channels the parent blocked are hidden.
 * 3. The keyword list hides matches instantly, offline.
 * 4. With no AI rules configured, everything else is allowed.
 * 5. A cached AI score for these exact rules is reused.
 * 6. Everything left is sent to the AI in batches.
 *
 * Anything that can't be decided comes back [Verdict.UNKNOWN], which the page keeps hidden.
 * This class blocks; call it off the main thread. Pass [aiPool] to check AI batches side by side.
 */
class DecisionEngine(
    private val store: VerdictStore,
    private val classifierFactory: (ProviderConfig) -> VideoClassifier? = Classifiers::create,
    private val metadata: MetadataFetcher? = YouTubeOEmbed,
    private val batchSize: Int = 12,
    private val aiPool: ExecutorService? = null,
) {
    fun decide(input: List<VideoInfo>, settings: FilterSettings, bypassCache: Boolean = false): List<Decision> {
        val all = LinkedHashMap<String, Decision>()
        decide(input, settings, bypassCache) { part -> synchronized(all) { part.forEach { all[it.id] = it } } }
        return input.distinctBy { it.id }.mapNotNull { all[it.id] }
    }

    /**
     * Same as [decide], but hands results over as soon as they're known: everything that needs
     * no AI first, then each AI batch as it comes back. Returns once every video has been sent.
     * [emit] may be called from several threads at once when [aiPool] is set.
     */
    fun decide(input: List<VideoInfo>, settings: FilterSettings, bypassCache: Boolean = false, emit: (List<Decision>) -> Unit) {
        val videos = input.filter { VideoInfo.isValidId(it.id) }.distinctBy { it.id }
        val decisions = LinkedHashMap<String, Decision>()
        val keywords = KeywordFilter(settings.blockedKeywords)
        val channels = ChannelBlocklist(settings.blockedChannels)
        val aiOn = settings.allowedContent.isNotBlank() ||
            (settings.effectiveRules().isNotBlank() && (settings.provider.type != ProviderType.NONE || settings.fallbackProvider.type != ProviderType.NONE)) ||
            (settings.provider.type != ProviderType.NONE && !settings.provider.isAiEnabled)
        val hash = settings.aiConfigHash()
        val needAi = ArrayList<VideoInfo>()
        val byId = HashMap<String, VideoInfo>()

        val looked = lookUpTitles(videos.filter { it.title.isBlank() && store.override(it.id) == null })

        for (raw in videos) {
            val parent = store.override(raw.id)
            if (parent != null) {
                decisions[raw.id] = Decision(raw.id, parent, DecisionSource.PARENT, reason = "Set by parent")
                continue
            }
            val v = looked[raw.id]?.mergeInto(raw) ?: raw
            byId[v.id] = v

            val channel = channels.match(v)
            if (channel != null) {
                decisions[v.id] = Decision(v.id, Verdict.BLOCK, DecisionSource.CHANNEL, reason = "Blocked channel: $channel")
                continue
            }
            val kw = keywords.firstMatch(v.allText)
            if (kw != null) {
                decisions[v.id] = Decision(v.id, Verdict.BLOCK, DecisionSource.KEYWORD, reason = "Keyword: $kw")
                continue
            }
            if (!aiOn) {
                decisions[v.id] = Decision(v.id, Verdict.ALLOW, DecisionSource.NO_RULES)
                continue
            }
            if (!settings.provider.isAiEnabled && !settings.fallbackProvider.isAiEnabled) {
                decisions[v.id] = Decision(v.id, Verdict.UNKNOWN, DecisionSource.ERROR, reason = "AI rules require a configured checker")
                continue
            }
            val cached = if (bypassCache) null else store.cachedScore(v.id, hash)
            if (cached != null) {
                decisions[v.id] = fromScore(cached, settings.strictness)
                continue
            }
            if (v.title.isBlank() && !(settings.provider.isAiEnabled && settings.provider.usesThumbnails) &&
                !(settings.fallbackProvider.isAiEnabled && settings.fallbackProvider.usesThumbnails)) {
                decisions[v.id] = Decision(v.id, Verdict.UNKNOWN, DecisionSource.ERROR, reason = "No title to check")
                continue
            }
            needAi += v
        }

        // Keep input order; record hidden ones for the parent's review list.
        fun send(list: List<VideoInfo>, found: Map<String, Decision>) {
            val out = list.mapNotNull { v ->
                found[v.id]?.also { d ->
                    if (d.verdict == Verdict.BLOCK && d.source != DecisionSource.PARENT) {
                        store.recordHidden(byId[v.id] ?: v, d)
                    }
                }
            }
            if (out.isNotEmpty()) emit(out)
        }
        send(videos, decisions)
        if (needAi.isEmpty()) return

        val classifier = try { if (settings.provider.isAiEnabled) classifierFactory(settings.provider) else null } catch (e: Exception) { null }
        val batches = needAi.chunked(batchSize)
        fun run(batch: List<VideoInfo>) = send(batch, classifyBatch(classifier, batch, settings, hash))
        if (aiPool == null || batches.size == 1) {
            batches.forEach(::run)
        } else {
            batches.map { b -> aiPool.submit { run(b) } }.forEach { it.get() }
        }
    }

    /** Titles for videos the page couldn't read, fetched side by side when there's a pool. */
    private fun lookUpTitles(videos: List<VideoInfo>): Map<String, VideoInfo> {
        val m = metadata ?: return emptyMap()
        if (videos.isEmpty()) return emptyMap()
        val fetch = { v: VideoInfo -> v.id to m.fetch(v.id) }
        val got = if (aiPool == null || videos.size == 1) videos.map(fetch)
        else videos.map { v -> aiPool.submit<Pair<String, VideoInfo?>> { fetch(v) } }.map { it.get() }
        return got.mapNotNull { (id, info) -> info?.let { id to it } }.toMap()
    }

    private fun classifyBatch(
        classifier: VideoClassifier?,
        batch: List<VideoInfo>,
        settings: FilterSettings,
        hash: String,
    ): Map<String, Decision> {
        var failure = "AI skipped this video or returned an invalid score"
        fun attempt(checker: VideoClassifier?, videos: List<VideoInfo>): Map<String, Score> = try {
            checker?.classify(videos, settings.effectiveRules()).orEmpty()
                .filter { it.p.isFinite() && it.p in 0.0..1.0 && videos.any { v -> v.id == it.id } }
                .associateBy { it.id }
        } catch (e: Exception) {
            failure = e.message ?: "AI error"
            emptyMap()
        }
        val got = attempt(classifier, batch).toMutableMap()
        val missing = batch.filter { it.id !in got }
        if (missing.isNotEmpty() && settings.fallbackProvider.isAiEnabled) {
            val fallback = try { classifierFactory(settings.fallbackProvider) } catch (e: Exception) { null }
            got.putAll(attempt(fallback, missing))
        }
        return batch.associate { v ->
            val score = got[v.id]
            v.id to if (score == null) {
                Decision(v.id, Verdict.UNKNOWN, DecisionSource.ERROR, reason = failure)
            } else {
                store.saveScore(score, hash, v)
                fromScore(score, settings.strictness).copy(freshAi = true)
            }
        }
    }

    private fun fromScore(s: Score, strictness: Strictness) =
        Decision(s.id, strictness.verdictFor(s.p), DecisionSource.AI, s.p, s.reason)

    private fun VideoInfo.mergeInto(page: VideoInfo) = page.copy(
        title = title.ifBlank { page.title },
        channel = page.channel.ifBlank { channel },
        channelId = page.channelId.ifBlank { channelId },
    )
}
