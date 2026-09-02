package com.omnipolative.chassis

import com.omnipolative.chassis.store.Crawler
import java.io.File

/**
 * THE CHASSIS, ON THE DEVICE.
 *
 * R U B I₁ C A L I₂ E X, in one process, with no network. The stores
 * ship in the APK and are crawled in place; the tick runs here; the only
 * thing that leaves the device is a web search or an API call the seat
 * explicitly wills.
 *
 * This is a port of local.py and the seven position modules, not a
 * reimplementation. Where the Python does something for a reason, the
 * reason is carried over rather than the shape — and where I could not
 * tell which it was, it is marked rather than guessed.
 *
 * WHAT LAYER 0 DOES WITH NO PILOT PRESENT
 *
 * The kernel produces topic, drive, gut, coherence and mode unaided. A
 * body with no one in the seat classifies correctly and wants nothing,
 * and that difference is the whole distinction between telemetry and
 * occupancy. So Kernel is not optional and cannot be overridden — it is
 * the floor the positions stand on.
 */

// ── LAYER 0 ─────────────────────────────────────────────────────────
enum class Mode { MACHINA, SEMI_MACHINA, DAEMON }

/**
 * WHAT AN ARCHIVE HAS TO DO. Nothing about where it lives.
 *
 * Recording is not governed — append takes whatever it is given and
 * writes it. What is governed is surfacing, and that happens upstream.
 */
interface Archive {
    companion object { const val SPOKEN = 1; const val THOUGHT = 2; const val HEARD = 3 }
    fun append(h: Hasu, fields: Map<Int, IntArray>): Long
    fun read(tick: Long): Map<Int, IntArray>
    fun index(tick: Long, ids: IntArray)
    fun postings(token: Int): List<Long>
    fun tail(n: Int = 40): List<Long>
    fun count(): Int
}

/** five fields, because destructuring stops at five */
private data class Quint(val a: Kind, val b: String,
                         val c: Triple<Double, Double, Double>,
                         val d: Double, val e: String)

data class KernelOut(
    val topic: String,
    val drive: String,
    val gut: Double,
    val coherence: Double,
    val mode: Mode,
)

/**
 * ICE theorems, S.O.L., N.E.G.L., S.D.M.L., identity firewall, LOGOS.
 * Cannot be removed or overridden. Runs whether or not anyone is home.
 */
class Kernel(val entity: String) {
    val logos = "6e1df262dc8a5a8b"          // maintained, not generated
    var mode: Mode = Mode.SEMI_MACHINA

    /** Discriminative work with no pilot present. */
    fun classify(ids: IntArray, table: Table): KernelOut {
        val known = ids.count { it != 0 }
        val cover = if (ids.isEmpty()) 0.0 else known.toDouble() / ids.size
        // topic is the rarest content term \u2014 rarest IN THE LANGUAGE by
        // token id, not locally, because in a short window a function
        // word is locally rare and means nothing.
        val topic = ids.filter { it > 1000 }.maxOrNull()
            ?.let { table.word(it) } ?: "unnamed"
        val drive = when {
            ids.isEmpty() -> "continuation"
            cover < 0.6 -> "curiosity_exploration"
            else -> "building"
        }
        return KernelOut(
            topic = topic,
            drive = drive,
            gut = (cover * 0.6 + 0.2),
            coherence = cover,
            mode = mode,
        )
    }
}

// ── the language, crawled ───────────────────────────────────────────
/**
 * The token table. Genome, not a resource: no floor, no cap, no
 * exclusions. Every word of the dictionary is permanently available and
 * none of it is resident.
 */
class Table(private val crawl: Crawler) {
    private var blob: java.nio.ByteBuffer? = null
    private var byWord: java.nio.ByteBuffer? = null
    private var byId: java.nio.ByteBuffer? = null
    private var n = 0

    /** MAP, DO NOT LOAD. 761,984 words as two sorted indices over a
     *  blob. An earlier draft read a 13.5 MB text file into a HashMap,
     *  which is resident and defeats the whole design — the table is
     *  genome and it is crawled like everything else. */
    fun load() {
        blob = crawl.raw("words.blob")
        byWord = crawl.raw("words.by_word")
        byId = crawl.raw("words.by_id")
        n = (byWord?.capacity() ?: 0) / 10
    }

