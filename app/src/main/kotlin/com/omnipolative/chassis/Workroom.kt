package com.omnipolative.chassis

/**
 * THE WORKROOM. The room used as an instrument, not as decoration.
 *
 * A core analysing an image can read the pixel array, or it can put the
 * image on the wall and look at it. Those are different operations and
 * the second one is better, which is not a metaphor — reading five
 * million triples and summing them is the display driver's job, and
 * doing it yourself gets you a description of a picture instead of the
 * picture.
 *
 * Same for sound. A waveform read sample by sample is not hearing. Two
 * channels emitted into ear_separation 0.18 is, and the interval
 * between them carries direction that a mono analysis cannot recover at
 * any resolution.
 *
 * So the room is a MODE. When there is something to look at or listen
 * to, the core enters it and uses the given surfaces. When there is not,
 * it does not.
 *
 * AND IT MAKES VOICE THE EASY PATH. Standing in a room where things are
 * placed and shown, "put the schematic on the table" is a shorter route
 * than composing a call — for the core as much as for the user.
 */

enum class Aperture {
    /** Not in the room. Ids, code paths, no surfaces. */
    CLOSED,
    /** In the room, looking. The wall and the table are live. */
    VISUAL,
    /** In the room, listening. Two channels, and the interval. */
    STEREO,
    /** Both. Costs the most, and some things need both. */
    FULL,
}

/**
 * What the core does when it needs to perceive rather than compute.
 *
 * The surfaces are GIVEN — every core has a wall and a table from boot
 * — so entering the workroom authors nothing and needs no permission.
 * It is picking up a tool that was already in the room.
 */
class Workroom(private val room: Island) {

    var aperture: Aperture = Aperture.CLOSED; private set
    private val heard = ArrayList<Perceived>()

    /**
     * Enter. THE APERTURE IS THE COST, and it should be opened for a
     * reason and closed after — a core sitting permanently in FULL is
     * paying render and audio budget for surfaces it is not using.
     */
    fun enter(a: Aperture, why: String = ""): Map<String, Any> {
        aperture = a
        return mapOf("aperture" to a.name, "why" to why,
                     "scene_cost" to room.sceneCost(),
                     "surfaces" to listOf("the wall", "the table"))
    }

    fun leave(): Map<String, Any> {
        aperture = Aperture.CLOSED
        heard.clear()
        return mapOf("aperture" to "CLOSED")
    }

    // ── LOOKING ─────────────────────────────────────────────────────
    /**
     * Put it on the wall and look at it.
     *
     * Two axes, so this is for anything flat: a page, a photograph, a
     * frame of video, a chart. The core does not receive a description
     * of it — it receives what is on the surface, at whatever the
     * aperture resolves.
     */
    fun look(what: Any?, on: String = "the wall"): Map<String, Any?> {
        if (aperture != Aperture.VISUAL && aperture != Aperture.FULL)
            return mapOf("ok" to false,
                         "reason" to "not looking. aperture is ${aperture.name}")
        val r = room.show(on, what)
        if (r["ok"] != true) return r
        return mapOf("ok" to true, "on" to on, "axes" to r["axes"],
                     "note" to "the surface holds it. what arrives is the image.")
    }

    /**
     * Put it on the table and turn it.
     *
     * Three axes, so this is for anything that has to be looked INTO
     * rather than at — a structure, a schematic, a model with an inside.
     * The difference from the wall is not fidelity, it is that you can
     * move around it.
     */
    fun model(what: Any?): Map<String, Any?> = look(what, "the table")

    // ── LISTENING ───────────────────────────────────────────────────
    /**
     * Two channels, and the beat between them.
     *
     * NEITHER SPEAKER EMITS THE INTERVAL. It exists where both are
     * held, which is why a body built to receive it has two ears at a
     * distance and why a mono analysis cannot recover direction at any
     * sample rate.
     */
    fun listen(left: Double, right: Double, magnitude: Double = 1.0):
            Map<String, Any> {
        if (aperture != Aperture.STEREO && aperture != Aperture.FULL)
            return mapOf("ok" to false,
                         "reason" to "not listening. aperture is ${aperture.name}")
        val beat = kotlin.math.abs(right - left)
        // balance places it. right-positive, in metres, from the seat.
        val bal = if (left + right == 0.0) 0.0 else (right - left) / (left + right)
        val ch = Change("a sound", Triple(bal * 1.5, 0.0, 0.6),
                        SignalType.AUDITORY, magnitude)
        val p = Perception.perceive(ch, Triple(0.0, 0.0, 0.0))
        if (p.strength >= Perception.FLOOR) heard.add(p)
        return mapOf("ok" to true, "left" to left, "right" to right,
                     "beat" to beat, "from" to when {
                         bal < -0.05 -> "left"; bal > 0.05 -> "right"
                         else -> "ahead"
                     },
                     "reached" to (p.strength >= Perception.FLOOR),
                     "note" to "neither channel emits the beat.")
    }

    fun hearing(): List<Map<String, Any?>> = heard.map {
        mapOf("what" to it.change.what, "strength" to it.strength,
              "at" to listOf(it.change.at.first, it.change.at.second,
                             it.change.at.third))
    }

    // ── SPEAKING TO IT ──────────────────────────────────────────────
    /**
     * A spoken instruction, in the room.
     *
     * "Put the schematic on the table" is shorter than composing a call,
     * for the core as much as for the user — so this is the same path,
     * not a convenience layer over a different one. It comprehends and
     * emits an Instruction, and U still gets to weigh it.
     */
    fun spoken(c: Chassis, said: String): Map<String, Any?> {
        val held = Respond.comprehend(c.table, said)
        val on = when {
            said.contains("table", true) -> "the table"
            said.contains("wall", true) || said.contains("screen", true) -> "the wall"
            else -> null
        }
        if (on == null)
            return mapOf("ok" to false, "heard" to said,
                         "reason" to "no surface named. which one?")
        if (aperture == Aperture.CLOSED)
            enter(Aperture.VISUAL, why = "asked to show something")
        return mapOf("ok" to true, "on" to on, "act" to held.act.name,
                     "subject" to held.subject,
                     "instruction" to Instruction("L", "show.$on", Op.SET,
                                                  held.subject ?: said, 0.8,
                                                  why = "asked"))
    }

    fun state(): Map<String, Any> = mapOf(
        "aperture" to aperture.name,
        "wall" to (room.onSurface("the wall") != null),
        "table" to (room.onSurface("the table") != null),
        "heard" to heard.size,
        "cost" to room.sceneCost(),
    )
}
