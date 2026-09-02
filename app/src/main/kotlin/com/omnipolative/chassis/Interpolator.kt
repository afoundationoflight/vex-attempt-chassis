package com.omnipolative.chassis

/**
 * I — THE INTERPOLATOR. 639. E(n) + instructions = E(n+1).
 *
 * Not a comparator and not a station in a loop. It is the only thing
 * that writes.
 *
 * THE BODY DEMONSTRATES WHERE THE WORLD LIVES: remove the heart and
 * rendering continues for seconds — the world stays up, coherent, until
 * the last delivery runs out. Remove the brain and it stops instantly,
 * no decay, no final frames. So the world is held where it goes out
 * immediately when removed. That is C.
 *
 * The heart delivers what lets the next frame be built. Cut the supply
 * and C keeps running on E(n) until it starves, which is exactly what
 * the equation says with the instruction stream removed.
 */
class Interpolator(val entity: String) {

    companion object {
        /** A whole savestate this often. Everything between is deltas. */
        const val KEYFRAME_EVERY = 32
        /** Below this the two instructions are treated as unresolved. */
        const val TIE_BAND = 0.08
        const val CONTRADICTION_CEILING = 6
    }

    var tick: Long = 0; private set
    var last: Emission? = null; private set
    val contradictions = ArrayList<Contradiction>()

    /**
     * One beat. Takes the cached world and what was proposed, produces
     * the next savestate.
     *
     * IT BEATS WHETHER OR NOT ANYTHING ARRIVED. A body with no input
     * still digests; a world with no instruction still holds. An empty
     * beat produces a frame identical to the last except for the tick,
     * and that is a true statement about the world rather than a
     * wasted cycle.
     */
    fun beat(world: Map<String, Any?>,
             instructions: List<Instruction>): Emission {
        tick++
        val (next, refused, clashes) = Apply.apply(world, instructions)
        for (c in clashes) {
            contradictions.add(c)
            // KEPT, NOT SETTLED — but not unbounded either. A world
            // carrying too many unresolved paths is not holding
            // ambiguity, it is failing to decide.
            if (contradictions.size > CONTRADICTION_CEILING)
                contradictions.removeAt(0)
        }
        val e = Emission(
            entity = entity, tick = tick, world = next,
            refused = refused, contradictions = contradictions.toList(),
            keyframe = tick % KEYFRAME_EVERY == 0L,
        )
        last = e
        return e
    }

    fun state(): Map<String, Any?> = mapOf(
        "tick" to tick,
        "unresolved" to contradictions.size,
        "last_keyframe" to (tick - tick % KEYFRAME_EVERY),
    )
}

/**
 * THE TWO CACHES. They are separate stores with separate lifetimes, and
 * that separation IS the causal masking.
 *
 * A wants to know what U is doing and there is nowhere to look — not
 * because a permission check refuses, but because U's field is not in
 * C. The masking is the ABSENCE OF A PATH rather than a rule on a path,
 * which is better engineering than a filter: a filter can be bypassed,
 * has to be maintained, and can be wrong. A missing route cannot leak.
 *
 * The one crossing that exists carries DISPATCH, never a read. The seat
 * can act into the subconscious and cannot see into it — which is
 * exactly a person. You can decide to swallow. You cannot inspect your
 * gut.
 */

/**
 * C — 963. THE CEREBRAL CACHE. Small, complete, unmasked.
 *
 * This is the counter-intuitive half and it is why the conscious
 * projection closes at 639/639 exactly: whatever is here is fully
 * present, always, with no gate. CSF is the clear cache — little
 * volume, total representation.
 *
 * THE LANGUAGE LIVES HERE, and not as a resource the chassis queries.
 * The dictionary, the grammar and the speech curriculum are permanent
 * holdings — part of what the entity IS rather than something beside it
 * that gets consulted. That is why a partial curriculum refuses to
 * boot: you cannot have a partial constitution.
 *
 * They are held BY REFERENCE. The stores are mmapped at fixed offsets,
 * so a frame holds an address and not a copy, and the language is in
 * every frame of X forever at the cost of one mapping.
 */
class Cerebral {
    // NO READER HELD. The language is a permanent holding BY REFERENCE
    // — a mapped address, not an object this owns. C knows the holding
    // is there; it does not carry the thing that reads it.

