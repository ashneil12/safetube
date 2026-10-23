import Foundation

/// Ad and tracking addresses the YouTube screen never needs, the same list as the Android app's
/// AdBlocklist.kt. Video ads themselves are removed by filter.js; this stops the ad servers,
/// ad reporting and trackers from loading at all. On iOS it becomes a WKContentRuleList.
public enum AdBlocklist {
    public static let hosts = [
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com",
        "googletagmanager.com", "googletagservices.com", "adservice.google.com", "imasdk.googleapis.com",
        "ads.youtube.com",
    ]

    /// Paths on YouTube's own servers that only serve or report ads.
    public static let youtubePaths = [
        "/api/stats/ads", "/pagead/", "/get_midroll_info", "/ptracking", "/youtubei/v1/log_event",
    ]

    /// The same test the Android app applies to each request.
    public static func blocks(_ url: URL) -> Bool {
        guard let host = url.host?.lowercased() else { return false }
        if hosts.contains(where: { host == $0 || host.hasSuffix(".\($0)") }) { return true }
        if host == "youtube.com" || host.hasSuffix(".youtube.com") {
            return youtubePaths.contains { url.path.hasPrefix($0) }
        }
        return false
    }

    /// WebKit content-blocker rules that block the same addresses.
    public static func contentRuleListJSON() -> String {
        func hostPattern(_ host: String) -> String {
            "^[a-z]+://([^/:]+\\.)?" + NSRegularExpression.escapedPattern(for: host)
        }
        var rules: [[String: Any]] = hosts.map { host in
            ["trigger": ["url-filter": hostPattern(host) + "[:/]"], "action": ["type": "block"]]
        }
        for path in youtubePaths {
            rules.append([
                "trigger": ["url-filter": hostPattern("youtube.com") + NSRegularExpression.escapedPattern(for: path)],
                "action": ["type": "block"],
            ])
        }
        return JSON.string(rules)
    }
}
