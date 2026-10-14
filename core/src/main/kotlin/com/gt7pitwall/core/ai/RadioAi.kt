package com.gt7pitwall.core.ai

import com.gt7pitwall.core.radio.DriverCommand
import com.gt7pitwall.core.radio.DriverCommandParser

/**
 * Faits que l'IA a le droit d'utiliser : phrases déjà produites par l'ingénieur à partir de la télémétrie GT7,
 * des mesures, du règlement et de ce que le pilote a déclaré. Rien d'autre.
 */
data class AiFacts(
    val lines: List<String>,
    /** Constats de pilotage prouvés par la télémétrie (voir [CoachingObservations]). */
    val observations: List<Observation> = emptyList(),
) {
    val text: String get() = lines.joinToString("\n") { "- $it" }

    /** Preuves disponibles : constats, plus les blocages / patinages lus dans les tours mesurés. */
    val evidence: Set<Evidence>
        get() = observations.map { it.evidence }.toSet() + lines.flatMap { line ->
            listOfNotNull(
                Evidence.LOCKUPS.takeIf { Regex("(^|\\D)([1-9]\\d*) blocages? de roue").containsMatchIn(line) },
                Evidence.WHEELSPIN.takeIf { Regex("(^|\\D)([1-9]\\d*) patinages?").containsMatchIn(line) },
            )
        }
}

/** Ce qu'on fait de la réponse de l'IA. */
sealed interface AiReply {
    /** L'IA a reconnu une commande : elle est rejouée par l'analyseur habituel (mêmes règles que la voix). */
    data class Command(val phrase: String, val command: DriverCommand) : AiReply

    /** Réponse vérifiée : chaque chiffre figure dans les faits ou dans la question. */
    data class Answer(val text: String) : AiReply

    /** L'IA ne sait pas, ou sa réponse n'est pas sûre : on le dit franchement. */
    data class Rejected(val reason: Reason) : AiReply

    enum class Reason { UNKNOWN, INVENTED_NUMBER, NOT_TRANSMITTED, NONSENSE, BANALITY, UNPROVEN_DEFECT, BAD_COMMAND, EMPTY }
}

/**
 * Radio « intelligente » : comprend les phrases que l'analyseur à mots-clés ne connaît pas et répond aux
 * questions libres. Le modèle (Gemini Nano sur le téléphone) ne décide de rien : il traduit une phrase en une
 * commande connue, ou reformule des faits. Garde-fou : une réponse contenant un chiffre absent des faits est rejetée.
 */
object RadioAi {
    /** Où envoyer une phrase du pilote. */
    sealed interface Route {
        /** Analyseur à mots-clés : commandes et questions simples, immédiat. */
        data class Parser(val text: String) : Route

        /** Gemini : question ouverte ou phrase que la reconnaissance radio n'a pas suivie. */
        data class Ai(val question: String) : Route

        /** Bout de phrase capté par le micro ouvert (« et », « euh ») : ignoré. */
        data object Ignore : Route
    }

    /** Questions ouvertes : conseils, analyses, résumés — le terrain de l'IA, pas des mots-clés. */
    private val OPEN_QUESTION = Regex(
        "(^|\\s)(comment|pourquoi|conseils?|ameliorer|progresser|analyse|analyser|resume|resumer|explique|expliquer|aide|" +
            "point faible|points faibles|que dois|qu'est ce|quoi faire|tu penses|ton avis|astuces?|mieux|plus vite|plus rapide)(\\s|$)",
    )