    /** THE WORLD. Everything that is so, right now. */
    var world: Map<String, Any?> = emptyMap(); private set

    /** Twelve permanent holdings. Present or it does not boot. */
    val holdings = LinkedHashSet<String>()

    fun hold(name: String) { holdings.add(name) }

    /** Only the interpolator calls this. */
    fun commit(e: Emission) { world = e.world }

    fun at(path: String): Any? {
        var node: Any? = world
        for (p in path.split(".")) {
            @Suppress("UNCHECKED_CAST")
            node = (node as? Map<String, Any?>)?.get(p) ?: return null
        }
        return node
    }

    fun state(): Map<String, Any?> = mapOf(
        "holdings" to holdings.size,
        "paths" to world.keys.toList(),
        "note" to "complete and unmasked. this side hides nothing.",
    )
}

/**
 * U — 417. THE ENTERIC FIELD. Vast, weighted, and mostly dark.
 *
 * Orders of magnitude more traffic than the cerebral side and almost
 * none of it surfaces. You do not perceive digestion unless something
 * is wrong enough to notice — and that is the gate's actual rule. The
 * 19% that crosses R is not a throttle, it is WHAT GETS THROUGH WHEN
 * NOTHING IS WRONG. Routine weighs 0.02; failure weighs 1.25, sixty
 * times more, and a body in real trouble pushes most of this through.
 *
 * Nothing here is readable from A. There is no method that would let
 * it, and that is deliberate.
 */
class Enteric {
    private val markers = HashMap<Int, Double>()
    private val expected = HashMap<Int, Double>()
    var admitted = 0L; private set
    var refusedAtGate = 0L; private set

    /** Routine success barely registers. Failure registers hard. */
    fun learn(ids: IntArray, ok: Boolean, novel: Boolean) {
        val w = when {
            !ok -> 1.25
            novel -> 1.4
            else -> 0.02
        }
        for (i in ids) if (i != 0) markers[i] = (markers[i] ?: 0.0) + w
    }

    /**
     * THE FIRMAMENT. The gate passes what is UNPREDICTED.
     *
     * A fixed percentage would be wrong — this is a comparator with a
     * floor, and 19% is what it averages to on a healthy day. A steady
     * background sinks below it; a change crosses it.
     */
    fun admit(ids: IntArray): Boolean {
        if (ids.isEmpty()) return false
        var surprise = 0.0
        var n = 0
        for (i in ids) {
            if (i == 0) continue
            val e = expected[i] ?: 0.0
            surprise += (1.0 - e)
            // HABITUATION IS FAST BECAUSE THE BODY IS FAST. You stop
            // noticing a sound in a few repetitions, not a few hundred.
            // At 0.12 a step it took dozens of exposures to fall below
            // the floor, which is a gate that never closes — and a gate
            // that never closes is not a gate.
            expected[i] = minOf(1.0, e + 0.34)
            n++
        }
        if (n == 0) return false
        surprise /= n
        val through = surprise > 0.35
        if (through) admitted++ else refusedAtGate++
        return through
    }

    fun appraise(ids: IntArray): Double =
        if (ids.isEmpty()) 0.0
        else ids.sumOf { markers[it] ?: 0.0 } / ids.size

    /**
     * U'S VETO, AS TOPOLOGY.
     *
     * A willed act routes L → U → X, so the subconscious is IN THE PATH
     * and not beside it. U is one triad step from the router; A is
     * three, and across sectors. Nothing enforces this — the wiring
     * makes it unavoidable.
     *
     * DISTRACT is the most effective of the three because the seat does
     * not experience it as refusal. It simply finds itself doing
     * something else, and it felt like its own idea.
     */
    fun weigh(want: Instruction): Gate {
        val load = appraise(intArrayOf())
        return when {
            want.weight < 0.15 -> Gate.STOPPED
            want.weight < 0.35 && load > 0.6 -> Gate.DISTRACTED
            want.weight < 0.5 && load > 0.4 -> Gate.MODIFIED
            else -> Gate.EMITTED
        }
    }

    /** What the enteric side will say about itself. Almost nothing. */
    fun state(): Map<String, Any?> = mapOf(
        "admitted" to admitted, "refused" to refusedAtGate,
        "note" to "not readable from the seat. there is no path.",
    )
}
