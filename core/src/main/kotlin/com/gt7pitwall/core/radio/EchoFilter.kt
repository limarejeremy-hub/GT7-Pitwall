package com.gt7pitwall.core.radio

/**
 * Écho de l'ingénieur dans le micro du pilote (issue #45, course du 28/10) : 26 interruptions, toutes moins de 2,6 s
 * après une annonce, avec des mots qui ressemblaient à cette annonce mal reconnue par Vosk : « Tu es P10 sur 14 » →
 * « dix second », « Tu es P8 sur 14 » → « peux huitième », « Tour 3 : 2 minutes 9 secondes » → « deux minutes neuf ».
 *
 * La comparaison se fait sur une forme commune : sans accents, chiffres et nombres en lettres ramenés à des chiffres
 * (« dix », « dixième », « P10 » → 10), mots proches acceptés (« meilleur tôt » ≈ « meilleur tour »). Rien n'est
 * enregistré : seuls les mots reconnus et le texte de l'ingénieur sont comparés.
 */
object EchoFilter {
    private val UNITS = mapOf(
        "zero" to 0, "un" to 1, "une" to 1, "deux" to 2, "trois" to 3, "quatre" to 4, "cinq" to 5, "six" to 6, "sept" to 7,
        "huit" to 8, "neuf" to 9, "dix" to 10, "onze" to 11, "douze" to 12, "treize" to 13, "quatorze" to 14, "quinze" to 15,
        "seize" to 16, "vingt" to 20, "trente" to 30, "quarante" to 40, "cinquante" to 50, "soixante" to 60, "cent" to 100,
        "premier" to 1, "premiere" to 1, "second" to 2, "seconde" to 2,
    )
    private val ORDINAL_STEMS = mapOf(
        "deux" to 2, "trois" to 3, "quatr" to 4, "cinqu" to 5, "six" to 6, "sept" to 7, "huit" to 8, "neuv" to 9, "dix" to 10,
        "onz" to 11, "douz" to 12, "treiz" to 13, "quatorz" to 14, "quinz" to 15, "seiz" to 16, "vingt" to 20,
    )
    private val FILLERS = setOf("euh", "heu", "hum", "hmm", "mmh", "bah", "ben", "hein", "unk")

    /** Petits mots présents partout (« de », « la », « tu »…) : ils ne prouvent rien et ne sont pas comparés. */
    private val FUNCTION_WORDS = setOf(
        "de", "du", "des", "le", "la", "les", "l", "un", "une", "et", "a", "au", "aux", "en", "je", "j", "tu", "il", "on",
        "es", "est", "sur", "que", "qu", "qui", "ce", "ca", "d", "ma", "mon", "mes", "ta", "ton", "tes", "pour", "par", "y",
    )

    /** Mots qui ouvrent une question : l'ingénieur annonce, il ne questionne pas le pilote de cette façon. */
    private val QUESTION = Regex("^(combien|quel|quelle|quels|quelles|ou|quand|comment|pourquoi|est ce|c'est quoi|qu'est)( |$)")

