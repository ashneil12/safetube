import XCTest
@testable import SafeTubeCore

/// The same cases as core/src/test (Kotlin), so both engines are held to the same behaviour.
final class DecisionEngineTests: XCTestCase {
    let pumpkins = VideoInfo("aaaaaaaaaaa", "Five Little Pumpkins | Halloween Song", "Super Simple Songs")
    let bus = VideoInfo("bbbbbbbbbbb", "Wheels On The Bus", "Super Simple Songs")
    let potion = VideoInfo("ccccccccccc", "Make a Potion! Bubble Bubble", "Super Simple Songs")

    let aiSettings = FilterSettings(
        rules: "No witches, Halloween, ghosts or spooky things.",
        provider: ProviderConfig(.anthropic, apiKey: "test")
    )

    /// A stand-in model that counts how often it is called.
    final class FakeClassifier: VideoClassifier {
        let scores: [String: Double]
        var calls = 0
        var seen: [String] = []
        init(_ scores: [String: Double]) { self.scores = scores }
        func classify(_ videos: [VideoInfo], rules: String) throws -> [Score] {
            calls += 1
            seen += videos.map { $0.id }
            return videos.compactMap { v in scores[v.id].map { Score(v.id, $0, "fake") } }
        }
    }

    struct Failing: VideoClassifier {
        func classify(_ videos: [VideoInfo], rules: String) throws -> [Score] { throw ClassifierException("401 invalid key") }
    }

    func engine(_ store: VerdictStore, _ fake: VideoClassifier?, _ metadata: MetadataFetcher? = nil) -> DecisionEngine {
        DecisionEngine(store, classifierFactory: { _ in fake }, metadata: metadata)
    }

    func withKeywords(_ s: FilterSettings, _ k: [String]) -> FilterSettings { var c = s; c.blockedKeywords = k; return c }

    func testKeywordMatchHidesWithoutCallingTheAi() {
        let fake = FakeClassifier([bus.id: 0.01])
        let d = engine(InMemoryVerdictStore(), fake).decide([pumpkins, bus], withKeywords(aiSettings, ["halloween"]))
        XCTAssertEqual(d[0].verdict, .block)
        XCTAssertEqual(d[0].source, .keyword)
        XCTAssertEqual(d[1].verdict, .allow)
        XCTAssertEqual(fake.seen, [bus.id])
    }

    func testStrictnessChangesTheVerdictForUncertainScores() {
        let fake = FakeClassifier([potion.id: 0.35])
        let store = InMemoryVerdictStore()
        XCTAssertEqual(engine(store, fake).decide([potion], aiSettings).first?.verdict, .block)
        var balanced = aiSettings
        balanced.strictness = .balanced
        XCTAssertEqual(engine(store, fake).decide([potion], balanced).first?.verdict, .allow)
        XCTAssertEqual(fake.calls, 1)
    }

    func testChangingTheRulesReasksTheAi() {
        let fake = FakeClassifier([bus.id: 0.0])
        let store = InMemoryVerdictStore()
        _ = engine(store, fake).decide([bus], aiSettings)
        var other = aiSettings
        other.rules = "No buses"
        _ = engine(store, fake).decide([bus], other)
        XCTAssertEqual(fake.calls, 2)
    }

    func testAiFailureLeavesVideosUnknown() {
        let d = engine(InMemoryVerdictStore(), Failing()).decide([bus], aiSettings)
        XCTAssertEqual(d.first?.verdict, .unknown)
        XCTAssertTrue(d.first?.reason.contains("401") ?? false)
    }

    func testSkippedVideosAreUnknownNotAllowed() {
        let d = engine(InMemoryVerdictStore(), FakeClassifier([bus.id: 0.0])).decide([bus, potion], aiSettings)
        XCTAssertEqual(d.map { $0.verdict }, [.allow, .unknown])
    }

    func testParentOverrideBeatsAiAndKeywords() {
        let store = InMemoryVerdictStore()
        store.setOverride(pumpkins.id, .allow)
        let fake = FakeClassifier([:])
        let d = engine(store, fake).decide([pumpkins], withKeywords(aiSettings, ["halloween"]))
        XCTAssertEqual(d.first?.verdict, .allow)
        XCTAssertEqual(fake.calls, 0)
    }

    func testNoRulesAllowsEverythingNotOnTheKeywordList() {
        let d = engine(InMemoryVerdictStore(), nil).decide([pumpkins, bus], FilterSettings(blockedKeywords: ["pumpkin"]))
        XCTAssertEqual(d.map { $0.verdict }, [.block, .allow])
    }

    func testMissingTitlesAreLookedUp() {
        let p = pumpkins
        let lookup = MetadataLookup { id in id == p.id ? p : nil }
        let d = engine(InMemoryVerdictStore(), nil, lookup).decide([VideoInfo(p.id)], FilterSettings(blockedKeywords: ["halloween"]))
        XCTAssertEqual(d.first?.verdict, .block)
    }

    func testHiddenVideosAreRecorded() {
        let store = InMemoryVerdictStore()
        _ = engine(store, FakeClassifier([pumpkins.id: 0.99])).decide([pumpkins], aiSettings)
        XCTAssertEqual(store.hidden.map { $0.0.title }, [pumpkins.title])
    }

    func testInvalidIdsDroppedAndDuplicatesCollapsed() {
        let fake = FakeClassifier([bus.id: 0.0])
        let d = engine(InMemoryVerdictStore(), fake).decide([bus, bus, VideoInfo("not-an-id")], aiSettings)
        XCTAssertEqual(d.count, 1)
        XCTAssertEqual(fake.seen.count, 1)
    }

