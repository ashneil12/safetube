import Foundation

/// Channels the parent never wants shown, whatever the rules say.
/// Each entry is a handle ("@SuperSimpleSongs"), a channel id ("UC..."), or a plain channel name.
public final class ChannelBlocklist {
    private var ids = Set<String>()
    private var names: [(String, NSRegularExpression)] = []

    public init(_ entries: [String]) {
        for raw in entries {
            guard let ref = ChannelBlocklist.parse(raw) else { continue }
            if Re.matchesWhole(ChannelBlocklist.handle, ref) || Re.matchesWhole(ChannelBlocklist.channelId, ref) {
                ids.insert(ref.lowercased())
            } else {
                let body = Re.words(ref).map(Re.escape).joined(separator: "\\s+")
                names.append((ref, Re.make("(?<![\\p{L}\\p{N}])\(body)(?![\\p{L}\\p{N}])", ignoreCase: true)))
            }
        }
    }

    /// Returns the matching entry if [video]'s channel is blocked.
    public func match(_ video: VideoInfo) -> String? {
        if let cid = ChannelBlocklist.normalizeId(video.channelId), ids.contains(cid.lowercased()) { return cid }
        // Names are matched against the channel line only, never the title.
        return names.first { Re.contains($0.1, video.channel) }?.0
    }

    public var isEmpty: Bool { ids.isEmpty && names.isEmpty }

    static let handle = Re.make("@[A-Za-z0-9._-]{1,100}")
    static let channelId = Re.make("UC[A-Za-z0-9_-]{22}")
    private static let channelPath = Re.make("/channel/(UC[A-Za-z0-9_-]{22})")
    private static let handleUrl = Re.make("youtube\\.com/(@[A-Za-z0-9._-]+)")
    private static let handleInPath = Re.make("(?:^|/)(@[A-Za-z0-9._-]+)")
    private static let idInPath = Re.make("(?:^|/)(UC[A-Za-z0-9_-]{22})")

    /// Turns whatever a parent pastes (a channel link, a handle, an id or a name) into the stored
    /// form: "@handle", "UC..." or the trimmed name. Returns nil for blank input.
    public static func parse(_ input: String) -> String? {
        let s = input.trimmed
        if s.isEmpty { return nil }
        if let id = Re.group1(channelPath, s) { return id }
        if let h = Re.group1(handleUrl, s) { return h }
        return s
    }

    /// "@handle" or "UC..." from a page link like "/@handle/videos" or "/channel/UC.../featured".
    public static func normalizeId(_ link: String) -> String? {
        if link.isBlank { return nil }
        if let h = Re.group1(handleInPath, link) { return h }
        if let id = Re.group1(idInPath, link) { return id }
        return nil
    }

    /// True if [input] looks like a link or id rather than a plain channel name.
    public static func isChannelLink(_ input: String) -> Bool {
        let s = input.trimmed
        return s.contains("/channel/") || s.contains("youtube.com/@") || Re.matchesWhole(handle, s) || Re.matchesWhole(channelId, s)
    }
}

/// Pulls a video id out of anything a parent might paste: a watch link, a short link, a Shorts link or a bare id.
public enum VideoLinks {
    private static let patterns = [
        Re.make("[?&]v=([A-Za-z0-9_-]{11})"),
        Re.make("youtu\\.be/([A-Za-z0-9_-]{11})"),
        Re.make("/(?:shorts|live|embed)/([A-Za-z0-9_-]{11})"),
    ]

    public static func videoId(_ input: String) -> String? {
        let s = input.trimmed
        if VideoInfo.isValidId(s) { return s }
        for p in patterns { if let id = Re.group1(p, s) { return id } }
        return nil
    }
}
