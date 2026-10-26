import Foundation

/// What the filter knows about one video, scraped from the YouTube page.
public struct VideoInfo: Equatable {
    public var id: String
    public var title: String
    public var channel: String
    /// Any other text YouTube shows next to the video (byline, badges, description snippet).
    public var context: String
    /// The channel's handle ("@supersimplesongs") or id ("UC..."), when the page showed a channel link.
    public var channelId: String

    public init(_ id: String, _ title: String = "", _ channel: String = "", context: String = "", channelId: String = "") {
        self.id = id
        self.title = title
        self.channel = channel
        self.context = context
        self.channelId = channelId
    }

    public var thumbnailUrl: String { "https://i.ytimg.com/vi/\(id)/mqdefault.jpg" }

    /// All the text we have about the video, used for keyword matching.
    public var allText: String { [title, channel, context].joined(separator: " ") }

    private static let idPattern = try! NSRegularExpression(pattern: "^[A-Za-z0-9_-]{11}$")

    public static func isValidId(_ id: String) -> Bool { Re.matchesWhole(idPattern, id) }
}

public enum Verdict: String, CaseIterable {
    case allow = "ALLOW"
    case block = "BLOCK"
    /// Could not decide (AI unreachable, bad key...). The video stays hidden.
    case unknown = "UNKNOWN"

    /// The word the page script understands.
    public var wire: String {
        switch self {
        case .allow: return "allow"
        case .block: return "block"
        case .unknown: return "unknown"
        }
    }
}

/// Where a decision came from, shown to the parent so they can see why something was hidden.
public enum DecisionSource: String {
    case parent = "PARENT", channel = "CHANNEL", keyword = "KEYWORD", ai = "AI", noRules = "NO_RULES", error = "ERROR"
}

public struct Decision: Equatable {
    public let id: String
    public let verdict: Verdict
    public let source: DecisionSource
    /// Probability (0..1) that the video breaks the parent's rules, when the AI produced one.
    public let score: Double?
    public let reason: String
    public let freshAi: Bool

    public init(_ id: String, _ verdict: Verdict, _ source: DecisionSource, score: Double? = nil, reason: String = "", freshAi: Bool = false) {
        self.id = id
        self.verdict = verdict
        self.source = source
        self.score = score
        self.reason = reason
        self.freshAi = freshAi
    }
}

/// How cautious to be with the AI's probability. Strict hides anything the model isn't fairly sure is fine.
public enum Strictness: String, CaseIterable {
    case strict = "STRICT"
    case balanced = "BALANCED"
    case permissive = "PERMISSIVE"

    public var blockAtOrAbove: Double {
        switch self {
        case .strict: return 0.2
        case .balanced: return 0.5
        case .permissive: return 0.8
        }
    }

    public func verdictFor(_ score: Double) -> Verdict { score >= blockAtOrAbove ? .block : .allow }
}

public enum ProviderType: String, CaseIterable {
    /// Keyword list only; nothing leaves the device.
    case none = "NONE"
    /// Cloudflare's Clef decision model on Workers AI. Has a daily free allowance.
    case clef = "CLEF"
    /// TypeSafe's Jev decision model through OpenRouter's Decisions API.
    case jev = "JEV"
    case anthropic = "ANTHROPIC"
    /// OpenAI, Gemini, OpenRouter chat models, Groq, Ollama and anything else that speaks /chat/completions.
    case openAiCompatible = "OPENAI_COMPATIBLE"

    public var label: String {
        switch self {
        case .none: return "Off"
        case .clef: return "Clef (Cloudflare)"
        case .jev: return "Jev (OpenRouter)"
        case .anthropic: return "Claude (Anthropic)"
        case .openAiCompatible: return "OpenAI-compatible"
        }
    }
}

public struct ProviderConfig: Equatable {
    public var type: ProviderType
    public var apiKey: String
    /// Base URL for OpenAI-compatible providers, e.g. https://openrouter.ai/api/v1
    public var baseUrl: String
    public var model: String
    /// Cloudflare account id, needed for Clef.
    public var accountId: String
    public var sendThumbnails: Bool

