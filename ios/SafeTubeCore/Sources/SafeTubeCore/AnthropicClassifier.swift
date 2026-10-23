import Foundation

/// Claude via the Anthropic Messages API, called directly from the device with the parent's own key.
public final class AnthropicClassifier: VideoClassifier {
    private let config: ProviderConfig
    private let endpoint: String

    public init(_ config: ProviderConfig, endpoint: String = "https://api.anthropic.com/v1/messages") {
        self.config = config
        self.endpoint = endpoint
    }

    public func classify(_ videos: [VideoInfo], rules: String) throws -> [Score] {
        if videos.isEmpty { return [] }
        let response = try Http.request(
            endpoint,
            method: "POST",
            headers: [
                "x-api-key": config.apiKey.trimmed,
                "anthropic-version": "2023-06-01",
            ],
            body: JSON.string(buildRequest(videos, rules: rules))
        )
        if !(200...299).contains(response.code) {
            throw ClassifierException("Anthropic API returned \(response.code): \(AnthropicClassifier.errorMessage(response.body))")
        }
        let json = JSON.object(response.body) ?? [:]
        if JSON.optString(json, "stop_reason") == "refusal" {
            throw ClassifierException("The model declined to classify this batch")
        }
        return try PromptBuilder.parseScores(AnthropicClassifier.text(of: json), expectedIds: videos.map { $0.id })
    }

    public func buildRequest(_ videos: [VideoInfo], rules: String) -> [String: Any] {
        var content: [[String: Any]] = [textBlock(PromptBuilder.rulesText(rules))]
        for (i, v) in videos.enumerated() {
            content.append(textBlock(PromptBuilder.videoText(i, v)))
            if config.usesThumbnails {
                content.append(["type": "image", "source": ["type": "url", "url": v.thumbnailUrl]])
            }
        }
        let model = config.modelOrDefault()
        var request: [String: Any] = [
            "model": model,
            "max_tokens": 4096,
            "system": PromptBuilder.system,
            "messages": [["role": "user", "content": content]],
        ]
        // A quick yes/no style judgement: low effort keeps it fast and cheap on models that support it.
        if AnthropicClassifier.supportsEffort(model) { request["output_config"] = ["effort": "low"] }
        return request
    }

    private func textBlock(_ text: String) -> [String: Any] { ["type": "text", "text": text] }

    /// All the text blocks of a Messages API reply, joined.
    static func text(of json: [String: Any]) -> String {
        let content = json["content"] as? [Any] ?? []
        return content.compactMap { $0 as? [String: Any] }
            .filter { JSON.optString($0, "type") == "text" }
            .map { JSON.optString($0, "text") }
            .joined()
    }

    static func errorMessage(_ body: String) -> String {
        if let o = JSON.object(body), let err = o["error"] as? [String: Any] {
            let m = JSON.optString(err, "message")
            return err["message"] == nil ? body : m
        }
        return body.take(300)
    }

    private static let noEffort = Re.make("claude-3|claude-(haiku|sonnet)-4-5|claude-(sonnet|opus)-4($|-0|-1|-2)")

    public static func supportsEffort(_ model: String) -> Bool {
        model.hasPrefix("claude-") && !Re.contains(noEffort, model)
    }
}
