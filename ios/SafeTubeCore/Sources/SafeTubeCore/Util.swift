import Foundation

extension String {
    public var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
    public var isBlank: Bool { trimmed.isEmpty }
    /// Kotlin's take(n): the first n characters (or the whole string).
    func take(_ n: Int) -> String { String(prefix(n)) }
    func ifBlank(_ other: @autoclosure () -> String) -> String { isBlank ? other() : self }
}

/// Small helpers around NSRegularExpression so the ports read like the Kotlin originals.
enum Re {
    static func make(_ pattern: String, ignoreCase: Bool = false) -> NSRegularExpression {
        // Patterns are either literals in this package or built from escaped user text.
        try! NSRegularExpression(pattern: pattern, options: ignoreCase ? [.caseInsensitive] : [])
    }

    static func escape(_ s: String) -> String { NSRegularExpression.escapedPattern(for: s) }

    static func contains(_ re: NSRegularExpression, _ s: String) -> Bool {
        re.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) != nil
    }

    static func matchesWhole(_ re: NSRegularExpression, _ s: String) -> Bool {
        guard let m = re.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) else { return false }
        return m.range.location == 0 && m.range.length == (s as NSString).length
    }

    /// The first capture group of the first match, like Kotlin's find(s)?.groupValues[1].
    static func group1(_ re: NSRegularExpression, _ s: String) -> String? {
        guard let m = re.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)), m.numberOfRanges > 1,
              let r = Range(m.range(at: 1), in: s) else { return nil }
        return String(s[r])
    }

    /// Kotlin's text.split(Regex("\\s+")): words separated by runs of whitespace.
    static func words(_ s: String) -> [String] {
        let parts = s.components(separatedBy: CharacterSet.whitespacesAndNewlines)
        // Kotlin keeps leading/trailing empties, but every caller trims first, so drop empties.
        return parts.filter { !$0.isEmpty }
    }
}

/// Reading loosely typed JSON the way org.json's opt* methods do.
public enum JSON {
    public static func object(_ text: String) -> [String: Any]? {
        guard let data = text.data(using: .utf8),
              let v = try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]) else { return nil }
        return v as? [String: Any]
    }

    public static func array(_ text: String) -> [Any]? {
        guard let data = text.data(using: .utf8),
              let v = try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]) else { return nil }
        return v as? [Any]
    }

    public static func string(_ value: Any) -> String {
        guard JSONSerialization.isValidJSONObject(value),
              let data = try? JSONSerialization.data(withJSONObject: value, options: []) else { return "{}" }
        return String(data: data, encoding: .utf8) ?? "{}"
    }

    /// A JSON string literal for [s], safe to paste into JavaScript.
    public static func quote(_ s: String) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: [s], options: []),
              var text = String(data: data, encoding: .utf8) else { return "\"\"" }
        text.removeFirst()
        text.removeLast()
        // U+2028/2029 are valid in JSON but end a line in older JavaScript engines.
        return text.replacingOccurrences(of: "\u{2028}", with: "\\u2028").replacingOccurrences(of: "\u{2029}", with: "\\u2029")
    }

    /// optString: a string value, numbers and booleans as text, otherwise "".
    public static func optString(_ o: [String: Any]?, _ key: String) -> String {
        guard let v = o?[key], !(v is NSNull) else { return "" }
        if let s = v as? String { return s }
        if let n = number(v) { return n == n.rounded() && abs(n) < 1e15 ? String(Int64(n)) : String(n) }
        if let b = v as? Bool { return String(b) }
        return ""
    }

    public static func optBool(_ o: [String: Any]?, _ key: String, _ fallback: Bool) -> Bool {
        guard let v = o?[key] else { return fallback }
        if let n = v as? NSNumber, isBool(n) { return n.boolValue }
        if let s = v as? String { if s.lowercased() == "true" { return true }; if s.lowercased() == "false" { return false } }
        return fallback
    }

    /// optDouble: a number, or a string that parses as one; booleans don't count.
    public static func optDouble(_ o: [String: Any]?, _ key: String) -> Double? {
        guard let v = o?[key] else { return nil }
        if let n = number(v) { return n }
        if let s = v as? String { return Double(s.trimmed) }
        return nil
    }

    /// A JSON number (never a boolean) as a Double.
    public static func number(_ v: Any?) -> Double? {
        guard let n = v as? NSNumber, !isBool(n) else { return nil }
        return n.doubleValue
    }

    /// The value, or JSON null.
    public static func orNull(_ v: Any?) -> Any { v ?? NSNull() }

    static func isBool(_ n: NSNumber) -> Bool { CFGetTypeID(n) == CFBooleanGetTypeID() }
}
