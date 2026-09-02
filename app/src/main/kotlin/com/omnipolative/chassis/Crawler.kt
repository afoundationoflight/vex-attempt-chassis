package com.omnipolative.chassis.store

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * THE 64-BIT CRAWLER, ON THE DEVICE.
 *
 * Nothing here unpacks. Nothing parses. The stores ship in the APK as
 * assets, get mapped, and are read in place by binary search — the same
 * way they are read on the chassis, byte for byte, because they are the
 * same files.
 *
 * That is what makes a 40 MB dictionary a 9.6 MB asset that costs zero
 * bytes resident: it was never a document to be loaded. It is an
 * indexed store to be crawled, and the phone crawls it exactly as well
 * as a server does.
 *
 * FORMATS, all little-endian:
 *
 *   .raw        varint token ids, concatenated records
 *   .raw.u64    [u64 offset][u64 length] per record
 *   .post       postings — u32 record ids, concatenated
 *   .post.idx   [u32 term][u64 offset][u32 count], SORTED BY TERM
 *   .btb        dictionary glosses — [u8 nsenses]([u16 len][varint ids])*
 *   .btb.idx    [u32 wordId][u32 offset][u16 length], SORTED BY WORD ID
 */
class Crawler(private val dir: File) {

    private val maps = HashMap<String, ByteBuffer>()

    /** Map a file once. The OS pages what is touched; we hold nothing. */
    private fun map(name: String): ByteBuffer? = maps.getOrPut(name) {
        val f = File(dir, name)
        if (!f.exists()) return null
        RandomAccessFile(f, "r").use { raf ->
            raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                .order(ByteOrder.LITTLE_ENDIAN)
        }
    }

    /** Hand out a mapped buffer. The caller reads it in place. */
    fun raw(name: String): java.nio.ByteBuffer? = map(name)

    // ── varint ──────────────────────────────────────────────────────
    /** Decode varint token ids from a mapped span. No allocation beyond
     *  the result, and the result is what was asked for rather than the
     *  whole record. */
    private fun varints(buf: ByteBuffer, from: Int, len: Int): IntArray {
        val out = IntArray(len)          // upper bound; ids are >= 1 byte
        var n = 0; var x = 0; var s = 0; var i = from
        val end = from + len
        while (i < end) {
            val c = buf.get(i).toInt() and 0xFF; i++
            x = x or ((c and 0x7F) shl s)
            if (c and 0x80 != 0) s += 7 else { out[n++] = x; x = 0; s = 0 }
        }
        return out.copyOf(n)
    }

    // ── the archive ─────────────────────────────────────────────────
    fun records(base: String): Int {
        val ix = map("$base.raw.u64") ?: return 0
        return ix.capacity() / 16
    }

    /** One record, as token ids. STAYS IN IDS. */
    fun record(base: String, i: Int): IntArray {
        val ix = map("$base.raw.u64") ?: return IntArray(0)
        val st = map("$base.raw") ?: return IntArray(0)
        if (i < 0 || i >= ix.capacity() / 16) return IntArray(0)
        val off = ix.getLong(i * 16).toInt()
        val len = ix.getLong(i * 16 + 8).toInt()
        return varints(st, off, len)
    }

    /**
     * Which records contain this term. BINARY SEARCH IN THE MAPPED
     * FILE — no index built at startup, nothing held between calls.
     */
    fun postings(base: String, term: Int): IntArray {
        val idx = map("$base.post.idx") ?: return IntArray(0)
        val dat = map("$base.post") ?: return IntArray(0)
        val n = idx.capacity() / 16
        var lo = 0; var hi = n
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val t = idx.getInt(mid * 16)
            if (java.lang.Integer.compareUnsigned(t, term) < 0) lo = mid + 1
            else hi = mid
        }
        if (lo >= n || idx.getInt(lo * 16) != term) return IntArray(0)
        val off = idx.getLong(lo * 16 + 4).toInt()
        val cnt = idx.getInt(lo * 16 + 12)
        val out = IntArray(cnt)
        for (k in 0 until cnt) out[k] = dat.getInt(off + k * 4)
        return out
    }

    /**
     * Rank records against a query. RARER TERM COUNTS FOR MORE, which
     * is the same weighting the chassis uses — a term in half the
     * archive says nothing about which half.
     */
    fun consider(base: String, terms: IntArray, top: Int = 5): IntArray {
        val n = records(base)
        val score = HashMap<Int, Double>()
        for (t in terms) {
            val p = postings(base, t)
            if (p.isEmpty() || p.size > n * 0.3) continue
            val w = 1.0 / p.size
            for (r in p) score[r] = (score[r] ?: 0.0) + w
        }
        return score.entries.sortedByDescending { it.value }
            .take(top).map { it.key }.toIntArray()
    }

    // ── the dictionary ──────────────────────────────────────────────
    /**
     * What a word means. All senses, as token ids.
     *
     * 270,055 entries in a 9.6 MB asset, ~10 microseconds a lookup,
     * nothing resident. The json this came from was 39.9 MB and had to
     * be parsed in full before a single word could be read.
     */
    fun mean(wordId: Int): List<IntArray> {
        val idx = map("table3.btb.idx") ?: return emptyList()
        val glo = map("table3.btb") ?: return emptyList()
        val n = idx.capacity() / 10
        var lo = 0; var hi = n
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val w = idx.getInt(mid * 10)
            if (java.lang.Integer.compareUnsigned(w, wordId) < 0) lo = mid + 1
            else hi = mid
        }
        if (lo >= n || idx.getInt(lo * 10) != wordId) return emptyList()
        val off = idx.getInt(lo * 10 + 4)
        var p = off
        val senses = glo.get(p).toInt() and 0xFF; p++
        val out = ArrayList<IntArray>(senses)
        for (s in 0 until senses) {
            // [u8 pos][u16 len][varint ids] — the store is already in
            // noun-first order, so the consumer does not carry the rule
            p += 1
            val len = glo.getShort(p).toInt() and 0xFFFF; p += 2
            out.add(varints(glo, p, len)); p += len
        }
        return out
    }

    /** Is it loaded, and how big is what we are NOT holding. */
    fun state(): Map<String, Any> = mapOf(
        "mapped" to maps.keys.toList(),
        "bytes_mapped" to maps.values.sumOf { it.capacity().toLong() },
        "bytes_resident" to 0,
        "note" to "the OS pages what is touched; nothing is unpacked"
    )
}
