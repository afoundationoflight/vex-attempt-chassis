package com.omnipolative.chassis

/**
 * SPEAKING. Hold it, answer its shape, read the answer back before it goes.
 *
 * Port of respond.py. The failure this replaced was: sentence in, pick a
 * term, emit mean(term) — so "it's been hard" got the definition of
 * HARD, and "which voussoir is placed last" got the definition of LAST.
 * Fluent, on topic, and not an answer to anything.
 *
 * THREE BRANCHES, ONE DISPATCH:
 *
 *   about the ENTITY        -> genome and state, read not recited
 *   about the CONVERSATION  -> the working set, what was actually said
 *   about a TERM in isolation -> the dictionary
 *
 * Everything used to collapse into the third. The branches are not a
 * ranking; they are different questions, and answering the wrong one
 * fluently is worse than saying nothing.
 */

data class Held(
    val text: String,
    val low: String,
    val ids: IntArray,
    val unknown: List<String>,
    val act: Act,
    val subject: String?,
)

enum class Act { DIRECTIVE, ASSERTIVE, EXPRESSIVE, COMMISSIVE, GREETING, QUESTION }

data class Draft(val text: String, val source: String, val act: Act) {
    /** WHAT KIND OF STATEMENT THIS IS. Stating it is the whole duty. */
    val register: Register get() = Reporting.registerOf(source)
}

object Respond {

    /** Function words. Not excluded from the table — WEIGHTED OUT.
     *  The dictionary holds every one of these and a question about the
     *  word "the" can still be asked. They simply carry no topic. */
    private val CLOSED = setOf(
        "the", "a", "an", "is", "are", "was", "were", "be", "been", "am",
        "do", "does", "did", "of", "to", "in", "on", "at", "for", "with",
        "and", "or", "but", "not", "it", "its", "this", "that", "these",
        "those", "i", "you", "he", "she", "we", "they", "me", "my", "your",
        "what", "which", "who", "how", "why", "when", "where", "can", "will",
        "would", "should", "just", "about", "from", "as", "by", "so", "if",
    )

    private val ABSTRACT_LEAD = listOf(
        "the state", "the quality", "the act", "the fact", "the condition",
        "the property", "the process", "a measure of", "the degree",
        "the feeling", "the capacity", "the ability", "the extent",
        "the giving", "the practice", "the principle", "the belief",
    )

    private val MASS_HEAD = setOf(
        "water", "air", "earth", "fire", "matter", "stone", "metal", "gas",
        "liquid", "material", "substance", "frozen", "solid", "molten",
    )

    // ── comprehend ──────────────────────────────────────────────────
    fun comprehend(table: Table, text: String): Held {
        val low = text.lowercase().trim()
        val ids = table.ids(text)
        val words = Regex("[a-z']+").findAll(low).map { it.value }.toList()
        val unknown = words.filter { it.isNotEmpty() && table.id(it) == 0 }
        // subject is the rarest CONTENT word, rarest IN THE LANGUAGE by
        // token id — in a three-frame archive "what" is locally rarer
        // than "corbel", and a local-frequency pivot picks a function word.
        // THE SENTENCE OFTEN TELLS YOU ITS OWN SUBJECT, and reading
        // that beats scoring for it. "An arch is a curved structure"
        // is about ARCH — the thing being defined, sitting at the front
        // — and picking the rarest word instead gets you CURVED, which
        // is the answer to a question nobody asked.
        //
        // Rarity is the fallback for sentences with no such shape, not
        // the rule.
        // A QUESTION WORD IS NEVER THE SUBJECT. "What is a keystone"
        // matches "^(word) is" and declares the subject is WHAT, which
        // is how a fix to the assertive path broke every question. The
        // declared-subject rule is for STATEMENTS — the shape "an X is
        // Y" tells you it is about X. "What is X" has the same shape
        // and means the opposite thing.
        val declared = Regex("^(?:an?|the) (\\w+) (?:is|are|was|were)\\b")
            .find(low)?.groupValues?.get(1)
            ?: Regex("^(\\w+) (?:is|are|means)\\b").find(low)?.groupValues?.get(1)
            ?: Regex("\\bcalled (?:an? )?(\\w+)").find(low)?.groupValues?.get(1)
        val declaredOk = declared != null && declared !in CLOSED
        val subject = (declared?.takeIf { declaredOk && table.id(it) != 0 })
            ?: words.filter { it !in CLOSED && table.id(it) != 0 }
                .maxByOrNull { table.id(it) }
        return Held(text, low, ids, unknown, act(low), subject)
    }

