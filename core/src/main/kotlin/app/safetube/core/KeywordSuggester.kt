package app.safetube.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns the parent's plain-English rules into a keyword list, so the instant offline check
 * catches as much as possible before any AI sees a video. Uses a chat model (Claude, Gemini,
 * OpenAI...) since decision models can't write lists. The parent reviews the list before saving.
 */
class KeywordSuggester(private val config: ProviderConfig) {

    fun suggest(rules: String, existing: List<String> = emptyList()): List<String> {
        if (rules.isBlank()) return emptyList()
        val user = buildString {
            append("The parent's rules:\n<rules>\n").append(rules.trim()).append("\n</rules>\n")
            if (existing.isNotEmpty()) append("\nKeywords they already have: ").append(existing.joinToString(", ")).append('\n')
        }
        val reply = when (config.type) {
            ProviderType.ANTHROPIC -> anthropic(user)
            ProviderType.OPENAI_COMPATIBLE -> openAi(user)
            else -> throw ClassifierException("Keyword suggestions need a chat model (Claude, Gemini, OpenAI...)")
        }
        return parse(reply, existing)
    }

    private fun anthropic(user: String): String {
        val body = JSONObject()
            .put("model", config.modelOrDefault())
            .put("max_tokens", 2048)
            .put("system", SYSTEM)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
        if (AnthropicClassifier.supportsEffort(config.modelOrDefault())) {
            body.put("output_config", JSONObject().put("effort", "low"))
        }
        val r = Http.request(
            "https://api.anthropic.com/v1/messages", "POST",
            mapOf("x-api-key" to config.apiKey.trim(), "anthropic-version" to "2023-06-01"),
            body.toString(),
        )
        if (r.code !in 200..299) throw ClassifierException("Anthropic API returned ${r.code}: ${r.body.take(300)}")
        val content = JSONObject(r.body).optJSONArray("content") ?: JSONArray()
        return (0 until content.length()).mapNotNull { content.optJSONObject(it) }
            .filter { it.optString("type") == "text" }
            .joinToString("") { it.optString("text") }
    }

    private fun openAi(user: String): String {
        val url = config.baseUrl.trim().trimEnd('/') + "/chat/completions"
        val body = JSONObject()
            .put("model", config.modelOrDefault())
            .put("temperature", 0)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", SYSTEM))
                    .put(JSONObject().put("role", "user").put("content", user))
            )
        val headers = if (config.apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${config.apiKey.trim()}")
        val r = Http.request(url, "POST", headers, body.toString())
        if (r.code !in 200..299) throw ClassifierException("$url returned ${r.code}: ${r.body.take(300)}")
        return try {
            JSONObject(r.body).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
        } catch (e: Exception) {
            throw ClassifierException("Unexpected reply from $url: ${r.body.take(200)}", e)
        }
    }

    companion object {
        val SYSTEM = """
            You help a parent set up a YouTube filter for their young child. The filter hides any
            video whose title, channel or description contains one of a list of keywords.

            From the parent's rules, list the words and short phrases that would appear in the
            titles of videos the parent wants hidden: the obvious words, synonyms, related
            characters and objects, seasonal terms, and common spellings kids' channels use.

            Leave out words that also appear in lots of harmless children's videos, and anything
            the parent says is fine; a wrong keyword hides good videos. Prefer single words.

            Reply with JSON only: {"keywords": ["...", "..."]} with 15 to 60 lowercase entries.
        """.trimIndent()

        internal fun parse(reply: String, existing: List<String>): List<String> {
            val start = reply.indexOf('{')
            val end = reply.lastIndexOf('}')
            if (start < 0 || end <= start) throw ClassifierException("Model reply had no JSON: ${reply.take(200)}")
            val arr = try {
                JSONObject(reply.substring(start, end + 1)).getJSONArray("keywords")
            } catch (e: Exception) {
                throw ClassifierException("Model reply had no keyword list: ${reply.take(200)}", e)
            }
            val have = existing.map { it.lowercase() }.toHashSet()
            return (0 until arr.length())
                .map { arr.optString(it).trim().lowercase() }
                .filter { it.isNotEmpty() && it.length <= 40 && have.add(it) }
        }
    }
}
