package app.safetube

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.safetube.core.Decision
import app.safetube.core.Score
import app.safetube.core.Verdict
import app.safetube.core.VerdictStore
import app.safetube.core.VideoInfo

/** On-device cache of decisions, so each video is checked by the AI only once per set of rules. */
class SqliteVerdictStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "verdicts.db", null, 4), VerdictStore {

    data class HiddenVideo(
        val id: String,
        val title: String,
        val channel: String,
        val reason: String,
        val score: Double?,
        val source: String = "SAVED",
    )

    data class Watched(val id: String, val title: String, val channel: String, val channelId: String, val ts: Long)

    /** A video the parent (or, if allowed, the child) hid or allowed by hand. by is "parent" or "child". */
    data class ParentChoice(val id: String, val title: String, val verdict: Verdict, val by: String)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE scores (id TEXT NOT NULL, hash TEXT NOT NULL, p REAL NOT NULL, reason TEXT, " +
                "title TEXT, ts INTEGER NOT NULL, PRIMARY KEY (id, hash))"
        )
        db.execSQL(
            "CREATE TABLE overrides (id TEXT PRIMARY KEY, verdict TEXT NOT NULL, title TEXT, ts INTEGER NOT NULL, " +
                "who TEXT NOT NULL DEFAULT 'parent')"
        )
        db.execSQL(
            "CREATE TABLE hidden (id TEXT PRIMARY KEY, title TEXT, channel TEXT, reason TEXT, p REAL, ts INTEGER NOT NULL, source TEXT)"
        )
        createHistory(db)
        createMetadata(db)
    }

    private fun createMetadata(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS video_metadata (id TEXT PRIMARY KEY, title TEXT, channel TEXT, channelId TEXT, context TEXT)")
    }

    private fun rememberVideo(video: VideoInfo) {
        writableDatabase.insertWithOnConflict("video_metadata", null, ContentValues().apply {
            put("id", video.id); put("title", video.title); put("channel", video.channel)
            put("channelId", video.channelId); put("context", video.context)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun videoInfo(id: String): VideoInfo? = readableDatabase.rawQuery(
        "SELECT title, channel, channelId, context FROM video_metadata WHERE id = ?", arrayOf(id)
    ).use { c -> if (c.moveToFirst()) VideoInfo(id, c.getString(0) ?: "", c.getString(1) ?: "", c.getString(3) ?: "", c.getString(2) ?: "") else null }

    private fun createHistory(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS history (id TEXT PRIMARY KEY, title TEXT, channel TEXT, channelId TEXT, ts INTEGER NOT NULL)"
        )
    }

    /** Remembers a watched video for the parent's list; the newest 300 are kept. */
    fun recordWatched(v: Watched) {
        val db = writableDatabase
        db.insertWithOnConflict(
            "history", null,
            ContentValues().apply {
                put("id", v.id); put("title", v.title); put("channel", v.channel)
                put("channelId", v.channelId); put("ts", v.ts)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        db.execSQL("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY ts DESC LIMIT 300)")
    }

    fun history(limit: Int = 100): List<Watched> =
        readableDatabase.rawQuery("SELECT id, title, channel, channelId, ts FROM history ORDER BY ts DESC LIMIT $limit", null).use {
            val out = ArrayList<Watched>()
            while (it.moveToNext()) {
                out += Watched(it.getString(0), it.getString(1) ?: "", it.getString(2) ?: "", it.getString(3) ?: "", it.getLong(4))
            }
            out
        }

    fun clearHistory() {
        writableDatabase.delete("history", null, null)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE overrides ADD COLUMN who TEXT NOT NULL DEFAULT 'parent'")
        if (oldVersion < 3) createHistory(db)
        if (oldVersion < 4) {
            createMetadata(db)
            db.execSQL("ALTER TABLE hidden ADD COLUMN source TEXT")
        }
    }

    override fun cachedScore(id: String, configHash: String): Score? =
        readableDatabase.rawQuery("SELECT p, reason FROM scores WHERE id = ? AND hash = ?", arrayOf(id, configHash)).use {
            if (it.moveToFirst()) Score(id, it.getDouble(0), it.getString(1) ?: "") else null
        }

    override fun saveScore(score: Score, configHash: String, video: VideoInfo) {
        rememberVideo(video)
        writableDatabase.insertWithOnConflict(
            "scores", null,
            ContentValues().apply {
                put("id", score.id)
                put("hash", configHash)
                put("p", score.p)
                put("reason", score.reason)
                put("title", video.title)
                put("ts", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    override fun override(id: String): Verdict? =
        readableDatabase.rawQuery("SELECT verdict FROM overrides WHERE id = ?", arrayOf(id)).use {
            if (it.moveToFirst()) Verdict.entries.firstOrNull { v -> v.name == it.getString(0) } else null
        }

    override fun setOverride(id: String, verdict: Verdict?) = setOverride(id, verdict, null)

    fun setOverride(id: String, verdict: Verdict?, title: String?, by: String = "parent") {
        if (verdict == null) {
            writableDatabase.delete("overrides", "id = ?", arrayOf(id))
            return
        }
        writableDatabase.insertWithOnConflict(
            "overrides", null,
            ContentValues().apply {
                put("id", id)
                put("verdict", verdict.name)
                put("title", title ?: titleFor(id))
                put("ts", System.currentTimeMillis())
                put("who", by)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        if (verdict == Verdict.ALLOW) writableDatabase.delete("hidden", "id = ?", arrayOf(id))
    }

    override fun recordHidden(video: VideoInfo, decision: Decision) {
        rememberVideo(video)
        writableDatabase.insertWithOnConflict(
            "hidden", null,
            ContentValues().apply {
                put("id", video.id)
                put("title", video.title)
                put("channel", video.channel)
                put("reason", decision.reason)
                put("source", decision.source.name)
                if (decision.score != null) put("p", decision.score) else putNull("p")
                put("ts", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun recentlyHidden(limit: Int = 100): List<HiddenVideo> =
        readableDatabase.rawQuery(
            "SELECT id, title, channel, reason, p, source FROM hidden ORDER BY ts DESC LIMIT $limit", null
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        HiddenVideo(
                            c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "",
                            if (c.isNull(4)) null else c.getDouble(4), c.getString(5) ?: "SAVED",
                        )
                    )
                }
            }
        }

    fun parentChoices(): List<ParentChoice> =
        readableDatabase.rawQuery("SELECT id, title, verdict, who FROM overrides ORDER BY ts DESC", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val v = Verdict.entries.firstOrNull { it.name == c.getString(2) } ?: continue
                    add(ParentChoice(c.getString(0), c.getString(1) ?: "", v, c.getString(3) ?: "parent"))
                }
            }
        }

    fun hiddenSince(ts: Long): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM hidden WHERE ts >= ?", arrayOf(ts.toString())).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    /** Forget AI scores and the hidden list (parent choices are kept). */
    fun clearDecisions() {
        writableDatabase.delete("scores", null, null)
        writableDatabase.delete("hidden", null, null)
    }

    private fun titleFor(id: String): String? =
        readableDatabase.rawQuery(
            "SELECT title FROM hidden WHERE id = ? UNION ALL SELECT title FROM scores WHERE id = ? LIMIT 1",
            arrayOf(id, id),
        ).use { if (it.moveToFirst()) it.getString(0) else null }
}
