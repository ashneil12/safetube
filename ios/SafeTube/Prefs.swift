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

    var allowedContent: String {
        get { string("allowedContent", "") }
        set { sp.set(newValue, forKey: "allowedContent") }
    }
    var exceptions: String {
        get { string("exceptions", "") }
        set { sp.set(newValue, forKey: "exceptions") }
    }
    var aiUnavailable: Bool {
        get {
            let settings = filterSettings()
            if settings.mustBlockForAiConfiguration {
                sp.set(settings.outageConfigHash(), forKey: "aiUnavailableHash")
                return true
            }
            return settings.blockOnAiFailure && string("aiUnavailableHash", "") == settings.outageConfigHash()
        }
        set {
            if newValue { sp.set(filterSettings().outageConfigHash(), forKey: "aiUnavailableHash") }
            else { sp.removeObject(forKey: "aiUnavailableHash") }
        }
    }
    var blockOnAiFailure: Bool {
        get { bool("blockOnAiFailure", true) }
        set { sp.set(newValue, forKey: "blockOnAiFailure") }
    }
    var fallbackProvider: ProviderConfig {
        get {
            ProviderConfig(ProviderType(rawValue: string("fallbackType", "NONE")) ?? .none,
                apiKey: Keychain.get("fallbackKey"), baseUrl: string("fallbackBaseUrl", ""),
                model: string("fallbackModel", ""), accountId: string("fallbackAccountId", ""),
                sendThumbnails: bool("fallbackThumbs", true))
        }
        set {
            sp.set(newValue.type.rawValue, forKey: "fallbackType")
            Keychain.set("fallbackKey", newValue.apiKey)
            sp.set(newValue.baseUrl, forKey: "fallbackBaseUrl")
            sp.set(newValue.model, forKey: "fallbackModel")
            sp.set(newValue.accountId, forKey: "fallbackAccountId")
            sp.set(newValue.sendThumbnails, forKey: "fallbackThumbs")
        }
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

    /// Skip video ads in the page and stop ad and tracker addresses from loading.
    var blockAds: Bool {
        get { bool("blockAds", true) }
        set { sp.set(newValue, forKey: "blockAds") }
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

    /// YouTube's built-in games (Playables): hidden, and their pages refuse to open.
    var blockGames: Bool {
        get { bool("blockGames", true) }
        set { sp.set(newValue, forKey: "blockGames") }
    }

    /// Stop YouTube moving on to another video by itself when one ends.
    var noAutoplay: Bool {
        get { bool("noAutoplay", true) }
        set { sp.set(newValue, forKey: "noAutoplay") }
    }

    /// YouTube's Restricted Mode, set only in this app's own browser: never on the Google account.
    var restrictedMode: Bool {
        get { bool("restrictedMode", false) }
        set { sp.set(newValue, forKey: "restrictedMode") }
    }

    /// Keep a list of watched videos for the parent. Stays on this device.
    var keepHistory: Bool {
        get { bool("keepHistory", true) }
        set { sp.set(newValue, forKey: "keepHistory") }
    }

    // ---- screen time (same rules as Prefs.kt)

    private func int(_ key: String, _ fallback: Int) -> Int { sp.object(forKey: key) == nil ? fallback : sp.integer(forKey: key) }
    private func long(_ key: String) -> Int64 { (sp.object(forKey: key) as? NSNumber)?.int64Value ?? 0 }
    private func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    /// Minutes of YouTube a day; 0 means no limit.
    var dailyLimitMin: Int {
        get { int("dailyLimitMin", 0) }
        set { sp.set(min(max(newValue, 0), 24 * 60), forKey: "dailyLimitMin") }
    }

    var bedtimeOn: Bool {
        get { bool("bedtimeOn", false) }
        set { sp.set(newValue, forKey: "bedtimeOn") }
    }

    /// "HH:MM", 24-hour. YouTube is off from bedtimeStart until bedtimeEnd.
    var bedtimeStart: String {
        get { string("bedtimeStart", "19:30") }
        set { sp.set(Prefs.validTime(newValue, "19:30"), forKey: "bedtimeStart") }
    }

    var bedtimeEnd: String {
        get { string("bedtimeEnd", "07:00") }
        set { sp.set(Prefs.validTime(newValue, "07:00"), forKey: "bedtimeEnd") }
    }

    /// Seconds watched today.
    var watchedTodaySec: Int { sp.integer(forKey: "watchedDay") == dayKey() ? sp.integer(forKey: "watchedSec") : 0 }

    func addWatched(_ seconds: Int) {
        lock.lock(); defer { lock.unlock() }
        let total = watchedTodaySec + seconds
        sp.set(dayKey(), forKey: "watchedDay")
        sp.set(total, forKey: "watchedSec")
    }

    /// Extra minutes a parent granted today on top of the daily limit.
    var extraTodayMin: Int { sp.integer(forKey: "extraDay") == dayKey() ? sp.integer(forKey: "extraMin") : 0 }

    /// Bedtime is paused until this time (ms) after a parent gives a few more minutes.
    var bedtimeExtendedUntil: Int64 {
        get { long("bedtimeExtendedUntil") }
        set { sp.set(NSNumber(value: newValue), forKey: "bedtimeExtendedUntil") }
    }

    /// A parent's "a few more minutes": works for both the daily limit and bedtime.
    func addExtraMinutes(_ minutes: Int) {
        let extra = extraTodayMin + minutes
        sp.set(dayKey(), forKey: "extraDay")
        sp.set(extra, forKey: "extraMin")
        bedtimeExtendedUntil = max(nowMs(), bedtimeExtendedUntil) + Int64(minutes) * 60_000
    }

    /// Why YouTube should be closed right now ("bedtime" or "limit"), or nil if it's fine to watch.
    func timeUp(_ now: Date = Date()) -> String? {
        let ms = Int64(now.timeIntervalSince1970 * 1000)
        if bedtimeOn && ms > bedtimeExtendedUntil && inBedtime(now) { return "bedtime" }
        let limit = dailyLimitMin
        if limit > 0 && watchedTodaySec >= (limit + extraTodayMin) * 60 { return "limit" }
        return nil
    }

    private func inBedtime(_ now: Date) -> Bool {
        let c = Calendar.current.dateComponents([.hour, .minute], from: now)
        let m = (c.hour ?? 0) * 60 + (c.minute ?? 0)
        let start = Prefs.minutes(bedtimeStart), end = Prefs.minutes(bedtimeEnd)
        return start <= end ? (m >= start && m < end) : (m >= start || m < end)
    }

    private static func minutes(_ hhmm: String) -> Int {
        let p = hhmm.split(separator: ":").compactMap { Int($0) }
        return p.count == 2 ? p[0] * 60 + p[1] : 0
    }

    private static let timePattern = try! NSRegularExpression(pattern: "^([01][0-9]|2[0-3]):[0-5][0-9]$")

    private static func validTime(_ v: String, _ fallback: String) -> String {
        let t = v.trimmed
        return timePattern.firstMatch(in: t, range: NSRange(location: 0, length: (t as NSString).length)) != nil ? t : fallback
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
            ),
            allowedContent: allowedContent, exceptions: exceptions,
            fallbackProvider: fallbackProvider, blockOnAiFailure: blockOnAiFailure
        )
    }

    func pageConfigJson(aiUnavailable: Bool = false) -> String {
        JSON.string([
            "aiUnavailable": aiUnavailable || self.aiUnavailable,
            "hideShorts": hideShorts,
            "hideComments": hideComments,
            "blockMenu": true,
            "kidsCanBlock": kidsCanBlock,
            "blockAds": blockAds,
            "blockGames": blockGames,
            "noAutoplay": noAutoplay,
            "keepHistory": keepHistory,
            "debug": false,
        ])
    }
}
