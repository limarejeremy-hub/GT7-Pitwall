package com.gt7pitwall.core.ai

import com.gt7pitwall.core.radio.DriverCommandParser

/** Ce que le pilote cherche à faire en parlant à la radio. */
enum class Intent {
    /** « Tu m'entends ? », « test radio » : vérifier la liaison. */
    RADIO_CHECK,

    /** « Tu es là ? » */
    PRESENCE,

    /** « Salut », « bonjour ». */
    GREETING,

    /** « Merci », « c'est gentil ». */
    THANKS,

    /** « Reçu », « c'est noté », « parfait ». */
    ACKNOWLEDGE,

    /** « Qu'est-ce que tu sais faire ? », « comment tu fonctionnes ? ». */
    CAPABILITIES,

    /** Pilotage : freinage, trajectoire, régularité, adhérence, accélération, sous-virage… */
    COACHING,

    /** Carburant, pneus, arrêts, règlement, trafic, positions, déroulé de la course. */
    STRATEGY,

    /** Rien de reconnaissable : conversation libre, ou question à préciser. */
    OTHER,
}

/**
 * Comprendre l'intention avant de répondre. Les sujets précis (pilotage, stratégie) passent avant les formules de
 * conversation : « tu comprends mon problème de sous-virage ? » est une question de pilotage, pas un test radio.
 * Les formules de conversation doivent couvrir toute la phrase (aux mots de remplissage près), jamais un morceau.
 */
object IntentClassifier {
    private val COACHING_WORDS = DrivingPrinciples.Topic.entries
        .filter { it != DrivingPrinciples.Topic.TYRES }
        .flatMap { it.keywords } + listOf(
        "rapide", "plus vite", "pilotage", "piloter", "pilote mieux", "conduite", "conduire", "adherence", "grip",
        "glisse", "perds du temps", "perd du temps", "gagner du temps", "temps au tour", "chrono", "chronos",
        "vibreur", "epingle", "dosage", "pedale", "volant", "braquage", "contre braque", "rapports", "boite",
    )

    private val STRATEGY_WORDS = listOf(
        "essence", "carburant", "litre", "litres", "conso", "consommation", "arret", "arrets", "stand", "stands", "box",
        "plein", "ravitaill", "relais", "strategie", "plan", "reglement", "obligatoire", "obligation", "tours restants",
        "combien de tours", "position", "classement", "place", "trafic", "ma course", "la course", "gomme", "gommes",
        "changer mes pneus", "changer les pneus", "pneus durs", "pneus mediums", "pneus tendres", "usure",
        "penalite", "drapeau",
    )

    /** Mots qui désignent clairement un geste de pilotage, même dans une question qui touche aussi la stratégie. */
    private val GESTURE_WORDS = listOf(
        "frein", "trajectoire", "corde", "apex", "sous vir", "survir", "patin", "bloqu", "accel", "virage",
    )

    private const val FILL = "(?:(?:alors|bon|euh|eh|he|ok|dis|dis moi|ingenieur|radio|bien|moi|donc|la|toujours|encore)\\s+)*"
    private const val END = "(?:\\s+(?:bien|la|moi|ingenieur|radio|toujours|encore|ou pas|oui))*"

    private fun whole(body: String) = Regex("^$FILL(?:$body)$END$")

    private val RADIO_CHECK = listOf(
        whole("(est ce que |est ce qu')?(tu|vous) (m'entends|m'entendez|me recois|me reçois|me captes|me comprends|comprends|m'as entendu|me suis)( bien)?( ce que je (dis|te dis|raconte|viens de dire))?"),
        whole("(est ce que )?(tu|vous) (entends|comprends) (ce que je dis|ce que je te dis|ma voix|bien)"),
        whole("(tu|vous) m'entends? (bien|correctement)?"),
        whole("(test|tests|essai) (radio|micro|micros)( un deux)?"),
        whole("(radio|micro) (test|check|ok)"),
        whole("(un deux )+(test|tests)?( micro)?"),
        whole("est ce que (ca|la radio|le micro) (marche|fonctionne|passe)"),
        whole("(ca|la radio|le micro) (marche|fonctionne|passe)( bien)?"),
        whole("tu recois"),
    )

    private val PRESENCE = listOf(
        whole("(tu es|t'es|es tu|t es) (la|toujours la|encore la|present|avec moi|reveille)"),
        whole("(il )?y a quelqu'un"),
        whole("allo"),
        whole("(tu|vous) (dors|dormez)"),
    )

    private val GREETING = listOf(
        whole("(salut|bonjour|bonsoir|coucou|hello|hey|yo|wesh)( (a toi|toi|l'ingenieur|mon ingenieur|pitwall|ca va|comment ca va|ca roule))?"),
        whole("(ca va|comment ca va|comment tu vas|tu vas bien)"),
    )

