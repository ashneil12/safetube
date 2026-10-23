import Foundation

/// Turns the parent's plain-English rules into a keyword list, so the instant offline check
/// catches as much as possible before any AI sees a video. Uses a chat model (Claude, Gemini,
/// OpenAI...) since decision models can't write lists. The parent reviews the list before saving.
public final class KeywordSuggester {
    private let config: ProviderConfig

    public init(_ config: ProviderConfig) { self.config = config }

    public func suggest(_ rules: String, existing: [String] = []) throws -> [String] {
        if rules.isBlank { return [] }
        var user = "The parent's rules:\n<rules>\n\(rules.trimmed)\n</rules>\n"
        if !existing.isEmpty { user += "\nKeywords they already have: \(existing.joined(separator: ", "))\n" }
        let reply: String
        switch config.type {
        case .anthropic: reply = try anthropic(user)
        case .openAiCompatible: reply = try openAi(user)
        default: throw ClassifierException("Keyword suggestions need a chat model (Claude, Gemini, OpenAI...)")
        }
        return try KeywordSuggester.parse(reply, existing: existing)
    }

    private func anthropic(_ user: String) throws -> String {
        var body: [String: Any] = [
            "model": config.modelOrDefault(),
            "max_tokens": 2048,
            "system": KeywordSuggester.system,
            "messages": [["role": "user", "content": user]],
        ]
        if AnthropicClassifier.supportsEffort(config.modelOrDefault()) { body["output_config"] = ["effort": "low"] }
        let r = try Http.request(
            "https://api.anthropic.com/v1/messages", method: "POST",
            headers: ["x-api-key": config.apiKey.trimmed, "anthropic-version": "2023-06-01"],
            body: JSON.string(body)
        )
        if !(200...299).contains(r.code) { throw ClassifierException("Anthropic API returned \(r.code): \(r.body.take(300))") }
        return AnthropicClassifier.text(of: JSON.object(r.body) ?? [:])
    }

    private func openAi(_ user: String) throws -> String {
        let url = OpenAiCompatibleClassifier.completionsUrl(config.baseUrl)
        let body: [String: Any] = [
            "model": config.modelOrDefault(),
            "temperature": 0,
            "messages": [
                ["role": "system", "content": KeywordSuggester.system],
                ["role": "user", "content": user],
            ],
        ]
        let headers = config.apiKey.isBlank ? [:] : ["Authorization": "Bearer \(config.apiKey.trimmed)"]
        let r = try Http.request(url, method: "POST", headers: headers, body: JSON.string(body))
        if !(200...299).contains(r.code) { throw ClassifierException("\(url) returned \(r.code): \(r.body.take(300))") }
        guard let text = OpenAiCompatibleClassifier.messageContent(r.body) else {
            throw ClassifierException("Unexpected reply from \(url): \(r.body.take(200))")
        }
        return text
    }

    public static let system = """
        You help a parent set up a YouTube filter for their young child. The filter hides any
        video whose title, channel or description contains one of a list of keywords.

        From the parent's rules, list the words and short phrases that would appear in the
        titles of videos the parent wants hidden: the obvious words, synonyms, related
        characters and objects, seasonal terms, and common spellings kids' channels use.

        Leave out words that also appear in lots of harmless children's videos, and anything
        the parent says is fine; a wrong keyword hides good videos. Prefer single words.

        Reply with JSON only: {"keywords": ["...", "..."]} with 15 to 60 lowercase entries.
        """

    public static func parse(_ reply: String, existing: [String]) throws -> [String] {
        guard let start = reply.firstIndex(of: "{"), let end = reply.lastIndex(of: "}"), start < end else {
            throw ClassifierException("Model reply had no JSON: \(reply.take(200))")
        }
        guard let o = JSON.object(String(reply[start...end])), let arr = o["keywords"] as? [Any] else {
            throw ClassifierException("Model reply had no keyword list: \(reply.take(200))")
        }
        var have = Set(existing.map { $0.lowercased() })
        return arr.map { item -> String in
            if let s = item as? String { return s.trimmed.lowercased() }
            if let n = JSON.number(item) { return String(n).lowercased() }
            return ""
        }.filter { !$0.isEmpty && $0.count <= 40 && have.insert($0).inserted }
    }
}