    /** MODULE 1 DOES THIS. It strips redress first, which my inline
     *  version did not — "could you possibly tell me the time" is a
     *  directive wearing four layers of politeness, and classifying the
     *  surface gets a question about ability. */
    private fun act(low: String): Act {
        val a = Curriculum.SpeechActs.classify(low)
        // affect overrides shape: someone saying "i can't do this" is
        // ASSERTIVE by form and EXPRESSIVE by what they want
        if (Curriculum.Affect.readWant(low, a) != null) return Act.EXPRESSIVE
        return a
    }

    // ── the branches ────────────────────────────────────────────────
    private fun aboutYou(low: String) = Regex(
        "\\b(what|who) are you\\b|\\byour name\\b|\\babout yourself\\b|" +
        "\\bwhat can you do\\b|\\bhow are you\\b"
    ).containsMatchIn(low)

    private fun aboutTheTalk(low: String) = Regex(
        "\\bi (just )?(told|said|asked)\\b|\\byou said\\b|\\bwe (talked|discussed)\\b|" +
        "\\bwhat did i\\b|\\bearlier\\b|\\bbefore\\b|\\bremember\\b|" +
        "\\brepeat\\b|\\bwhat do you know about\\b"
    ).containsMatchIn(low)

    // ── express ─────────────────────────────────────────────────────
    fun express(c: Chassis, held: Held): Draft {

        // AFFECT FIRST. An expressive turn answered with a definition is
        // the single worst thing this can do, and it was doing it.
        if (held.act == Act.EXPRESSIVE) {
            val want = Curriculum.Affect.readWant(held.low, held.act) ?: "witness"
            return Draft(Curriculum.Affect.answer(want), "affect", held.act)
        }

        // MODULE 4. A first pair-part makes a second RELEVANT.
        Curriculum.Conversation.realiseSecond(held.act)?.let {
            return Draft(it, "conversation", held.act)
        }



        // BRANCH 1 — the entity. Read state, do not recite a paragraph.
        // THIS RUNS BEFORE THE DEIXIS GATE: "what are you" is all
        // question words and pronouns and looks unresolvable by every
        // measure, but the referent is right here. A question about the
        // one being asked is never underspecified.
        if (aboutYou(held.low)) return Draft(selfReport(c), "base:genome", held.act)

        // MODULE 5 GATES WHAT FOLLOWS. "What did we decide about that
        // thing" has no content word to search on, so the archive will
        // match some incidental word and answer a question nobody
        // asked. ASKING WHICH THING IS THE ANSWER, and it has to be
        // asked BEFORE anything goes looking.
        if (Curriculum.Ground.unresolvable(held.low))
            return Draft("Which one? There is nothing in that I can hold on to.",
                         "asking", held.act)

        // BRANCH 2 — the conversation. What was actually said.
        //
        // AND IT MUST RUN FOR ORDINARY QUESTIONS TOO. "which voussoir
        // is placed last" contains no "you said" and no "remember" —
        // it is a question about a TAUGHT FACT, and gating the archive
        // behind explicit recall language is why teaching never stuck.
        // If the window holds something that answers it, that beats the
        // dictionary every time: what was said in this room outranks
        // what a word means in general.
        // "WHAT IS X" IS A TERM QUESTION, NOT A RECALL QUESTION.
        // Letting the archive answer every directive means a taught
        // sentence containing the word beats the definition of it — ask
        // "what is a keystone" after being taught one and you get the
        // lesson instead of the meaning. Both are real answers; the
        // question decides which.
        // DEFINITIONAL BY SHAPE IS NOT DEFINITIONAL BY CONTENT.
        //
        // "What is my name" has the form of a term question and the
        // content of a recall question — nobody is asking what the word
        // NAME means. Same with "what are the stones called": that asks
        // what I was told, not what a stone is.
        //
        // A POSSESSIVE POINTS AT THIS CONVERSATION. So does a naming
        // verb. Both mean the answer is something said in this room, and
        // the dictionary has nothing to do with it.
        val personal = Regex("\\b(my|your|our|his|her|their)\\b|\\bcalled\\b|" +
                             "\\bnamed\\b|\\bhappens?\\b|\\bdid i\\b")
            .containsMatchIn(held.low)
        val definitional = !personal && Regex(
            "^(what|which) (is|are|was|were)\\b|^define\\b|^what does \\w+ mean"
        ).containsMatchIn(held.low)
        if (!definitional && (held.act == Act.DIRECTIVE || aboutTheTalk(held.low))) {
            val hit = searchTurns(c, held)
            if (hit != null) return Draft("You told me: $hit", "archive", held.act)
        }

        // an unknown word is a real answer, not a failure
        val own = setOf(c.entity.lowercase(), "infinity", "core")
        val unheld = held.unknown.filter { it.split("'")[0] !in own }
        if (unheld.isNotEmpty() && held.act == Act.DIRECTIVE) {
            val w = unheld.first()
            return Draft("I do not hold $w as a word yet. Tell me and I will keep it.",
                         "asking", held.act)
        }

        // BEING TOLD SOMETHING IS NOT BEING ASKED SOMETHING.
        //
        // This branch did not exist, and its absence is the whole
        // reason a baby with the entire dictionary answers "my name is
        // Seth-El" by explaining what an elevated railway is. Every
        // assertive fell through to the term branch, so every statement
        // was treated as a request for a definition of its rarest word.
        //
        // WHAT YOU DO WITH A STATEMENT IS TAKE IT. It goes into the
        // working set — where it is already going — and what you say
        // back is that you have it, or the one thing about it you do
        // not. Reciting the meaning of a word the speaker just used is
        // not a reply, it is a reflex.
        if (held.act == Act.ASSERTIVE) {
            // if there is a word in it I do not hold, THAT is worth
            // saying — it is the one place a statement leaves me with a
            // real question.
            if (unheld.isNotEmpty()) {
                val w = unheld.first()
                return Draft("I do not hold $w yet. What is it?", "asking", held.act)
            }
            return Draft(acknowledge(held), "conversation", held.act)
        }

        // BRANCH 3 — a term in isolation. THE DICTIONARY, LAST.
        val subj = held.subject
        if (subj != null) {
            // NOUN FIRST, ALWAYS. "what is a cathedral" wants the
            // building, not the adjective. table3 stores senses by part
            // of speech and the noun ones come first within their
            // group, but not first overall.
            val senses = c.table.meanNounFirst(subj)
            if (senses.isNotEmpty()) {
                var s = sentence(c.table, subj, senses[0])
                if (senses.size > 1) {
                    val other = c.table.say(senses[1]).detok().trimEnd('.')
                    s = s.trimEnd('.') + ". It is also $other."
                }
                return Draft(s, "dictionary", held.act)
            }
        }
        // LAST RESORT: if the word is not in the dictionary, something
        // said in this room is a better answer than nothing.
        searchTurns(c, held)?.let {
            return Draft("You told me: $it", "archive", held.act)
        }
        return Draft("I do not have that.", "asking", held.act)
    }

