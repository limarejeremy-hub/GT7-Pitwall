package com.gt7pitwall.core.ai

import com.gt7pitwall.core.radio.DriverCommand
import com.gt7pitwall.core.radio.DriverCommandParser

/**
 * Faits que l'IA a le droit d'utiliser : phrases déjà produites par l'ingénieur à partir de la télémétrie GT7,
 * des mesures, du règlement et de ce que le pilote a déclaré. Rien d'autre.
 */
data class AiFacts(val lines: List<String>) {
    val text: String get() = lines.joinToString("\n") { "- $it" }
}

/** Ce qu'on fait de la réponse de l'IA. */
sealed interface AiReply {
    /** L'IA a reconnu une commande : elle est rejouée par l'analyseur habituel (mêmes règles que la voix). */
    data class Command(val phrase: String, val command: DriverCommand) : AiReply

    /** Réponse vérifiée : chaque chiffre figure dans les faits ou dans la question. */
    data class Answer(val text: String) : AiReply

    /** L'IA ne sait pas, ou sa réponse n'est pas sûre : on le dit franchement. */
    data class Rejected(val reason: Reason) : AiReply

    enum class Reason { UNKNOWN, INVENTED_NUMBER, NOT_TRANSMITTED, BAD_COMMAND, EMPTY }
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
    fun route(text: String, freeText: String?): Route {
        val t = DriverCommandParser.normalize(text)
        val unknownWords = Regex("\\[unk\\]").findAll(t).count()
        val clean = t.replace("[unk]", " ").replace(Regex("\\s+"), " ").trim()
        val question = freeText?.let { DriverCommandParser.normalize(it) }?.takeIf { it.isNotBlank() } ?: clean
        // « radio, question : … » force l'IA.
        Regex("^question\\s*:?\\s*").find(question)?.let { return Route.Ai(question.substring(it.range.last + 1).ifBlank { question }) }
        val cmd = DriverCommandParser.parse(clean)
        val statefulCommand = cmd != null && cmd !is DriverCommand.Ask && cmd != DriverCommand.Repeat
        if (statefulCommand) return Route.Parser(clean)
        if (OPEN_QUESTION.containsMatchIn(question)) return Route.Ai(question)
        if (unknownWords >= 2) return Route.Ai(question)
        if (freeText != null && question.split(' ').size > LONG_SENTENCE_WORDS) return Route.Ai(question)
        return if (cmd != null) Route.Parser(clean) else Route.Ai(question)
    }

    private const val LONG_SENTENCE_WORDS = 7

    /** Phrases-types que l'IA peut renvoyer comme commande ; chacune est comprise par [DriverCommandParser]. */
    val COMMAND_EXAMPLES = listOf(
        "course de 12 tours", "course de 30 minutes", "2 arrêts obligatoires", "consommation fois 2", "usure fois 3",
        "les pneus tiennent 8 tours", "j'ai fait un arrêt aux stands", "annule l'arrêt", "j'ai changé les pneus",
        "j'ai mis les durs", "je pars en mediums", "je suis 5e", "j'ai doublé", "je me suis fait doubler",
        "il pleut", "piste sèche", "silence", "radio essentielle", "tu peux parler", "répète",
    )

    const val UNKNOWN = "INCONNU"
    const val COMMAND_PREFIX = "COMMANDE:"
    private const val MAX_ANSWER_CHARS = 280

    val systemInstruction: String = """
        Tu es l'ingénieur de course à la radio d'un pilote de Gran Turismo 7. Tu tutoies le pilote.
        Réponds en français, en une ou deux phrases courtes faites pour être dites à voix haute.
        Utilise UNIQUEMENT les FAITS fournis. N'invente aucun chiffre, aucun temps, aucune position, aucun adversaire,
        aucune météo. Écris les nombres en chiffres, tels qu'ils apparaissent dans les faits.
        Si la réponse n'est pas dans les faits, réponds exactement : $UNKNOWN
        Si le pilote annonce quelque chose ou demande un changement (règlement, arrêt, pneus, position, pluie, radio),
        réponds uniquement par une ligne « $COMMAND_PREFIX <phrase> » en reprenant la forme d'un de ces exemples :
        ${COMMAND_EXAMPLES.joinToString(" ; ")}.
    """.trimIndent()

    fun prompt(question: String, facts: AiFacts): String =
        "FAITS :\n${facts.text}\n\nLE PILOTE DIT : « ${question.trim()} »\n\nRÉPONSE :"

    fun parse(raw: String?, question: String, facts: AiFacts): AiReply {
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

        val answer = shorten(text.lines().filter { it.isNotBlank() }.joinToString(" ") { it.trim() })
        if (!numbersAllowed(answer, facts.text + "\n" + question)) return AiReply.Rejected(AiReply.Reason.INVENTED_NUMBER)
        if (talksAboutOthers(answer)) return AiReply.Rejected(AiReply.Reason.NOT_TRANSMITTED)
        return AiReply.Answer(answer)
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
        "position" to "pos", "blocage" to "bloc", "blocages" to "bloc", "patinage" to "pat", "patinages" to "pat",
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
    } + ("deuxieme" to 2) + ("second" to 2) + ("seconde" to 2)

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

    /** Deux phrases au plus, pour la radio. */
    private fun shorten(s: String): String {
        val sentences = Regex("(?<=[.!?])\\s+").split(s).filter { it.isNotBlank() }
        var out = sentences.take(2).joinToString(" ")
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
