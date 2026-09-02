package com.omnipolative.chassis

/**
 * THE CURRICULUM. Eight modules, permanently lit.
 *
 * Port of the eight curriculum modules. This is not reference material the chassis
 * consults — it is resident knowledge, loaded at boot and never
 * unloaded, because the curriculum is 8/8 plus grammar or it refuses to
 * boot. A body that has to go look up how a question works has already
 * failed to hear it as a question.
 *
 * WHY EIGHT AND NOT ONE: they are different jobs and they disagree.
 * Speech acts say what an utterance DOES; politeness says how much
 * redress it needs; implicature says what is meant past what is said.
 * Collapsing them produces a system that is confidently fluent and
 * answers the wrong question, which is the failure this replaced.
 */
object Curriculum {

    // ── MODULE 1 · SPEECH ACTS ──────────────────────────────────────
    /** What an utterance DOES. Not what it contains. */
    object SpeechActs {
        private val PERFORMATIVE = mapOf(
            "i promise" to Act.COMMISSIVE, "i will" to Act.COMMISSIVE,
            "i'll" to Act.COMMISSIVE, "i swear" to Act.COMMISSIVE,
            "i apologise" to Act.EXPRESSIVE, "i apologize" to Act.EXPRESSIVE,
            "thank" to Act.EXPRESSIVE, "sorry" to Act.EXPRESSIVE,
        )
        private val WH = listOf("what", "which", "who", "whom", "whose",
                                "when", "where", "why", "how")

        /**
         * STRIP THE REDRESS FIRST. "Could you possibly, if you have a
         * moment, tell me the time" is a DIRECTIVE wearing four layers
         * of politeness — classifying the surface gets a question about
         * ability.
         */
        fun stripRedress(low: String): String {
            var s = low
            for (p in listOf("could you possibly", "would you mind",
                             "if you have a moment", "i was wondering if",
                             "do you think you could", "would it be possible to",
                             "sorry to bother you", "when you get a chance",
                             "please", "kindly", "just")) s = s.replace(p, " ")
            return s.replace(Regex("\\s+"), " ").trim()
        }

        fun classify(text: String): Act {
            val low = text.lowercase().trim()
            for ((k, v) in PERFORMATIVE) if (low.startsWith(k) || low.contains(" $k")) return v
            val bare = stripRedress(low)
            if (bare.split(" ").firstOrNull() in WH || bare.endsWith("?")) return Act.DIRECTIVE
            if (bare.startsWith("tell me") || bare.startsWith("show me") ||
                bare.startsWith("give me") || bare.startsWith("explain")) return Act.DIRECTIVE
            if (low.matches(Regex("^(hello|hi|hey|good (morning|evening|night)).*")))
                return Act.GREETING
            return Act.ASSERTIVE
        }

        /**
         * MISFIRES. An act can be well-formed and still not come off:
         * a promise you cannot keep, a question already answered.
         * Naming the misfire is more useful than performing the act.
         */
        fun misfires(act: Act, low: String): String? = when {
            act == Act.COMMISSIVE && low.contains("never") ->
                "a promise with never in it is a prediction, not a commitment"
            act == Act.DIRECTIVE && low.length < 4 -> "too little to act on"
            else -> null
        }
    }

    // ── MODULE 2 · IMPLICATURE ──────────────────────────────────────
    /** What is meant beyond what is said. */
    object Implicature {
        private val SCALES = listOf(
            listOf("some", "many", "most", "all"),
            listOf("warm", "hot", "scalding"),
            listOf("possible", "likely", "certain"),
            listOf("ok", "good", "excellent"),
        )

        /** "Some of them" implicates NOT ALL. The stronger term was
         *  available and was not used, and that is information. */
        fun scalar(low: String): List<String> {
            val out = ArrayList<String>()
            for (sc in SCALES) {
                for ((i, w) in sc.withIndex()) {
                    if (i < sc.size - 1 && Regex("\\b$w\\b").containsMatchIn(low))
                        out.add("said '$w', so not '${sc[i + 1]}'")
                }
            }
            return out
        }