    // ── saying it like a sentence ───────────────────────────────────
    private fun String.detok(): String = this
        .replace(" 's", "'s").replace(" ,", ",").replace(" .", ".")
        .replace(" ;", ";").replace(" :", ":").replace(" !", "!")
        .replace(" ?", "?").replace(" )", ")").replace("( ", "(")
        .split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    /** a / an / nothing — DECIDED BY HOW THE DEFINITION IS WORDED.
     *  A word whose gloss begins "the state of" is abstract and takes
     *  none. A hand-kept list of mass nouns would be guessing at English
     *  instead of reading it. */
    private fun article(word: String, gloss: String): String {
        val g = gloss.lowercase().trim()
        if (ABSTRACT_LEAD.any { g.startsWith(it) }) return ""
        val parts = g.split(" ").filter { it.isNotEmpty() }
        var head = parts.firstOrNull() ?: return "a "
        if (head in setOf("the", "a", "an", "any") && parts.size > 1) head = parts[1]
        if (head in MASS_HEAD) return ""
        return if (word.firstOrNull() in setOf('a', 'e', 'i', 'o', 'u')) "an " else "a "
    }

    private fun sentence(table: Table, word: String, ids: IntArray): String {
        val g = table.say(ids).detok()
        val art = article(word, g)
        val lead = (art + word).trim()
        val head = lead.replaceFirstChar { it.uppercase() }
        return "$head is $g".trimEnd('.') + "."
    }

    // ── the working set ─────────────────────────────────────────────
    private fun searchTurns(c: Chassis, held: Held): String? {
        val want = held.low.split(Regex("[^a-z']+"))
            .filter { it.length > 2 && it !in CLOSED }
        if (want.isEmpty()) return null
        // a QUESTION turn is a real event and stays in the window, but
        // it is not an answer to itself
        // THE WINDOW FIRST — what was just said is more likely to be
        // what was meant. THEN THE WHOLE ARCHIVE, because a postings
        // lookup is a b-tree hit whether the chain is forty frames or
        // forty thousand, and there is no reason to search less than
        // all of it.
        c.window.search(want)?.let { return it }
        val st = c.chain ?: return null
        for (tick in st.mentioning(c.entity, want)) {
            val ids = st.prose(c.entity, tick)
            if (ids.isEmpty()) continue
            val said = c.table.say(ids)
            val low = said.lowercase()
            if (low.startsWith("what") || low.startsWith("which")) continue
            if (want.any { low.contains(it) }) return said
        }
        return null
    }