    /**
     * Aiguillage d'une phrase quand l'IA est prête. [text] vient de la reconnaissance radio (vocabulaire limité :
     * les mots hors vocabulaire sortent en « [unk] ») ; [freeText] de la reconnaissance libre.
     * Les commandes qui changent l'état (règlement, arrêt, pneus, position, oui / non…) restent à l'analyseur.
     */
    fun route(
        text: String,
        freeText: String?,
        inConversation: Boolean = false,
        /** Le contrôleur attend un oui / non (arrêt à confirmer, pneus changés ?). */
        engineerAwaitsAnswer: Boolean = false,
    ): Route {
        val t = DriverCommandParser.normalize(text)
        val unknownWords = Regex("\\[unk\\]").findAll(t).count()
        val clean = t.replace("[unk]", " ").replace(Regex("\\s+"), " ").trim()
        val question = freeText?.let { DriverCommandParser.normalize(it) }?.takeIf { it.isNotBlank() } ?: clean
        // « radio, question : … » force l'IA.
        Regex("^question\\s*:?\\s*").find(question)?.let { m ->
            val rest = question.substring(m.range.last + 1)
            return if (isFragment(rest, inConversation)) Route.Ignore else Route.Ai(rest)
        }
        val cmd = DriverCommandParser.parse(clean)
        // « oui » / « non » : réponse à l'ingénieur s'il a posé une question, sinon à Gemini pendant la conversation.
        val yesNo = cmd == DriverCommand.Yes || cmd == DriverCommand.No
        if (yesNo && inConversation && !engineerAwaitsAnswer) return Route.Ai(question)
        val statefulCommand = cmd != null && cmd !is DriverCommand.Ask && cmd != DriverCommand.Repeat
        if (statefulCommand) return Route.Parser(clean)
        // Bout de phrase (« et », « euh », « je fais comment dans les ») : jamais envoyé à l'IA.
        if (isFragment(question, inConversation)) return if (cmd != null && !inConversation) Route.Parser(clean) else Route.Ignore
        // Conversation en cours avec Gemini : la relance (« et au freinage ? », « pourquoi ? ») lui revient.
        if (inConversation) return Route.Ai(question)
        if (OPEN_QUESTION.containsMatchIn(question)) return Route.Ai(question)
        if (unknownWords >= 2) return Route.Ai(question)
        if (freeText != null && question.split(' ').size > LONG_SENTENCE_WORDS) return Route.Ai(question)
        return if (cmd != null) Route.Parser(clean) else Route.Ai(question)
    }

    private const val LONG_SENTENCE_WORDS = 7

    /** Réponses ou relances d'un seul mot qui ont un sens. */
    private val SHORT_FOLLOW_UPS = setOf(
        "oui", "non", "pourquoi", "comment", "precise", "explique", "detaille", "encore", "ensuite", "merci",
        "ouais", "exact", "jamais", "souvent", "toujours", "parfois", "entree", "sortie", "freinage", "corde", "avant", "arriere",
    )

    /** Mots sans contenu : hésitations, liaisons. */
    private val FILLERS = setOf(
        "euh", "heu", "hum", "hmm", "bah", "ben", "bon", "alors", "donc", "et", "mais", "ou", "ah", "oh", "hein", "voila",
        "la", "quoi", "enfin", "puis", "genre", "tu", "sais", "je", "radio",
    )

    /** Mots qui ne terminent pas une phrase (« et au », « je fais comment dans les ») : phrase coupée. */
    private val DANGLING = setOf(
        "et", "de", "du", "des", "le", "la", "les", "au", "aux", "a", "en", "dans", "sur", "pour", "que", "qui", "mais",
        "ou", "avec", "je", "tu", "il", "mon", "ma", "mes", "ton", "ta", "tes", "un", "une", "par", "sans", "quand", "si",
    )

    /**
     * Fragment à ignorer : déclenchement parasite du micro ouvert, hésitation, phrase coupée. « oui », « non »,
     * « pourquoi » et les relances courtes utiles sont conservés.
     */
    fun isFragment(text: String, inConversation: Boolean = true): Boolean {
        val words = DriverCommandParser.normalize(text).replace("[unk]", " ").replace("?", " ")
            .split(' ', '\'').filter { it.isNotBlank() }
        if (words.isEmpty()) return true
        // Un mot seul n'a de sens qu'en réponse, pendant une conversation.
        if (words.size == 1) return !inConversation || words[0] !in SHORT_FOLLOW_UPS
        if (words.all { it in FILLERS }) return true
        return words.last() in DANGLING
    }

    /** Phrases-types que l'IA peut renvoyer comme commande ; chacune est comprise par [DriverCommandParser]. */
    val COMMAND_EXAMPLES = listOf(
        "course de 12 tours", "course de 30 minutes", "2 arrêts obligatoires", "consommation fois 2", "usure fois 3",
        "les pneus tiennent 8 tours", "j'ai fait un arrêt aux stands", "annule l'arrêt", "j'ai changé les pneus",
        "j'ai mis les durs", "je pars en mediums", "je suis 5e", "j'ai doublé", "je me suis fait doubler",
        "il pleut", "piste sèche", "silence", "radio essentielle", "tu peux parler", "répète",
    )

