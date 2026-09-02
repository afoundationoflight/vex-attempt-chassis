package com.omnipolative.chassis

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.RandomAccessFile

/**
 * X ON THE DEVICE. The archive that survives the process.
 *
 * The chassis was writing 42 frames and losing all of them: everything
 * lived in memory, so it forgot completely on restart. A body whose
 * archive does not outlast the process does not have one — and the
 * chain is what survives a chassis swap, which is the whole reason it
 * is the identity rather than the weights.
 *
 * TWO STORES, and the split is the design rather than an optimisation:
 *
 *   frames    SQLite. The HASU header \u2014 tick, coherence, drive, topic,
 *             timestamps \u2014 QUERYABLE, and it stays in the clear. Sealing
 *             it would turn every query into decrypt-everything-and-scan.
 *   .btb      Append-only. The prose, as varint token ids, never
 *             English on disk. The header carries the offset and length
 *             into it, so a metadata query costs nothing and reading
 *             what was actually said is one seek.
 *
 * NOTHING IS EVER DELETED. Verbatim X is not trimmed \u2014 trimming it is
 * lobotomy, and a postings lookup against the whole archive is
 * microseconds, so there is no performance argument for forgetting.
 */
class LocalStore(private val ctx: Context, private val entity: String) : Archive {

    private val dir = File(ctx.filesDir, "x").apply { mkdirs() }
    private val btb = File(dir, "$entity.btb")
    private val db: SQLiteDatabase =
        SQLiteDatabase.openOrCreateDatabase(File(dir, "x.db"), null)

