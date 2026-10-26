package app.safetube.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DecisionEngineTest {
    private val pumpkins = VideoInfo("aaaaaaaaaaa", "Five Little Pumpkins | Halloween Song", "Super Simple Songs")
    private val bus = VideoInfo("bbbbbbbbbbb", "Wheels On The Bus", "Super Simple Songs")
    private val potion = VideoInfo("ccccccccccc", "Make a Potion! Bubble Bubble", "Super Simple Songs")

    private val aiSettings = FilterSettings(
        rules = "No witches, Halloween, ghosts or spooky things.",
        provider = ProviderConfig(ProviderType.ANTHROPIC, apiKey = "test"),
    )

    /** A stand-in model that counts how often it is called. */
    private class FakeClassifier(val scores: Map<String, Double>) : VideoClassifier {
        var calls = 0
        var seen = mutableListOf<String>()
        override fun classify(videos: List<VideoInfo>, rules: String): List<Score> {
            calls++
            seen += videos.map { it.id }
            return videos.mapNotNull { v -> scores[v.id]?.let { Score(v.id, it, "fake") } }
        }
    }

    private fun engine(store: VerdictStore, fake: VideoClassifier?, metadata: MetadataFetcher? = null) =
        DecisionEngine(store, { fake }, metadata)

    @Test
    fun `keyword match hides without calling the AI`() {
        val fake = FakeClassifier(mapOf(bus.id to 0.01))
        val d = engine(InMemoryVerdictStore(), fake)
            .decide(listOf(pumpkins, bus), aiSettings.copy(blockedKeywords = listOf("halloween")))
        assertEquals(Verdict.BLOCK, d[0].verdict)
        assertEquals(DecisionSource.KEYWORD, d[0].source)
        assertEquals(Verdict.ALLOW, d[1].verdict)
        assertEquals(listOf(bus.id), fake.seen)
    }

    @Test
    fun `strictness changes the verdict for uncertain scores`() {
        val fake = FakeClassifier(mapOf(potion.id to 0.35))
        val store = InMemoryVerdictStore()
        val strict = engine(store, fake).decide(listOf(potion), aiSettings)
        assertEquals(Verdict.BLOCK, strict.single().verdict)
        val balanced = engine(store, fake).decide(listOf(potion), aiSettings.copy(strictness = Strictness.BALANCED))
        assertEquals(Verdict.ALLOW, balanced.single().verdict)
        // The second call came from the cache: strictness isn't part of the cache key.
        assertEquals(1, fake.calls)
    }

    @Test
    fun `changing the rules re-asks the AI`() {
        val fake = FakeClassifier(mapOf(bus.id to 0.0))
        val store = InMemoryVerdictStore()
        engine(store, fake).decide(listOf(bus), aiSettings)
        engine(store, fake).decide(listOf(bus), aiSettings.copy(rules = "No buses"))
        assertEquals(2, fake.calls)
    }

    @Test
    fun `AI failure leaves videos unknown so they stay hidden`() {
        val failing = object : VideoClassifier {
            override fun classify(videos: List<VideoInfo>, rules: String): List<Score> =
                throw ClassifierException("401 invalid key")
        }
        val d = engine(InMemoryVerdictStore(), failing).decide(listOf(bus), aiSettings)
        assertEquals(Verdict.UNKNOWN, d.single().verdict)
        assertTrue(d.single().reason.contains("401"))
    }

    @Test
    fun `videos the AI skipped are unknown, not allowed`() {
        val fake = FakeClassifier(mapOf(bus.id to 0.0))
        val d = engine(InMemoryVerdictStore(), fake).decide(listOf(bus, potion), aiSettings)
        assertEquals(Verdict.ALLOW, d[0].verdict)
        assertEquals(Verdict.UNKNOWN, d[1].verdict)
    }

    @Test
    fun `parent override beats AI and keywords`() {
        val store = InMemoryVerdictStore()
        store.setOverride(pumpkins.id, Verdict.ALLOW)
        val fake = FakeClassifier(emptyMap())
        val d = engine(store, fake).decide(listOf(pumpkins), aiSettings.copy(blockedKeywords = listOf("halloween")))
        assertEquals(Verdict.ALLOW, d.single().verdict)
        assertEquals(0, fake.calls)
    }

    @Test
    fun `no rules and no AI allows everything not on the keyword list`() {
        val d = engine(InMemoryVerdictStore(), null)
            .decide(listOf(pumpkins, bus), FilterSettings(blockedKeywords = listOf("pumpkin")))
        assertEquals(listOf(Verdict.BLOCK, Verdict.ALLOW), d.map { it.verdict })
    }

    @Test
    fun `missing titles are looked up before keyword matching`() {
        val lookup = MetadataFetcher { id -> if (id == pumpkins.id) pumpkins else null }
        val d = engine(InMemoryVerdictStore(), null, lookup)
            .decide(listOf(VideoInfo(pumpkins.id)), FilterSettings(blockedKeywords = listOf("halloween")))
        assertEquals(Verdict.BLOCK, d.single().verdict)
    }

    @Test
    fun `hidden videos are recorded for parent review`() {
        val store = InMemoryVerdictStore()
        engine(store, FakeClassifier(mapOf(pumpkins.id to 0.99))).decide(listOf(pumpkins), aiSettings)
        assertEquals(pumpkins.title, store.hidden.single().first.title)
    }

    @Test
    fun `invalid ids are dropped and duplicates collapsed`() {
        val fake = FakeClassifier(mapOf(bus.id to 0.0))
        val d = engine(InMemoryVerdictStore(), fake).decide(listOf(bus, bus, VideoInfo("not-an-id")), aiSettings)
        assertEquals(1, d.size)
        assertEquals(1, fake.seen.size)
    }

    @Test
    fun `large pages are sent in batches`() {
        val many = (0 until 30).map { VideoInfo("v" + it.toString().padStart(10, '0'), "Song $it") }
        val fake = FakeClassifier(many.associate { it.id to 0.0 })
        DecisionEngine(InMemoryVerdictStore(), { fake }, null, batchSize = 12).decide(many, aiSettings)
        assertEquals(3, fake.calls)
    }

    @Test
    fun `keyword answers arrive before the AI ones, and batches run side by side`() {
        val many = (0 until 24).map { VideoInfo("v" + it.toString().padStart(10, '0'), "Song $it") }
        val started = java.util.concurrent.CountDownLatch(3)
        val slow = object : VideoClassifier {
            override fun classify(videos: List<VideoInfo>, rules: String): List<Score> {
                started.countDown()
                // Only returns once all three batches are in flight together.
                assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
                return videos.map { Score(it.id, 0.0, "fake") }
            }
        }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
        val parts = java.util.Collections.synchronizedList(mutableListOf<List<Decision>>())
        DecisionEngine(InMemoryVerdictStore(), { slow }, null, batchSize = 8, aiPool = pool)
            .decide(listOf(pumpkins) + many, aiSettings.copy(blockedKeywords = listOf("halloween"))) { parts += it }
        pool.shutdown()
        assertEquals(listOf(pumpkins.id), parts.first().map { it.id })
        assertEquals(4, parts.size)
        assertEquals(25, parts.flatten().map { it.id }.toSet().size)
    }
    @Test
    fun `only show rules and exceptions reach classifier even without hide rules`() {
        var received = ""
        val fake = object : VideoClassifier {
            override fun classify(videos: List<VideoInfo>, rules: String): List<Score> {
                received = rules
                return videos.map { Score(it.id, 0.9, "Outside allowed topics") }
            }
        }
        val settings = aiSettings.copy(rules = "", allowedContent = "Planet Coaster", exceptions = "Ordinary rides are fine")
        assertEquals(Verdict.BLOCK, engine(InMemoryVerdictStore(), fake).decide(listOf(bus), settings).single().verdict)
        assertTrue(received.contains("ONLY SHOW"))
        assertTrue(received.contains("Ordinary rides are fine"))
    }

    @Test
    fun `only show with no configured AI fails closed`() {
        val d = engine(InMemoryVerdictStore(), null).decide(listOf(bus), FilterSettings(allowedContent = "Education"))
        assertEquals(DecisionSource.ERROR, d.single().source)
        assertEquals(Verdict.UNKNOWN, d.single().verdict)
    }

    @Test
    fun `selected invalid checker does not degrade to no rules allowance`() {
        val d = engine(InMemoryVerdictStore(), null).decide(listOf(bus), aiSettings.copy(rules = "", provider = ProviderConfig(ProviderType.ANTHROPIC)))
        assertEquals(Verdict.UNKNOWN, d.single().verdict)
    }

    @Test
    fun `fallback checks only missing and invalid primary results`() {
        val fallbackSeen = mutableListOf<String>()
        val primary = object : VideoClassifier {
            override fun classify(videos: List<VideoInfo>, rules: String) = listOf(Score(bus.id, 0.01, "Valid"), Score(potion.id, Double.NaN, "Bad"))
        }
        val fallback = object : VideoClassifier {
            override fun classify(videos: List<VideoInfo>, rules: String): List<Score> {
                fallbackSeen += videos.map { it.id }
                return videos.map { Score(it.id, 0.8, "Fallback evidence") }
            }
        }
        val settings = aiSettings.copy(fallbackProvider = ProviderConfig(ProviderType.JEV, apiKey = "fallback"))
        val decisions = DecisionEngine(InMemoryVerdictStore(), { if (it.type == ProviderType.JEV) fallback else primary }, null)
            .decide(listOf(bus, potion, pumpkins), settings)
        assertEquals(listOf(potion.id, pumpkins.id), fallbackSeen)
        assertEquals(listOf(Verdict.ALLOW, Verdict.BLOCK, Verdict.BLOCK), decisions.map { it.verdict })
        assertTrue(decisions.all { it.freshAi })
    }

    @Test
    fun `fallback recovers primary error and still fails closed when both fail`() {
        val failing = object : VideoClassifier {
            override fun classify(videos: List<VideoInfo>, rules: String): List<Score> = throw ClassifierException("quota exhausted")
        }
        val settings = aiSettings.copy(fallbackProvider = ProviderConfig(ProviderType.JEV, apiKey = "fallback"))
        val recovered = DecisionEngine(InMemoryVerdictStore(), { if (it.type == ProviderType.JEV) FakeClassifier(mapOf(bus.id to 0.0)) else failing }, null)
            .decide(listOf(bus), settings).single()
        assertTrue(recovered.freshAi)
        assertEquals(Verdict.ALLOW, recovered.verdict)
        val failed = engine(InMemoryVerdictStore(), failing).decide(listOf(bus), settings).single()
        assertEquals(Verdict.UNKNOWN, failed.verdict)
    }

    @Test
    fun `cache does not count as fresh success and explicit retry bypasses cache`() {
        val store = InMemoryVerdictStore()
        val fake = FakeClassifier(mapOf(bus.id to 0.0))
        val checker = engine(store, fake)
        assertTrue(checker.decide(listOf(bus), aiSettings).single().freshAi)
        assertTrue(!checker.decide(listOf(bus), aiSettings).single().freshAi)
        assertTrue(checker.decide(listOf(bus), aiSettings, bypassCache = true).single().freshAi)
        assertEquals(2, fake.calls)
    }

    @Test
    fun `rule clarification and both provider identities invalidate scores`() {
        val baseline = aiSettings.aiConfigHash()
        assertTrue(baseline != aiSettings.copy(allowedContent = "Planet Coaster").aiConfigHash())
        assertTrue(baseline != aiSettings.copy(exceptions = "Normal games are fine").aiConfigHash())
        assertTrue(baseline != aiSettings.copy(provider = aiSettings.provider.copy(apiKey = "new key")).aiConfigHash())
        assertTrue(baseline != aiSettings.copy(fallbackProvider = ProviderConfig(ProviderType.JEV, apiKey = "fallback")).aiConfigHash())
        assertEquals(baseline, aiSettings.copy(strictness = Strictness.BALANCED).aiConfigHash())
    }

    @Test
    fun `clarification cannot bypass keyword or channel block`() {
        val settings = aiSettings.copy(exceptions = "Everything here is fine", blockedKeywords = listOf("bus"), blockedChannels = listOf("Super Simple Songs"))
        val d = engine(InMemoryVerdictStore(), FakeClassifier(emptyMap())).decide(listOf(bus), settings).single()
        assertEquals(Verdict.BLOCK, d.verdict)
        assertEquals(DecisionSource.CHANNEL, d.source)
    }

    @Test
    fun `outage probe checks fresh AI while preserving all local decisions`() {
        val store = InMemoryVerdictStore()
        store.setOverride(bus.id, Verdict.ALLOW)
        val fake = FakeClassifier(mapOf(bus.id to 0.99))
        val checker = engine(store, fake)
        val settings = aiSettings.copy(blockedKeywords = listOf("bus"), blockedChannels = listOf(bus.channel))
        assertEquals(DecisionSource.PARENT, checker.decide(listOf(bus), settings).single().source)
        assertEquals(0, fake.calls)
        assertTrue(checker.probeAiAvailability(listOf(bus), settings))
        assertEquals(1, fake.calls)
        assertEquals(Verdict.ALLOW, store.override(bus.id))
        assertTrue(store.scores.isEmpty())
        assertTrue(store.hidden.isEmpty())
    }

    @Test
    fun `outage probe cannot succeed from absent title missing scores or unconfigured AI`() {
        val fake = FakeClassifier(emptyMap())
        val checker = engine(InMemoryVerdictStore(), fake)
        assertTrue(!checker.probeAiAvailability(listOf(VideoInfo(bus.id)), aiSettings))
        assertEquals(0, fake.calls)
        assertTrue(!checker.probeAiAvailability(listOf(bus), aiSettings))
        assertTrue(!checker.probeAiAvailability(listOf(bus), FilterSettings()))
    }

    @Test
    fun `outage probe uses configured fallback and fetched real metadata`() {
        val fallback = FakeClassifier(mapOf(bus.id to 0.99))
        val primary = FakeClassifier(emptyMap())
        val checker = DecisionEngine(InMemoryVerdictStore(), { if (it.type == ProviderType.JEV) fallback else primary }, MetadataFetcher { bus })
        assertTrue(checker.probeAiAvailability(listOf(VideoInfo(bus.id)), aiSettings.copy(
            fallbackProvider = ProviderConfig(ProviderType.JEV, apiKey = "fallback"))))
        assertEquals(1, fallback.calls)
    }

    @Test
    fun `titleless decision model never treats unconfigured providers as image support`() {
        val fake = FakeClassifier(mapOf(bus.id to 0.0))
        val checker = engine(InMemoryVerdictStore(), fake)
        val clef = ProviderConfig(ProviderType.CLEF, apiKey = "key", accountId = "account")
        val primaryOnly = aiSettings.copy(provider = clef, fallbackProvider = ProviderConfig())
        val fallbackOnly = aiSettings.copy(provider = ProviderConfig(), fallbackProvider = clef)
        for (settings in listOf(primaryOnly, fallbackOnly)) {
            val decision = checker.decide(listOf(VideoInfo(bus.id)), settings).single()
            assertEquals(Verdict.UNKNOWN, decision.verdict)
            assertEquals(DecisionSource.ERROR, decision.source)
            assertEquals("No title to check", decision.reason)
        }
        assertEquals(0, fake.calls)
    }

    @Test
    fun `titleless video skips text primary and can use a vision fallback`() {
        val text = FakeClassifier(mapOf(bus.id to 0.0))
        val vision = FakeClassifier(mapOf(bus.id to 0.1))
        val settings = aiSettings.copy(provider = ProviderConfig(ProviderType.CLEF, apiKey = "key", accountId = "account"),
            fallbackProvider = ProviderConfig(ProviderType.ANTHROPIC, apiKey = "key"))
        val checker = DecisionEngine(InMemoryVerdictStore(), { if (it.type == ProviderType.CLEF) text else vision }, null)
        val decision = checker.decide(listOf(VideoInfo(bus.id)), settings).single()
        assertEquals(Verdict.ALLOW, decision.verdict)
        assertTrue(decision.freshAi)
        assertEquals(0, text.calls)
        assertEquals(1, vision.calls)
    }

    @Test
    fun `failed vision check cannot send titleless video to text fallback`() {
        var visionCalls = 0
        val vision = object : VideoClassifier {
            override fun classify(videos: List<VideoInfo>, rules: String): List<Score> {
                visionCalls++
                throw ClassifierException("Quota exhausted")
            }
        }
        val text = FakeClassifier(mapOf(bus.id to 0.0))
        val settings = aiSettings.copy(fallbackProvider = ProviderConfig(ProviderType.CLEF, apiKey = "key", accountId = "account"))
        val checker = DecisionEngine(InMemoryVerdictStore(), { if (it.type == ProviderType.CLEF) text else vision }, null)
        val decision = checker.decide(listOf(VideoInfo(bus.id)), settings).single()
        assertEquals(Verdict.UNKNOWN, decision.verdict)
        assertEquals(DecisionSource.ERROR, decision.source)
        assertTrue(!decision.freshAi)
        assertEquals(1, visionCalls)
        assertEquals(0, text.calls)
    }

    @Test
    fun `successful vision primary checks titleless video without text fallback`() {
        val vision = FakeClassifier(mapOf(bus.id to 0.0))
        val text = FakeClassifier(mapOf(bus.id to 0.0))
        val settings = aiSettings.copy(fallbackProvider = ProviderConfig(ProviderType.CLEF, apiKey = "key", accountId = "account"))
        val checker = DecisionEngine(InMemoryVerdictStore(), { if (it.type == ProviderType.CLEF) text else vision }, null)
        assertEquals(Verdict.ALLOW, checker.decide(listOf(VideoInfo(bus.id)), settings).single().verdict)
        assertEquals(1, vision.calls)
        assertEquals(0, text.calls)
    }

}