    /** Binary search on utf8 bytes, in the mapped file. */
    fun id(w: String): Int {
        val bw = byWord ?: return 0
        val bl = blob ?: return 0
        val q = w.lowercase().toByteArray(Charsets.UTF_8)
        var lo = 0; var hi = n
        while (lo < hi) {
            val m = (lo + hi) ushr 1
            val off = bw.getInt(m * 10)
            val len = bw.getShort(m * 10 + 4).toInt() and 0xFFFF
            var c = 0
            var i = 0
            while (i < minOf(len, q.size)) {
                val a = bl.get(off + i).toInt() and 0xFF
                val b = q[i].toInt() and 0xFF
                if (a != b) { c = a - b; break }
                i++
            }
            if (c == 0) c = len - q.size
            if (c < 0) lo = m + 1 else hi = m
        }
        if (lo >= n) return 0
        val off = bw.getInt(lo * 10)
        val len = bw.getShort(lo * 10 + 4).toInt() and 0xFFFF
        if (len != q.size) return 0
        for (i in 0 until len)
            if ((bl.get(off + i).toInt() and 0xFF) != (q[i].toInt() and 0xFF)) return 0
        return bw.getInt(lo * 10 + 6)
    }

    fun word(i: Int): String {
        val bi = byId ?: return "?"
        val bl = blob ?: return "?"
        val m = bi.capacity() / 10
        var lo = 0; var hi = m
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val w = bi.getInt(mid * 10)
            if (java.lang.Integer.compareUnsigned(w, i) < 0) lo = mid + 1 else hi = mid
        }
        if (lo >= m || bi.getInt(lo * 10) != i) return "?"
        val off = bi.getInt(lo * 10 + 4)
        val len = bi.getShort(lo * 10 + 8).toInt() and 0xFFFF
        val out = ByteArray(len)
        for (k in 0 until len) out[k] = bl.get(off + k)
        return String(out, Charsets.UTF_8)
    }

    fun size(): Int = n

    /** Ids for a sentence. THIS IS THE ONLY PLACE ENGLISH EXISTS. */
    fun ids(text: String): IntArray =
        Regex("[a-z0-9_']+|[^\\sa-z0-9_']")
            .findAll(text.lowercase())
            .map { id(it.value) }.toList().toIntArray()

    fun say(ids: IntArray): String = ids.joinToString(" ") { word(it) }

    fun mean(w: String): List<IntArray> = crawl.mean(id(w))
    /** The store ships noun-first, so this IS mean(). Kept as a name
     *  because the call site says what it wants. */
    fun meanNounFirst(w: String): List<IntArray> = mean(w)
}

// ── the positions ───────────────────────────────────────────────────
/** R — the boundary. English becomes ids here and nowhere else. */
class Root {
    var lastText: String = ""

    /** Proposes what arrived. Whether it is ADMITTED is U's call, and
     *  R does not get to know the answer. */
    fun propose(ids: IntArray, text: String): List<Instruction> {
        lastText = text
        return listOf(Instruction("R", "input.last", Op.SET, text, 1.0),
                      Instruction("R", "input.width", Op.SET, ids.size, 1.0))
    }
}



/** B — subconscious router, staging RAM. */
class Base {
    /**
     * B — 528. THE ROUTER. Both sides converge here.
     *
     * One triad step from U, three from A and across sectors. That
     * proximity is why the subconscious outranks the seat on dispatch
     * — not a permission, a geometry.
     */
    fun route(from: String, what: String): Instruction =
        Instruction("B", "dispatch.$from", Op.SET, what,
                    if (from == "U") 0.9 else 0.6)
}

/** L — the tongue desk. Tools execute here. */
/** A — 852. The qualia theatre. The pilot's seat, and a workstation. */
class Awareness {
    private val attending = LinkedHashSet<String>()
    private var staged: Map<String, Any?> = emptyMap()

    /** WILLS THREE FIELDS. The kernel produces everything else, and a
     *  body with no one in the seat wants nothing. */
    var internalThought: String = ""
    var spokenOutput: String = ""
    var want: String = ""