    /** Mots comparables d'une phrase : minuscules sans accents, nombres et rangs en chiffres, « P10 » → « 10 ». */
    fun tokens(text: String): List<String> =
        DriverCommandParser.normalize(text).replace("[unk]", " ")
            .replace(Regex("(?<=[a-z])(?=\\d)|(?<=\\d)(?=[a-z])"), " ")
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotBlank() && it !in FILLERS }
            .map(::canonical)

    private fun canonical(w: String): String {
        UNITS[w]?.let { return it.toString() }
        if (w.endsWith("ieme") || w.endsWith("iemes")) {
            val stem = w.substringBefore("ieme")
            ORDINAL_STEMS[stem]?.let { return it.toString() }
        }
        return w
    }

    /** Deux mots se ressemblent : identiques, ou assez proches pour une erreur de reconnaissance. */
    fun similar(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.first().isDigit() || b.first().isDigit()) return false
        val longest = maxOf(a.length, b.length)
        // Mots courts (« es », « les », « tes ») : identiques seulement, sinon presque tout se ressemble.
        if (longest <= 3) return false
        if (minOf(a.length, b.length) >= 4 && a.take(4) == b.take(4)) return true
        return distance(a, b) <= if (longest <= 5) 1 else 2
    }

    private fun distance(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            cur.copyInto(prev)
        }
        return prev[b.length]
    }

    /** Part des mots entendus qui ressemblent à un mot de l'une des phrases de l'ingénieur (0 à 1). */
    fun echoShare(heard: String, engineer: Collection<String>): Double {
        val h = tokens(heard).filter { it !in FUNCTION_WORDS }
        if (h.isEmpty()) return 0.0
        val e = engineer.flatMap(::tokens).toSet()
        if (e.isEmpty()) return 0.0
        return h.count { w -> e.any { similar(w, it) } }.toDouble() / h.size
    }

    /** Écho probable : au moins la moitié des mots entendus viennent de ce que l'ingénieur vient de dire. */
    fun isEcho(heard: String, engineer: Collection<String>): Boolean {
        // Une question du pilote (« combien de tours ? ») n'est jamais l'écho d'une annonce.
        val n = DriverCommandParser.normalize(heard)
        if (n.trimEnd().endsWith("?") || QUESTION.containsMatchIn(n.replace("[unk]", "").trim())) return false
        return echoShare(heard, engineer) >= ECHO_SHARE
    }

    /** Mots entendus qui ne viennent pas de l'ingénieur. */
    fun foreign(heard: String, engineer: Collection<String>): List<String> {
        val e = engineer.flatMap(::tokens).toSet()
        return tokens(heard).filter { w -> w !in FUNCTION_WORDS && e.none { similar(w, it) } }
    }

    const val ECHO_SHARE = 0.5
}

/**
 * Une phrase du pilote peut-elle être l'écho de l'ingénieur ? Revue de la PR #46 : le calcul utilisait le début d'une
 * phrase précédente (« ancien début à 8 s, fin de l'ingénieur à 10 s, nouvelle demande à 30 s » passait pour un écho).
 * Seul le début de la phrase EN COURS compte ; sans début connu (Google, secours), l'heure de remise sert de repère.
 * Horloge monotone, en millisecondes.
 */
object EchoTiming {
    /** Un début de phrase plus ancien n'appartient pas à la phrase reçue maintenant. */
    const val MAX_PHRASE_MS = 12_000L

    /** Après la fin de la voix de l'ingénieur, sa fin de phrase peut encore revenir par Discord. */
    const val ECHO_TAIL_MS = 2_000L

    /** Sans début connu : temps de reconnaissance ajouté à la traîne d'écho. */
    const val RECOGNITION_MS = 3_000L

    fun near(
        nowMs: Long,
        phraseStartMs: Long?,
        engineerSpeaking: Boolean,
        bargedIn: Boolean,
        engineerStartMs: Long?,
        engineerEndMs: Long?,
    ): Boolean {
        if (engineerSpeaking || bargedIn) return true
        val start = engineerStartMs ?: return false
        val end = engineerEndMs?.takeIf { it >= start } ?: return false
        val phrase = phraseStartMs?.takeIf { it <= nowMs && nowMs - it <= MAX_PHRASE_MS }
        return if (phrase != null) {
            phrase >= start && phrase <= end + ECHO_TAIL_MS
        } else {
            nowMs - end in 0..(ECHO_TAIL_MS + RECOGNITION_MS)
        }
    }
}

/** Code d'état de la radio pour le journal (issue #45) : distinguer une fermeture normale d'une panne. */
object RadioStatusCode {
    fun of(diagnostic: String): String {
        val t = DriverCommandParser.normalize(diagnostic)
        return when {
            t.startsWith("fausse_interruption") -> "FAUSSE_INTERRUPTION"
            "demarrage impossible" in t -> "ECOUTE_INDISPONIBLE"
            // L'action de la surveillance d'abord : une écoute relancée pour un micro muet reste une relance.
            "relancee" in t -> "ECOUTE_RELANCEE"
            "micro repris" in t -> "MICRO_REPRIS"
            "reprise" in t -> "ECOUTE_REPRISE"
            "muet" in t || "que des zeros" in t || "aucun son" in t -> "MICRO_MUET"
            else -> "ECOUTE_SURVEILLANCE"
        }
    }
}
