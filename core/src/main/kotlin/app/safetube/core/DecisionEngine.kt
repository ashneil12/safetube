package app.safetube.core

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
 * This class is synchronous; call it off the main thread.
 */
class DecisionEngine(
    private val store: VerdictStore,
    private val classifierFactory: (ProviderConfig) -> VideoClassifier? = Classifiers::create,
    private val metadata: MetadataFetcher? = YouTubeOEmbed,
    private val batchSize: Int = 12,
) {
    fun decide(input: List<VideoInfo>, settings: FilterSettings): List<Decision> {
        val videos = input.filter { VideoInfo.isValidId(it.id) }.distinctBy { it.id }
        val decisions = LinkedHashMap<String, Decision>()
        val keywords = KeywordFilter(settings.blockedKeywords)
        val channels = ChannelBlocklist(settings.blockedChannels)
        val aiOn = settings.provider.isAiEnabled && settings.rules.isNotBlank()
        val hash = settings.aiConfigHash()
        val needAi = ArrayList<VideoInfo>()
        val byId = HashMap<String, VideoInfo>()

        for (raw in videos) {
            val parent = store.override(raw.id)
            if (parent != null) {
                decisions[raw.id] = Decision(raw.id, parent, DecisionSource.PARENT, reason = "Set by parent")
                continue
            }
            val v = if (raw.title.isBlank()) metadata?.fetch(raw.id)?.mergeInto(raw) ?: raw else raw
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
            val cached = store.cachedScore(v.id, hash)
            if (cached != null) {
                decisions[v.id] = fromScore(cached, settings.strictness)
                continue
            }
            if (v.title.isBlank() && !settings.provider.usesThumbnails) {
                decisions[v.id] = Decision(v.id, Verdict.UNKNOWN, DecisionSource.ERROR, reason = "No title to check")
                continue
            }
            needAi += v
        }

        if (needAi.isNotEmpty()) {
            val classifier = classifierFactory(settings.provider)
            for (batch in needAi.chunked(batchSize)) {
                val scores = try {
                    classifier?.classify(batch, settings.rules)
                        ?: throw ClassifierException("No AI provider configured")
                } catch (e: ClassifierException) {
                    batch.forEach { decisions[it.id] = Decision(it.id, Verdict.UNKNOWN, DecisionSource.ERROR, reason = e.message ?: "AI error") }
                    continue
                }
                val got = scores.associateBy { it.id }
                for (v in batch) {
                    val s = got[v.id]
                    if (s == null) {
                        decisions[v.id] = Decision(v.id, Verdict.UNKNOWN, DecisionSource.ERROR, reason = "AI skipped this video")
                    } else {
                        store.saveScore(s, hash, v)
                        decisions[v.id] = fromScore(s, settings.strictness)
                    }
                }
            }
        }

        // Keep input order; record hidden ones for the parent's review list.
        return videos.mapNotNull { v ->
            decisions[v.id]?.also { d ->
                if (d.verdict == Verdict.BLOCK && d.source != DecisionSource.PARENT) {
                    store.recordHidden(byId[v.id] ?: v, d)
                }
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
