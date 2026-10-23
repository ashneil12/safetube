import Foundation

/// Looks up a video's title and channel when the page didn't show them (direct links, autoplay).
public protocol MetadataFetcher {
    func fetch(_ id: String) -> VideoInfo?
}

/// A fetcher made from a closure, handy in tests.
public struct MetadataLookup: MetadataFetcher {
    let body: (String) -> VideoInfo?
    public init(_ body: @escaping (String) -> VideoInfo?) { self.body = body }
    public func fetch(_ id: String) -> VideoInfo? { body(id) }
}

/// YouTube's public oEmbed endpoint: no API key, returns title and channel name.
public struct YouTubeOEmbed: MetadataFetcher {
    public static let shared = YouTubeOEmbed()
    public init() {}

    public func fetch(_ id: String) -> VideoInfo? {
        let watch = "https://www.youtube.com/watch?v=\(id)"
            .addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? ""
        guard let r = try? Http.request("https://www.youtube.com/oembed?format=json&url=\(watch)", timeoutMs: 10_000),
              r.code == 200, let json = JSON.object(r.body) else { return nil }
        return VideoInfo(
            id,
            JSON.optString(json, "title"),
            JSON.optString(json, "author_name"),
            channelId: ChannelBlocklist.normalizeId(JSON.optString(json, "author_url")) ?? ""
        )
    }
}