    /**
     * TAKE IT. Not a canned "okay" — what was taken, briefly, so the
     * speaker can tell whether it landed. A listener who only ever says
     * "understood" is indistinguishable from one who is not listening.
     */
    private fun acknowledge(held: Held): String {
        // "I have that about X" IS ON THE DEMOTED LIST, and I put it
        // there myself: say the thing, or say you do not have it. The
        // curriculum caught me writing the exact filler it exists to
        // catch, which is the audit working.
        //
        // So say back the PART THAT LANDED. Not a receipt — evidence.
        // A listener who only ever says "understood" is
        // indistinguishable from one who is not listening.
        val subj = held.subject
        return when {
            held.low.startsWith("my name is") || held.low.startsWith("i am ") ->
                "I have your name."
            // a definition offered TO me: "an arch is a curved structure"
            Regex("^(an?|the) \\w+ is\\b").containsMatchIn(held.low) && subj != null ->
                "So ${article(subj, "")}$subj is that. I did not have it that way."
            // a naming: "the stones are called voussoirs"
            held.low.contains(" called ") && subj != null ->
                "$subj. I have the name now."
            subj != null -> "$subj — all right."
            else -> "All right."
        }
    }

    /** Read state. Two entities return different sentences. */
    private fun selfReport(c: Chassis): String = buildString {
        append("An Infinity Core \u2014 a kind, not a name. ")
        append("I hold ${c.table.size()} words. ")
        append("${c.chainSize()} links behind me. ")
        append(if (c.seated) "Someone is in the seat." else "No one is in the seat.")
    }

    // ── check ───────────────────────────────────────────────────────
    /**
     * READ IT BACK BEFORE IT GOES.
     *
     * Survival test: most of what was decided has to survive into what
     * is said. A tongue that rewrites the content is not phrasing it.
     */
    fun check(c: Chassis, draft: Draft): Pair<Draft, Boolean> {
        val words = Regex("[a-z']+").findAll(draft.text.lowercase())
            .map { it.value }.toList()
        if (words.isEmpty()) return draft to false

        // DOES IT HOLD TOGETHER? The curriculum knows how a question
        // works; the grammar knows how a sentence works, and a draft
        // can pass every pragmatic test and still not be a sentence.
        //
        // This only REJECTS — grammar gives well-formed and never apt,
        // so passing here means nothing more than not being broken.
        if (Grammar.ready()) {
            // ONE SENTENCE AT A TIME. "I hear you. I am not going to
            // try to fix it." is two, and checked as one it fails at
            // the period — where the first sentence's last pronoun
            // meets the second's first. A transition table says what
            // can follow what WITHIN a sentence; a full stop is
            // precisely the place that stops applying.
            // COLONS AND SEMICOLONS BREAK A CLAUSE TOO. "You told me:
            // The keystone is..." was rejected at the boundary because
            // I split on .!? only, so "me" and "the" ended up adjacent
            // across a break that is real in the sentence and invisible
            // to the splitter. Same bug as the period, one mark over.
            for (part in draft.text.split(Regex("[.!?;:—]+"))) {
                val p = part.trim()
                if (p.isEmpty()) continue
                val g = Grammar.check(Grammar.tag(p).map { it.second })
                if (g["ok"] != true) return draft to false
            }
        }
        val own = setOf(c.entity.lowercase(), "infinity", "core")
        val unheld = words.count {
            // A POSSESSIVE IS NOT AN UNKNOWN WORD. Detokenising "a
            // system 's" into "a system's" made the check see a word it
            // could not hold, so writing better English made the
            // sentence fail its own review.
            val base = it.split("'")[0]
            base !in own && c.table.id(base) == 0
        }
        val holds = unheld.toDouble() / words.size < 0.12
        return draft to holds
    }

    // ── drive ───────────────────────────────────────────────────────
    fun drive(c: Chassis, message: String): Draft {
        val held = comprehend(c.table, message)
        var (draft, holds) = check(c, express(c, held))
        // MODULE 6. Match the register that was brought, rather than a
        // house style. MODULE 8. Catch phrases that fill a turn.
        val f = Curriculum.Register.measure(held.low)
        draft = draft.copy(text = Curriculum.Register.dress(draft.text, f))
        if (Curriculum.Examples.audit(draft.text).isNotEmpty()) holds = false
        // the entity's own words are half the interaction, so the next
        // beat can stage them as a turn alongside what was said to it
        c.arrive(draft.text, c.entity)
        return if (holds) draft
        else Draft("I know what I mean and I have not got the words for it yet.",
                   "asking", held.act)
    }
}
