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
 * « Si je suis en galère d'essence, tu vas m'aider ? », « pendant les courses tu vas m'aider comment ? » : le pilote
 * demande ce que l'ingénieur fera pour lui, sur un sujet, pas la valeur du moment. Répondre « je n'ai pas de
 * télémétrie » serait à côté de la question.
 */
enum class HelpTopic(val query: com.gt7pitwall.core.radio.DriverQuery?) {
    FUEL(com.gt7pitwall.core.radio.DriverQuery.FUEL),
    TYRES(com.gt7pitwall.core.radio.DriverQuery.TYRE_CHANGE),
    STOPS(com.gt7pitwall.core.radio.DriverQuery.PLAN),
    RULES(null),
    POSITION(null),
    DRIVING(null),
    RACE(null),
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
        "penalite", "drapeau", "rentrer", "rentre aux", "rentrent", "prolonger", "relais",
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

    /** Demande d'aide future ou hypothétique (« tu vas m'aider ? », « si je… tu… ? »), pas une demande immédiate. */
    private val HELP = Regex(
        "\\btu (vas|va|pourras|pourrais|sauras|saurais|peux|pourrait) (m'aider|m'aide|me venir en aide|me prevenir|m'avertir|me sauver|faire quoi|faire comment)" +
            "|\\btu m'aideras|\\btu me previendras|\\btu m'avertiras|\\btu me diras quoi|\\btu es capable|\\bt'es capable" +
            "|\\bcomment (tu vas|tu peux|est ce que tu vas|est ce que tu peux) m'aider|\\btu (vas|peux) m'aider comment" +
            "|\\ben quoi tu (vas|peux) m'aider|\\btu sers a quoi|\\btu fais quoi (pendant|en|quand)",
    )

    /** Demande d'un conseil maintenant (« tu peux m'aider à mieux freiner ? ») : c'est du coaching, pas une présentation. */
    private val ASK_ADVICE = Regex("m'aider a (mieux |etre |aller |gagner |ameliorer |progresser |freiner |passer |prendre |sortir |gerer )")

    /** Sujet d'une question sur l'aide de l'ingénieur, ou null si ce n'en est pas une. */
    fun helpTopic(text: String): HelpTopic? {
        val t = clean(text)
        val hypothetical = Regex("^(et )?si (je|j'|on|il|y a)").containsMatchIn(t) && Regex("\\btu\\b").containsMatchIn(t)
        if (!HELP.containsMatchIn(t) && !hypothetical) return null
        if (ASK_ADVICE.containsMatchIn(t)) return null
        val split = " " + t.replace('\'', ' ') + " "
        fun has(vararg w: String) = w.any { " $it" in split }
        return when {
            has("essence", "carburant", "conso", "consommation", "litre", "litres", "panne") -> HelpTopic.FUEL
            has("pneu", "pneus", "gomme", "gommes", "usure") -> HelpTopic.TYRES
            has("arret", "arrets", "stand", "stands", "box", "plein", "ravitaill") -> HelpTopic.STOPS
            has("reglement", "obligatoire", "obligation", "penalite") -> HelpTopic.RULES
            has("position", "classement", "place", "depasse", "double") -> HelpTopic.POSITION
            COACHING_WORDS.any { " $it" in split } -> HelpTopic.DRIVING
            else -> HelpTopic.RACE
        }
    }

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
        Intent.PRESENCE -> if (telemetryLive) "Toujours là, je te reçois." else "Toujours là. $NO_TELEMETRY"
        Intent.GREETING -> if (telemetryLive) "Salut ! Je suis avec toi, je surveille la course." else "Salut ! Je suis là. $NO_TELEMETRY"
        Intent.THANKS -> "Avec plaisir."
        Intent.ACKNOWLEDGE -> "Parfait, on continue."
        Intent.CAPABILITIES ->
            "Je suis ton ingénieur : je lis la télémétrie de GT7, je calcule l'essence, les pneus et les arrêts selon " +
                "le règlement, et je réponds à tes questions de pilotage ou de stratégie. Dis « radio » puis ta question." +
                if (telemetryLive) "" else " $NO_TELEMETRY"
        else -> error("Pas de réponse locale pour $intent")
    }

    /** Ce que l'ingénieur fera pour le pilote sur ce sujet : vrai, sans aucune donnée de course. */
    fun help(topic: HelpTopic, telemetryLive: Boolean): String {
        val text = when (topic) {
            HelpTopic.FUEL ->
                "Oui, compte sur moi. Je mesure ta consommation à chaque tour : je te dis pour combien de tours il te reste " +
                    "de l'essence, et je te préviens s'il faut économiser ou rentrer au stand."
            HelpTopic.TYRES ->
                "Oui. Je suis la température de tes pneus et les tours faits avec le train actuel ; avec la durée de vie que " +
                    "tu m'indiques, je te dis quand les changer."
            HelpTopic.STOPS ->
                "Oui. Avec le règlement et ta consommation, je calcule quand t'arrêter et combien d'essence remettre, et je " +
                    "recalcule après chaque arrêt."
            HelpTopic.RULES ->
                "Oui : donne-moi le règlement, par exemple « course de 12 tours » ou « arrêt obligatoire », et je l'applique " +
                    "à toute la stratégie."
            HelpTopic.POSITION ->
                "GT7 ne me transmet pas les positions en course : annonce-moi la tienne, « je suis cinquième », et je la " +
                    "suivrai tour après tour."
            HelpTopic.DRIVING ->
                "Oui. Avec la télémétrie, je repère les blocages de roue, les patinages, ta régularité et le secteur où tu " +
                    "perds du temps, et je te donne un geste précis à travailler."
            HelpTopic.RACE ->
                "Pendant la course, je te donne à chaque tour ton chrono, l'essence qui reste et le plan d'arrêt ; je te " +
                    "préviens si l'essence ou les pneus deviennent critiques, et tu peux me poser tes questions à la radio."
        }
        return if (telemetryLive) text else "$text $NO_TELEMETRY"
    }

    /** Question de stratégie libre sans télémétrie : rien à calculer, on le dit sans inventer. */
    const val NO_RACE_DATA =
        "Je n'ai aucune donnée de course pour l'instant : pas de télémétrie GT7. Dès qu'elle arrive, je te donne " +
            "l'essence, les pneus et les arrêts."
}
