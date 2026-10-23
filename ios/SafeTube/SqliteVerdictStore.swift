import Foundation
import SQLite3
import SafeTubeCore

private let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

/// On-device cache of decisions, so each video is checked by the AI only once per set of rules.
/// Same tables as the Android app's SqliteVerdictStore.
final class SqliteVerdictStore: VerdictStore {
    static let shared = SqliteVerdictStore()

    struct HiddenVideo {
        let id: String
        let title: String
        let channel: String
        let reason: String
        let score: Double?
    }

    /// A video the parent (or, if allowed, the child) hid or allowed by hand. by is "parent" or "child".
    struct ParentChoice {
        let id: String
        let title: String
        let verdict: Verdict
        let by: String
    }

    private var db: OpaquePointer?
    /// One connection, used from the engine's threads and the main thread; every call holds this.
    private let lock = NSRecursiveLock()

    private init() {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let path = dir.appendingPathComponent("verdicts.db").path
        if sqlite3_open_v2(path, &db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX, nil) != SQLITE_OK {
            db = nil
            sqlite3_open(":memory:", &db)
        }
        exec("CREATE TABLE IF NOT EXISTS scores (id TEXT NOT NULL, hash TEXT NOT NULL, p REAL NOT NULL, reason TEXT, " +
             "title TEXT, ts INTEGER NOT NULL, PRIMARY KEY (id, hash))")
        exec("CREATE TABLE IF NOT EXISTS overrides (id TEXT PRIMARY KEY, verdict TEXT NOT NULL, title TEXT, ts INTEGER NOT NULL, " +
             "who TEXT NOT NULL DEFAULT 'parent')")
        exec("CREATE TABLE IF NOT EXISTS hidden (id TEXT PRIMARY KEY, title TEXT, channel TEXT, reason TEXT, p REAL, ts INTEGER NOT NULL)")
    }

    // ------------------------------------------------------------------ VerdictStore

    func cachedScore(_ id: String, configHash: String) -> Score? {
        query("SELECT p, reason FROM scores WHERE id = ? AND hash = ?", [id, configHash]) { s in
            Score(id, sqlite3_column_double(s, 0), text(s, 1))
        }.first
    }

    func saveScore(_ score: Score, configHash: String, video: VideoInfo) {
        run("INSERT OR REPLACE INTO scores (id, hash, p, reason, title, ts) VALUES (?, ?, ?, ?, ?, ?)",
            [score.id, configHash, score.p, score.reason, video.title, now()])
    }

    func parentOverride(_ id: String) -> Verdict? {
        query("SELECT verdict FROM overrides WHERE id = ?", [id]) { s in Verdict(rawValue: text(s, 0)) }.first ?? nil
    }

    func setOverride(_ id: String, _ verdict: Verdict?) { setOverride(id, verdict, title: nil) }

    func setOverride(_ id: String, _ verdict: Verdict?, title: String?, by: String = "parent") {
        lock.lock(); defer { lock.unlock() }
        guard let verdict = verdict else {
            run("DELETE FROM overrides WHERE id = ?", [id])
            return
        }
        let t = JSON.orNull(title ?? titleFor(id))
        run("INSERT OR REPLACE INTO overrides (id, verdict, title, ts, who) VALUES (?, ?, ?, ?, ?)",
            [id, verdict.rawValue, t, now(), by])
        if verdict == .allow { run("DELETE FROM hidden WHERE id = ?", [id]) }
    }

    func recordHidden(_ video: VideoInfo, _ decision: Decision) {
        let p = JSON.orNull(decision.score)
        run("INSERT OR REPLACE INTO hidden (id, title, channel, reason, p, ts) VALUES (?, ?, ?, ?, ?, ?)",
            [video.id, video.title, video.channel, decision.reason, p, now()])
    }

    // ------------------------------------------------------------------ parent screens

    func recentlyHidden(limit: Int = 100) -> [HiddenVideo] {
        query("SELECT id, title, channel, reason, p FROM hidden ORDER BY ts DESC LIMIT \(limit)", []) { s in
            HiddenVideo(id: text(s, 0), title: text(s, 1), channel: text(s, 2), reason: text(s, 3),
                        score: sqlite3_column_type(s, 4) == SQLITE_NULL ? nil : sqlite3_column_double(s, 4))
        }
    }

    func parentChoices() -> [ParentChoice] {
        query("SELECT id, title, verdict, who FROM overrides ORDER BY ts DESC", []) { s -> ParentChoice? in
            guard let v = Verdict(rawValue: text(s, 2)) else { return nil }
            let who = text(s, 3)
            return ParentChoice(id: text(s, 0), title: text(s, 1), verdict: v, by: who.isEmpty ? "parent" : who)
        }.compactMap { $0 }
    }

    func hiddenSince(_ ts: Int64) -> Int {
        query("SELECT COUNT(*) FROM hidden WHERE ts >= ?", [ts]) { s in Int(sqlite3_column_int64(s, 0)) }.first ?? 0
    }

    /// Forget AI scores and the hidden list (parent choices are kept).
    func clearDecisions() {
        run("DELETE FROM scores", [])
        run("DELETE FROM hidden", [])
    }

    private func titleFor(_ id: String) -> String? {
        query("SELECT title FROM hidden WHERE id = ? UNION ALL SELECT title FROM scores WHERE id = ? LIMIT 1", [id, id]) { s in
            sqlite3_column_type(s, 0) == SQLITE_NULL ? nil : text(s, 0)
        }.first ?? nil
    }

    // ------------------------------------------------------------------ SQLite plumbing

    private func now() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    private func exec(_ sql: String) {
        lock.lock(); defer { lock.unlock() }
        sqlite3_exec(db, sql, nil, nil, nil)
    }

    private func prepare(_ sql: String, _ args: [Any]) -> OpaquePointer? {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return nil }
        for (i, a) in args.enumerated() {
            let idx = Int32(i + 1)
            switch a {
            case let s as String: sqlite3_bind_text(stmt, idx, s, -1, SQLITE_TRANSIENT)
            case let d as Double: sqlite3_bind_double(stmt, idx, d)
            case let n as Int64: sqlite3_bind_int64(stmt, idx, n)
            case let n as Int: sqlite3_bind_int64(stmt, idx, Int64(n))
            default: sqlite3_bind_null(stmt, idx)
            }
        }
        return stmt
    }

    private func run(_ sql: String, _ args: [Any]) {
        lock.lock(); defer { lock.unlock() }
        guard let stmt = prepare(sql, args) else { return }
        defer { sqlite3_finalize(stmt) }
        sqlite3_step(stmt)
    }

    private func query<T>(_ sql: String, _ args: [Any], _ row: (OpaquePointer) -> T) -> [T] {
        lock.lock(); defer { lock.unlock() }
        guard let stmt = prepare(sql, args) else { return [] }
        defer { sqlite3_finalize(stmt) }
        var out: [T] = []
        while sqlite3_step(stmt) == SQLITE_ROW { out.append(row(stmt)) }
        return out
    }
}

private func text(_ stmt: OpaquePointer, _ col: Int32) -> String {
    guard let c = sqlite3_column_text(stmt, col) else { return "" }
    return String(cString: c)
}