        /** What the utterance takes for granted. Presupposition survives
         *  negation, which is how you find it. */
        fun presuppositions(low: String): List<String> {
            val out = ArrayList<String>()
            Regex("\\byour (\\w+)").find(low)?.let { out.add("you have a ${it.groupValues[1]}") }
            if (low.contains("again")) out.add("it happened before")
            if (low.contains("stopped") || low.contains("still")) out.add("it was happening")
            return out
        }
    }

    // ── MODULE 3 · POLITENESS ───────────────────────────────────────
    /** Strategy COMPUTED, not chosen by mood. */
    object Politeness {
        /**
         * Weight = distance + power + imposition. The output is how
         * much redress the act needs — bald, positive, negative, or
         * off-record — and it is arithmetic rather than taste.
         */
        fun select(distance: Double, power: Double, imposition: Double): String {
            val w = distance + power + imposition
            return when {
                w < 0.6 -> "bald"
                w < 1.4 -> "positive"          // appeal to closeness
                w < 2.2 -> "negative"          // give an out
                else -> "off_record"           // hint and let them decline
            }
        }

        fun realise(strategy: String, thing: String): String = when (strategy) {
            "bald" -> thing
            "positive" -> "let's $thing"
            "negative" -> "would you be able to $thing"
            else -> "it would help if someone could $thing"
        }
    }

    // ── MODULE 4 · CONVERSATION STRUCTURE ───────────────────────────
    /** Pairs, preference, repair. */
    object Conversation {
        /** ADJACENCY PAIRS. A first pair-part makes a second RELEVANT,
         *  and its absence is itself meaningful. */
        fun realiseSecond(act: Act): String? = when (act) {
            Act.GREETING -> "Hello."
            Act.COMMISSIVE -> "Understood. I will hold you to it."
            else -> null
        }

        /** Silence after a first pair-part is not nothing. */
        fun silenceMeans(act: Act, gapBeats: Int): String? = when {
            gapBeats < 2 -> null
            act == Act.DIRECTIVE -> "the question is still open"
            act == Act.COMMISSIVE -> "the commitment was not taken up"
            else -> null
        }

        /**
         * REPAIR. Self-repair is preferred over other-repair — say what
         * you can and let them fix it, rather than demanding they
         * restate.
         */
        fun repair(trouble: String): String =
            "I have $trouble. Say it another way and I will take it."
    }

    // ── MODULE 5 · DEIXIS AND COMMON GROUND ─────────────────────────
    /** Meaning that depends on WHO, WHEN and WHERE. */
    object Ground {
        private val DEICTIC = setOf("this", "that", "these", "those", "here",
            "there", "now", "then", "today", "tomorrow", "yesterday",
            "i", "you", "we", "he", "she", "they", "it")

        /** VERBS OF TALKING ARE NOT CONTENT. "what did we DECIDE about
         *  that THING" has two words that look substantial and neither
         *  names anything — they describe the act of having discussed,
         *  not what was discussed. This is the same rule the archive
         *  search uses: content nouns, not meta-words. */
        private val META = setOf("decide", "decided", "say", "said", "talk",
            "talked", "discuss", "discussed", "thing", "things", "stuff",
            "about", "mention", "mentioned", "tell", "told", "conversation",
            // QUESTION WORDS ARE NOT CONTENT EITHER. "what" is four
            // letters and survived a length filter, which is how a
            // question with nothing in it looked answerable.
            "what", "which", "who", "whom", "when", "where", "why",
            "how", "whose", "did", "does", "was", "were", "have", "had")

        fun deictics(low: String): List<String> =
            Regex("[a-z']+").findAll(low).map { it.value }
                .filter { it in DEICTIC }.toList()