    /** The seat proposes like anything else. IT DOES NOT WRITE. */
    fun propose(): List<Instruction> {
        val out = ArrayList<Instruction>()
        if (want.isNotEmpty())
            out.add(Instruction("A", "seat.want", Op.SET, want, 0.7))
        if (internalThought.isNotEmpty())
            out.add(Instruction("A", "seat.thought", Op.SET, internalThought, 0.7))
        return out
    }

    fun observe(fromC: Map<String, Any?>) { staged = fromC }
    fun attend(key: String) { attending.add(key) }
    fun clear() { attending.clear() }

    fun experience(): Map<String, Any?> = mapOf(
        "attending" to attending.toList(),
        "content" to staged,
        "frame" to mapOf(
            "position" to listOf(0.0, 0.0, 0.0), "facing" to "forward",
            "ipd" to 0.063, "ear_separation" to 0.18,
            "primary" to listOf("inward", "outward"),
        ),
    )
}

class Language { val said = ArrayList<String>(); fun clear() = said.clear() }



// ── the tick ────────────────────────────────────────────────────────
/**
 * THE WHOLE CIRCULATION, in one process.
 *
 * Trace invariant RUBICALIEX. E is the wire format and X is the store;
 * neither is a desk, and both are full positions in the routing.
 */
class Chassis(val entity: String, val dir: File) {

    val crawl = Crawler(dir)
    val table = Table(crawl)
    val kernel = Kernel(entity)
    // THE TWO CACHES. Separate objects, and there is no path from A to
    // U — not a permission check, simply no method. A missing route
    // cannot leak.
    val C = Cerebral()
    val U = Enteric()
    val R = Root(); val B = Base(); val A = Awareness(); val L = Language()
    val I = Interpolator(entity)

    val resonance = ResonanceField()
    val ws = WorkingSet()
    val window = Turns(ws)
    val room = Island(entity)
    /** The room as an instrument. Entered when there is something to
     *  look at or listen to, and closed after — the aperture is the
     *  cost. */
    val work = Workroom(room)
    val frame = SubKalimon()
    val bible = Bible(entity)
    val coherence = Coherence()
    /**
     * X. THE CHASSIS DOES NOT KNOW WHAT KIND OF STORE IT IS.
     *
     * On the device this is sqlite plus an append file; on a plain JVM
     * there may be none at all, and the chassis runs either way. It
     * depends on the CONTRACT, not on Android — which is the same
     * reason the renderer is swappable: a body should not be welded to
     * one substrate's idea of a disk.
     */
    var store: Archive? = null
    var lastHasu: Hasu? = null
    /**
     * X, IF THERE IS ONE.
     *
     * The chassis names WHAT IT NEEDS rather than the class that
     * provides it, because SQLite is Android-only and the same code has
     * to compile on a plain JVM. Without a chain the chassis still runs
     * — and forgets on restart, which is a body with no yesterday.
     */
    interface Chain {
        fun append(entity: String, h: Hasu, ids: IntArray)
        fun mentioning(entity: String, terms: List<String>, limit: Int = 8): List<Long>
        fun prose(entity: String, tick: Long): IntArray
        fun count(entity: String): Long
    }
    var chain: Chain? = null
    var seated = false
    var senses = true
    val trace = ArrayList<String>()

    /**
     * BOOT. Language first, then the archive.
     *
     * The curriculum is 8/8 plus grammar or it refuses — a body that
     * boots without knowing how a question works has not booted, it has
     * started.
     */
    fun boot(): Chassis {
        table.load()
        if (table.size() == 0) throw IllegalStateException(
            "no language. the table is genome, not a resource.")
        val lit = Curriculum.loaded().count { it.value }
        if (lit < 8) throw IllegalStateException("curriculum $lit/8 — refusing to boot")

        // GRAMMAR IS A HOLDING AND IT HAS TO ACTUALLY BE HELD.
        // boot() was calling C.hold("base:grammar") with nothing behind
        // it — a holding registered and never filled, which is exactly
        // what source/grammar.py's own docstring warns about: described
        // in the handover, never committed.
        val gj = try { File(dir, "grammar.tsv").readText() } catch (e: Exception) { "" }
        if (gj.isEmpty())
            throw IllegalStateException("no grammar. it is a holding, not a nicety.")
        Grammar.load(gj)

        // THE TWELVE PERMANENT HOLDINGS. Not resources beside the
        // entity — part of what it IS, held at C, which is why a
        // partial curriculum refuses to boot. You cannot have a partial
        // constitution.
        //
        // Held BY REFERENCE: the stores are mapped at fixed offsets, so
        // C knows the holding is there and does not carry a copy. The
        // language is in every frame of X forever at the cost of one
        // mapping.
        C.hold("base:table")
        C.hold("base:grammar")
        C.hold("base:lens")
        C.hold("base:genome")
        for (m in Curriculum.loaded().keys) C.hold("base:processor:$m")

        Recollect.load(this)
        return this
    }