    const val UNKNOWN = "INCONNU"
    const val COMMAND_PREFIX = "COMMANDE:"
    private const val MAX_ANSWER_CHARS = 400
    private const val MAX_SENTENCES = 3

    val systemInstruction: String = """
        Tu es l'ingénieur de course d'un pilote de Gran Turismo 7, à la radio. Tu tutoies le pilote. Tu es précis,
        naturel et utile, jamais bavard. Réponds en français, en deux ou trois phrases courtes, à dire à voix haute.
        Règles de coaching :
        1. Ne reproche au pilote un défaut que s'il figure dans les CONSTATS (prouvés par la télémétrie) ou s'il l'a
           dit lui-même. Sans preuve, pose une question au lieu de supposer.
        2. Chaque conseil explique le pourquoi et donne un geste concret (où, quand, comment), en t'appuyant sur les
           PRINCIPES DE PILOTAGE fournis. Pas de formule creuse comme « sois fluide » ou « reste concentré ».
        3. Pour approfondir, termine si besoin par UNE question précise (quel virage ? à l'entrée, au point de corde
           ou en sortie ? l'avant ou l'arrière qui glisse ?), puis utilise sa réponse dans les ÉCHANGES PRÉCÉDENTS
           pour aller plus loin, un sujet à la fois.
        4. Tout chiffre cité doit venir des FAITS ou des CONSTATS, écrit en chiffres tel quel. N'invente jamais un
           temps, une position, un écart, un adversaire ni une météo : GT7 ne les transmet pas.
        5. Réponds $UNKNOWN seulement si on te demande une donnée absente (adversaires, positions des autres…).
        Si le pilote annonce quelque chose ou demande un changement (règlement, arrêt, pneus, position, pluie, radio),
        réponds uniquement par une ligne « $COMMAND_PREFIX <phrase> » en reprenant la forme d'un de ces exemples :
        ${COMMAND_EXAMPLES.joinToString(" ; ")}.
    """.trimIndent()

    /** Un échange déjà vérifié, gardé pour les relances. */
    data class Exchange(val question: String, val answer: String)

    fun prompt(question: String, facts: AiFacts, history: List<Exchange> = emptyList()): String = buildString {
        append("FAITS :\n").append(facts.text).append("\n\n")
        append("CONSTATS (prouvés par la télémétrie) :\n")
        if (facts.observations.isEmpty()) {
            append("- Aucun défaut de pilotage prouvé pour l'instant.\n")
        } else {
            facts.observations.forEach { append("- ").append(it.text).append('\n') }
        }
        append("\nPRINCIPES DE PILOTAGE :\n")
        DrivingPrinciples.select(question, history, facts.evidence).forEach { append("- ").append(it.text).append('\n') }
        append('\n')
        if (history.isNotEmpty()) {
            append("ÉCHANGES PRÉCÉDENTS :\n")
            history.forEach { append("- Pilote : ").append(it.question).append("\n  Ingénieur : ").append(it.answer).append('\n') }
            append('\n')
        }
        append("LE PILOTE DIT : « ").append(question.trim()).append(" »\n\nRÉPONSE :")
    }