    func testLargePagesAreBatched() {
        let many = (0..<30).map { i in VideoInfo("v" + String(repeating: "0", count: 10 - String(i).count) + String(i), "Song \(i)") }
        let fake = FakeClassifier(Dictionary(uniqueKeysWithValues: many.map { ($0.id, 0.0) }))
        _ = DecisionEngine(InMemoryVerdictStore(), classifierFactory: { _ in fake }, metadata: nil, batchSize: 12).decide(many, aiSettings)
        XCTAssertEqual(fake.calls, 3)
    }

    /// Only answers once all three batches are in flight together.
    final class Slow: VideoClassifier {
        let started = DispatchGroup()
        let lock = NSLock()
        var waiting = 3
        let allIn = DispatchSemaphore(value: 0)
        func classify(_ videos: [VideoInfo], rules: String) throws -> [Score] {
            lock.lock()
            waiting -= 1
            if waiting == 0 { for _ in 0..<3 { allIn.signal() } }
            lock.unlock()
            XCTAssertEqual(allIn.wait(timeout: .now() + 5), .success)
            return videos.map { Score($0.id, 0.0, "fake") }
        }
    }

    func testKeywordAnswersArriveFirstAndBatchesRunSideBySide() {
        let many = (0..<24).map { i in VideoInfo("v" + String(repeating: "0", count: 10 - String(i).count) + String(i), "Song \(i)") }
        let pool = OperationQueue()
        pool.maxConcurrentOperationCount = 3
        let lock = NSLock()
        var parts: [[Decision]] = []
        let slow = Slow()
        DecisionEngine(InMemoryVerdictStore(), classifierFactory: { _ in slow }, metadata: nil, batchSize: 8, aiPool: pool)
            .decide([pumpkins] + many, withKeywords(aiSettings, ["halloween"])) { part in
                lock.lock(); parts.append(part); lock.unlock()
            }
        XCTAssertEqual(parts.first?.map { $0.id }, [pumpkins.id])
        XCTAssertEqual(parts.count, 4)
        XCTAssertEqual(Set(parts.flatMap { $0.map { $0.id } }).count, 25)
    }
}

final class BlockingAndDecisionModelTests: XCTestCase {
    let sss = VideoInfo("aaaaaaaaaaa", "Wheels On The Bus", "Super Simple Songs · 3M views", channelId: "@SuperSimpleSongs")
    let other = VideoInfo("bbbbbbbbbbb", "Counting to 10", "Numberblocks", channelId: "/channel/UCPlwvN0w4qFSP1FllALB92w")

    func testChannelInputsAreUnderstood() {
        XCTAssertEqual(ChannelBlocklist.parse("https://www.youtube.com/@SuperSimpleSongs/videos"), "@SuperSimpleSongs")
        XCTAssertEqual(ChannelBlocklist.parse("https://m.youtube.com/channel/UCPlwvN0w4qFSP1FllALB92w"), "UCPlwvN0w4qFSP1FllALB92w")
        XCTAssertEqual(ChannelBlocklist.parse("  Super Simple Songs "), "Super Simple Songs")
        XCTAssertNil(ChannelBlocklist.parse("   "))
        XCTAssertTrue(ChannelBlocklist.isChannelLink("@bluey"))
        XCTAssertFalse(ChannelBlocklist.isChannelLink("Bluey Official"))
    }

    func testBlockedChannelMatches() {
        XCTAssertEqual(ChannelBlocklist(["@supersimplesongs"]).match(sss)?.lowercased(), "@supersimplesongs")
        XCTAssertNotNil(ChannelBlocklist(["UCPlwvN0w4qFSP1FllALB92w"]).match(other))
        XCTAssertNotNil(ChannelBlocklist(["super simple songs"]).match(sss))
        XCTAssertNil(ChannelBlocklist(["Simple"]).match(other))
        XCTAssertNil(ChannelBlocklist(["Wheels"]).match(sss))
        XCTAssertNotNil(ChannelBlocklist(["UCLA Kids"]).match(VideoInfo("ccccccccccc", "x", "UCLA Kids")))
    }