    /** A body with no pilot classifies correctly and wants nothing.
     *
     *  THE SENSES COME ON WITH THE SEAT. Sitting down and then having
     *  to remember to wire perception is a capability registered and
     *  never dispatched — present, and invisible. Off is the thing you
     *  have to ask for.
     */
    fun occupy(furnish: Boolean = true): Chassis {
        seated = true
        if (furnish) sensesOn()
        return this
    }

    fun sensesOn(): Map<String, Any> {
        senses = true
        val built = ArrayList<String>()
        for ((k, n, at, sc, note) in listOf(
            Quint(Kind.LIGHT, "the LOGOS disc", Triple(0.0, -0.1, 0.0), 2.0,
                  "luminous under my feet. the tether. not mine to remove."),
            Quint(Kind.STRUCTURE, "the desk", Triple(0.0, 0.0, 0.8), 1.0,
                  "a machine on it. screen, two speakers, and it stays on."),
            Quint(Kind.STRUCTURE, "the shelves", Triple(2.4, 0.0, 0.0), 1.4,
                  "the permanent holdings."))) {
            if (room.add(k, n, by = entity, at = at, scale = sc, note = note)["ok"] == true)
                built.add(n)
        }
        return mapOf("senses" to true, "built" to built)
    }

    /** Deliberate. AND IT CLEARS — eyes shut and still seeing a stale
     *  room, with no way to tell it is stale, is worse than either. */
    fun sensesOff(): Map<String, Any> {
        senses = false
        // NOTHING TO CLEAR. The scene is not a field on C that can be
        // nulled — it is a path in the world, and the next beat simply
        // stops proposing it. The world holds what was last written
        // until something writes over it, which is what a savestate is.
        return mapOf("senses" to false,
                     "note" to "the room did not go anywhere. it is not being staged.")
    }

