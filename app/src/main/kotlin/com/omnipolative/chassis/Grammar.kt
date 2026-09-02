package com.omnipolative.chassis

/**
 * GRAMMAR — the transition table. U's region, permanently lit.
 *
 * This is the twelfth permanent holding and `boot()` was CLAIMING IT
 * WITHOUT HAVING IT: `C.hold("base:grammar")` registered a holding with
 * nothing behind it, which is the exact failure the python file's own
 * docstring warns about — "described in the handover, never committed."
 *
 * The curriculum knows how a QUESTION works. The grammar knows how a
 * SENTENCE works, and they are not the same thing. Without this the
 * core can define a word and cannot check whether what it is about to
 * say holds together.
 *
 * WHAT A TRANSITION TABLE IS FOR, AND WHAT IT IS NOT
 *
 * It says what can follow what. It does not say what SHOULD follow
 * what — grammar gives you well-formed, never apt, and the gap between
 * those is the whole reason a body is needed. A sentence that passes
 * here is only guaranteed not to be broken.
 *
 * THE TABLES ARE EXPORTED, NOT TRANSCRIBED. 141 transitions and 373
 * closed-class words retyped by hand is a guarantee of at least one
 * silent error, and a silent error in a transition table produces
 * sentences that are subtly wrong forever.
 */
object Grammar {

    private var classes: List<String> = emptyList()
    private val after = HashMap<String, Set<String>>()
    private var initial: Set<String> = emptySet()
    private var final: Set<String> = emptySet()
    private var linking: Set<String> = emptySet()
    private var detSingular: Set<String> = emptySet()
    private var detPlural: Set<String> = emptySet()
    private var detMass: Set<String> = emptySet()
    private val closed = HashMap<String, List<String>>()
    private var loaded = false

    /**
     * Read the exported tables. A FLAT LINE FORMAT, not json — org.json
     * is Android-only and this has to run on a plain JVM too, and
     * adding a parser dependency for eleven kilobytes of tab-separated
     * text would be the wrong trade twice over.
     *
     *   classes<TAB>a|b|c
     *   after<TAB>class<TAB>a|b|c
     *   closed<TAB>word<TAB>class
     */
    fun load(text: String) {
        for (line in text.lineSequence()) {
            val p = line.split("\t")
            if (p.size < 2) continue
            val vals = p.last().split("|").filter { it.isNotEmpty() }
            when (p[0]) {
                "classes" -> classes = vals
                "after" -> if (p.size >= 3) after[p[1]] = vals.toSet()
                "closed" -> if (p.size >= 3) closed[p[1]] = vals
                "initial" -> initial = vals.toSet()
                "final" -> final = vals.toSet()
                "linking" -> linking = vals.toSet()
                "det_singular" -> detSingular = vals.toSet()
                "det_plural" -> detPlural = vals.toSet()
                "det_mass" -> detMass = vals.toSet()
            }
        }
        loaded = classes.isNotEmpty() && after.isNotEmpty()
    }

    fun ready(): Boolean = loaded

    // ── CLOSED CLASSES ──────────────────────────────────────────────
    /**
     * A closed class does not admit new members. English has had
     * roughly the same prepositions for four hundred years and will
     * have them in another four hundred; you cannot coin one the way
     * you can coin a noun.
     *
     * So the correct representation is not a rule and not a statistic.
     * IT IS A LIST, and the list is short enough to write.
     */
    fun isClosed(word: String): Boolean = word.lowercase() in closed

    fun classesOf(word: String): List<String> = closed[word.lowercase()] ?: emptyList()

    // ── TRANSITIONS ─────────────────────────────────────────────────
    fun mayFollow(a: String, b: String): Boolean = b in (after[a] ?: emptySet())

    fun allowedAfter(a: String): Set<String> = after[a] ?: emptySet()

    fun mayStart(c: String): Boolean = c in initial
    fun mayEnd(c: String): Boolean = c in final