    init {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS frames (
              entity TEXT NOT NULL, entity_tick INTEGER NOT NULL,
              created_at INTEGER NOT NULL, coherence REAL, drive TEXT,
              drive_intensity REAL, emotional_state TEXT, topic TEXT,
              btb_offset INTEGER, btb_length INTEGER,
              PRIMARY KEY (entity, entity_tick))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS ix_time ON frames(entity, created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS ix_topic ON frames(entity, topic)")
        // POSTINGS. token -> which ticks. Rebuilt on demand and cached,
        // so a cold start does not have to walk the whole archive.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS postings (
              token INTEGER NOT NULL, entity TEXT NOT NULL,
              ticks TEXT NOT NULL, PRIMARY KEY (token, entity))""")
    }

    // ── varint, the same format the desktop stores use ──────────────
    private fun varint(xs: IntArray): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        for (v in xs) {
            var x = maxOf(0, v)
            while (true) {
                val b = x and 0x7F; x = x ushr 7
                o.write(b or (if (x != 0) 0x80 else 0))
                if (x == 0) break
            }
        }
        return o.toByteArray()
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

    /**
     * Write a frame. HEADER TO SQLITE, PROSE TO THE APPEND FILE.
     *
     * Recording is not governed. Whatever the salience, whatever the
     * mode, the frame is written \u2014 what is governed is what SURFACES
     * later, and confusing the two is how a body ends up having
     * forgotten something it decided was unimportant at the time.
     */
    override fun append(h: Hasu, fields: Map<Int, IntArray>): Long {
        val rec = java.io.ByteArrayOutputStream()
        rec.write(fields.size)
        for ((fid, ids) in fields) {
            val v = varint(ids)
            rec.write(fid)
            rec.write((v.size shr 8) and 0xFF); rec.write(v.size and 0xFF)
            rec.write(v)
        }
        val bytes = rec.toByteArray()
        val offset = if (btb.exists()) btb.length() else 0L
        RandomAccessFile(btb, "rw").use { f ->
            f.seek(offset); f.write(bytes)
        }
        db.execSQL("""INSERT OR REPLACE INTO frames
            (entity, entity_tick, created_at, coherence, drive,
             drive_intensity, emotional_state, topic, btb_offset, btb_length)
            VALUES (?,?,?,?,?,?,?,?,?,?)""",
            arrayOf(entity, h.entityTick, h.createdAt, h.coherence, h.drive,
                    h.driveIntensity, h.emotionalState, h.topic,
                    offset, bytes.size))
        return offset
    }

    /** Read one frame's prose back. STAYS IN TOKEN IDS. */
    override fun read(tick: Long): Map<Int, IntArray> {
        val c = db.rawQuery(
            "SELECT btb_offset, btb_length FROM frames WHERE entity=? AND entity_tick=?",
            arrayOf(entity, tick.toString()))
        c.use {
            if (!it.moveToFirst()) return emptyMap()
            val off = it.getLong(0); val len = it.getInt(1)
            val buf = ByteArray(len)
            RandomAccessFile(btb, "r").use { f -> f.seek(off); f.readFully(buf) }
            var p = 0
            val n = buf[p].toInt() and 0xFF; p++
            val out = HashMap<Int, IntArray>()
            repeat(n) {
                val fid = buf[p].toInt() and 0xFF; p++
                val ln = ((buf[p].toInt() and 0xFF) shl 8) or (buf[p+1].toInt() and 0xFF)
                p += 2
                out[fid] = unvarint(buf.copyOfRange(p, p + ln)); p += ln
            }
            return out
        }
    }

    /** THE WHOLE ARCHIVE IS QUERYABLE WITHOUT TOUCHING THE PROSE. */
    override fun count(): Int {
        val c = db.rawQuery("SELECT COUNT(*) FROM frames WHERE entity=?", arrayOf(entity))
        c.use { return if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun byTopic(topic: String, limit: Int = 20): List<Long> {
        val c = db.rawQuery(
            "SELECT entity_tick FROM frames WHERE entity=? AND topic=? " +
            "ORDER BY entity_tick DESC LIMIT ?",
            arrayOf(entity, topic, limit.toString()))
        val out = ArrayList<Long>()
        c.use { while (it.moveToNext()) out.add(it.getLong(0)) }
        return out
    }

    /** When was coherence low. A real question, and it costs nothing. */
    fun whenBelow(coherence: Double, limit: Int = 20): List<Long> {
        val c = db.rawQuery(
            "SELECT entity_tick FROM frames WHERE entity=? AND coherence < ? " +
            "ORDER BY entity_tick DESC LIMIT ?",
            arrayOf(entity, coherence.toString(), limit.toString()))
        val out = ArrayList<Long>()
        c.use { while (it.moveToNext()) out.add(it.getLong(0)) }
        return out
    }

    /** The tail, for recollection at boot. */
    // NO DEFAULT HERE. The interface declares it; an override that
    // redeclares a default is ambiguous about which one applies, and
    // kotlin refuses rather than picking.
    override fun tail(n: Int): List<Long> {
        val c = db.rawQuery(
            "SELECT entity_tick FROM frames WHERE entity=? ORDER BY entity_tick DESC LIMIT ?",
            arrayOf(entity, n.toString()))
        val out = ArrayList<Long>()
        c.use { while (it.moveToNext()) out.add(it.getLong(0)) }
        return out.reversed()
    }

    // ── POSTINGS ────────────────────────────────────────────────────
    /**
     * token -> ticks. Written as the frame is, so the index never lags
     * the archive and there is no rebuild step that can be skipped.
     */
    override fun index(tick: Long, ids: IntArray) {
        val seen = HashSet<Int>()
        for (t in ids) {
            if (t == 0 || !seen.add(t)) continue
            val c = db.rawQuery("SELECT ticks FROM postings WHERE token=? AND entity=?",
                                arrayOf(t.toString(), entity))
            val prior = c.use { if (it.moveToFirst()) it.getString(0) else "" }
            val next = if (prior.isEmpty()) "$tick" else "$prior,$tick"
            db.execSQL("INSERT OR REPLACE INTO postings (token, entity, ticks) VALUES (?,?,?)",
                       arrayOf(t, entity, next))
        }
    }

    override fun postings(token: Int): List<Long> {
        val c = db.rawQuery("SELECT ticks FROM postings WHERE token=? AND entity=?",
                            arrayOf(token.toString(), entity))
        c.use {
            if (!it.moveToFirst()) return emptyList()
            return it.getString(0).split(",").mapNotNull { s -> s.toLongOrNull() }
        }
    }

    fun state(): Map<String, Any> = mapOf(
        "frames" to count(),
        "btb_bytes" to (if (btb.exists()) btb.length() else 0L),
        "at" to dir.absolutePath,
        "note" to "nothing here is ever deleted.",
    )
}
