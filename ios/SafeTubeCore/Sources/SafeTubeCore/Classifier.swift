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
    func classify(_ videos: [VideoInfo], rules: String) throws -> [Score]
}

public enum Classifiers {
    public static func create(_ config: ProviderConfig) -> VideoClassifier? {
        switch config.type {
        case .none: return nil
        case .clef, .jev: return DecisionModelClassifier(config)
        case .anthropic: return AnthropicClassifier(config)
        case .openAiCompatible: return OpenAiCompatibleClassifier(config)
        }
    }
}
