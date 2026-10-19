package app.safetube.core

import org.json.JSONArray
import org.json.JSONObject

/** Claude via the Anthropic Messages API, called directly from the device with the parent's own key. */
class AnthropicClassifier(
    private val config: ProviderConfig,
    private val endpoint: String = "https://api.anthropic.com/v1/messages",
) : VideoClassifier {

    override fun classify(videos: List<VideoInfo>, rules: String): List<Score> {
        if (videos.isEmpty()) return emptyList()
        val response = Http.request(
            endpoint,
            method = "POST",
            headers = mapOf(
                "x-api-key" to config.apiKey.trim(),
                "anthropic-version" to "2023-06-01",
            ),
            body = buildRequest(videos, rules).toString(),
        )
        if (response.code !in 200..299) {
            throw ClassifierException("Anthropic API returned ${response.code}: ${errorMessage(response.body)}")
        }
        val json = JSONObject(response.body)
        if (json.optString("stop_reason") == "refusal") {
            throw ClassifierException("The model declined to classify this batch")
        }
        val text = StringBuilder()
        val content = json.optJSONArray("content") ?: JSONArray()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") text.append(block.optString("text"))
        }
        return PromptBuilder.parseScores(text.toString(), videos.map { it.id })
    }

    internal fun buildRequest(videos: List<VideoInfo>, rules: String): JSONObject {
        val content = JSONArray()
        content.put(textBlock(PromptBuilder.rulesText(rules)))
        videos.forEachIndexed { i, v ->
            content.put(textBlock(PromptBuilder.videoText(i, v)))
            if (config.sendThumbnails) {
                content.put(
                    JSONObject()
                        .put("type", "image")
                        .put("source", JSONObject().put("type", "url").put("url", v.thumbnailUrl))
                )
            }
        }
        val model = config.modelOrDefault()
        val request = JSONObject()
            .put("model", model)
            .put("max_tokens", 4096)
            .put("system", PromptBuilder.SYSTEM)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        // A quick yes/no style judgement: low effort keeps it fast and cheap on models that support it.
        if (supportsEffort(model)) request.put("output_config", JSONObject().put("effort", "low"))
        return request
    }

    private fun textBlock(text: String) = JSONObject().put("type", "text").put("text", text)

    private fun errorMessage(body: String): String = try {
        JSONObject(body).getJSONObject("error").optString("message", body)
    } catch (e: Exception) {
        body.take(300)
    }

    companion object {
        private val NO_EFFORT = Regex("claude-3|claude-(haiku|sonnet)-4-5|claude-(sonnet|opus)-4($|-0|-1|-2)")
        fun supportsEffort(model: String) = model.startsWith("claude-") && !NO_EFFORT.containsMatchIn(model)
    }
}
