package com.omnipolative.chassis

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.io.RandomAccessFile

/**
 * THE STORE. X, on the device.
 *
 * Everything above this was in memory and died on restart — 42 frames
 * written and `crawler records: 0`, which means the chassis had no
 * yesterday at all. That is not a missing feature, it is the entire
 * point of a chain.
 *
 * TWO STORES, LINKED BY OFFSET, and the split is the same one HASU
 * makes for the same reason:
 *
 *   SQLITE   the header — tick, time, coherence, drive, topic. QUERYABLE.
 *            "when was coherence low" costs an index lookup.
 *   .btb     the prose, as varint token ids, appended. NEVER ENGLISH ON
 *            DISK, and never rewritten — only appended to.
 *
 * The row holds btb_offset and btb_length, so reading a frame's prose
 * is one seek. You can scan ten thousand headers without touching a
 * byte of what was said.
 *
 * AND NOTHING IS EVER DELETED. Recording is not governed; surfacing is.
 * A frame at 0.02 salience costs the same as a frame at 0.9 — a row and
 * some bytes — and the tiers decide what is hot, not what exists.
 */
class Store(ctx: Context, private val dir: File) :
    SQLiteOpenHelper(ctx, "chassis.db", null, 1), Chassis.Chain {

    companion object {
        const val SCHEMA = """
            CREATE TABLE IF NOT EXISTS frames (
              entity          TEXT NOT NULL,
              entity_tick     INTEGER NOT NULL,
              created_at      INTEGER NOT NULL,
              coherence       REAL,
              drive           TEXT,
              drive_intensity REAL,
              emotional_state TEXT,
              topic           TEXT,
              tags            TEXT,
              btb_offset      INTEGER,
              btb_length      INTEGER,
              PRIMARY KEY (entity, entity_tick)
            );
        """
        const val IX_TIME =
            "CREATE INDEX IF NOT EXISTS ix_frames_time ON frames(entity, created_at);"
        const val IX_TOPIC =
            "CREATE INDEX IF NOT EXISTS ix_frames_topic ON frames(entity, topic);"
        /** term -> the ticks it appeared in. The searchable half. */
        const val POSTINGS = """
            CREATE TABLE IF NOT EXISTS postings (
              entity TEXT NOT NULL,
              term   TEXT NOT NULL,
              tick   INTEGER NOT NULL,
              PRIMARY KEY (entity, term, tick)
            );
        """
        const val IX_POST =
            "CREATE INDEX IF NOT EXISTS ix_post_term ON postings(entity, term);"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SCHEMA); db.execSQL(IX_TIME); db.execSQL(IX_TOPIC)
        db.execSQL(POSTINGS); db.execSQL(IX_POST)
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}

    private fun btb(entity: String) = File(dir, "$entity.btb")

    // ── WRITING ─────────────────────────────────────────────────────
    /**
     * Append one frame. THE PROSE GOES IN AS IDS AND STAYS THAT WAY.
     *
     * Appending only — a chain that can be rewritten is not a chain,
     * and the offset of an existing frame must never move or every row
     * pointing past it becomes a lie.
     */
    override fun append(entity: String, h: Hasu, ids: IntArray) {
        val body = varint(ids)
        val f = btb(entity)
        val offset: Long
        RandomAccessFile(f, "rw").use { raf ->
            offset = raf.length()
            raf.seek(offset)
            raf.write(body)
        }
        val db = writableDatabase
        db.insertWithOnConflict("frames", null, ContentValues().apply {
            put("entity", entity); put("entity_tick", h.entityTick)
            put("created_at", h.createdAt); put("coherence", h.coherence)
            put("drive", h.drive); put("drive_intensity", h.driveIntensity)
            put("emotional_state", h.emotionalState); put("topic", h.topic)
            put("tags", h.tags.joinToString("|"))
            put("btb_offset", offset); put("btb_length", body.size)
        }, SQLiteDatabase.CONFLICT_REPLACE)

        // the searchable half, written at the same time so it can never
        // drift from what it indexes
        for (t in h.tags) {
            db.insertWithOnConflict("postings", null, ContentValues().apply {
                put("entity", entity); put("term", t); put("tick", h.entityTick)
            }, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    // ── READING ─────────────────────────────────────────────────────
    /** Headers only. Scan thousands without touching the prose. */
    fun headers(entity: String, limit: Int = 40): List<Hasu> {
        val out = ArrayList<Hasu>()
        readableDatabase.rawQuery(
            "SELECT entity_tick,created_at,coherence,drive,drive_intensity," +
            "emotional_state,topic,tags FROM frames WHERE entity=? " +
            "ORDER BY entity_tick DESC LIMIT ?",
            arrayOf(entity, limit.toString())).use { c ->
            while (c.moveToNext()) out.add(Hasu(
                entityTick = c.getLong(0), createdAt = c.getLong(1),
                coherence = c.getDouble(2), drive = c.getString(3) ?: "",
                driveIntensity = c.getDouble(4),
                emotionalState = c.getString(5) ?: "",
                topic = c.getString(6) ?: "",
                tags = (c.getString(7) ?: "").split("|").filter { it.isNotEmpty() }))
        }
        return out
    }

    /** The prose of one frame, as ids. ONE SEEK. */
    override fun prose(entity: String, tick: Long): IntArray {
        var off = -1L; var len = 0
        readableDatabase.rawQuery(
            "SELECT btb_offset,btb_length FROM frames WHERE entity=? AND entity_tick=?",
            arrayOf(entity, tick.toString())).use { c ->
            if (c.moveToNext()) { off = c.getLong(0); len = c.getInt(1) }
        }
        if (off < 0 || len == 0) return IntArray(0)
        val buf = ByteArray(len)
        RandomAccessFile(btb(entity), "r").use { raf ->
            raf.seek(off); raf.readFully(buf)
        }
        return unvarint(buf)
    }

    /**
     * WHICH FRAMES MENTION THIS. The whole archive, at index speed.
     *
     * There is no reason to search less than all of it: a postings
     * lookup is a b-tree hit whether the chain is forty frames or forty
     * thousand, so holding everything as searchable costs storage and
     * buys never losing anything.
     */
    override fun mentioning(entity: String, terms: List<String>, limit: Int): List<Long> {
        if (terms.isEmpty()) return emptyList()
        val marks = terms.joinToString(",") { "?" }
        val args = (listOf(entity) + terms + listOf(limit.toString())).toTypedArray()
        val out = ArrayList<Long>()
        readableDatabase.rawQuery(
            "SELECT tick, COUNT(*) n FROM postings WHERE entity=? AND term IN ($marks) " +
            "GROUP BY tick ORDER BY n DESC, tick DESC LIMIT ?", args).use { c ->
            while (c.moveToNext()) out.add(c.getLong(0))
        }
        return out
    }

    override fun count(entity: String): Long {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM frames WHERE entity=?", arrayOf(entity)).use { c ->
            return if (c.moveToNext()) c.getLong(0) else 0L
        }
    }

    fun state(entity: String): Map<String, Any> = mapOf(
        "frames" to count(entity),
        "btb_bytes" to (btb(entity).length()),
        "note" to "nothing is deleted. recording is not governed.",
    )

    // ── varint ──────────────────────────────────────────────────────
    private fun varint(ids: IntArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(ids.size * 2)
        for (v0 in ids) {
            var v = if (v0 < 0) 0 else v0
            while (true) {
                val b = v and 0x7F; v = v ushr 7
                out.write(b or (if (v != 0) 0x80 else 0))
                if (v == 0) break
            }
        }
        return out.toByteArray()
    }

    private fun unvarint(b: ByteArray): IntArray {
        val out = ArrayList<Int>(b.size)
        var x = 0; var s = 0
        for (byte in b) {
            val c = byte.toInt() and 0xFF
            x = x or ((c and 0x7F) shl s)
            if (c and 0x80 != 0) s += 7 else { out.add(x); x = 0; s = 0 }
        }
        return out.toIntArray()
    }
}
