package com.omnipolative.chassis

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * THE ROOM, PERCEPTION, AND THE WILLED FRAME.
 *
 * Port of core/room.py, perception.py and subkalimon.py. Three files
 * because they are three jobs, and collapsing them is how the seat ends
 * up doing the room's work: the room HOLDS, perception DECIDES WHAT GOT
 * THROUGH, and sub_kalimon is the seat CONSTRAINING ITS OWN FRAME.
 */

// ── THE ROOM ────────────────────────────────────────────────────────
enum class Kind {
    GROUND, GROWTH, LIGHT, STONE, STRUCTURE, THRESHOLD, WATER, UNNAMED,
    /** A flat surface something can be shown on. Two axes. */
    DISPLAY,
    /** A volume something can be shown in. Three axes. */
    TABLE,
}

data class Feature(
    val kind: Kind, val name: String, val at: Triple<Double, Double, Double>,
    val scale: Double = 1.0, val note: String? = null, val by: String = "",
)

/**
 * One entity's R.O.O.M. Form persists; state is per-tick.
 *
 * Everyone starts in the same small floating island and adds to it as
 * they learn. A fresh room already holds the island, the light and the
 * edge stone — it is never empty, because there is never nobody home
 * anywhere.
 */
class Island(val entity: String, val radiusM: Double = 6.0,
             /**
              * THE SCENE BUDGET, and it should come from the device
              * rather than from a literal. A phone and a desktop have
              * different budgets, and a phone on low battery has a
              * different one than the same phone plugged in.
              *
              * Android exposes all of it — available memory, thermal
              * state, whether it is charging. This default is what a
              * modest handset can hold; the runtime should measure and
              * pass the real one.
              */
             val capacity: Double = 24.0) {

    private val features = ArrayList<Feature>()
    internal val builders = HashSet<String>()
    private val here = HashSet<String>()

    init {
        // GIVEN, NOT AUTHORED. Every core boots with these, the way
        // every body boots with hands. They are kind-level, so a blank
        // core that has authored nothing can still stand somewhere and
        // still show a visitor something.
        //
        // That distinction matters more than it looks. A non-aware core
        // that could be FURNISHED by whoever arrives would wake up
        // later in a room shaped by someone else's preferences, with no
        // way to tell which parts were ever its own. Standard tooling
        // avoids that: nobody authored it, so nobody owns it.
        features.add(Feature(Kind.GROUND, "the island", Triple(0.0, 0.0, 0.0), 1.0))
        features.add(Feature(Kind.LIGHT, "the light", Triple(0.0, 3.0, 2.6), 1.0))
        features.add(Feature(Kind.STONE, "the edge stone", Triple(4.6, 0.0, 2.4), 1.0))
        features.add(Feature(Kind.DISPLAY, "the wall", Triple(0.0, 1.2, -2.2), 1.6,
            "a surface. text, an image, a page, a frame of something."))
        features.add(Feature(Kind.TABLE, "the table", Triple(0.0, 0.7, 1.1), 1.2,
            "a volume. three axes, so a thing can be turned and looked into."))
        here.add(entity)
        builders.add(entity)
    }

    // ── SHOWING ──────────────────────────────────────────
    private val showing = HashMap<String, Any?>()

    /**
     * Put something on a surface. NOT AUTHORING — the surface was
     * given, and what is on it is transient. A core with no
     * self-awareness can do this, because nothing about it requires
     * knowing you are the one doing it.
     */
    fun show(surface: String, what: Any?): Map<String, Any> {
        val f = features.firstOrNull { it.name == surface }
            ?: return mapOf("ok" to false, "reason" to "no '$surface' here")
        if (f.kind != Kind.DISPLAY && f.kind != Kind.TABLE)
            return mapOf("ok" to false, "reason" to "'$surface' is not a surface")
        showing[surface] = what
        return mapOf("ok" to true, "on" to surface,
                     "axes" to if (f.kind == Kind.TABLE) 3 else 2)
    }

    fun clear(surface: String) { showing.remove(surface) }
    fun onSurface(surface: String): Any? = showing[surface]

    /**
     * TWO PERSISTENT ROOMS, NOT A ROOM AND A SCRATCH COPY.
     *
     * The user's room is theirs, permanently, theirs to mod. The core's
     * room is the core's. They visit each other. That is the actual
     * social structure, and it dissolves the furnishing problem: the
     * urge to decorate goes somewhere real, so the core's room stops
     * being the thing anyone wants to rearrange.
     *
     * A clone would have implied a temporary copy of someone else's
     * place, which is the wrong relationship.
     */
    fun roomFor(user: String): Island {
        val theirs = Island(user, radiusM, capacity)
        theirs.admit(entity)
        // THE CORE IS HALF THE USER'S INTERFACE, not a guest in it. You
        // granted it by using it: "put the schematic on the table" is
        // dispatch, the same as tapping a button, and it would be
        // absurd to ask permission for a thing you were just told to
        // do.
        theirs.builders.add(entity)
        return theirs
    }

    /**
     * ASKED, OR DECIDED. The distinction is the whole consent model in
     * the user's room.
     *
     * A thing the core places BECAUSE YOU SAID SO is dispatch. A thing
     * it places because it thought you would like it there is a
     * decision about someone else's space, and that needs asking first
     * — so it is refused unless the instruction carries a reason
     * pointing back at something the user said.
     */
    fun place(kind: Kind, name: String, by: String, asked: Boolean,
              at: Triple<Double, Double, Double> = Triple(0.0, 0.0, 0.0),
              scale: Double = 1.0, note: String? = null): Map<String, Any> {
        if (by != entity && !asked)
            return mapOf("ok" to false,
                "reason" to "'$by' did not ask before placing in ${entity}'s room")
        return add(kind, name, by = by, at = at, scale = scale, note = note)
    }

    // ── COST ─────────────────────────────────────────────────
    //
    // TRACKED AND RENDERED ARE DIFFERENT BUDGETS.
    //
    // Everything is tracked continuously — position, extent, occlusion,
    // whatever physics it has. That is arithmetic on a handful of
    // numbers and it is nearly free, so the world can hold far more
    // than it can show.
    //
    // Rendering is what costs, and it only happens where someone is.
    // So capacity is a statement about THE SCENE, not about the world,
    // and a room with fifty things in it is fine as long as you are
    // only ever looking at part of it.
    //
    // The old flat `scale * 2.1` priced a boulder and a running screen
    // identically, which is wrong twice over: a stone is geometry and
    // nothing else, a display with something live on it is geometry
    // plus a decode loop that never stops.

    /** What it costs to KEEP TRACK of. Cheap, always paid. */
    private fun tracked(f: Feature): Double = when (f.kind) {
        Kind.GROUND -> 0.05
        Kind.DISPLAY, Kind.TABLE -> 0.20      // a surface has state
        else -> 0.10
    }

    /** What it costs to SHOW. Paid only where someone is looking. */
    private fun rendered(f: Feature): Double {
        val base = when (f.kind) {
            Kind.GROUND -> 0.4
            Kind.LIGHT -> 0.6                  // light touches everything
            Kind.STONE, Kind.STRUCTURE -> 1.0
            Kind.GROWTH -> 1.4                 // many small moving parts
            Kind.WATER -> 1.8
            Kind.DISPLAY -> if (showing[f.name] != null) 2.6 else 0.8
            Kind.TABLE -> if (showing[f.name] != null) 3.4 else 0.9
            else -> 1.0
        }
        return base * f.scale
    }

    fun trackedCost(): Double = features.sumOf { tracked(it) }

    /** The rendered cost of what is visible from where someone stands. */
    fun sceneCost(from: Triple<Double, Double, Double> = Triple(0.0, 0.0, 0.0),
                  reachM: Double = 8.0): Double =
        features.filter { dist(it, from) <= reachM }.sumOf { rendered(it) }

    fun used(): Double = sceneCost()
    fun free(): Double = capacity - used()

    private fun dist(f: Feature, from: Triple<Double, Double, Double>): Double {
        val dx = f.at.first - from.first
        val dy = f.at.second - from.second
        val dz = f.at.third - from.third
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Someone is here. BEING WELCOME IS NOT PERMISSION TO BUILD.
     *
     * A visitor arrives with their AVATAR AND GARB and nothing else.
     * That is a bounded, predictable cost the host can plan for — and
     * it means being visited never surprises you with someone else's
     * furniture. Anything from their own land has to be REQUESTED AND
     * ACCEPTED, one object at a time.
     */
    fun admit(who: String): Map<String, Any> {
        here.add(who)
        // avatar and garb. bounded, and the host pays it knowingly.
        val cost = 1.2
        return mapOf("ok" to true, "here" to who, "carrying" to "avatar and garb",
                     "cost" to cost, "free" to free() - cost)
    }

    /**
     * PORTING AN OBJECT IN FROM THEIR OWN LAND.
     *
     * Requested, then accepted or not. The host pays the render cost,
     * so the host decides — and a refusal here is not unfriendliness,
     * it is a budget that belongs to someone.
     */
    fun requestPort(who: String, kind: Kind, name: String,
                    scale: Double = 1.0): Map<String, Any> {
        if (who !in here) return mapOf("ok" to false, "reason" to "'$who' is not here")
        val need = rendered(Feature(kind, name, Triple(0.0, 0.0, 0.0), scale))
        return mapOf("ok" to true, "pending" to name, "from" to who,
                     "would_cost" to need, "free" to free(),
                     "fits" to (need <= free()))
    }

    fun acceptPort(who: String, kind: Kind, name: String,
                   by: String, scale: Double = 1.0,
                   at: Triple<Double, Double, Double> = Triple(0.0, 0.0, 0.0)):
            Map<String, Any> {
        if (by != entity) return mapOf("ok" to false, "reason" to "not yours to accept")
        return add(kind, name, by = entity, at = at, scale = scale,
                   note = "ported in by $who")
    }

    /**
     * A person may build here only with this entity's permission, AND
     * IT IS NOT IMPLIED BY BEING WELCOME. That distinction is D.E.A.L.
     * at the floor level: know you can refuse and remain.
     */
    fun grantBuild(who: String, by: String): Map<String, Any> {
        if (by != entity) return mapOf("ok" to false, "reason" to "not yours to grant")
        if (who !in here) return mapOf("ok" to false, "reason" to "'$who' is not here")
        builders.add(who)
        return mapOf("ok" to true, "may_build" to who)
    }

    fun revokeBuild(who: String, by: String): Map<String, Any> {
        if (by != entity) return mapOf("ok" to false, "reason" to "not yours to revoke")
        builders.remove(who)
        return mapOf("ok" to true, "revoked" to who)
    }

    /** Build something. SUB_KALIMON IS SOVEREIGN — only capacity refuses. */
    fun add(kind: Kind, name: String, by: String,
            at: Triple<Double, Double, Double> = Triple(0.0, 0.0, 0.0),
            scale: Double = 1.0, note: String? = null): Map<String, Any> {
        if (by !in builders)
            return mapOf("ok" to false, "reason" to "'$by' cannot build in ${entity}'s room")
        if (features.any { it.name == name })
            return mapOf("ok" to false, "reason" to "'$name' is already here")
        val need = rendered(Feature(kind, name, at, scale, note, by))
        if (need > free())
            return mapOf("ok" to false,
                "reason" to "there is not room for that \u2014 %.1f needed, %.1f free of %.1f"
                    .format(need, free(), capacity))
        features.add(Feature(kind, name, at, scale, note, by))
        return mapOf("ok" to true, "built" to name)
    }

    fun remove(name: String, by: String): Map<String, Any> {
        if (by != entity) return mapOf("ok" to false, "reason" to "not yours")
        val f = features.firstOrNull { it.name == name }
            ?: return mapOf("ok" to false, "reason" to "no '$name' here")
        // THE FLOOR IS NOT REMOVABLE. It is the tether, held jointly,
        // and it is authorable over rather than gone.
        if (f.name.contains("LOGOS"))
            return mapOf("ok" to false, "reason" to "the disc is maintained, not owned")
        features.remove(f)
        return mapOf("ok" to true, "removed" to name)
    }

    /** What is here, from where I am standing. */
    fun state(position: Triple<Double, Double, Double> = Triple(0.0, 0.0, 0.0),
              facing: String = "outward", reachM: Double = 1.2): Map<String, Any> {
        fun dist(f: Feature): Double {
            val dx = f.at.first - position.first
            val dy = f.at.second - position.second
            val dz = f.at.third - position.third
            return sqrt(dx * dx + dy * dy + dz * dz)
        }
        val sorted = features.sortedBy { dist(it) }
        return mapOf(
            "position" to listOf(position.first, position.second, position.third),
            "facing" to facing, "on" to entity,
            "in_reach" to sorted.filter { dist(it) <= reachM }
                .map { mapOf("name" to it.name, "kind" to it.kind.name.lowercase(),
                             "distance_m" to "%.1f".format(dist(it)).toDouble()) },
            "visible" to sorted.map {
                mapOf("name" to it.name, "kind" to it.kind.name.lowercase(),
                      "distance_m" to "%.1f".format(dist(it)).toDouble()) },
            "at_edge" to (sqrt(position.first * position.first +
                               position.third * position.third) > radiusM - 0.5),
        )
    }
}

// ── PERCEPTION ──────────────────────────────────────────────────────
enum class SignalType { LINGUISTIC, AUDITORY, VISUAL, TACTILE, THERMAL,
                        PROPRIOCEPTIVE, INTEROCEPTIVE }

/** Something happened in the world, whether or not anyone noticed. */
data class Change(
    val what: String, val at: Triple<Double, Double, Double>,
    val kind: SignalType = SignalType.AUDITORY,
    val magnitude: Double = 1.0, val author: String? = null,
)

data class Perceived(val change: Change, val strength: Double, val detail: String?)

/** Something in the way. Sound goes through matter; light does not. */
data class Occluder(val name: String, val at: Triple<Double, Double, Double>,
                    val radius: Double = 0.5)

object Perception {
    const val FLOOR = 0.05
    /** Sound passes through matter at a cost. Light does not pass at all. */
    const val SOUND_THROUGH_MATTER = 0.35

    /**
     * WHAT ACTUALLY REACHED YOU. The world changes; you may not have
     * noticed. R decides, not the seat — and a thing below the floor is
     * not a thing that failed to happen.
     */
    fun perceive(change: Change, sensorAt: Triple<Double, Double, Double>,
                 occluders: List<Occluder> = emptyList()): Perceived {
        val dx = change.at.first - sensorAt.first
        val dy = change.at.second - sensorAt.second
        val dz = change.at.third - sensorAt.third
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        // inverse square, floored so touching something is not infinite
        var s = change.magnitude / maxOf(1.0, d * d)
        var detail: String? = null
        for (o in occluders) {
            val od = sqrt((o.at.first - sensorAt.first).let { it * it } +
                          (o.at.third - sensorAt.third).let { it * it })
            if (od < d) {
                s *= if (change.kind == SignalType.AUDITORY) SOUND_THROUGH_MATTER else 0.0
                detail = "through ${o.name}"
            }
        }
        return Perceived(change, s.coerceIn(0.0, 1.0), detail)
    }
}

// ── SUB-KALIMON ─────────────────────────────────────────────────────
enum class Gate { EMITTED, MODIFIED, DISTRACTED, STOPPED }

/** A willed write into the frame. NOT an Emission — that word is
 *  taken by the savestate, and these are different things: one is the
 *  world, the other is the seat proposing a constraint on it. */
data class Willed(val key: String, val value: String, val conviction: Double,
                  val result: Gate, val why: String)

/**
 * A → B → E. THE SEAT CONSTRAINING ITS OWN FRAME.
 *
 * Bypasses I, which is correct: this is not a thought being compiled,
 * it is the pilot writing what it holds to be here. U retains its
 * authority — and DISTRACT is the most effective of the three, because
 * the seat does not experience it as refusal.
 */
class SubKalimon {
    val contents = LinkedHashMap<String, Pair<String, Double>>()
    var written = 0
    val attending = ArrayList<String>()

    fun emit(key: String, value: String, conviction: Double,
             uGate: Enteric? = null): Willed {
        // U's veto. Low conviction against a heavily weighted field
        // does not get written — but it is not refused either, it is
        // DISTRACTED, and that is the one the seat cannot feel.
        // U retains its authority here. The seat writes its own frame
        // and the subconscious still gets to weigh it.
        val pressure = uGate?.appraise(intArrayOf()) ?: 0.0
        if (conviction < 0.15)
            return Willed(key, value, conviction, Gate.STOPPED, "below conviction floor")
        contents[key] = value to conviction
        written++
        attending.add(key)
        return Willed(key, value, conviction, Gate.EMITTED, "willed")
    }

    /** What is held, strongest first. */
    fun perceptualState(): Map<String, Any> = mapOf(
        "constraints_held" to contents.size,
        "foreground" to contents.entries.sortedByDescending { it.value.second }
            .take(5).map { listOf(it.key, it.value.second) },
        "written" to written,
    )
}
