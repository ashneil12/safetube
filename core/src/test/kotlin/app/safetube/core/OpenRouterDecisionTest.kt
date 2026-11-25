package app.safetube.core

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenRouterDecisionTest {
    private val config = ProviderConfig(ProviderType.OPENROUTER_DECISIONS, apiKey = "test-key")
    private val sample = VideoInfo("aaaaaaaaaaa", "Planet Coaster park tour", "Family Builds")

    @Test
    fun `OpenRouter needs only a key and defaults to Clef Flash without changing legacy models`() {
        assertTrue(config.isAiEnabled)
        assertFalse(config.copy(apiKey = " ").isAiEnabled)
        assertTrue(config.isDecisionModel)
        assertFalse(config.usesThumbnails)
        assertEquals("cloudflare/clef-flash", config.modelOrDefault())
        assertEquals("clef", ProviderConfig(ProviderType.CLEF).modelOrDefault())
        assertEquals("typesafe/jev-1.14", ProviderConfig(ProviderType.JEV).modelOrDefault())
        val c = DecisionModelClassifier(config)
        assertEquals("https://openrouter.ai/api/alpha/decisions", c.endpoint())
        assertEquals("cloudflare/clef-flash", c.buildRequest(listOf(sample), "No frightening content").getString("model"))
        assertEquals("typesafe/jev-1.14", config.copy(model = "typesafe/jev-1.14").modelOrDefault())
        assertTrue(Classifiers.create(config) is DecisionModelClassifier)
    }

    @Test
    fun `Clef requests are split without dropping rules or mixing video answers`() {
        val videos = (0 until 12).map { VideoInfo("v" + it.toString().padStart(10, '0'), "Title $it " + "x".repeat(200), "Channel", "c".repeat(250)) }
        val rules = "Only show park-building tutorials. " + "Ordinary rides are fine. ".repeat(15)
        val bodies = ArrayList<JSONObject>()
        val c = DecisionModelClassifier(config, transport = { url, headers, body ->
            assertEquals("https://openrouter.ai/api/alpha/decisions", url)
            assertEquals("Bearer test-key", headers["Authorization"])
            val request = JSONObject(body)
            bodies += request
            assertEquals(rules.trim(), request.getJSONObject("state").getString("parent_rules"))
            assertTrue(request.getJSONObject("state").toString().toByteArray(Charsets.UTF_8).size <= DecisionModelClassifier.MAX_CLEF_STATE_BYTES)
            val answers = JSONObject()
            val stateVideos = request.getJSONObject("state").getJSONObject("videos")
            stateVideos.keys().forEach { key ->
                val index = stateVideos.getJSONObject(key).getString("title").substringAfter("Title ").substringBefore(' ').toInt()
                answers.put(key, JSONObject().put("noul", index / 20.0))
            }
            Http.Response(200, JSONObject().put("answers", answers).toString())
        })
        val scores = c.classify(videos, rules)
        assertTrue(bodies.size > 1)
        assertEquals(videos.map { it.id }, scores.map { it.id })
        assertEquals((0 until 12).map { it / 20.0 }, scores.map { it.p })
    }

    @Test
    fun `oversized single state is refused before any request and never truncates rules`() {
        var calls = 0
        val c = DecisionModelClassifier(config, transport = { _, _, _ -> calls++; Http.Response(200, "{}") })
        val failure = assertFailsWith<ClassifierException> { c.classify(listOf(sample), "親のルール".repeat(300)) }
        assertTrue(failure.message!!.contains("Shorten"))
        assertEquals(0, calls)
        val request = c.buildRequest(listOf(sample), "親のルール".repeat(300))
        assertEquals("親のルール".repeat(300), request.getJSONObject("state").getString("parent_rules"))
    }

    @Test
    fun `whole batch is preflighted so later oversized metadata causes no partial sends`() {
        var calls = 0
        val c = DecisionModelClassifier(config, transport = { _, _, _ -> calls++; Http.Response(200, "{}") })
        val large = sample.copy(id = "bbbbbbbbbbb", title = "界".repeat(300), channel = "界".repeat(120), context = "界".repeat(300))
        assertFailsWith<ClassifierException> { c.classify(listOf(sample, large), "No scary content") }
        assertEquals(0, calls)
    }

    @Test
    fun `direct Clef and legacy custom Clef models receive the same bounded-state protection`() {
        assertTrue(ProviderConfig(ProviderType.CLEF).needsBoundedDecisionState)
        assertTrue(ProviderConfig(ProviderType.JEV, model = "cloudflare/clef-flash").needsBoundedDecisionState)
        assertTrue(config.copy(model = "Cloudflare/Clef").needsBoundedDecisionState)
        assertFalse(ProviderConfig(ProviderType.JEV).needsBoundedDecisionState)
        val custom = DecisionModelClassifier(config.copy(model = "typesafe/jev-1.14"))
        assertEquals(1, custom.requestGroups(List(12) { sample }, "x".repeat(3000)).size)
    }

    @Test
    fun `missing and invalid OpenRouter answers are not turned into allowances`() {
        val c = DecisionModelClassifier(config, transport = { _, _, _ ->
            Http.Response(200, """{"answers":{"v1":{"noul":9},"v2":{"noul":0.1}}}""")
        })
        val scores = c.classify(listOf(sample), "Only educational content")
        assertTrue(scores.isEmpty())
    }

    @Test
    fun `only show requests ask the disallowed question instead of mere harmlessness`() {
        val c = DecisionModelClassifier(config)
        val plain = c.buildRequest(listOf(sample), "ONLY SHOW content matching this description; block everything outside it: science")
        val plainQ = plain.getJSONObject("questions").getJSONObject("v1")
        assertTrue(plainQ.getString("instructions").contains("including being outside an ONLY SHOW description"))
        assertEquals("Nothing about video v1 goes against the parent's rules", plainQ.getJSONObject("criteria").getString("false"))

        val req = c.buildRequest(listOf(sample), "ONLY SHOW content matching this description; block everything outside it: science", onlyShow = true)
        val q = req.getJSONObject("questions").getJSONObject("v1")
        val instructions = q.getString("instructions")
        assertTrue(instructions.startsWith("Video v1 can be shown only if the supplied evidence positively places it inside"))
        assertTrue(instructions.contains("Is it disallowed?"))
        assertTrue(instructions.contains("Generic child-safe but off-topic, unrelated, or weakly evidenced content is disallowed"))
        val criteria = q.getJSONObject("criteria")
        assertTrue(criteria.getString("true").contains("outside the ONLY SHOW description, not positively established inside it"))
        assertTrue(criteria.getString("false").contains("positively places video v1 inside the ONLY SHOW description"))
    }

    @Test
    fun `the reported christian-only nursery fixture is framed as an eligibility decision`() {
        val nursery = VideoInfo(
            "ddddddddddd",
            "Baa Baa Black Sheep | Animal Sounds for Toddlers",
            "CoComelon - Nursery Rhymes",
            "Educational songs for kids and toddlers • animals, phonics and learning",
        )
        var asked = JSONObject()
        val c = DecisionModelClassifier(config, transport = { _, _, body ->
            asked = JSONObject(body)
            // Representative of the fixed question's live Clef Flash answers for this fixture:
            // high enough to block at Balanced, unlike the harm-framed mid-range scores.
            Http.Response(200, JSONObject().put("answers", JSONObject().put("v1", JSONObject().put("noul", 0.84))).toString())
        })
        val rules = FilterSettings(allowedContent = "all christian content").effectiveRules()
        val scores = c.classify(listOf(nursery), rules, onlyShow = true)
        assertEquals(0.84, scores.single().p)
        val q = asked.getJSONObject("questions").getJSONObject("v1")
        assertTrue(q.getString("instructions").contains("Is it disallowed?"))
        assertTrue(q.getJSONObject("criteria").getString("false").contains("positively places"))
        assertEquals(Verdict.BLOCK, Strictness.BALANCED.verdictFor(scores.single().p))
    }

    @Test
    fun `missing answers under an only show rule fail closed`() {
        val c = DecisionModelClassifier(config, transport = { _, _, _ ->
            Http.Response(200, """{"answers":{}}""")
        })
        assertTrue(c.classify(listOf(sample), "ONLY SHOW x", onlyShow = true).isEmpty())
    }

    @Test
    fun `only show decision scores cached before the eligibility fix are invalidated`() {
        fun oldIdentity(p: ProviderConfig) = listOf(p.type.name, p.baseUrl.trim(), p.modelOrDefault(), p.accountId.trim(), Hashing.sha256(p.apiKey), p.usesThumbnails.toString()).joinToString("\u0000")
        fun previousHash(s: FilterSettings) = Hashing.sha256(listOf(s.effectiveRules(), oldIdentity(s.provider), oldIdentity(s.fallbackProvider), PromptBuilder.VERSION.toString()).joinToString("\u0000")).take(16)

        // Jev carries no Clef state-version marker, so any hash change here comes only
        // from the only-show eligibility semantics.
        val jev = ProviderConfig(ProviderType.JEV, apiKey = "test-key")
        // An only-show family on a decision model: cached pre-fix allows must be re-checked.
        val onlyShow = FilterSettings(rules = "", allowedContent = "Planet Coaster", provider = jev)
        assertFalse(previousHash(onlyShow) == onlyShow.aiConfigHash())
        // The same upgrade is not a parent policy change: an in-progress outage survives it.
        assertEquals(previousHash(onlyShow), onlyShow.outageConfigHash())
        // Chat-provider only-show rules are unaffected: the chat prompt semantics did not change.
        val chat = onlyShow.copy(provider = ProviderConfig(ProviderType.ANTHROPIC, apiKey = "key"))
        assertEquals(previousHash(chat), chat.aiConfigHash())
        // Families without an only-show rule keep every cached score: nothing about their question changed.
        val noOnlyShow = FilterSettings(rules = "No scary content", provider = jev)
        assertEquals(previousHash(noOnlyShow), noOnlyShow.aiConfigHash())
        // A decision model only in the fallback position is covered too.
        val fallbackOnly = FilterSettings(allowedContent = "Science", provider = ProviderConfig(ProviderType.ANTHROPIC, apiKey = "key"), fallbackProvider = jev)
        assertFalse(previousHash(fallbackOnly) == fallbackOnly.aiConfigHash())
    }

    @Test
    fun `only Clef-family cache identities invalidate old unbounded state scores`() {
        fun oldIdentity(p: ProviderConfig) = listOf(p.type.name, p.baseUrl.trim(), p.modelOrDefault(), p.accountId.trim(), Hashing.sha256(p.apiKey), p.usesThumbnails.toString()).joinToString("\u0000")
        fun previousHash(s: FilterSettings) = Hashing.sha256(listOf(s.effectiveRules(), oldIdentity(s.provider), oldIdentity(s.fallbackProvider), PromptBuilder.VERSION.toString()).joinToString("\u0000")).take(16)
        val legacy = FilterSettings(rules = "No scary content", provider = ProviderConfig(ProviderType.JEV, apiKey = "old-key"))
        assertEquals(previousHash(legacy), legacy.aiConfigHash())
        val direct = legacy.copy(provider = ProviderConfig(ProviderType.CLEF, apiKey = "old-key", accountId = "account"))
        assertFalse(previousHash(direct) == direct.aiConfigHash())
        assertEquals(previousHash(direct), direct.outageConfigHash())
        assertFalse(direct.outageConfigHash() == direct.copy(rules = "Only science").outageConfigHash())
        assertFalse(direct.outageConfigHash() == direct.copy(provider = direct.provider.copy(apiKey = "changed")).outageConfigHash())
        assertEquals(direct.aiConfigHash(), direct.copy(strictness = Strictness.PERMISSIVE).aiConfigHash())
    }
}