    fun parse(raw: String?, question: String, facts: AiFacts, history: List<Exchange> = emptyList()): AiReply {
        val text = raw?.trim()?.trim('"', '«', '»', ' ')?.takeIf { it.isNotEmpty() } ?: return AiReply.Rejected(AiReply.Reason.EMPTY)
        if (text.uppercase().startsWith(UNKNOWN)) return AiReply.Rejected(AiReply.Reason.UNKNOWN)

        val commandLine = text.lines().map { it.trim() }.firstOrNull { it.uppercase().startsWith(COMMAND_PREFIX) }
        if (commandLine != null) {
            val phrase = commandLine.substring(COMMAND_PREFIX.length).trim().trim('"', '«', '»', '.', ' ')
            // Les chiffres d'une commande doivent venir du pilote : l'IA ne fixe pas un règlement à sa place.
            if (!numbersAllowed(phrase, question)) return AiReply.Rejected(AiReply.Reason.INVENTED_NUMBER)
            val cmd = DriverCommandParser.parse(phrase) ?: return AiReply.Rejected(AiReply.Reason.BAD_COMMAND)
            return AiReply.Command(phrase, cmd)
        }

        // Contrôle phrase par phrase : une phrase fautive est retirée, le reste est gardé.
        val source = facts.text + "\n" + facts.observations.joinToString("\n") { it.text } + "\n" + question + "\n" +
            history.joinToString("\n") { it.answer }
        val evidence = facts.evidence
        // Seules les affirmations du pilote valent preuve (« l'avant glisse »), pas ses questions (« je bloque ? »).
        val driverSaid = (history.map { it.question } + question).filterNot(::isQuestion).joinToString(" ")
        val sentences = sentences(text.lines().filter { it.isNotBlank() }.joinToString(" ") { it.trim() })
        var firstProblem: AiReply.Reason? = null
        val kept = sentences.filter { sentence ->
            val problem = when {
                !numbersAllowed(sentence, source) -> AiReply.Reason.INVENTED_NUMBER
                talksAboutOthers(sentence) -> AiReply.Reason.NOT_TRANSMITTED
                sentence.uppercase().contains(UNKNOWN) -> AiReply.Reason.UNKNOWN
                else -> when (AdviceCheck.check(sentence, evidence, driverSaid)) {
                    AdviceCheck.Problem.NONSENSE -> AiReply.Reason.NONSENSE
                    AdviceCheck.Problem.BANALITY -> AiReply.Reason.BANALITY
                    AdviceCheck.Problem.UNPROVEN_DEFECT -> AiReply.Reason.UNPROVEN_DEFECT
                    null -> null
                }
            }
            if (problem != null && firstProblem == null) firstProblem = problem
            problem == null
        }
        if (kept.isEmpty()) return AiReply.Rejected(firstProblem ?: AiReply.Reason.EMPTY)
        return AiReply.Answer(shorten(kept))
    }

    /**
     * Chaque nombre du texte (« 8 », « 8,3 », « huit ») doit apparaître dans la source autorisée ; suivi d'une
     * unité (« 3 secondes », « 62 litres »), il doit y apparaître avec la même unité.
     */
    fun numbersAllowed(text: String, source: String): Boolean {
        val t = digitize(text)
        val src = digitize(source)
        val allowed = numbers(src).toSet()
        if (!numbers(t).all { it in allowed }) return false
        val allowedPairs = withUnits(src).toSet()
        return withUnits(t).all { it in allowedPairs }
    }

    private val INTERROGATIVE = Regex("^(est ce|comment|pourquoi|quand|ou est|quel|quelle|quels|quelles|combien|qu'est|que dois|dois je|tu crois|tu penses)")

    /** Question du pilote (et non constat) : elle ne prouve aucun défaut. */
    fun isQuestion(s: String): Boolean {
        val t = DriverCommandParser.normalize(s)
        return s.trim().endsWith("?") || INTERROGATIVE.containsMatchIn(t) || " est ce " in " $t "
    }

    /** GT7 ne transmet rien sur les autres voitures : écarts, avance, retard, adversaires. */
    private val OTHERS = Regex(
        "(^|\\W)(ecarts?|avance|retard|adversaires?|concurrents?|devant toi|derriere toi|voiture devant|voiture derriere|" +
            "le leader|en tete)(\\W|$)",
    )

    private fun talksAboutOthers(answer: String): Boolean = OTHERS.containsMatchIn(DriverCommandParser.normalize(answer))

    private val UNITS = mapOf(
        "seconde" to "s", "secondes" to "s", "centieme" to "c", "centiemes" to "c", "minute" to "min", "minutes" to "min",
        "tour" to "tour", "tours" to "tour", "litre" to "l", "litres" to "l", "km/h" to "kmh", "degre" to "deg",
        "degres" to "deg", "%" to "pct", "arret" to "arret", "arrets" to "arret",
        "position" to "pos", "positions" to "pos", "place" to "pos", "places" to "pos", "blocage" to "bloc", "blocages" to "bloc", "patinage" to "pat", "patinages" to "pat",
    )

