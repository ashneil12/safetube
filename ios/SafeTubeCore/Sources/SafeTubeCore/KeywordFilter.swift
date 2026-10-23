import Foundation

/// Instant, offline matching against the parent's keyword list.
/// Matches whole words (and simple plurals), case-insensitively, so "witch"
/// catches "Witches Brew" but not "switch".
public final class KeywordFilter {
    private let patterns: [(String, NSRegularExpression)]

    public init(_ keywords: [String]) {
        var seen = Set<String>()
        var out: [(String, NSRegularExpression)] = []
        for raw in keywords {
            let kw = raw.trimmed
            if kw.isEmpty || !seen.insert(kw.lowercased()).inserted { continue }
            let body = Re.words(kw).map(Re.escape).joined(separator: "\\s+")
            out.append((kw, Re.make("(?<![\\p{L}\\p{N}])\(body)(?:s|es|'s)?(?![\\p{L}\\p{N}])", ignoreCase: true)))
        }
        patterns = out
    }

    /// Returns the first keyword found in [text], or nil.
    public func firstMatch(_ text: String) -> String? { patterns.first { Re.contains($0.1, text) }?.0 }

    public var isEmpty: Bool { patterns.isEmpty }

    /// Parses a comma- or newline-separated list as typed by a parent.
    public static func parseList(_ raw: String) -> [String] {
        raw.components(separatedBy: CharacterSet(charactersIn: ",\n;")).map { $0.trimmed }.filter { !$0.isEmpty }
    }
}
