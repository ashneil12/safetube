import XCTest
@testable import SafeTubeCore

/// Reads the Kotlin sources in core/ and checks the Swift port says exactly the same things,
/// so a prompt or default changed on Android fails this test until the iPhone app follows.
final class SameAsAndroidTests: XCTestCase {
    private func kotlin(_ file: String) throws -> String {
        let repo = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
        let url = repo.appendingPathComponent("core/src/main/kotlin/app/safetube/core/\(file)")
        return try String(contentsOf: url, encoding: .utf8)
    }

    /// The text of `val NAME = """ ... """.trimIndent()`, with Kotlin's trimIndent applied.
    private func trimIndented(_ source: String, _ name: String) throws -> String {
        let start = try XCTUnwrap(source.range(of: "val \(name) = \"\"\""))
        let rest = source[start.upperBound...]
        let end = try XCTUnwrap(rest.range(of: "\"\"\".trimIndent()"))
        var lines = String(rest[..<end.lowerBound]).components(separatedBy: "\n")
        if let f = lines.first, f.trimmingCharacters(in: .whitespaces).isEmpty { lines.removeFirst() }
        if let l = lines.last, l.trimmingCharacters(in: .whitespaces).isEmpty { lines.removeLast() }
        let indent = lines.filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
            .map { $0.prefix { $0 == " " || $0 == "\t" }.count }.min() ?? 0
        return lines.map { $0.trimmingCharacters(in: .whitespaces).isEmpty ? "" : String($0.dropFirst(indent)) }
            .joined(separator: "\n")
    }

    private func constant(_ source: String, _ name: String) throws -> String {
        let re = try NSRegularExpression(pattern: "const val \(name)\\s*=\\s*\"([^\"]*)\"")
        let ns = source as NSString
        let m = try XCTUnwrap(re.firstMatch(in: source, range: NSRange(location: 0, length: ns.length)))
        return ns.substring(with: m.range(at: 1))
    }

    func testClassifierPromptIsIdentical() throws {
        XCTAssertEqual(PromptBuilder.system, try trimIndented(kotlin("PromptBuilder.kt"), "SYSTEM"))
        XCTAssertTrue(try kotlin("PromptBuilder.kt").contains("const val VERSION = \(PromptBuilder.version)"))
    }

    func testKeywordPromptIsIdentical() throws {
        XCTAssertEqual(KeywordSuggester.system, try trimIndented(kotlin("KeywordSuggester.kt"), "SYSTEM"))
    }

    func testDefaultModelsAreIdentical() throws {
        let models = try kotlin("Models.kt")
        XCTAssertEqual(ProviderConfig.defaultClefModel, try constant(models, "DEFAULT_CLEF_MODEL"))
        XCTAssertEqual(ProviderConfig.defaultJevModel, try constant(models, "DEFAULT_JEV_MODEL"))
        XCTAssertEqual(ProviderConfig.defaultAnthropicModel, try constant(models, "DEFAULT_ANTHROPIC_MODEL"))
    }

    func testStartingSettingsAreIdentical() throws {
        let url = repoRoot().appendingPathComponent("app/src/main/java/app/safetube/Prefs.kt")
        let prefs = try String(contentsOf: url, encoding: .utf8)
        XCTAssertEqual(AppDefaults.geminiBaseUrl, try constant(prefs, "GEMINI_BASE_URL"))
        XCTAssertEqual(AppDefaults.geminiModel, try constant(prefs, "GEMINI_MODEL"))
        XCTAssertEqual(AppDefaults.keywords, try constant(prefs, "DEFAULT_KEYWORDS"))
        XCTAssertTrue(prefs.contains("sp.getString(\"baseUrl\", \"\(AppDefaults.checkerBaseUrl)\")"))
        // DEFAULT_RULES is written as several string literals joined with +.
        let start = try XCTUnwrap(prefs.range(of: "const val DEFAULT_RULES ="))
        let tail = prefs[start.upperBound...]
        let end = tail.range(of: "const val")?.lowerBound ?? tail.endIndex
        let literal = try NSRegularExpression(pattern: "\"([^\"]*)\"")
        let chunk = String(tail[..<end]) as NSString
        let joined = literal.matches(in: chunk as String, range: NSRange(location: 0, length: chunk.length))
            .map { chunk.substring(with: $0.range(at: 1)) }.joined()
        XCTAssertEqual(AppDefaults.rules, joined)
    }

    private func repoRoot() -> URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
    }

    func testStrictnessThresholdsAreIdentical() throws {
        let models = try kotlin("Models.kt")
        for s in Strictness.allCases {
            XCTAssertTrue(models.contains("\(s.rawValue)(\(s.blockAtOrAbove))"), "\(s.rawValue) threshold differs")
        }
    }
}
