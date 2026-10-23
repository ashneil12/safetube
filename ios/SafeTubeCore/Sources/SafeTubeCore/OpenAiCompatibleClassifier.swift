import Foundation

/// Any endpoint that speaks the OpenAI chat-completions format: OpenAI, OpenRouter, Groq,
/// Together, a local Ollama or LM Studio server, and so on.
public final class OpenAiCompatibleClassifier: VideoClassifier {
    private let config: ProviderConfig

    public init(_ config: ProviderConfig) { self.config = config }

    public func classify(_ videos: [VideoInfo], rules: String) throws -> [Score] {
        if videos.isEmpty { return [] }
        let url = OpenAiCompatibleClassifier.completionsUrl(config.baseUrl)
        var headers: [String: String] = [:]
        if !config.apiKey.isBlank { headers["Authorization"] = "Bearer \(config.apiKey.trimmed)" }
        // Optional attribution headers OpenRouter asks for; harmless elsewhere.
        headers["HTTP-Referer"] = "https://github.com/ashneil12/safetube"
        headers["X-Title"] = "SafeTube"
        let response = try Http.request(url, method: "POST", headers: headers, body: JSON.string(buildRequest(videos, rules: rules)))
        if !(200...299).contains(response.code) {
            throw ClassifierException("\(url) returned \(response.code): \(response.body.take(300))")
        }
        guard let text = OpenAiCompatibleClassifier.messageContent(response.body) else {
            throw ClassifierException("Unexpected reply from \(url): \(response.body.take(200))")
        }
        return try PromptBuilder.parseScores(text, expectedIds: videos.map { $0.id })
    }

    public func buildRequest(_ videos: [VideoInfo], rules: String) -> [String: Any] {
        var parts: [[String: Any]] = [["type": "text", "text": PromptBuilder.rulesText(rules)]]
        for (i, v) in videos.enumerated() {
            parts.append(["type": "text", "text": PromptBuilder.videoText(i, v)])
            if config.usesThumbnails {
                parts.append(["type": "image_url", "image_url": ["url": v.thumbnailUrl]])
            }
        }
        return [
            "model": config.modelOrDefault(),
            "temperature": 0,
            "messages": [
                ["role": "system", "content": PromptBuilder.system],
                ["role": "user", "content": parts],
            ],
        ]
    }

    static func completionsUrl(_ baseUrl: String) -> String {
        var base = baseUrl.trimmed
        while base.hasSuffix("/") { base.removeLast() }
        return base + "/chat/completions"
    }

    /// choices[0].message.content, or nil if the reply isn't shaped like that.
    static func messageContent(_ body: String) -> String? {
        guard let o = JSON.object(body), let choices = o["choices"] as? [Any],
              let first = choices.first as? [String: Any],
              let message = first["message"] as? [String: Any] else { return nil }
        return JSON.optString(message, "content")
    }
}
