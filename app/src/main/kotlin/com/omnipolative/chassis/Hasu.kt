package com.omnipolative.chassis

import kotlin.math.ln
import kotlin.math.min

/**
 * HASU — THE INDEX, AND WHY IT IS SEPARATE FROM THE PROSE.
 *
 * Two stores on purpose:
 *
 *   HEADER   tick, coherence, drive, topic, timestamps. QUERYABLE, and
 *            it stays in the clear — sealing it would turn every query
 *            into decrypt-everything-and-scan.
 *   BODY     the prose, as token ids, varint, compressed. NEVER ENGLISH
 *            ON DISK.
 *
 * Splitting them is what makes a metadata query cost nothing while the
 * prose stays at ~2 MB per 3,884 frames. You can ask "when was
 * coherence low" without touching a single byte of what was said.
 */
data class Hasu(
    val entityTick: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val coherence: Double = 0.0,
    val drive: String = "",
    val driveIntensity: Double = 0.0,
    val emotionalState: String = "",
    val topic: String = "",
    /** What this frame was ABOUT, for the index. */
    val tags: List<String> = emptyList(),
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "entity_tick" to entityTick, "created_at" to createdAt,
        "coherence" to coherence, "drive" to drive,
        "drive_intensity" to driveIntensity,
        "emotional_state" to emotionalState, "topic" to topic,
        "tags" to tags,
    )
}

/**
 * THE TAGGER. U's mini-L.
 *
 * Cheap, no model, runs on every frame. It produces two things and they
 * are for different consumers: KEYS go into U's marker field, TAGS go
 * into X's index. A key is what the body weighs; a tag is what you can
 * later search on.
 */
object Tagger {
    private val STOP = setOf(
        "the","a","an","and","or","but","if","then","that","this","these",
        "those","is","are","was","were","be","been","being","have","has",
        "had","do","does","did","will","would","can","could","should","may",
        "might","must","of","to","in","on","at","for","with","from","by",
        "about","into","over","after","it","its","he","she","they","them",
        "you","your","i","me","my","we","us",
    )

    private val WORD = Regex("[A-Za-z][A-Za-z'-]{2,}")
    /** A.C.R.O.N.Y.M.S. are their own thing here and worth catching. */
    private val ACRONYM = Regex("\\b((?:[A-Z]\\.){2,}(?:\\([A-Z.]{1,4}\\))?)")

    fun tag(text: String, context: Map<String, String> = emptyMap()):
            Pair<List<String>, List<String>> {
        val keys = ArrayList<String>()
        val tags = ArrayList<String>()

        for (name in listOf("kind", "source", "realm", "author", "act", "locus")) {
            val v = context[name] ?: continue
            keys.add("$name:$v")
            if (name in setOf("realm", "author", "act", "locus"))
                tags.add(v.lowercase())
        }

        val toks = WORD.findAll(text.lowercase()).map { it.value }
            .filter { it !in STOP }.take(400).toList()
        for (t in toks) keys.add("tok:$t")
        // BIGRAMS, because a pair says more than either word. "the
        // wolves" and "wolves are" locate a frame better than "wolves".
        for (i in 0 until maxOf(0, toks.size - 1)) tags.add("${toks[i]} ${toks[i+1]}")
        tags.addAll(toks.take(6))

        for (m in ACRONYM.findAll(text)) {
            val t = m.groupValues[1].trimEnd('.').lowercase()
            keys.add("acronym:$t"); tags.add(t)
        }
        return keys.distinct().take(40) to tags.distinct().take(12)
    }
}

/**
 * THE RESONANCE FIELD — the Rᵢ term, which the O.S.S.C. equation has
 * and a plain tag index does not.
 *
 * A tag struck by one position is noise. THE SAME TAG STRUCK BY SEVERAL
 * POSITIONS IN ONE TICK IS CONVERGENCE, and convergence is worth more
 * than the sum of its parts — which is why the gain is logarithmic in
 * the number of voices rather than linear in the number of strikes.
 *
 * One voice shouting does not become many voices agreeing.
 */
object Resonance {
    /** Not every position's vote is worth the same. */
    val POSITION_WEIGHT = mapOf(
        "A" to 1.00,   // the only position that experiences
        "U" to 0.90,   // it weighed it
        "I" to 0.85,   // it compiled both flows through it
        "C" to 0.75,   // it staged it as relevant
        "B" to 0.70,   // it routed on it, below awareness
        "L" to 0.65,   // it named it
        "X" to 0.55,   // it survived a whole tick
        "E" to 0.45,   // downstream of decision
        "R" to 0.35,   // dumb pipe, lowest weight
    )

