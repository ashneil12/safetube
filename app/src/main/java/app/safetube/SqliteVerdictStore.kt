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
    SQLiteOpenHelper(context.applicationContext, "verdicts.db", null, 2), VerdictStore {

    data class HiddenVideo(
        val id: String,
        val title: String,
        val channel: String,
        val reason: String,
        val score: Double?,
    )

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
            "CREATE TABLE hidden (id TEXT PRIMARY KEY, title TEXT, channel TEXT, reason TEXT, p REAL, ts INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE overrides ADD COLUMN who TEXT NOT NULL DEFAULT 'parent'")
    }

    override fun cachedScore(id: String, configHash: String): Score? =
        readableDatabase.rawQuery("SELECT p, reason FROM scores WHERE id = ? AND hash = ?", arrayOf(id, configHash)).use {
            if (it.moveToFirst()) Score(id, it.getDouble(0), it.getString(1) ?: "") else null
        }

    override fun saveScore(score: Score, configHash: String, video: VideoInfo) {
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
        writableDatabase.insertWithOnConflict(
            "hidden", null,
            ContentValues().apply {
                put("id", video.id)
                put("title", video.title)
                put("channel", video.channel)
                put("reason", decision.reason)
                if (decision.score != null) put("p", decision.score) else putNull("p")
                put("ts", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun recentlyHidden(limit: Int = 100): List<HiddenVideo> =
        readableDatabase.rawQuery(
            "SELECT id, title, channel, reason, p FROM hidden ORDER BY ts DESC LIMIT $limit", null
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        HiddenVideo(
                            c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "",
                            if (c.isNull(4)) null else c.getDouble(4),
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
