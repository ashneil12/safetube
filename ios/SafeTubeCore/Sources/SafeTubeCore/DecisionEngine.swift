import Foundation

/// Decides, for each video, whether the child may see it. In order:
///
/// 1. A parent's manual allow/block for this exact video always wins.
/// 2. Videos from channels the parent blocked are hidden.
/// 3. The keyword list hides matches instantly, offline.
/// 4. With no AI rules configured, everything else is allowed.
/// 5. A cached AI score for these exact rules is reused.
/// 6. Everything left is sent to the AI in batches.
///
/// Anything that can't be decided comes back `.unknown`, which the page keeps hidden.
/// This class blocks; call it off the main thread. Pass `aiPool` to check AI batches side by side.
/// (A port of core/DecisionEngine.kt.)
public final class DecisionEngine {
    private let store: VerdictStore
    private let classifierFactory: (ProviderConfig) -> VideoClassifier?
    private let metadata: MetadataFetcher?
    private let batchSize: Int
    /// Runs AI batches and title look-ups concurrently. Never call decide() from this queue itself.
    private let aiPool: OperationQueue?

    public init(
        _ store: VerdictStore,
        classifierFactory: @escaping (ProviderConfig) -> VideoClassifier? = Classifiers.create,
        metadata: MetadataFetcher? = YouTubeOEmbed.shared,
        batchSize: Int = 12,
        aiPool: OperationQueue? = nil
    ) {
        self.store = store
        self.classifierFactory = classifierFactory
        self.metadata = metadata
        self.batchSize = max(1, batchSize)
        self.aiPool = aiPool
    }

    /// Every decision at once, in input order.
    public func decide(_ input: [VideoInfo], _ settings: FilterSettings, forceFresh: Bool = false) -> [Decision] {
        let lock = NSLock()
        var all: [String: Decision] = [:]
        decide(input, settings, forceFresh: forceFresh) { part in
            lock.lock()
            for d in part { all[d.id] = d }
            lock.unlock()
        }
        var seen = Set<String>()
        return input.filter { seen.insert($0.id).inserted }.compactMap { all[$0.id] }
    }

    /// Same as the other decide(), but hands results over as soon as they're known: everything that
    /// needs no AI first, then each AI batch as it comes back. Returns once every video has been sent.
    /// `emit` may be called from several threads at once when `aiPool` is set.
    public func decide(_ input: [VideoInfo], _ settings: FilterSettings, forceFresh: Bool = false, emit: @escaping ([Decision]) -> Void) {
        var seenIds = Set<String>()
        let videos = input.filter { VideoInfo.isValidId($0.id) && seenIds.insert($0.id).inserted }
        var decisions: [String: Decision] = [:]
        let keywords = KeywordFilter(settings.blockedKeywords)
        let channels = ChannelBlocklist(settings.blockedChannels)
        let aiOn = settings.requiresAi
        let hash = settings.aiConfigHash()
        var needAi: [VideoInfo] = []
        var byId: [String: VideoInfo] = [:]

        let looked = lookUpTitles(videos.filter { $0.title.isBlank && store.parentOverride($0.id) == nil })

        for raw in videos {
            if let parent = store.parentOverride(raw.id) {
                decisions[raw.id] = Decision(raw.id, parent, .parent, reason: "Set by parent")
                continue
            }
            let v = looked[raw.id].map { DecisionEngine.merge($0, into: raw) } ?? raw
            byId[v.id] = v

            if let channel = channels.match(v) {
                decisions[v.id] = Decision(v.id, .block, .channel, reason: "Blocked channel: \(channel)")
                continue
            }
            if let kw = keywords.firstMatch(v.allText) {
                decisions[v.id] = Decision(v.id, .block, .keyword, reason: "Keyword: \(kw)")
                continue
            }
            if !aiOn {
                decisions[v.id] = Decision(v.id, .allow, .noRules)
                continue
            }
            if !forceFresh, let cached = store.cachedScore(v.id, configHash: hash) {
                decisions[v.id] = fromScore(cached, settings.strictness)
                continue
            }
            if v.title.isBlank && !settings.provider.usesThumbnails && !settings.fallbackProvider.usesThumbnails {
                decisions[v.id] = Decision(v.id, .unknown, .error, reason: "No title to check")
                continue
            }
            needAi.append(v)
        }

        // Keep input order; record hidden ones for the parent's review list.
        let hiddenLog = self.store
        let known = byId
        func send(_ list: [VideoInfo], _ found: [String: Decision]) {
            let out: [Decision] = list.compactMap { v in
                guard let d = found[v.id] else { return nil }
                if d.verdict == .block && d.source != .parent { hiddenLog.recordHidden(known[v.id] ?? v, d) }
                return d
            }
            if !out.isEmpty { emit(out) }
        }
        send(videos, decisions)
        if needAi.isEmpty { return }

        let classifier = classifierFactory(settings.provider)
        let batches = stride(from: 0, to: needAi.count, by: batchSize).map {
            Array(needAi[$0..<min($0 + batchSize, needAi.count)])
        }
        let run: ([VideoInfo]) -> Void = { batch in
            send(batch, self.classifyBatch(classifier, batch, settings, hash))
        }
        if let pool = aiPool, batches.count > 1 {
            pool.addOperations(batches.map { b in BlockOperation { run(b) } }, waitUntilFinished: true)
        } else {
            batches.forEach(run)
        }
    }

