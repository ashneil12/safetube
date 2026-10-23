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
}