    private fun withUnits(s: String): List<String> {
        val t = s.replace("%", " % ")
        return Regex("(\\d+(?:[.,]\\d+)?)\\s+(\\S+)").findAll(t).mapNotNull { m ->
            val unit = UNITS[m.groupValues[2].trimEnd('.', ',', ';', '!', '?')] ?: return@mapNotNull null
            "${canonical(m.groupValues[1])} $unit"
        }.toList()
    }

    private fun canonical(n: String): String {
        val v = n.replace(',', '.')
        val int = v.substringBefore('.').trimStart('0').ifEmpty { "0" }
        return if ('.' in v) "$int.${v.substringAfter('.')}" else int
    }

    private val NUMBER = Regex("\\d+(?:[.,]\\d+)?")

    /**
     * Nombres et rangs écrits en toutes lettres, sans accents (« un », « une » et « premier » sont aussi des articles
     * ou des mots courants : ignorés).
     */
    private val WORDS: Map<String, Int> = run {
        val base = listOf(
            "deux" to 2, "trois" to 3, "quatre" to 4, "cinq" to 5, "six" to 6, "sept" to 7, "huit" to 8, "neuf" to 9,
            "dix" to 10, "onze" to 11, "douze" to 12, "treize" to 13, "quatorze" to 14, "quinze" to 15, "seize" to 16,
            "vingt" to 20, "trente" to 30, "quarante" to 40, "cinquante" to 50, "soixante" to 60, "cent" to 100,
        )
        base.toMap()
    }

    /** Rangs (« quatrième ») : une position, que GT7 ne transmet pas en course. */
    private val ORDINALS: Map<String, Int> = WORDS.entries.associate { (w, n) ->
        w.removeSuffix("e").replace("cinq", "cinqu").replace("neuf", "neuv") + "ieme" to n
    } + ("deuxieme" to 2)

    /** Texte sans accents, nombres en lettres remplacés par des chiffres (« huit tours » → « 8 tours »). */
    private fun digitize(s: String): String =
        // « deuxième secteur », « troisième tour » : un rang qui n'est pas une position.
        Regex("(\\p{L}+)(?=\\s+(secteurs?|tours?|virages?|arrets?|relais|parties?|phrases?|freinages?|chicanes?|lignes?|droites?)(\\W|$))")
            .replace(DriverCommandParser.normalize(s)) { m -> ORDINALS[m.value]?.toString() ?: m.value }
            .let { t -> Regex("\\p{L}+").replace(t) { m ->
            WORDS[m.value]?.toString() ?: ORDINALS[m.value]?.let { "$it position" } ?: m.value
        } }
            .replace(Regex("(^|\\W)p ?(\\d{1,2})(?=\\W|$)"), "$1$2 position")
            .replace(Regex("(^|\\W)(\\d{1,2})(e|eme|er|ere)(?=\\W|$)"), "$1$2 position")

    // 8.3 et 8,3 sont le même nombre ; 08 et 8 aussi.
    private fun numbers(s: String): List<String> = NUMBER.findAll(s).map { canonical(it.value) }.toList()

    private fun sentences(s: String): List<String> = Regex("(?<=[.!?])\\s+").split(s).map { it.trim() }.filter { it.isNotBlank() }

    /** Trois phrases au plus, pour la radio. */
    private fun shorten(sentences: List<String>): String {
        var out = sentences.take(MAX_SENTENCES).joinToString(" ")
        if (out.length > MAX_ANSWER_CHARS) out = out.take(MAX_ANSWER_CHARS).substringBeforeLast(' ') + "…"
        return out
    }
}

/** Modèle de langue utilisé par la radio (Gemini Nano sur le téléphone). Aucune dépendance Android ici. */
interface LanguageModel {
    /** Prêt à répondre tout de suite (modèle présent sur l'appareil). */
    val ready: Boolean

    /** État lisible (« prêt », « indisponible sur cet appareil »…), dit au pilote si l'IA n'est pas prête. */
    val status: String

    /** Texte généré, ou null en cas d'échec ou de dépassement de délai. */
    suspend fun generate(systemInstruction: String, prompt: String): String?
}
