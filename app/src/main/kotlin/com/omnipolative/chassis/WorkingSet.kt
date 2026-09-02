package com.omnipolative.chassis

/**
 * C · THE WORKING SET. Selective load, dump-flush, and fade.
 *
 * Port of core/crown_partial.py, which the Python called "NEVER BUILT,
 * twelfth instance" for a long time — apply({}) was called with empty
 * arrivals on every tick, so nothing a conversation contained ever
 * reached the working set. Capability present, dispatch absent.
 *
 * THE DIVISION OF LABOUR, and it is the whole design:
 *
 *   U POINTS    it flags what is thinning. It does not decide, and it
 *               does not know why.
 *   A ELECTS    the one part that cannot be delegated. Election is an
 *               act of the seat.
 *   B EXECUTES  it carries out what was elected. It does not choose.
 *
 * SALIENCE DECAYS, RESIDENCY DOES NOT. There is no evict on low
 * salience. A thing that stops being salient moves to FADING, where it
 * costs nothing and can be revived by one mention. Tier is WHERE a
 * thing sits, not WHETHER it exists — which is Mode(σ) ∈ {C,O,D} at the
 * scale of a conversation: accessibility, not existence.
 */

enum class Tier { ACTIVE, FADING }

/** What the working set did to a staged item. Distinct from
 *  Instruction.Op, which is what a position proposes to the world —
 *  these are cache moves, not world writes. */
enum class WsOp { FADE, FLUSH, REVIVE, LOAD, KEEP, REFRESH, EVICT, PIN }

/** U POINTS. It does not decide, and it does not know why. */
data class Flag(
    val key: String,
    val remainingWill: Double,
    val anomalous: Boolean = false,
    val cause: String? = null,
    val displacedBy: String? = null,
)

/** A'S OWN ACT. The one part that cannot be delegated. */
data class Election(val key: String, val op: WsOp, val why: String)

data class Staged(
    val key: String,
    val value: Any?,
    var will: Double = 0.0,
    var level: Int = 0,
    var loadedAt: Int = 0,
    var touched: Int = 0,
    var pinned: Boolean = false,
    val source: String = "R",
)

