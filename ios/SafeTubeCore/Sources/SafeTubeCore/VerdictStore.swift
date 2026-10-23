import Foundation

/// Local memory of decisions, so a video is only ever sent to the AI once per set of rules.
/// The app backs this with SQLite; tests use [InMemoryVerdictStore].
public protocol VerdictStore: AnyObject {
    /// A cached AI score for [id] made under the rules identified by [configHash].
    func cachedScore(_ id: String, configHash: String) -> Score?
    func saveScore(_ score: Score, configHash: String, video: VideoInfo)

    /// A parent's manual allow/block for [id], which beats everything else.
    func parentOverride(_ id: String) -> Verdict?
    func setOverride(_ id: String, _ verdict: Verdict?)

    /// Remembers hidden videos so the parent can review them.
    func recordHidden(_ video: VideoInfo, _ decision: Decision)
}

public final class InMemoryVerdictStore: VerdictStore {
    public var scores: [String: Score] = [:]
    public var overrides: [String: Verdict] = [:]
    public var hidden: [(VideoInfo, Decision)] = []

    private let lock = NSLock()

    public init() {}

    private func locked<T>(_ body: () -> T) -> T { lock.lock(); defer { lock.unlock() }; return body() }

    public func cachedScore(_ id: String, configHash: String) -> Score? { locked { scores["\(configHash)/\(id)"] } }
    public func saveScore(_ score: Score, configHash: String, video: VideoInfo) { locked { scores["\(configHash)/\(score.id)"] = score } }
    public func parentOverride(_ id: String) -> Verdict? { locked { overrides[id] } }
    public func setOverride(_ id: String, _ verdict: Verdict?) { locked { overrides[id] = verdict } }
    public func recordHidden(_ video: VideoInfo, _ decision: Decision) { locked { hidden.append((video, decision)) } }
}