    /// Titles for videos the page couldn't read, fetched side by side when there's a pool.
    private func lookUpTitles(_ videos: [VideoInfo]) -> [String: VideoInfo] {
        guard let m = metadata, !videos.isEmpty else { return [:] }
        let lock = NSLock()
        var got: [String: VideoInfo] = [:]
        let fetch: (VideoInfo) -> Void = { v in
            guard let info = m.fetch(v.id) else { return }
            lock.lock()
            got[v.id] = info
            lock.unlock()
        }
        if let pool = aiPool, videos.count > 1 {
            pool.addOperations(videos.map { v in BlockOperation { fetch(v) } }, waitUntilFinished: true)
        } else {
            videos.forEach(fetch)
        }
        return got
    }

    private func classifyBatch(_ classifier: VideoClassifier?, _ batch: [VideoInfo], _ settings: FilterSettings,
                               _ hash: String) -> [String: Decision] {
        var got: [String: Score] = [:]
        var failure = "No valid AI score returned"
        func attempt(_ config: ProviderConfig, _ candidate: VideoClassifier?, _ pending: [VideoInfo]) {
            guard !pending.isEmpty else { return }
            do {
                guard config.isAiEnabled, let c = candidate else {
                    throw ClassifierException("AI provider is missing or misconfigured")
                }
                let wanted = Set(pending.map { $0.id })
                for score in try c.classify(pending, rules: settings.effectiveRules()) {
                    if wanted.contains(score.id), score.p.isFinite, (0...1).contains(score.p) { got[score.id] = score }
                }
            } catch { failure = (error as? ClassifierException)?.message ?? error.localizedDescription }
        }
        attempt(settings.provider, classifier, batch)
        let missing = batch.filter { got[$0.id] == nil }
        if !missing.isEmpty, settings.fallbackProvider.type != .none {
            attempt(settings.fallbackProvider, classifierFactory(settings.fallbackProvider), missing)
        }
        var out: [String: Decision] = [:]
        for v in batch {
            if let score = got[v.id] {
                store.saveScore(score, configHash: hash, video: v)
                out[v.id] = fromScore(score, settings.strictness, freshAi: true)
            } else {
                out[v.id] = Decision(v.id, .unknown, .error, reason: failure)
            }
        }
        return out
    }

    private func fromScore(_ s: Score, _ strictness: Strictness, freshAi: Bool = false) -> Decision {
        Decision(s.id, strictness.verdictFor(s.p), .ai, score: s.p, reason: s.reason, freshAi: freshAi)
    }

    /// Fills the gaps in what the page showed with what oEmbed said.
    private static func merge(_ looked: VideoInfo, into page: VideoInfo) -> VideoInfo {
        var v = page
        v.title = looked.title.ifBlank(page.title)
        v.channel = page.channel.ifBlank(looked.channel)
        v.channelId = page.channelId.ifBlank(looked.channelId)
        return v
    }
}
