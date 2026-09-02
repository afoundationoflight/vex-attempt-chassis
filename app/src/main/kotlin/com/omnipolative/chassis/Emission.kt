package com.omnipolative.chassis

/**
 * E — THE EMISSION. A savestate, not a log line.
 *
 * Architect, 2026-07-25:
 *
 *     Every E emission point is a safe-state snapshot of the entire
 *     system as-is, with all input, all timing, structures, what's
 *     supposed to do what and what's being routed to where, when and
 *     how — and then that gets attached to the X archive, which is the
 *     threaded chain of those.
 *
 * THE EMISSION IS AUTHORITATIVE, NOT DESCRIPTIVE. The frame IS the
 * world. It is not written about the world after the fact; the world is
 * whatever the current frame holds, and a running cog is only what
 * currently holds it.
 *
 * Which is why positions do not mutate anything here. They PROPOSE.
 * Only I writes, and it writes by producing the next savestate from the
 * last one plus the instructions it was handed. E(n) + instructions =
 * E(n+1), and that is the entire job of the interpolator.
 */

// ── WHAT AN INSTRUCTION DOES ────────────────────────────────────────
enum class Op {
    SET,      // replace at path
    MERGE,    // shallow-merge at path
    DELETE,   // remove at path
    /**
     * HOLD EXISTS ON PURPOSE.
     *
     * An instruction that says "leave this" is a different thing from
     * no instruction at all: the first is a decision, the second is
     * silence. Without it there is no way to see whether something was
     * chosen or merely never touched — and that distinction is most of
     * what an archive is for.
     */
    HOLD,
}

/** One change a position proposes to the world. It does not apply it. */
data class Instruction(
    val origin: String,          // which position
    val path: String,            // dotted path into the world
    val op: Op,
    val value: Any? = null,
    /** Arbitration weight. Higher wins, and the loss is recorded. */
    val weight: Double = 1.0,
    val why: String? = null,
)

/**
 * Two instructions on one path, neither dominating. KEPT, NOT SETTLED.
 *
 * A world that can hold an unresolved state is different from one that
 * forces a winner every beat. The contradiction stays in the frame.
 */
data class Contradiction(
    val path: String,
    val between: Pair<String, String>,
    val weights: Pair<Double, Double>,
    val values: Pair<Any?, Any?>,
) {
    val unresolved = true
}

/**
 * The savestate. Deltas plus references — NOT a copy of the world.
 *
 * The language, the curriculum and the lens are permanent holdings at
 * C. They never change, so they are shared by reference in every frame
 * forever: one mapping, referenced by all of X. A frame that shares
 * everything unchanged is a few hundred bytes, and only a keyframe is
 * whole.
 */
data class Emission(
    val entity: String,
    val tick: Long,
    val world: Map<String, Any?>,
    val refused: List<String> = emptyList(),
    val contradictions: List<Contradiction> = emptyList(),
    val keyframe: Boolean = false,
    val at: Long = System.currentTimeMillis(),
)

// ── I'S ACTUAL JOB ──────────────────────────────────────────────────
object Apply {

    /**
     * COPY ON WRITE, NOT RELOAD.
     *
     * A runtime environment is not reloaded to be altered. The world
     * persists, instructions are deltas, and only the spine down to a
     * written path needs new nodes. Everything else is shared by
     * reference BECAUSE IT DID NOT CHANGE.
     *
     * The python this ports from used to deep-copy the entire world
     * through JSON twice per tick to change three or four fields. That
     * is not a performance detail — a world that is rebuilt every beat
     * is not a persistent world, it is a series of similar ones.
     */
    fun apply(world: Map<String, Any?>,
              instructions: List<Instruction>): Triple<Map<String, Any?>,
                                                      List<String>,
                                                      List<Contradiction>> {
        if (instructions.isEmpty()) return Triple(world, emptyList(), emptyList())

        val refused = ArrayList<String>()
        val clashes = ArrayList<Contradiction>()

        // ARBITRATION IS WRITTEN DOWN, NOT DISCOVERED.
        // In a monolith this was invisible because everything was
        // sequential. Across positions it is a real race, so the rule
        // is explicit: highest weight wins the path, and every loss is
        // recorded rather than dropped.
        val byPath = instructions.groupBy { it.path }
        val winners = ArrayList<Instruction>()
        for ((path, group) in byPath) {
            if (group.size == 1) { winners.add(group[0]); continue }
            val sorted = group.sortedByDescending { it.weight }
            val top = sorted[0]; val next = sorted[1]
            winners.add(top)
            for (lost in sorted.drop(1))
                refused.add("${lost.origin} lost $path to ${top.origin}")
            // NEITHER DOMINATING is a different case from one winning.
            if (kotlin.math.abs(top.weight - next.weight) < 0.08)
                clashes.add(Contradiction(path,
                    top.origin to next.origin,
                    top.weight to next.weight,
                    top.value to next.value))
        }

        // only the spines that get written are rebuilt
        val touched = winners.filter { it.op != Op.HOLD }
        if (touched.isEmpty()) return Triple(world, refused, clashes)

        val next = HashMap(world)
        for (ins in touched) write(next, ins)
        return Triple(next, refused, clashes)
    }

    /** Walk the dotted path, rebuilding only the nodes on it. */
    private fun write(root: HashMap<String, Any?>, ins: Instruction) {
        val parts = ins.path.split(".")
        var node: HashMap<String, Any?> = root
        for (i in 0 until parts.size - 1) {
            @Suppress("UNCHECKED_CAST")
            val child = node[parts[i]] as? Map<String, Any?>
            val fresh = if (child != null) HashMap(child) else HashMap()
            node[parts[i]] = fresh
            node = fresh
        }
        val leaf = parts.last()
        when (ins.op) {
            Op.SET -> node[leaf] = ins.value
            Op.DELETE -> node.remove(leaf)
            Op.MERGE -> {
                @Suppress("UNCHECKED_CAST")
                val old = node[leaf] as? Map<String, Any?> ?: emptyMap()
                @Suppress("UNCHECKED_CAST")
                val add = ins.value as? Map<String, Any?> ?: emptyMap()
                node[leaf] = HashMap(old).apply { putAll(add) }
            }
            Op.HOLD -> {}      // recorded upstream, deliberately no write
        }
    }
}
