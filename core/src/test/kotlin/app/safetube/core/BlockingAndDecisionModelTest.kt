package app.safetube.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlockingAndDecisionModelTest {
    private val sss = VideoInfo("aaaaaaaaaaa", "Wheels On The Bus", "Super Simple Songs · 3M views", channelId = "@SuperSimpleSongs")
    private val other = VideoInfo("bbbbbbbbbbb", "Counting to 10", "Numberblocks", channelId = "/channel/UCPlwvN0w4qFSP1FllALB92w")

    @Test
    fun `channel links, handles, ids and names are all understood`() {
        assertEquals("@SuperSimpleSongs", ChannelBlocklist.parse("https://www.youtube.com/@SuperSimpleSongs/videos"))
        assertEquals("UCPlwvN0w4qFSP1FllALB92w", ChannelBlocklist.parse("https://m.youtube.com/channel/UCPlwvN0w4qFSP1FllALB92w"))
        assertEquals("Super Simple Songs", ChannelBlocklist.parse("  Super Simple Songs "))
        assertNull(ChannelBlocklist.parse("   "))
        assertTrue(ChannelBlocklist.isChannelLink("@bluey"))
        assertFalse(ChannelBlocklist.isChannelLink("Bluey Official"))
    }

    @Test
    fun `blocked channel matches by handle, id or name, case-insensitively`() {
        assertEquals("@supersimplesongs", ChannelBlocklist(listOf("@supersimplesongs")).match(sss)?.lowercase())
        assertTrue(ChannelBlocklist(listOf("UCPlwvN0w4qFSP1FllALB92w")).match(other) != null)
        assertTrue(ChannelBlocklist(listOf("super simple songs")).match(sss) != null)
        assertNull(ChannelBlocklist(listOf("Simple")).match(other))
        // A name only matches the channel line, never the video title.
        assertNull(ChannelBlocklist(listOf("Wheels")).match(sss))
        // Names that happen to start with "UC" are still names.
        assertTrue(ChannelBlocklist(listOf("UCLA Kids")).match(VideoInfo("ccccccccccc", "x", "UCLA Kids")) != null)
    }

    @Test
    fun `video links of every shape give the id`() {
        assertEquals("dQw4w9WgXcQ", VideoLinks.videoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10"))
        assertEquals("dQw4w9WgXcQ", VideoLinks.videoId("https://youtu.be/dQw4w9WgXcQ?si=abc"))
        assertEquals("dQw4w9WgXcQ", VideoLinks.videoId("https://youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", VideoLinks.videoId("dQw4w9WgXcQ"))
        assertNull(VideoLinks.videoId("https://www.youtube.com/@SuperSimpleSongs"))
    }

    @Test
    fun `engine hides blocked channels but a parent-allowed video still shows`() {
        val store = InMemoryVerdictStore()
        val settings = FilterSettings(blockedChannels = listOf("@SuperSimpleSongs"))
        val engine = DecisionEngine(store, { null }, null)
        val d = engine.decide(listOf(sss, other), settings)
        assertEquals(listOf(Verdict.BLOCK, Verdict.ALLOW), d.map { it.verdict })
        assertEquals(DecisionSource.CHANNEL, d[0].source)
        store.setOverride(sss.id, Verdict.ALLOW)
        assertEquals(Verdict.ALLOW, engine.decide(listOf(sss), settings).single().verdict)
    }

    @Test
    fun `a single blocked video stays blocked whatever the rules say`() {
        val store = InMemoryVerdictStore()
        store.setOverride(other.id, Verdict.BLOCK)
        val d = DecisionEngine(store, { null }, null).decide(listOf(other), FilterSettings())
        assertEquals(Verdict.BLOCK, d.single().verdict)
    }

    @Test
    fun `clef request goes to the account's Workers AI endpoint with one yes-no question per video`() {
        val c = DecisionModelClassifier(ProviderConfig(ProviderType.CLEF, apiKey = "t", accountId = "acc123"))
        assertEquals("https://api.cloudflare.com/client/v4/accounts/acc123/ai/run/@cf/cloudflare/clef", c.endpoint())
        val req = c.buildRequest(listOf(sss, other), "No witches")
        assertEquals("clef", req.getString("model"))
        assertEquals("No witches", req.getJSONObject("state").getString("parent_rules"))
        assertEquals("Wheels On The Bus", req.getJSONObject("state").getJSONObject("videos").getJSONObject("v1").getString("title"))
        val q = req.getJSONObject("questions").getJSONObject("v2")
        assertEquals("noul", q.getString("type"))
        assertTrue(q.getJSONObject("criteria").has("true"))
    }

    @Test
    fun `jev goes to the OpenRouter decisions API`() {
        val c = DecisionModelClassifier(ProviderConfig(ProviderType.JEV, apiKey = "k"))
        assertEquals("https://openrouter.ai/api/alpha/decisions", c.endpoint())
        assertEquals("typesafe/jev-1.14", c.buildRequest(listOf(sss), "r").getString("model"))
    }

    @Test
    fun `decision answers are read from OpenRouter and Cloudflare reply shapes`() {
        val c = DecisionModelClassifier(ProviderConfig(ProviderType.CLEF, apiKey = "t", accountId = "a"))
        val openRouter = """{"id":"x","answers":{"v1":{"type":"noul","noul":0.93},"v2":{"type":"noul","noul":0.04}},"usage":{"cost":0.00001}}"""
        assertEquals(listOf(0.93, 0.04), c.parseAnswers(openRouter, listOf(sss, other)).map { it.p })
        val cloudflare = """{"result":{"model":"clef","answers":{"v1":{"type":"noul","probabilities":{"true":0.8,"false":0.2}}}},"success":true}"""
        val scores = c.parseAnswers(cloudflare, listOf(sss, other))
        assertEquals(1, scores.size) // v2 missing: that video comes back unknown
        assertEquals(0.8, scores[0].p)
    }

    @Test
    fun `decision models never send thumbnails`() {
        assertFalse(ProviderConfig(ProviderType.CLEF, sendThumbnails = true).usesThumbnails)
        assertTrue(ProviderConfig(ProviderType.ANTHROPIC, sendThumbnails = true).usesThumbnails)
        assertFalse(ProviderConfig(ProviderType.CLEF, apiKey = "t").isAiEnabled) // needs the account id too
    }

    @Test
    fun `suggested keywords are cleaned and de-duplicated against the existing list`() {
        val reply = """Here you go: {"keywords":["Witch","cauldron","witch","halloween","  ","spooky"]}"""
        assertEquals(listOf("witch", "cauldron", "spooky"), KeywordSuggester.parse(reply, listOf("Halloween")))
    }
}
