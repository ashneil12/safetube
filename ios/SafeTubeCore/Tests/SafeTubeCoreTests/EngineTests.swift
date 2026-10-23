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

    func testClampsProbabilities() throws {
        XCTAssertEqual(try PromptBuilder.parseScores(#"{"results":[{"id":"aaaaaaaaaaa","p":7}]}"#, expectedIds: ["aaaaaaaaaaa"]).first?.p, 1.0)
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
