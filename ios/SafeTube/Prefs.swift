import Foundation
import SafeTubeCore

/// Everything the parent configures, stored privately on the device. Same keys and defaults as
/// the Android app's Prefs.kt; API keys go in the Keychain instead of plain storage.
final class Prefs {
    static let shared = Prefs()

    private let sp = UserDefaults.standard
    private let lock = NSLock()

    private func string(_ key: String, _ fallback: String) -> String { sp.string(forKey: key) ?? fallback }
    private func bool(_ key: String, _ fallback: Bool) -> Bool { sp.object(forKey: key) == nil ? fallback : sp.bool(forKey: key) }

    var rules: String {
        get { string("rules", AppDefaults.rules) }
        set { sp.set(newValue, forKey: "rules") }
    }

    var keywords: String {
        get { string("keywords", AppDefaults.keywords) }
        set { sp.set(newValue, forKey: "keywords") }
    }

    var strictness: Strictness {
        get { Strictness(rawValue: string("strictness", "")) ?? .strict }
        set { sp.set(newValue.rawValue, forKey: "strictness") }
    }

    var providerType: ProviderType {
        get { ProviderType(rawValue: string("provider", "")) ?? .none }
        set { sp.set(newValue.rawValue, forKey: "provider") }
    }

    var apiKey: String {
        get { Keychain.get("apiKey") }
        set { Keychain.set("apiKey", newValue) }
    }

    var baseUrl: String {
        get { string("baseUrl", AppDefaults.checkerBaseUrl) }
        set { sp.set(newValue, forKey: "baseUrl") }
    }

    var model: String {
        get { string("model", "") }
        set { sp.set(newValue, forKey: "model") }
    }

    var accountId: String {
        get { string("accountId", "") }
        set { sp.set(newValue, forKey: "accountId") }
    }

    /// One entry per line: "@handle", "UC..." or a channel name.
    var blockedChannels: String {
        get { string("blockedChannels", "") }
        set { sp.set(newValue, forKey: "blockedChannels") }
    }

    func blockedChannelList() -> [String] {
        blockedChannels.components(separatedBy: "\n").map { $0.trimmed }.filter { !$0.isEmpty }
    }

    func addBlockedChannel(_ entry: String) {
        let list = blockedChannelList()
        if !list.contains(where: { $0.caseInsensitiveCompare(entry) == .orderedSame }) {
            blockedChannels = (list + [entry]).joined(separator: "\n")
        }
    }

    func removeBlockedChannel(_ entry: String) {
        blockedChannels = blockedChannelList().filter { $0.caseInsensitiveCompare(entry) != .orderedSame }.joined(separator: "\n")
    }

    // ---- the chat model that suggests keywords from the rules (can differ from the video checker)

    var helperType: ProviderType {
        get { ProviderType(rawValue: string("helperType", "")) ?? .none }
        set { sp.set(newValue.rawValue, forKey: "helperType") }
    }

    var helperKey: String {
        get { Keychain.get("helperKey") }
        set { Keychain.set("helperKey", newValue) }
    }

    var helperBaseUrl: String {
        get { string("helperBaseUrl", AppDefaults.geminiBaseUrl) }
        set { sp.set(newValue, forKey: "helperBaseUrl") }
    }

    var helperModel: String {
        get { string("helperModel", AppDefaults.geminiModel) }
        set { sp.set(newValue, forKey: "helperModel") }
    }

    var sendThumbnails: Bool {
        get { bool("thumbs", true) }
        set { sp.set(newValue, forKey: "thumbs") }
    }

    var hideShorts: Bool {
        get { bool("hideShorts", true) }
        set { sp.set(newValue, forKey: "hideShorts") }
    }

    /// Let the child hide videos and channels themselves. Only the parent can undo it.
    var kidsCanBlock: Bool {
        get { bool("kidsCanBlock", true) }
        set { sp.set(newValue, forKey: "kidsCanBlock") }
    }

    var hideComments: Bool {
        get { bool("hideComments", true) }
        set { sp.set(newValue, forKey: "hideComments") }
    }

    /// Bumped whenever settings change so open pages re-check what they show.
    private(set) var version: Int {
        get { sp.integer(forKey: "version") }
        set { sp.set(newValue, forKey: "version") }
    }

    func bumpVersion() {
        lock.lock(); defer { lock.unlock() }
        version += 1
    }

    // ---- where the child was, so a relaunch carries on instead of starting over

    var lastUrl: String? { sp.string(forKey: "lastUrl") }
    /// Milliseconds since 1970, like the Android app.
    var lastUrlAt: Int64 { (sp.object(forKey: "lastUrlAt") as? NSNumber)?.int64Value ?? 0 }

    func rememberPage(_ url: String) {
        sp.set(url, forKey: "lastUrl")
        sp.set(NSNumber(value: Int64(Date().timeIntervalSince1970 * 1000)), forKey: "lastUrlAt")
    }

    // ---- PIN

    var hasPin: Bool { sp.string(forKey: "pinHash") != nil }

    func setPin(_ pin: String) {
        let salt = Hashing.newSalt()
        sp.set(salt, forKey: "pinSalt")
        sp.set(Hashing.pinHash(pin, salt: salt), forKey: "pinHash")
        sp.set(pin.count, forKey: "pinLength")
    }

    /// Length of the PIN, so the keypad knows when to say it's wrong.
    var pinLength: Int { sp.integer(forKey: "pinLength") }

    func checkPin(_ pin: String) -> Bool {
        guard let salt = sp.string(forKey: "pinSalt") else { return false }
        return sp.string(forKey: "pinHash") == Hashing.pinHash(pin, salt: salt)
    }

    // ---- the overview's "videos checked today" count

    func recordChecked(_ count: Int) {
        lock.lock(); defer { lock.unlock() }
        let today = dayKey()
        let sofar = sp.integer(forKey: "checkedDay") == today ? sp.integer(forKey: "checkedCount") : 0
        sp.set(today, forKey: "checkedDay")
        sp.set(sofar + count, forKey: "checkedCount")
    }

    func checkedToday() -> Int { sp.integer(forKey: "checkedDay") == dayKey() ? sp.integer(forKey: "checkedCount") : 0 }

    private func dayKey() -> Int {
        let c = Calendar.current
        let now = Date()
        return c.component(.year, from: now) * 1000 + (c.ordinality(of: .day, in: .year, for: now) ?? 0)
    }

    // ---- what the engine and the page need

    func filterSettings() -> FilterSettings {
        FilterSettings(
            rules: rules,
            blockedKeywords: KeywordFilter.parseList(keywords),
            blockedChannels: blockedChannelList(),
            strictness: strictness,
            provider: ProviderConfig(
                providerType,
                apiKey: apiKey,
                baseUrl: baseUrl,
                model: model,
                accountId: accountId,
                sendThumbnails: sendThumbnails
            )
        )
    }

    func pageConfigJson() -> String {
        JSON.string([
            "hideShorts": hideShorts,
            "hideComments": hideComments,
            "blockMenu": true,
            "kidsCanBlock": kidsCanBlock,
            "debug": false,
        ])
    }
}