class WorkingSet(
    private val floor: Double = 0.05,
    private val budget: Double = 12.0,
) {
    val staged = LinkedHashMap<String, Staged>()
    val fading = LinkedHashMap<String, Staged>()
    /** Below the fading floor. Costs nothing to hold and is still
     *  searchable — quiet, not gone. */
    val cold = LinkedHashMap<String, Staged>()
    var tick = 0
    var flushes = 0
    var evictedTotal = 0
    private val decay = 0.92
    private val fadeFloor = 0.02
    val ops = ArrayList<Pair<WsOp, String>>()

    /**
     * One beat. ARRIVALS ARE NOT OPTIONAL — this was called with an
     * empty map for months and the working set stayed empty while the
     * conversation went past it.
     */
    fun apply(arrivals: Map<String, Any?>, willOf: (String) -> Double = { 0.6 }): Map<String, Any> {
        tick++
        ops.clear()

        // ── LOAD. Each incoming turn is a keyed arrival.
        for ((k, v) in arrivals) {
            // REVIVAL REACHES ALL THE WAY DOWN. Something mentioned
            // again comes back from cold at full weight, because it
            // never stopped existing.
            val prior = fading.remove(k) ?: cold.remove(k)
            if (prior != null) {
                // REVIVE. One mention brings it back at full weight,
                // because it never stopped existing.
                prior.will = maxOf(prior.will, willOf(k))
                prior.touched = tick
                staged[k] = prior
                ops.add(WsOp.REVIVE to k)
            } else if (staged.containsKey(k)) {
                staged[k]!!.will = maxOf(staged[k]!!.will, willOf(k))
                staged[k]!!.touched = tick
                ops.add(WsOp.REFRESH to k)
            } else {
                staged[k] = Staged(k, v, will = willOf(k), loadedAt = tick,
                                   touched = tick)
                ops.add(WsOp.LOAD to k)
            }
        }

        // ── U POINTS. Decay, then flag what thinned.
        val flags = ArrayList<Flag>()
        for (s in staged.values) {
            if (s.pinned) continue
            s.will *= decay
            if (s.will < floor) flags.add(Flag(s.key, s.will, cause = "thinned"))
        }

        // ── A ELECTS. Not eviction — FADE. The distinction is the point.
        val elected = flags.map { Election(it.key, WsOp.FADE, "below floor") }

        // ── B EXECUTES.
        for (e in elected) {
            val s = staged.remove(e.key) ?: continue
            fading[e.key] = s
            ops.add(WsOp.FADE to e.key)
        }

        // ── budget. Even over budget, the overflow FADES.
        while (staged.size > budget.toInt()) {
            val weakest = staged.values.filter { !it.pinned }.minByOrNull { it.will }
                ?: break
            staged.remove(weakest.key)
            fading[weakest.key] = weakest
            ops.add(WsOp.FADE to weakest.key)
        }

        // ── the fading tier decays, AND NOTHING IS DESTROYED.
        //
        // This was deleting the row outright, which is not fading, it
        // is forgetting — a fact taught forty turns ago was GONE
        // rather than quiet, and "evicted=43" was the count of things
        // this body had lost. Recording is not governed; surfacing is,
        // and I had the two the wrong way round.
        //
        // AND THERE IS NO REASON TO DESTROY ANYTHING. A postings lookup
        // is microseconds against the whole archive, so holding it all
        // as searchable costs a few megabytes and buys never losing
        // anything. The tiers are about what is HOT, not about what
        // exists.
        val quiet = ArrayList<String>()
        for (s in fading.values) {
            s.will *= decay
            if (s.will < fadeFloor) quiet.add(s.key)
        }
        for (k in quiet) {
            val s = fading.remove(k) ?: continue
            cold[k] = s          // OUT OF THE TIERS, NOT OUT OF EXISTENCE
        }

        return mapOf(
            "tick" to tick, "active" to staged.size, "fading" to fading.size,
            "quiet" to quiet, "cold" to cold.size, "evicted" to 0,
        )
    }

    /** Pin something. Permanent holdings do not fade. */
    fun pin(key: String) { staged[key]?.pinned = true; ops.add(WsOp.PIN to key) }

    /**
     * EVERYTHING, hot first.
     *
     * There is no reason to search less than all of it. Active, then
     * fading, then cold — ordered by how likely it is to be what you
     * meant, but nothing withheld.
     */
    fun reachable(): List<Staged> =
        staged.values.toList() + fading.values.toList() + cold.values.toList()

    fun state(): Map<String, Any> = mapOf(
        "resident" to staged.size, "fading" to fading.size,
        "cold" to cold.size, "held" to (staged.size + fading.size + cold.size),
        "lost" to 0, "tick" to tick,
    )
}

/**
 * THE TURN WINDOW. An interaction is a turn — what was said TO it and
 * what it said BACK. Storing only one half made the conversation branch
 * search a record with the answers missing from it.
 */
class Turns(private val ws: WorkingSet) {

    fun heard(n: Int, said: String) =
        ws.apply(mapOf("turn:$n" to mapOf("said" to said, "by" to "architect")))

    fun replied(n: Int, said: String) =
        ws.apply(mapOf("turn:$n:reply" to mapOf("said" to said, "by" to "self")))

    /**
     * Find what was actually said. A QUESTION TURN IS A REAL EVENT and
     * stays in the window, but it is not an answer to itself — asking
     * "which voussoir is placed last" twice should not return the
     * question.
     */
    fun search(want: List<String>): String? {
        // WHAT THE ARCHITECT SAID OUTRANKS WHAT I SAID. Both halves are
        // in the window and both are real events, but my own reply is
        // not a source for a fact I was taught — returning it is
        // quoting myself back and calling it recall.
        return find(want, "architect") ?: find(want, null)
    }

    private fun find(want: List<String>, by: String?): String? {
        for (s in ws.reachable().asReversed()) {
            @Suppress("UNCHECKED_CAST")
            val m = s.value as? Map<String, Any?> ?: continue
            val said = (m["said"] as? String) ?: continue
            if (by != null && m["by"] != by) continue
            val low = said.lowercase()
            if (low.startsWith("what") || low.startsWith("which") ||
                low.startsWith("who") || low.startsWith("how")) continue
            // soft plural: keystones matches keystone
            if (want.any { low.contains(it) || low.contains(it.trimEnd('s')) })
                return said
        }
        return null
    }
}