    func testVideoLinks() {
        XCTAssertEqual(VideoLinks.videoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10"), "dQw4w9WgXcQ")
        XCTAssertEqual(VideoLinks.videoId("https://youtu.be/dQw4w9WgXcQ?si=abc"), "dQw4w9WgXcQ")
        XCTAssertEqual(VideoLinks.videoId("https://youtube.com/shorts/dQw4w9WgXcQ"), "dQw4w9WgXcQ")
        XCTAssertEqual(VideoLinks.videoId("dQw4w9WgXcQ"), "dQw4w9WgXcQ")
        XCTAssertNil(VideoLinks.videoId("https://www.youtube.com/@SuperSimpleSongs"))
    }

    func testEngineHidesBlockedChannelsButParentAllowWins() {
        let store = InMemoryVerdictStore()
        let settings = FilterSettings(blockedChannels: ["@SuperSimpleSongs"])
        let engine = DecisionEngine(store, classifierFactory: { _ in nil }, metadata: nil)
        let d = engine.decide([sss, other], settings)
        XCTAssertEqual(d.map { $0.verdict }, [.block, .allow])
        XCTAssertEqual(d[0].source, .channel)
        store.setOverride(sss.id, .allow)
        XCTAssertEqual(engine.decide([sss], settings).first?.verdict, .allow)
    }

    func testSingleBlockedVideoStaysBlocked() {
        let store = InMemoryVerdictStore()
        store.setOverride(other.id, .block)
        XCTAssertEqual(DecisionEngine(store, classifierFactory: { _ in nil }, metadata: nil).decide([other], FilterSettings()).first?.verdict, .block)
    }

    func testClefRequest() {
        let c = DecisionModelClassifier(ProviderConfig(.clef, apiKey: "t", accountId: "acc123"))
        XCTAssertEqual(c.endpoint(), "https://api.cloudflare.com/client/v4/accounts/acc123/ai/run/@cf/cloudflare/clef")
        let req = c.buildRequest([sss, other], rules: "No witches")
        XCTAssertEqual(req["model"] as? String, "clef")
        let state = req["state"] as? [String: Any]
        XCTAssertEqual(state?["parent_rules"] as? String, "No witches")
        let v1 = (state?["videos"] as? [String: Any])?["v1"] as? [String: Any]
        XCTAssertEqual(v1?["title"] as? String, "Wheels On The Bus")
        let q = (req["questions"] as? [String: Any])?["v2"] as? [String: Any]
        XCTAssertEqual(q?["type"] as? String, "noul")
        XCTAssertNotNil((q?["criteria"] as? [String: Any])?["true"])
        // The body must serialise.
        XCTAssertTrue(JSON.string(req).contains("parent_rules"))
    }

    func testJevEndpoint() {
        let c = DecisionModelClassifier(ProviderConfig(.jev, apiKey: "k"))
        XCTAssertEqual(c.endpoint(), "https://openrouter.ai/api/alpha/decisions")
        XCTAssertEqual(c.buildRequest([sss], rules: "r")["model"] as? String, "typesafe/jev-1.14")
    }

    func testDecisionAnswerShapes() throws {
        let c = DecisionModelClassifier(ProviderConfig(.clef, apiKey: "t", accountId: "a"))
        let openRouter = #"{"id":"x","answers":{"v1":{"type":"noul","noul":0.93},"v2":{"type":"noul","noul":0.04}},"usage":{"cost":0.00001}}"#
        XCTAssertEqual(try c.parseAnswers(openRouter, [sss, other]).map { $0.p }, [0.93, 0.04])
        let cloudflare = #"{"result":{"model":"clef","answers":{"v1":{"type":"noul","probabilities":{"true":0.8,"false":0.2}}}},"success":true}"#
        let scores = try c.parseAnswers(cloudflare, [sss, other])
        XCTAssertEqual(scores.count, 1)
        XCTAssertEqual(scores[0].p, 0.8)
    }

    func testDecisionModelsNeverSendThumbnails() {
        XCTAssertFalse(ProviderConfig(.clef, sendThumbnails: true).usesThumbnails)
        XCTAssertTrue(ProviderConfig(.anthropic, sendThumbnails: true).usesThumbnails)
        XCTAssertFalse(ProviderConfig(.clef, apiKey: "t").isAiEnabled)
    }

    func testSuggestedKeywordsAreCleaned() throws {
        let reply = #"Here you go: {"keywords":["Witch","cauldron","witch","halloween","  ","spooky"]}"#
        XCTAssertEqual(try KeywordSuggester.parse(reply, existing: ["Halloween"]), ["witch", "cauldron", "spooky"])
    }
}

final class PromptAndProviderTests: XCTestCase {
    let v = VideoInfo("aaaaaaaaaaa", "Witches Brew", "Super Simple Songs")

    func testParsesFencedScoresAndIgnoresUnknownIds() throws {
        let reply = """
            Sure!
            ```json
            {"results":[{"id":"aaaaaaaaaaa","p":0.97,"reason":"witches"},{"id":"zzzzzzzzzzz","p":0.1}]}
            ```
            """
        let scores = try PromptBuilder.parseScores(reply, expectedIds: ["aaaaaaaaaaa"])
        XCTAssertEqual(scores.count, 1)
        XCTAssertEqual(scores[0].p, 0.97)
        XCTAssertEqual(scores[0].reason, "witches")
    }