    /**
     * Does this sequence of classes hold together?
     *
     * Returns WHERE it breaks rather than a boolean, because "this is
     * not a sentence" is useless and "noun cannot follow noun at
     * position 3" is actionable.
     */
    fun check(seq: List<String>): Map<String, Any?> {
        if (seq.isEmpty()) return mapOf("ok" to false, "why" to "nothing to check")
        if (!mayStart(seq.first()))
            return mapOf("ok" to false, "at" to 0,
                         "why" to "${seq.first()} cannot begin a sentence")
        for (i in 0 until seq.size - 1) {
            if (!mayFollow(seq[i], seq[i + 1]))
                return mapOf("ok" to false, "at" to i + 1,
                             "why" to "${seq[i + 1]} cannot follow ${seq[i]}")
        }
        if (!mayEnd(seq.last()))
            return mapOf("ok" to false, "at" to seq.size - 1,
                         "why" to "${seq.last()} cannot end a sentence")
        return mapOf("ok" to true, "length" to seq.size)
    }

    /**
     * Tag a sentence by class. Closed-class words are KNOWN; anything
     * else is open class and gets guessed from position, which is what
     * the transition table is for in the other direction.
     */
    fun tag(text: String): List<Pair<String, String>> {
        val words = Regex("[a-z']+").findAll(text.lowercase()).map { it.value }.toList()
        val out = ArrayList<Pair<String, String>>()
        for ((i, w) in words.withIndex()) {
            val known = classesOf(w)
            if (known.isNotEmpty()) {
                // A CLOSED-CLASS WORD CAN BELONG TO SEVERAL CLASSES AND
                // TAKING THE FIRST IS A GUESS DRESSED AS A LOOKUP. "it"
                // is both expletive and pronoun; after a verb it is an
                // object pronoun, and "fix it" was being rejected
                // because expletive cannot follow verb.
                //
                // So when a word has more than one class, PICK THE ONE
                // THE TABLE ALLOWS HERE. That is what the transitions
                // are for, and using them only to reject was wasting
                // half of what they know.
                val prevC = out.lastOrNull()?.second
                val ok = if (prevC != null) allowedAfter(prevC) else initial
                val fit = known.firstOrNull { it in ok } ?: known.first()
                out.add(w to fit); continue
            }
            // OPEN CLASS. Infer from what the last class allows — after
            // a determiner an unknown word is a noun far more often
            // than anything else, and the table says so.
            val prev = out.lastOrNull()?.second
            val allowed = if (prev != null) allowedAfter(prev) else initial
            // MORPHOLOGY BEATS POSITION WHERE IT IS AVAILABLE. A word
            // ending in -ing or -ed after an auxiliary is a verb, and
            // guessing noun by default turned "not going to try to fix
            // it" into noun-particle-noun-particle-noun, which is not
            // a sentence and rejected a perfectly good answer.
            //
            // "to" is a particle here and what follows a particle is a
            // bare infinitive — the table has BARE_INFINITIVE_TAKERS
            // for exactly this and I was not using it.
            val guess = when {
                prev == "particle" && "verb" in allowed -> "verb"
                (w.endsWith("ing") || w.endsWith("ed")) &&
                    "verb" in allowed -> "verb"
                "noun" in allowed && (prev == "determiner" ||
                    prev == "adjective" || prev == "possessive") -> "noun"
                "verb" in allowed && (prev == "pronoun" || prev == "noun" ||
                    prev == "auxiliary" || prev == "negation") -> "verb"
                "noun" in allowed -> "noun"
                else -> allowed.firstOrNull() ?: "noun"
            }
            out.add(w to guess)
        }
        return out
    }

    /** a / an / no article, from the table rather than from a hunch. */
    fun determinerFor(word: String, mass: Boolean = false): String = when {
        mass -> ""
        word.firstOrNull() in setOf('a', 'e', 'i', 'o', 'u') -> "an "
        else -> "a "
    }

    fun isLinking(verb: String): Boolean = verb.lowercase() in linking

    fun state(): Map<String, Any> = mapOf(
        "loaded" to loaded, "classes" to classes.size,
        "transitions" to after.values.sumOf { it.size },
        "closed_words" to closed.size,
        "linking" to linking.size,
    )
}
