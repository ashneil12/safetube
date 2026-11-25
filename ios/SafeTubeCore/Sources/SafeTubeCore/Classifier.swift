import Foundation

/// The AI's opinion of one video: probability [p] that it breaks the parent's rules.
public struct Score: Equatable {
    public let id: String
    public let p: Double
    public let reason: String

    public init(_ id: String, _ p: Double, _ reason: String) {
        self.id = id
        self.p = p
        self.reason = reason
    }
}

public struct ClassifierException: Error, CustomStringConvertible, LocalizedError {
    public let message: String
    public init(_ message: String) { self.message = message }
    public var description: String { message }
    public var errorDescription: String? { message }
}

/// The one thing every AI provider has to do. Swap providers by swapping implementations;
/// nothing else in the app knows which model is behind it.
public protocol VideoClassifier {
    /// Scores a batch of videos. May return fewer scores than videos if the model skipped some.
    /// `rules` is the parent's combined rules prose; `onlyShow` tells the checker explicitly that an
    /// ONLY SHOW description is active, so a video may only score low (be allowed) on positive evidence
    /// it belongs inside that description. Chat models read this in the prose itself; decision models
    /// use it to frame the typed question as eligibility rather than general harmlessness.
    func classify(_ videos: [VideoInfo], rules: String, onlyShow: Bool) throws -> [Score]
}

public extension VideoClassifier {
    func classify(_ videos: [VideoInfo], rules: String) throws -> [Score] {
        try classify(videos, rules: rules, onlyShow: false)
    }
}

public enum Classifiers {
    public static func create(_ config: ProviderConfig) -> VideoClassifier? {
        switch config.type {
        case .none: return nil
        case .clef, .jev, .openRouterDecisions: return DecisionModelClassifier(config)
        case .anthropic: return AnthropicClassifier(config)
        case .openAiCompatible: return OpenAiCompatibleClassifier(config)
        }
    }
}
