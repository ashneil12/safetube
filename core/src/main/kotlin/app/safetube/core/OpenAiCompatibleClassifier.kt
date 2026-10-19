package app.safetube.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Any endpoint that speaks the OpenAI chat-completions format: OpenAI, OpenRouter, Groq,
 * Together, a local Ollama or LM Studio server, and so on.
 */
class OpenAiCompatibleClassifier(private val config: ProviderConfig) : VideoClassifier {

    override fun classify(videos: List<VideoInfo>, rules: String): List<Score> {
        if (videos.isEmpty()) return emptyList()
        val url = config.baseUrl.trim().trimEnd('/') + "/chat/completions"
        val headers = buildMap {
            if (config.apiKey.isNotBlank()) put("Authorization", "Bearer ${config.apiKey.trim()}")
            // Optional attribution headers OpenRouter asks for; harmless elsewhere.
            put("HTTP-Referer", "https://github.com/ashneil12/safetube")
            put("X-Title", "SafeTube")
        }
        val response = Http.request(url, "POST", headers, buildRequest(videos, rules).toString())
        if (response.code !in 200..299) {
            throw ClassifierException("$url returned ${response.code}: ${response.body.take(300)}")
        }
        val text = try {
            JSONObject(response.body)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content")
        } catch (e: Exception) {
            throw ClassifierException("Unexpected reply from $url: ${response.body.take(200)}", e)
        }
        return PromptBuilder.parseScores(text, videos.map { it.id })
    }

    internal fun buildRequest(videos: List<VideoInfo>, rules: String): JSONObject {
        val parts = JSONArray()
        parts.put(JSONObject().put("type", "text").put("text", PromptBuilder.rulesText(rules)))
        videos.forEachIndexed { i, v ->
            parts.put(JSONObject().put("type", "text").put("text", PromptBuilder.videoText(i, v)))
            if (config.usesThumbnails) {
                parts.put(
                    JSONObject().put("type", "image_url")
                        .put("image_url", JSONObject().put("url", v.thumbnailUrl))
                )
            }
        }
        return JSONObject()
            .put("model", config.modelOrDefault())
            .put("temperature", 0)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", PromptBuilder.SYSTEM))
                    .put(JSONObject().put("role", "user").put("content", parts))
            )
    }
}
