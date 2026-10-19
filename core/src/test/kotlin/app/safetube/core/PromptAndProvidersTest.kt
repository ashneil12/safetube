package app.safetube.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PromptAndProvidersTest {
    private val v = VideoInfo("aaaaaaaaaaa", "Witches Brew", "Super Simple Songs")

    @Test
    fun `parses scores wrapped in a code fence and ignores unknown ids`() {
        val reply = """
            Sure!
            ```json
            {"results":[{"id":"aaaaaaaaaaa","p":0.97,"reason":"witches"},{"id":"zzzzzzzzzzz","p":0.1}]}
            ```
        """.trimIndent()
        val scores = PromptBuilder.parseScores(reply, listOf("aaaaaaaaaaa"))
        assertEquals(1, scores.size)
        assertEquals(0.97, scores[0].p)
        assertEquals("witches", scores[0].reason)
    }

    @Test
    fun `clamps out-of-range probabilities`() {
        val s = PromptBuilder.parseScores("""{"results":[{"id":"aaaaaaaaaaa","p":7}]}""", listOf("aaaaaaaaaaa"))
        assertEquals(1.0, s.single().p)
    }

    @Test
    fun `rejects replies without JSON`() {
        assertFailsWith<ClassifierException> { PromptBuilder.parseScores("I can't help with that", listOf("x")) }
    }

    @Test
    fun `anthropic request carries rules, video text and thumbnail URLs`() {
        val req = AnthropicClassifier(ProviderConfig(ProviderType.ANTHROPIC, apiKey = "k"))
            .buildRequest(listOf(v), "No witches")
        assertEquals(ProviderConfig.DEFAULT_ANTHROPIC_MODEL, req.getString("model"))
        val body = req.toString()
        assertTrue(body.contains("No witches"))
        assertTrue(body.contains("Witches Brew"))
        assertTrue(body.contains("https://i.ytimg.com/vi/aaaaaaaaaaa/mqdefault.jpg"))
        assertEquals("low", req.getJSONObject("output_config").getString("effort"))
    }

    @Test
    fun `thumbnails can be turned off`() {
        val req = OpenAiCompatibleClassifier(
            ProviderConfig(ProviderType.OPENAI_COMPATIBLE, baseUrl = "http://x/v1", model = "m", sendThumbnails = false)
        ).buildRequest(listOf(v), "No witches")
        assertFalse(req.toString().contains("ytimg"))
    }

    @Test
    fun `effort is only sent to models that accept it`() {
        assertTrue(AnthropicClassifier.supportsEffort("claude-haiku-5-5"))
        assertTrue(AnthropicClassifier.supportsEffort("claude-opus-5-5"))
        assertFalse(AnthropicClassifier.supportsEffort("claude-haiku-4-5"))
        assertFalse(AnthropicClassifier.supportsEffort("claude-3-5-haiku-latest"))
    }

    @Test
    fun `keyword filter matches whole words and plurals only`() {
        val f = KeywordFilter(KeywordFilter.parseList("witch, trick or treat\nghost"))
        assertEquals("witch", f.firstMatch("Witches Brew Song"))
        assertEquals("trick or treat", f.firstMatch("Knock Knock, Trick  or Treat?"))
        assertEquals("ghost", f.firstMatch("The Friendly Ghost's House"))
        assertNull(f.firstMatch("Nintendo Switch unboxing"))
    }

    @Test
    fun `AI is only on when it has what it needs`() {
        assertFalse(ProviderConfig(ProviderType.ANTHROPIC).isAiEnabled)
        assertTrue(ProviderConfig(ProviderType.ANTHROPIC, apiKey = "k").isAiEnabled)
        assertTrue(ProviderConfig(ProviderType.OPENAI_COMPATIBLE, baseUrl = "http://192.168.1.5:11434/v1", model = "llava").isAiEnabled)
    }
}