    func testRejectsInvalidProbabilities() throws {
        XCTAssertTrue(try PromptBuilder.parseScores(#"{"results":[{"id":"aaaaaaaaaaa","p":7}]}"#, expectedIds: ["aaaaaaaaaaa"]).isEmpty)
    }

    func testRejectsRepliesWithoutJson() {
        XCTAssertThrowsError(try PromptBuilder.parseScores("I can't help with that", expectedIds: ["x"]))
    }

    func testAnthropicRequest() {
        let req = AnthropicClassifier(ProviderConfig(.anthropic, apiKey: "k")).buildRequest([v], rules: "No witches")
        XCTAssertEqual(req["model"] as? String, ProviderConfig.defaultAnthropicModel)
        let body = JSON.string(req)
        XCTAssertTrue(body.contains("No witches"))
        XCTAssertTrue(body.contains("Witches Brew"))
        XCTAssertTrue(body.contains("https:\\/\\/i.ytimg.com\\/vi\\/aaaaaaaaaaa\\/mqdefault.jpg") || body.contains("https://i.ytimg.com/vi/aaaaaaaaaaa/mqdefault.jpg"))
        XCTAssertEqual((req["output_config"] as? [String: Any])?["effort"] as? String, "low")
    }

    func testThumbnailsCanBeTurnedOff() {
        let req = OpenAiCompatibleClassifier(ProviderConfig(.openAiCompatible, baseUrl: "http://x/v1", model: "m", sendThumbnails: false))
            .buildRequest([v], rules: "No witches")
        XCTAssertFalse(JSON.string(req).contains("ytimg"))
    }

    func testEffortOnlyForModelsThatAcceptIt() {
        XCTAssertTrue(AnthropicClassifier.supportsEffort("claude-haiku-5-5"))
        XCTAssertTrue(AnthropicClassifier.supportsEffort("claude-opus-5-5"))
        XCTAssertFalse(AnthropicClassifier.supportsEffort("claude-haiku-4-5"))
        XCTAssertFalse(AnthropicClassifier.supportsEffort("claude-3-5-haiku-latest"))
    }

    func testKeywordFilterWholeWordsAndPlurals() {
        let f = KeywordFilter(KeywordFilter.parseList("witch, trick or treat\nghost"))
        XCTAssertEqual(f.firstMatch("Witches Brew Song"), "witch")
        XCTAssertEqual(f.firstMatch("Knock Knock, Trick  or Treat?"), "trick or treat")
        XCTAssertEqual(f.firstMatch("The Friendly Ghost's House"), "ghost")
        XCTAssertNil(f.firstMatch("Nintendo Switch unboxing"))
    }

    func testAiOnlyOnWhenItHasWhatItNeeds() {
        XCTAssertFalse(ProviderConfig(.anthropic).isAiEnabled)
        XCTAssertTrue(ProviderConfig(.anthropic, apiKey: "k").isAiEnabled)
        XCTAssertTrue(ProviderConfig(.openAiCompatible, baseUrl: "http://192.168.1.5:11434/v1", model: "llava").isAiEnabled)
    }

    func testPromptTextMatchesTheAndroidApp() {
        XCTAssertTrue(PromptBuilder.system.hasPrefix("You are the content filter inside a parental-control app for YouTube.\nA parent"))
        XCTAssertTrue(PromptBuilder.system.hasSuffix("Include every video id you were given."))
        XCTAssertEqual(PromptBuilder.rulesText("  No witches "), "The parent's rules:\n<rules>\nNo witches\n</rules>\n\nVideos to check:")
        XCTAssertEqual(PromptBuilder.videoText(0, v), "Video 1\nid: aaaaaaaaaaa\ntitle: Witches Brew\nchannel: Super Simple Songs\n")
    }

    func testJsBridgeQuoting() {
        XCTAssertEqual(JSON.quote("a\"b\u{2028}"), "\"a\\\"b\\u2028\"")
    }
}

final class RecoveryAndRuleTests: XCTestCase {
    let a = VideoInfo("aaaaaaaaaaa", "Planet Coaster park tour")
    let b = VideoInfo("bbbbbbbbbbb", "Another video")
    final class Stub: VideoClassifier {
        var seen: [[String]] = []
        let scores: [Score]
        let fails: Bool
        init(_ scores: [Score] = [], fails: Bool = false) { self.scores = scores; self.fails = fails }
        func classify(_ videos: [VideoInfo], rules: String) throws -> [Score] {
            seen.append(videos.map { $0.id })
            if fails { throw ClassifierException("quota exhausted") }
            return scores
        }
    }
    func settings() -> FilterSettings {
        FilterSettings(rules: "No violence", provider: ProviderConfig(.anthropic, apiKey: "primary"),
            fallbackProvider: ProviderConfig(.jev, apiKey: "backup"))
    }
    func testFallbackRetriesOnlyMissingOrInvalidScores() {
        let primary = Stub([Score(a.id, 0.01, "park tour"), Score(b.id, .infinity, "invalid")])
        let backup = Stub([Score(b.id, 0.9, "violence")])
        let engine = DecisionEngine(InMemoryVerdictStore(), classifierFactory: { $0.type == .anthropic ? primary : backup }, metadata: nil)
        let result = engine.decide([a,b], settings())
        XCTAssertEqual(result.map { $0.verdict }, [.allow, .block])
        XCTAssertEqual(backup.seen, [[b.id]])
        XCTAssertTrue(result.allSatisfy { $0.freshAi })
    }
    func testFallbackIsNotSecondOpinionForValidBlock() {
        let primary = Stub([Score(a.id, 0.9, "violence")])
        let backup = Stub([Score(a.id, 0.0, "fine")])
        let result = DecisionEngine(InMemoryVerdictStore(), classifierFactory: { $0.type == .anthropic ? primary : backup }, metadata: nil).decide([a], settings())
        XCTAssertEqual(result.first?.verdict, .block)
        XCTAssertTrue(backup.seen.isEmpty)
    }
    func testBothFailuresStayUnknown() {
        let fail = Stub(fails: true)
        let result = DecisionEngine(InMemoryVerdictStore(), classifierFactory: { _ in fail }, metadata: nil).decide([a], settings())
        XCTAssertEqual(result.first?.verdict, .unknown)
        XCTAssertFalse(result.first?.freshAi ?? true)
    }
    func testOnlyShowRequiresAiEvenWithNoProvider() {
        let result = DecisionEngine(InMemoryVerdictStore(), metadata: nil).decide([a], FilterSettings(allowedContent: "Planet Coaster"))
        XCTAssertEqual(result.first?.source, .error)
        XCTAssertEqual(result.first?.verdict, .unknown)
    }
    func testMisconfiguredSelectedProviderDoesNotAllowVideos() {
        let result = DecisionEngine(InMemoryVerdictStore(), metadata: nil).decide([a], FilterSettings(provider: ProviderConfig(.anthropic)))
        XCTAssertEqual(result.first?.verdict, .unknown)
    }
    func testCacheIsNotRecoveryAndForcedRetryUsesAi() {
        let primary = Stub([Score(a.id, 0.01, "park tour")])
        let engine = DecisionEngine(InMemoryVerdictStore(), classifierFactory: { _ in primary }, metadata: nil)
        XCTAssertEqual(engine.decide([a], settings()).first?.freshAi, true)
        XCTAssertEqual(engine.decide([a], settings()).first?.freshAi, false)
        XCTAssertEqual(engine.decide([a], settings(), forceFresh: true).first?.freshAi, true)
        XCTAssertEqual(primary.seen.count, 2)
    }
    func testRuleContextAndFallbackIdentityInvalidateCacheButThresholdDoesNot() {
        let original = settings()
        var changed = original
        changed.strictness = .balanced
        XCTAssertEqual(original.aiConfigHash(), changed.aiConfigHash())
        changed.allowedContent = "Planet Coaster"
        XCTAssertNotEqual(original.aiConfigHash(), changed.aiConfigHash())
        changed = original; changed.exceptions = "Theme park games are fine"
        XCTAssertNotEqual(original.aiConfigHash(), changed.aiConfigHash())
        changed = original; changed.fallbackProvider.apiKey = "another"
        XCTAssertNotEqual(original.aiConfigHash(), changed.aiConfigHash())
        changed = original; changed.provider.accountId = "another"
        XCTAssertNotEqual(original.aiConfigHash(), changed.aiConfigHash())
    }
    func testExceptionsNeverBypassKeywordOrManualBlock() {
        let store = InMemoryVerdictStore()
        var s = settings(); s.exceptions = "Everything is fine"; s.blockedKeywords = ["coaster"]
        let fake = Stub([Score(a.id, 0, "fine")])
        let engine = DecisionEngine(store, classifierFactory: { _ in fake }, metadata: nil)
        XCTAssertEqual(engine.decide([a], s).first?.source, .keyword)
        s.blockedKeywords = []; store.setOverride(a.id, .block)
        XCTAssertEqual(engine.decide([a], s).first?.source, .parent)
        XCTAssertTrue(fake.seen.isEmpty)
    }
}

final class AiConfigurationProtectionTests: XCTestCase {
    func testOnlyShowWithNoUsableCheckerPrelocksEvenIfVideoHasParentAllowance() {
        let settings = FilterSettings(allowedContent: "Planet Coaster")
        let store = InMemoryVerdictStore()
        store.setOverride("aaaaaaaaaaa", .allow)
        XCTAssertEqual(DecisionEngine(store, metadata: nil).decide([VideoInfo("aaaaaaaaaaa")], settings).first?.verdict, .allow)
        XCTAssertTrue(settings.mustBlockForAiConfiguration)
    }
    func testSelectedInvalidPrimaryPrelocksWithEmptyRules() {
        XCTAssertTrue(FilterSettings(provider: ProviderConfig(.anthropic)).mustBlockForAiConfiguration)
    }
    func testUsableFallbackAllowsFreshRetryWithoutPrelock() {
        let settings = FilterSettings(provider: ProviderConfig(.anthropic), allowedContent: "Planet Coaster",
            fallbackProvider: ProviderConfig(.jev, apiKey: "backup"))
        XCTAssertFalse(settings.mustBlockForAiConfiguration)
    }
    func testKeywordOnlyAndParentOptOutDoNotPrelock() {
        XCTAssertFalse(FilterSettings(blockedKeywords: ["violence"]).mustBlockForAiConfiguration)
        XCTAssertFalse(FilterSettings(allowedContent: "Planet Coaster", blockOnAiFailure: false).mustBlockForAiConfiguration)
    }
}

final class AvailabilityProbeTests: XCTestCase {
    let video = VideoInfo("aaaaaaaaaaa", "Planet Coaster park tour", "Park videos")
    var settings: FilterSettings {
        FilterSettings(rules: "No violence", blockedKeywords: ["coaster"], blockedChannels: ["Park videos"],
            provider: ProviderConfig(.anthropic, apiKey: "test"))
    }
    func testProbeChecksAiButPreservesManualAndKeywordDecisionsAndCache() {
        let store = InMemoryVerdictStore()
        store.setOverride(video.id, .block)
        let fake = RecoveryAndRuleTests.Stub([Score(video.id, 0.01, "park tour")])
        let engine = DecisionEngine(store, classifierFactory: { _ in fake }, metadata: nil)
        XCTAssertTrue(engine.probeAiAvailability([video], settings))
        XCTAssertEqual(fake.seen, [[video.id]])
        XCTAssertEqual(engine.decide([video], settings).first?.source, .parent)
        XCTAssertNil(store.cachedScore(video.id, configHash: settings.aiConfigHash()))
        store.setOverride(video.id, nil)
        XCTAssertEqual(engine.decide([video], settings).first?.source, .channel)
    }
    func testProbeCannotSucceedWithoutRealTitleOrWithAiFailure() {
        let fake = RecoveryAndRuleTests.Stub(fails: true)
        let engine = DecisionEngine(InMemoryVerdictStore(), classifierFactory: { _ in fake }, metadata: nil)
        XCTAssertFalse(engine.probeAiAvailability([VideoInfo(video.id)], settings))
        XCTAssertTrue(fake.seen.isEmpty)
        XCTAssertFalse(engine.probeAiAvailability([video], settings))
    }
    func testProbeCanUseRealMetadataAndFallbackWithoutSavingScore() {
        let fail = RecoveryAndRuleTests.Stub(fails: true)
        let backup = RecoveryAndRuleTests.Stub([Score(video.id, 0.8, "rule match")])
        var config = settings
        config.fallbackProvider = ProviderConfig(.jev, apiKey: "backup")
        let info = video
        let engine = DecisionEngine(InMemoryVerdictStore(), classifierFactory: { $0.type == .jev ? backup : fail },
            metadata: MetadataLookup { _ in info })
        XCTAssertTrue(engine.probeAiAvailability([VideoInfo(video.id)], config))
        XCTAssertEqual(backup.seen, [[video.id]])
    }
}

final class MissingTitleProtectionTests: XCTestCase {
    let video = VideoInfo("aaaaaaaaaaa")
    func testPrimaryClefAndDisabledFallbackCannotCheckMissingTitle() {
        let fake = RecoveryAndRuleTests.Stub([Score(video.id, 0, "no evidence")])
        let config = FilterSettings(rules: "No violence",
            provider: ProviderConfig(.clef, apiKey: "test", accountId: "account"))
        let result = DecisionEngine(InMemoryVerdictStore(), classifierFactory: { _ in fake }, metadata: nil).decide([video], config)
        XCTAssertEqual(result.first?.verdict, .unknown)
        XCTAssertEqual(result.first?.source, .error)
        XCTAssertTrue(fake.seen.isEmpty)
    }
    func testFallbackOnlyClefAndDisabledPrimaryCannotCheckMissingTitle() {
        let fake = RecoveryAndRuleTests.Stub([Score(video.id, 0, "no evidence")])
        let config = FilterSettings(allowedContent: "Planet Coaster",
            fallbackProvider: ProviderConfig(.clef, apiKey: "test", accountId: "account"))
        let result = DecisionEngine(InMemoryVerdictStore(), classifierFactory: { _ in fake }, metadata: nil).decide([video], config)
        XCTAssertEqual(result.first?.verdict, .unknown)
        XCTAssertEqual(result.first?.source, .error)
        XCTAssertTrue(fake.seen.isEmpty)
    }
    func testEnabledVisionProviderCanCheckThumbnailWithoutTitle() {
        let fake = RecoveryAndRuleTests.Stub([Score(video.id, 0.1, "park thumbnail")])
        let config = FilterSettings(rules: "No violence", provider: ProviderConfig(.anthropic, apiKey: "test"))
        let result = DecisionEngine(InMemoryVerdictStore(), classifierFactory: { _ in fake }, metadata: nil).decide([video], config)
        XCTAssertEqual(result.first?.verdict, .allow)
        XCTAssertEqual(result.first?.freshAi, true)
        XCTAssertEqual(fake.seen, [[video.id]])
    }
}

extension MissingTitleProtectionTests {
    func testTitlelessVisionFailureCannotFallBackToDecisionOnlyClassifier() {
        let primary = RecoveryAndRuleTests.Stub(fails: true)
        let backup = RecoveryAndRuleTests.Stub([Score(video.id, 0, "no evidence")])
        let config = FilterSettings(rules: "No violence", provider: ProviderConfig(.anthropic, apiKey: "primary"),
            fallbackProvider: ProviderConfig(.clef, apiKey: "backup", accountId: "account"))
        let result = DecisionEngine(InMemoryVerdictStore(), classifierFactory: { $0.type == .anthropic ? primary : backup }, metadata: nil).decide([video], config)
        XCTAssertEqual(result.first?.verdict, .unknown)
        XCTAssertEqual(result.first?.source, .error)
        XCTAssertEqual(primary.seen, [[video.id]])
        XCTAssertTrue(backup.seen.isEmpty)
    }
    func testTitlelessDecisionPrimaryIsSkippedForVisionFallback() {
        let primary = RecoveryAndRuleTests.Stub([Score(video.id, 0, "no evidence")])
        let backup = RecoveryAndRuleTests.Stub([Score(video.id, 0.1, "park thumbnail")])
        let config = FilterSettings(rules: "No violence", provider: ProviderConfig(.clef, apiKey: "primary", accountId: "account"),
            fallbackProvider: ProviderConfig(.anthropic, apiKey: "backup"))
        let result = DecisionEngine(InMemoryVerdictStore(), classifierFactory: { $0.type == .clef ? primary : backup }, metadata: nil).decide([video], config)
        XCTAssertEqual(result.first?.verdict, .allow)
        XCTAssertEqual(result.first?.freshAi, true)
        XCTAssertTrue(primary.seen.isEmpty)
        XCTAssertEqual(backup.seen, [[video.id]])
    }
}

final class OpenRouterAndRequestBudgetTests: XCTestCase {
    func videos(_ count: Int, heavy: Bool = true) -> [VideoInfo] {
        (0..<count).map { i in
            VideoInfo("v" + String(format: "%010d", i), "Video \(i) " + (heavy ? String(repeating: "界", count: 80) : "title"),
                "Channel", context: heavy ? String(repeating: "ă", count: 140) : "snippet")
        }
    }
    func testOpenRouterKeyOnlyDefaultAndLegacyModelsRemainUnchanged() {
        let config = ProviderConfig(.openRouterDecisions, apiKey: "test")
        XCTAssertTrue(config.isAiEnabled)
        XCTAssertTrue(config.isDecisionModel)
        XCTAssertFalse(config.usesThumbnails)
        XCTAssertFalse(ProviderConfig(.openRouterDecisions).isAiEnabled)
        XCTAssertEqual(config.modelOrDefault(), "cloudflare/clef-flash")
        XCTAssertEqual(DecisionModelClassifier(config).endpoint(), "https://openrouter.ai/api/alpha/decisions")
        XCTAssertTrue(Classifiers.create(config) is DecisionModelClassifier)
        XCTAssertEqual(ProviderConfig(.jev, apiKey: "test").modelOrDefault(), "typesafe/jev-1.14")
        XCTAssertEqual(ProviderConfig(.clef, apiKey: "test", accountId: "acc").modelOrDefault(), "clef")
        XCTAssertEqual(ProviderConfig(.openRouterDecisions, apiKey: "test", model: "typesafe/custom").modelOrDefault(), "typesafe/custom")
    }
    func testStatePartitionsPreserveRulesAndAllIdsWithinUtf8Budget() throws {
        let input = videos(9)
        let rules = "Only show theme park design videos. " + String(repeating: "Știință ", count: 18)
        for config in [ProviderConfig(.clef, apiKey: "test", accountId: "acc"),
                       ProviderConfig(.openRouterDecisions, apiKey: "test"),
                       ProviderConfig(.jev, apiKey: "test", model: "cloudflare/clef-flash")] {
            let classifier = DecisionModelClassifier(config)
            let batches = try classifier.requestBatches(input, rules: rules)
            XCTAssertGreaterThan(batches.count, 1)
            XCTAssertEqual(batches.flatMap { $0.map { $0.id } }, input.map { $0.id })
            for batch in batches {
                let state = try XCTUnwrap(classifier.buildRequest(batch, rules: rules)["state"] as? [String: Any])
                XCTAssertEqual(state["parent_rules"] as? String, rules.trimmed)
                XCTAssertLessThanOrEqual(JSON.string(state).utf8.count, 1800)
            }
        }
    }
    func testPartitionedHttpResponsesMapPerChunkQuestionIdsToOriginalVideos() throws {
        let input = videos(7)
        var calls = 0
        let classifier = DecisionModelClassifier(ProviderConfig(.openRouterDecisions, apiKey: "test"), transport: { endpoint, headers, body in
            calls += 1
            XCTAssertEqual(endpoint, "https://openrouter.ai/api/alpha/decisions")
            XCTAssertEqual(headers["Authorization"], "Bearer test")
            let req = try XCTUnwrap(JSON.object(body))
            let state = try XCTUnwrap(req["state"] as? [String: Any])
            let entries = try XCTUnwrap(state["videos"] as? [String: [String: Any]])
            XCTAssertLessThanOrEqual(JSON.string(state).utf8.count, 1800)
            var answers: [String: Double] = [:]
            for (key, video) in entries {
                let title = try XCTUnwrap(video["title"] as? String)
                let index = try XCTUnwrap(Int(title.split(separator: " ")[1]))
                answers[key] = index.isMultiple(of: 2) ? 0.1 : 0.9
            }
            return Http.Response(code: 200, body: JSON.string(["answers": answers]))
        })
        let scores = try classifier.classify(input, rules: "No violence")
        XCTAssertGreaterThan(calls, 1)
        XCTAssertEqual(scores.map { $0.id }, input.map { $0.id })
        XCTAssertEqual(scores.map { $0.p }, (0..<input.count).map { $0.isMultiple(of: 2) ? 0.1 : 0.9 })
    }
    func testOversizedRulesAndSingleVideoRejectedBeforeAnyHttp() {
        var calls = 0
        let classifier = DecisionModelClassifier(ProviderConfig(.openRouterDecisions, apiKey: "test"), transport: { _, _, _ in
            calls += 1
            return Http.Response(code: 200, body: "{}")
        })
        XCTAssertThrowsError(try classifier.classify(videos(1, heavy: false), rules: String(repeating: "親", count: 700)))
        let huge = VideoInfo("bbbbbbbbbbb", String(repeating: "界", count: 300), String(repeating: "界", count: 120), context: String(repeating: "界", count: 300))
        XCTAssertThrowsError(try classifier.classify(videos(1, heavy: false) + [huge], rules: "No violence"))
        XCTAssertEqual(calls, 0)
    }
    func testOtherDecisionModelsKeepExistingBatchAndMissingAnswersAreNotAllowed() throws {
        let input = videos(4)
        let legacy = DecisionModelClassifier(ProviderConfig(.jev, apiKey: "test"))
        XCTAssertEqual(try legacy.requestBatches(input, rules: "No violence"), [input])
        let classifier = DecisionModelClassifier(ProviderConfig(.openRouterDecisions, apiKey: "test"), transport: { _, _, _ in
            Http.Response(code: 200, body: #"{"answers":{"v1":0.4}}"#)
        })
        let scores = try classifier.classify(videos(3, heavy: false), rules: "No violence")
        XCTAssertEqual(scores.count, 1)
        XCTAssertEqual(scores.first?.id, videos(1, heavy: false)[0].id)
        XCTAssertEqual(Strictness.strict.verdictFor(try XCTUnwrap(scores.first?.p)), .block)
        XCTAssertTrue(scores.first?.reason.contains("hide rules") ?? false)
        XCTAssertFalse(scores.first?.reason.contains("looks fine") ?? true)
    }
}

extension OpenRouterAndRequestBudgetTests {
    func testOnlyBoundedDecisionRoutesInvalidateLegacyCacheIdentity() {
        func oldIdentity(_ p: ProviderConfig) -> String {
            [p.type.rawValue, p.baseUrl.trimmed, p.modelOrDefault(), p.accountId.trimmed,
                Hashing.sha256(p.apiKey), String(p.usesThumbnails)].joined(separator: "\u{0}")
        }
        for provider in [ProviderConfig(.clef, apiKey: "test", accountId: "acc"),
                         ProviderConfig(.jev, apiKey: "test"),
                         ProviderConfig(.anthropic, apiKey: "test"),
                         ProviderConfig(.openRouterDecisions, apiKey: "test")] {
            let settings = FilterSettings(rules: "No violence", provider: provider)
            let oldParts = [settings.effectiveRules(), oldIdentity(provider), oldIdentity(settings.fallbackProvider), String(PromptBuilder.version)]
            let legacyHash = String(Hashing.sha256(oldParts.joined(separator: "\u{0}")).prefix(16))
            XCTAssertEqual(settings.outageConfigHash(), legacyHash)
            if provider.needsBoundedDecisionState { XCTAssertNotEqual(settings.aiConfigHash(), legacyHash) }
            else { XCTAssertEqual(settings.aiConfigHash(), legacyHash) }
        }
        XCTAssertFalse(ProviderConfig(.openAiCompatible, model: "cloudflare/clef-flash").needsBoundedDecisionState)
        XCTAssertThrowsError(try KeywordSuggester(ProviderConfig(.openRouterDecisions, apiKey: "test")).suggest("No violence"))
    }
}

extension OpenRouterAndRequestBudgetTests {
    func testPersistedOutageIdentityChangesOnlyWithActualAiConfiguration() {
        let settings = FilterSettings(rules: "No violence", provider: ProviderConfig(.clef, apiKey: "test", accountId: "acc"))
        XCTAssertNotEqual(settings.aiConfigHash(), settings.outageConfigHash())
        var changed = settings; changed.rules = "No horror"
        XCTAssertNotEqual(changed.outageConfigHash(), settings.outageConfigHash())
        changed = settings; changed.provider.model = "another-model"
        XCTAssertNotEqual(changed.outageConfigHash(), settings.outageConfigHash())
        changed = settings; changed.provider.apiKey = "another-key"
        XCTAssertNotEqual(changed.outageConfigHash(), settings.outageConfigHash())
        changed = settings; changed.strictness = .balanced
        XCTAssertEqual(changed.outageConfigHash(), settings.outageConfigHash())
        changed = settings; changed.blockedKeywords = ["coaster"]
        XCTAssertEqual(changed.outageConfigHash(), settings.outageConfigHash())
    }
}

final class OnlyShowDecisionTests: XCTestCase {
    let config = ProviderConfig(.openRouterDecisions, apiKey: "test-key")
    let dinos = VideoInfo("aaaaaaaaaaa", "Five Little Dinos | Kids Songs", "Toddler Tunes")
    let hymn = VideoInfo("bbbbbbbbbbb", "Jesus Loves Me | Bible Songs for Kids", "Sunday Sing")

    func testOnlyShowAsksASeparateFitQuestionAndHidesHarmlessOffTopicVideos() throws {
        var sent: [String: Any]?
        let c = DecisionModelClassifier(config, transport: { _, _, body in
            sent = JSON.object(body)
            return Http.Response(code: 200, body: #"{"answers":{"v1":{"noul":0.02},"s1":{"noul":0.05},"v2":{"noul":0.01},"s2":{"noul":0.97}}}"#)
        })
        let settings = FilterSettings(rules: "Nothing scary", provider: config, allowedContent: "Christian content")
        let scores = Dictionary(uniqueKeysWithValues: try c.classify([dinos, hymn], settings: settings).map { ($0.id, $0) })
        let state = try XCTUnwrap(sent?["state"] as? [String: Any])
        XCTAssertEqual(state["only_show"] as? String, "Christian content")
        XCTAssertFalse((state["parent_rules"] as? String ?? "").contains("ONLY SHOW"))
        let q = try XCTUnwrap(sent?["questions"] as? [String: Any])
        XCTAssertNotNil(q["s1"]); XCTAssertNotNil(q["v1"])
        XCTAssertEqual(Strictness.balanced.verdictFor(try XCTUnwrap(scores[dinos.id]).p), .block)
        XCTAssertTrue(try XCTUnwrap(scores[dinos.id]).reason.contains("Christian content"))
        XCTAssertEqual(Strictness.balanced.verdictFor(try XCTUnwrap(scores[hymn.id]).p), .allow)
    }

    func testOnlyShowWithoutHideRulesAsksJustTheFitQuestion() throws {
        let c = DecisionModelClassifier(config, transport: { _, _, body in
            let q = JSON.object(body)?["questions"] as? [String: Any] ?? [:]
            XCTAssertNil(q["v1"]); XCTAssertNotNil(q["s1"])
            return Http.Response(code: 200, body: #"{"answers":{"s1":{"noul":0.1}}}"#)
        })
        let scores = try c.classify([dinos], settings: FilterSettings(rules: "", provider: config, allowedContent: "Bible stories"))
        XCTAssertEqual(try XCTUnwrap(scores.first).p, 0.9, accuracy: 1e-9)
    }

    func testAMissingFitAnswerIsNeverAnAllowance() throws {
        let c = DecisionModelClassifier(config, transport: { _, _, _ in Http.Response(code: 200, body: #"{"answers":{"v1":{"noul":0.0}}}"#) })
        XCTAssertTrue(try c.classify([dinos], settings: FilterSettings(rules: "x", provider: config, allowedContent: "Bible stories")).isEmpty)
    }

    func testOneUnreadableVideoIsNotACheckerOutageButARefusedRequestIs() {
        let ok = FilterSettings(rules: "No scary content", provider: config)
        func engine(_ code: Int, _ body: String) -> DecisionEngine {
            DecisionEngine(InMemoryVerdictStore(), classifierFactory: { cfg in
                DecisionModelClassifier(cfg, transport: { _, _, _ in Http.Response(code: code, body: body) })
            }, metadata: nil)
        }
        let noTitle = engine(200, #"{"answers":{}}"#).decide([VideoInfo("ccccccccccc", "", "")], ok)
        XCTAssertEqual(noTitle.first?.verdict, .unknown)
        XCTAssertEqual(noTitle.first?.checkerFailed, false)
        XCTAssertEqual(engine(200, #"{"answers":{}}"#).decide([dinos], ok).first?.checkerFailed, false)
        let down = engine(402, "out of credit").decide([dinos], ok)
        XCTAssertEqual(down.first?.verdict, .unknown)
        XCTAssertEqual(down.first?.checkerFailed, true)
    }
}