    private val THANKS = listOf(
        whole("(merci|merci beaucoup|merci bien|super merci|top merci|parfait merci|nickel merci|cool merci|ok merci|d'accord merci|c'est gentil|thanks)( a toi| pour (ca|l'info|les infos|ton aide))?"),
    )

    private val ACKNOWLEDGE = listOf(
        whole("(super|parfait|nickel|top|genial|bien recu|recu|c'est note|note|compris|bien compris|entendu|ca marche|bien vu)"),
    )

    private val CAPABILITIES = listOf(
        whole("(qu'est ce que|que) (tu sais|sais tu|tu peux|peux tu) faire"),
        whole("(tu sais|tu peux) faire quoi"),
        whole("(a quoi|a quoi est ce que) tu sers"),
        whole("(comment|comment est ce que) tu (fonctionnes|marches)"),
        whole("(qui es tu|tu es qui|t'es qui|qui est la|presente toi|tu fais quoi)"),
        whole("(qu'est ce que|que) (je peux|puis je) te (dire|demander)"),
        whole("(c'est quoi|qu'est ce que c'est) pitwall"),
    )

    fun classify(text: String): Intent {
        val t = clean(text)
        if (t.isEmpty()) return Intent.OTHER
        // « d'essence », « l'arrêt » : le mot compte aussi après une apostrophe.
        val padded = " $t "
        val split = " " + t.replace('\'', ' ') + " "
        fun has(words: List<String>) = words.any { " $it" in padded || " $it" in split }
        val coaching = has(COACHING_WORDS)
        val strategy = has(STRATEGY_WORDS)
        when {
            coaching && !strategy -> return Intent.COACHING
            strategy && !coaching -> return Intent.STRATEGY
            coaching && strategy -> return if (GESTURE_WORDS.any { it in t }) Intent.COACHING else Intent.STRATEGY
        }
        return when {
            RADIO_CHECK.any { it.matches(t) } -> Intent.RADIO_CHECK
            PRESENCE.any { it.matches(t) } -> Intent.PRESENCE
            GREETING.any { it.matches(t) } -> Intent.GREETING
            THANKS.any { it.matches(t) } -> Intent.THANKS
            ACKNOWLEDGE.any { it.matches(t) } -> Intent.ACKNOWLEDGE
            CAPABILITIES.any { it.matches(t) } -> Intent.CAPABILITIES
            else -> Intent.OTHER
        }
    }

    /** Intentions auxquelles l'ingénieur répond lui-même, tout de suite, sans Gemini. */
    val LOCAL = setOf(Intent.RADIO_CHECK, Intent.PRESENCE, Intent.GREETING, Intent.THANKS, Intent.ACKNOWLEDGE, Intent.CAPABILITIES)

    fun clean(text: String): String =
        DriverCommandParser.normalize(text)
            .replace("[unk]", " ")
            .replace(Regex("[?!.,;:«»\"]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}

/**
 * Réponses de l'ingénieur aux messages simples : immédiates, naturelles, et honnêtes sur ce qu'il sait. Sans
 * télémétrie, il le dit, sans inventer de tour, de position ni de performance.
 */
object LocalReplies {
    const val NO_TELEMETRY = "Pas de télémétrie pour le moment, mais je suis prêt."

    fun text(intent: Intent, telemetryLive: Boolean): String = when (intent) {
        Intent.RADIO_CHECK ->
            if (telemetryLive) "Affirmatif, je te reçois cinq sur cinq !" else "Affirmatif, je te reçois cinq sur cinq ! $NO_TELEMETRY"
        Intent.PRESENCE -> if (telemetryLive) "Toujours là, je t'écoute." else "Toujours là. $NO_TELEMETRY"
        Intent.GREETING -> if (telemetryLive) "Salut ! Je suis avec toi, je surveille la course." else "Salut ! Je suis là. $NO_TELEMETRY"
        Intent.THANKS -> "Avec plaisir."
        Intent.ACKNOWLEDGE -> "Parfait, on continue."
        Intent.CAPABILITIES ->
            "Je suis ton ingénieur : je lis la télémétrie de GT7, je calcule l'essence, les pneus et les arrêts selon " +
                "le règlement, et je réponds à tes questions de pilotage ou de stratégie. Dis « radio » puis ta question." +
                if (telemetryLive) "" else " $NO_TELEMETRY"
        else -> error("Pas de réponse locale pour $intent")
    }

    /** Question de stratégie libre sans télémétrie : rien à calculer, on le dit sans inventer. */
    const val NO_RACE_DATA =
        "Je n'ai aucune donnée de course pour l'instant : pas de télémétrie GT7. Dès qu'elle arrive, je te donne " +
            "l'essence, les pneus et les arrêts."
}