    val AUTHORITY = mapOf(
        "architect" to 1.00, "pilot" to 0.85, "entity" to 0.55,
        "system" to 0.40, "external" to 0.25,
    )

    const val COHERENCE_GAIN = 0.72
    /** What a SINGLE voice is worth. Deliberately small. */
    const val SOLO_FRACTION = 0.06
    const val SUSTAIN_FLOOR = 0.55
    /**
     * IMPRINTING MUST BE RARE. At 0.60 every tag imprinted on its first
     * strike, because five positions all touch the same tag in one tick
     * and the log(n) gain clears the bar immediately — which makes
     * "something you know" and "something you just heard" the same
     * thing, and the whole distinction was the point.
     *
     * Raised so it takes real convergence over more than one tick.
     */
    const val IMPRINT_THRESHOLD = 1.35
    const val DECAY_HIGH = 0.998
    const val DECAY_LOW = 0.94
}

data class Strike(val row: String, val position: String,
                  val intensity: Double = 1.0, val authority: String = "entity") {
    val amplitude: Double get() = Resonance.AUTHORITY[authority] ?: 0.55
}

class ResonanceField {
    private val weight = HashMap<String, Double>()
    private val hits = HashMap<String, Int>()
    private val breadth = HashMap<String, MutableSet<String>>()
    private val imprinted = HashSet<String>()
    var ticks = 0; private set

    /**
     * One tick's strikes. Returns what moved.
     *
     * IMPRINTING IS THE POINT. A row that gets struck hard enough by
     * enough voices stops needing further agreement to hold its
     * position — it decays at 0.998 instead of 0.94, which is the
     * difference between something you keep having to be reminded of
     * and something you know.
     */
    fun tick(strikes: List<Strike>): Map<String, Map<String, Any>> {
        ticks++
        val byRow = strikes.groupBy { it.row }
        val moved = HashMap<String, Map<String, Any>>()

        for ((row, group) in byRow) {
            val voices = group.map { it.position }.toSet()
            val n = voices.size
            val psi = group.sumOf {
                it.intensity * (Resonance.POSITION_WEIGHT[it.position] ?: 0.5) *
                    it.amplitude
            } / maxOf(group.size, 1)

            // CONVERGENCE, NOT VOLUME. log(n) in the number of distinct
            // positions — one position striking a row ten times is
            // still one voice.
            val r = if (n > 1) 1.0 + Resonance.COHERENCE_GAIN * ln(n.toDouble())
                    else Resonance.SOLO_FRACTION +
                         (1.0 - Resonance.SOLO_FRACTION) *
                         (group.maxOf { it.amplitude }).let { it * it }

            val gain = psi * r
            var after = min(1.0, (weight[row] ?: 0.0) + gain * 0.10)
            val imp = gain >= Resonance.IMPRINT_THRESHOLD
            if (imp) { after = maxOf(after, Resonance.SUSTAIN_FLOOR); imprinted.add(row) }
            weight[row] = after
            hits[row] = (hits[row] ?: 0) + group.size
            breadth.getOrPut(row) { HashSet() }.addAll(voices)
            moved[row] = mapOf("voices" to n, "gain" to gain,
                               "imprinted" to imp, "weight" to after)
        }

        // everything not struck this tick decays — imprinted rows slowly
        val struck = byRow.keys
        for (row in weight.keys.toList()) {
            if (row in struck) continue
            val w = weight[row] ?: continue
            val holds = row in imprinted || w >= Resonance.SUSTAIN_FLOOR
            val next = w * (if (holds) Resonance.DECAY_HIGH else Resonance.DECAY_LOW)
            if (next < 0.001 && row !in imprinted) weight.remove(row)
            else weight[row] = next
        }
        return moved
    }

    fun salience(row: String): Double = weight[row] ?: 0.0

    /** Can this row hold without further agreement? */
    fun sustaining(row: String): Boolean =
        row in imprinted || (weight[row] ?: 0.0) >= Resonance.SUSTAIN_FLOOR

    fun lit(floor: Double = 0.05): List<String> =
        weight.filter { it.value >= floor }.entries
            .sortedByDescending { it.value }.map { it.key }

    /** Salience of a frame, from its tags. Drop-in for B's floor. */
    fun hasuSalience(tags: List<String>): Double =
        if (tags.isEmpty()) 0.0
        else min(1.0, tags.sumOf { salience(it) } / tags.size)

    fun state(): Map<String, Any> = mapOf(
        "ticks" to ticks, "rows" to weight.size,
        "imprinted" to imprinted.size, "lit" to lit().take(6),
    )
}