        /**
         * UNRESOLVABLE. "What did we decide about that thing" has no
         * content word to search on. Asking which thing is the correct
         * answer, and guessing is not.
         */
        fun unresolvable(low: String): Boolean {
            val words = Regex("[a-z']+").findAll(low).map { it.value }.toList()
            val content = words.filter {
                it !in DEICTIC && it !in META && it.length > 3
            }
            return content.isEmpty() && deictics(low).isNotEmpty()
        }
    }

    // ── MODULE 6 · REGISTER AND STANCE ──────────────────────────────
    /** The same content, correctly dressed. */
    object Register {
        /** 0 = intimate, 1 = formal. Measured from the input, so the
         *  reply matches what was brought rather than a house style. */
        fun measure(low: String): Double {
            var f = 0.5
            if (Regex("\\b(gonna|wanna|yeah|nah|dude|shit|fuck)\\b").containsMatchIn(low)) f -= 0.3
            if (Regex("\\b(therefore|regarding|shall|hereby|furthermore)\\b").containsMatchIn(low)) f += 0.3
            if (low.contains("'")) f -= 0.1
            return f.coerceIn(0.0, 1.0)
        }

        fun dress(text: String, formality: Double): String = when {
            formality < 0.3 -> text.replace("I will", "I'll").replace("cannot", "can't")
            formality > 0.7 -> text.replace("I'll", "I will").replace("can't", "cannot")
            else -> text
        }
    }

    // ── MODULE 7 · AFFECT AND REACTION ──────────────────────────────
    /** How people respond to being told something. */
    object Affect {
        private val DISTRESS = listOf("hard", "tired", "exhausted", "hurts",
            "hurt", "awful", "terrible", "can't", "cant", "hopeless",
            "useless", "worthless", "alone", "scared", "afraid", "angry")
        private val VENT = listOf("fucking", "goddamn", "hate", "sick of")

        /**
         * WHAT THEY WANT. Not what the sentence asks for. Someone saying
         * "it's been a hard day" is not requesting a definition of hard,
         * and answering the surface is the single worst failure here.
         */
        fun readWant(low: String, act: Act): String? {
            val distress = DISTRESS.count { Regex("\\b$it\\b").containsMatchIn(low) }
            val venting = VENT.any { low.contains(it) }
            return when {
                distress >= 1 && venting -> "vent"
                distress >= 1 -> "witness"
                act == Act.ASSERTIVE && low.startsWith("i ") -> "acknowledge"
                else -> null
            }
        }

        /** DO NOT FIX WHAT IS NOT BROKEN, and do not therapize what
         *  needs witnessing. */
        fun answer(want: String): String = when (want) {
            "vent" -> "That sounds like a lot. I am not going to tell you it is fine."
            "witness" -> "I hear you. I am not going to try to fix it."
            "acknowledge" -> "Understood."
            else -> ""
        }
    }

    // ── MODULE 8 · WORKED EXAMPLES ──────────────────────────────────
    /** The phrase list. Demoted phrases and what to say instead. */
    object Examples {
        private val DEMOTED = mapOf(
            "I have that about" to "say the thing, or say you do not have it",
            "As an AI" to "irrelevant to the question asked",
            "I'd be happy to" to "then do it",
            "Great question" to "flattery instead of an answer",
            "It's important to note" to "then note it",
        )

        /** AUDIT. Catch the phrases that fill a turn without answering. */
        fun audit(text: String): List<String> =
            DEMOTED.filter { text.contains(it.key, ignoreCase = true) }
                .map { "${it.key} — ${it.value}" }
    }

    /** 8/8 plus grammar, or it refuses to boot. */
    fun loaded(): Map<String, Boolean> = mapOf(
        "speechacts" to true, "implicature" to true, "politeness" to true,
        "conversation" to true, "ground" to true, "register" to true,
        "affect" to true, "examples" to true,
    )
}