    public init(_ type: ProviderType = .none, apiKey: String = "", baseUrl: String = "", model: String = "",
                accountId: String = "", sendThumbnails: Bool = true) {
        self.type = type
        self.apiKey = apiKey
        self.baseUrl = baseUrl
        self.model = model
        self.accountId = accountId
        self.sendThumbnails = sendThumbnails
    }

    public var isAiEnabled: Bool {
        switch type {
        case .none: return false
        case .clef: return !apiKey.isBlank && !accountId.isBlank
        case .jev, .anthropic: return !apiKey.isBlank
        // Local servers (Ollama etc.) often need no key.
        case .openAiCompatible: return !baseUrl.isBlank && !model.isBlank
        }
    }

    /// Decision models answer typed questions instead of writing text.
    public var isDecisionModel: Bool { type == .clef || type == .jev }

    /// Thumbnails are only sent to chat models; the decision-model APIs don't take image links.
    public var usesThumbnails: Bool { sendThumbnails && !isDecisionModel }

    public func modelOrDefault() -> String {
        if !model.isBlank { return model }
        switch type {
        case .clef: return ProviderConfig.defaultClefModel
        case .jev: return ProviderConfig.defaultJevModel
        case .anthropic: return ProviderConfig.defaultAnthropicModel
        case .openAiCompatible, .none: return ""
        }
    }

    public static let defaultClefModel = "clef"
    public static let defaultJevModel = "typesafe/jev-1.14"
    public static let defaultAnthropicModel = "claude-haiku-5-5"
}

public struct FilterSettings {
    /// The parent's plain-English description of what to hide.
    public var rules: String
    /// Words or phrases that always hide a video, no AI needed.
    public var blockedKeywords: [String]
    /// Channels whose videos are always hidden: "@handle", a "UC..." id, or the channel's name.
    public var blockedChannels: [String]
    public var strictness: Strictness
    public var provider: ProviderConfig
    public var allowedContent: String
    public var exceptions: String
    public var fallbackProvider: ProviderConfig
    public var blockOnAiFailure: Bool

    public init(rules: String = "", blockedKeywords: [String] = [], blockedChannels: [String] = [],
                strictness: Strictness = .strict, provider: ProviderConfig = ProviderConfig(),
                allowedContent: String = "", exceptions: String = "",
                fallbackProvider: ProviderConfig = ProviderConfig(), blockOnAiFailure: Bool = true) {
        self.rules = rules
        self.blockedKeywords = blockedKeywords
        self.blockedChannels = blockedChannels
        self.strictness = strictness
        self.provider = provider
        self.allowedContent = allowedContent
        self.exceptions = exceptions
        self.fallbackProvider = fallbackProvider
        self.blockOnAiFailure = blockOnAiFailure
    }

    public func effectiveRules() -> String {
        var parts: [String] = []
        if !rules.isBlank { parts.append("Hide content matching: " + rules.trimmed) }
        if !allowedContent.isBlank { parts.append("ONLY SHOW content matching this description; block everything outside it: " + allowedContent.trimmed) }
        if !exceptions.isBlank { parts.append("Parent clarification and exceptions to AI rules: " + exceptions.trimmed + ". This never overrides manual blocks or keyword blocks.") }
        return parts.joined(separator: "\n\n")
    }

    /// Identifies everything that changes what the AI would say about a video.
    /// Cached AI scores are only reused while this stays the same. Strictness is
    /// deliberately left out: scores are cached and the threshold is applied on read.
    public func aiConfigHash() -> String {
        let parts = [effectiveRules(), provider.type.rawValue, provider.baseUrl.trimmed,
            provider.modelOrDefault(), provider.accountId.trimmed, Hashing.sha256(provider.apiKey),
            String(provider.usesThumbnails), fallbackProvider.type.rawValue,
            fallbackProvider.baseUrl.trimmed, fallbackProvider.modelOrDefault(),
            fallbackProvider.accountId.trimmed, Hashing.sha256(fallbackProvider.apiKey),
            String(fallbackProvider.usesThumbnails), String(PromptBuilder.version)]
        return String(Hashing.sha256(parts.joined(separator: "\u{0}")).prefix(16))
    }
}