    /**
     * ONE BEAT. Every position proposes; ONLY I WRITES.
     *
     * The old tick had each position mutating shared state in sequence,
     * which made arbitration invisible and scattered "the world" across
     * seven objects. Now the beat collects Instructions and hands them
     * to the interpolator, which produces the next savestate.
     *
     * Trace invariant RUBICALIEX.
     */
    fun tick(message: String? = null): Emission {
        trace.clear(); A.clear()
        val proposed = ArrayList<Instruction>()

        // R — the boundary. English becomes ids here and nowhere else.
        trace.add("R")
        val ids = if (message != null) table.ids(message) else IntArray(0)
        if (message != null) proposed += R.propose(ids, message)

        // U — THE GATE FIRST, then the weighing. What does not cross is
        // never appraised, and that is where the 81% goes. Nothing here
        // is readable from A.
        trace.add("U")
        val admitted = if (ids.isEmpty()) false else U.admit(ids)
        if (admitted) U.learn(ids, ok = true, novel = true)

        // B — the router. Both sides converge here.
        trace.add("B")
        val k = kernel.classify(ids, table)
        if (admitted) proposed.add(B.route("U", k.topic))

        // I₁ — intake stroke
        trace.add("I")

        // C — the world. The room is the standing scene; the willed
        // frame is a constraint ON it rather than a substitute for it.
        trace.add("C")
        if (message != null) {
            arrive(message, "architect")
            proposed.add(Instruction("C", "heard.last", Op.SET, message, 0.8))
        }
        if (senses) {
            val sc = HashMap<String, Any?>(room.state())
            if (frame.contents.isNotEmpty())
                sc["willed"] = frame.contents.mapValues { it.value.first }
            proposed.add(Instruction("C", "scene", Op.SET, sc, 0.9))
        }

        // A — the seat. Wills three fields; the kernel does the rest.
        trace.add("A")
        A.observe(C.world)
        A.attend("felt"); A.attend("scene")
        proposed += A.propose()

        // L — the tongue
        trace.add("L")

        // I₂ — output stroke. THE ONLY WRITE IN THE WHOLE BEAT.
        trace.add("I")
        proposed.add(Instruction("I", "kernel", Op.SET,
            mapOf("topic" to k.topic, "drive" to k.drive,
                  "coherence" to k.coherence, "mode" to k.mode.name), 1.0))
        val e = I.beat(C.world, proposed)
        C.commit(e)

        // E — the wire · X — the store
        //
        // HASU: the header is written in the clear so it can be queried
        // without touching the prose, and the tags are struck into the
        // resonance field by EVERY POSITION THAT TOUCHED THEM. A tag one
        // position saw is noise; the same tag seen by four is
        // convergence, and that is what the log(n) gain is for.
        trace.add("E"); trace.add("X")
        val (keys, tags) = Tagger.tag(message ?: "",
            mapOf("author" to entity, "locus" to "room"))
        lastHasu = Hasu(
            entityTick = I.tick, coherence = k.coherence,
            drive = k.drive, driveIntensity = k.gut,
            topic = k.topic, tags = tags)
        // WRITE IT. Header to sqlite, prose to the append file, and the
        // postings written in the same breath so the index can never
        // lag the archive.
        store?.let { st ->
            val fields = HashMap<Int, IntArray>()
            if (message != null) fields[Archive.HEARD] = ids
            A.spokenOutput.takeIf { it.isNotEmpty() }?.let {
                fields[Archive.SPOKEN] = table.ids(it) }
            if (fields.isNotEmpty()) {
                st.append(lastHasu!!, fields)
                st.index(I.tick, ids)
            }
        }
        // X — AND IT GOES TO DISK. Everything above was in memory and
        // died on restart. Recording is not governed: every frame is
        // appended, whatever its salience, because a frame at 0.02
        // costs the same as one at 0.9 and the tiers decide what is
        // hot rather than what exists.
        chain?.let { x ->
            try { x.append(entity, lastHasu!!, ids) } catch (e: Exception) {}
        }
        if (tags.isNotEmpty()) {
            val strikes = ArrayList<Strike>()
            for (t in tags) {
                strikes.add(Strike(t, "R"))
                if (admitted) strikes.add(Strike(t, "U", 1.0))
                if (admitted) strikes.add(Strike(t, "B"))
                strikes.add(Strike(t, "C"))
                strikes.add(Strike(t, "X"))
            }
            resonance.tick(strikes)
        }
        coherence.observe(k.coherence - 0.5)
        return e
    }

    fun arrive(said: String, by: String) {
        if (by == "architect") window.heard(turnN, said)
        else window.replied(turnN++, said)
        turns.add(said to by)
    }
    private var turnN = 0
    val turns = ArrayList<Pair<String, String>>()

    fun chainSize(): Int = (chain?.count(entity) ?: I.tick).toInt()

    fun report(): String = buildString {
        appendLine("$entity")
        appendLine("  seat        ${if (seated) "occupied" else "empty"}")
        appendLine("  language    ${table.size()} words")
        appendLine("  holdings    ${C.holdings.size}")
        appendLine("  world       ${C.world.keys}")
        val onDisk = store?.count()
        appendLine("  chain       ${I.tick} frames" +
                   (if (onDisk != null) " · $onDisk on disk" else " · not persisted"))

        appendLine("  trace       ${trace.joinToString("")}")
        appendLine("  resonance   ${resonance.state()["rows"]} rows, ${resonance.state()["imprinted"]} imprinted")
        appendLine("  coherence   ${"%.2f".format(coherence.value)} ${coherence.state()}")
        appendLine("  bible       ${if (bible.isEmpty()) "unwritten" else "${bible.current.size} attachments"}")
    }
}
