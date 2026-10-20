package com.gt7pitwall.core.radio

/**
 * Ce que le pilote fait d'une phrase, indépendamment des mots-clés : un fait accompli, une négation, une intention,
 * une question… L'analyseur trouve le sujet (« arrêt », « pneus », « obligatoire ») ; le garde trouve la forme.
 */
object StatementGuard {
    enum class Mode {
        /** « J'ai fait mon arrêt. » */
        DONE,

        /** « Je n'ai pas fait mon arrêt », « pas encore ». */
        NEGATED,

        /** « Je vais faire mon arrêt », « je pense rentrer ». */
        FUTURE,

        /** « Je veux prolonger mon relais. » */
        WISH,

        /** « Est-ce qu'on doit rentrer ? » — avec ou sans point d'interrogation. */
        QUESTION,

        /** « Si je rentre maintenant… » */
        HYPOTHETICAL,

        /** « Celui devant moi a fait son arrêt. » */
        ABOUT_OTHERS,

        /** « Je suis peut-être sixième. » */
        UNCERTAIN,
    }

    private val HYPOTHETICAL = Regex(
        "^(et |mais |alors |bon |ok |donc )?(si |s'il |s'ils |s'on |imaginons|imagine |suppose|supposons|au cas ou)" +
            "| et si | au cas ou | si (je|j'|on|il|ils|elle|elles|le|la|les|ceux|celui|tu|ca|y a|jamais) ",
    )

    private val QUESTION_START = Regex(
        "^(et |mais |alors |bon |ok |donc |dis moi |dis )?(est ce que|est ce qu'|est ce|quand|combien|comment|pourquoi|quel|quelle|quels|" +
            "quelles|qu'est|que dois|que faut|ou est|ou en|dois je|doit on|faut il|y a t il|est il|tu crois|tu penses|tu sais|" +
            "tu trouves|on fait quoi|on doit|on devrait|je dois|je devrais|ca vaut|vaut il|c'est mieux|il vaut mieux)( |$)",
    )
    private val QUESTION_ANY = listOf(" on fait quoi", " ou pas ", " ou bien ", " quoi faire", " est ce que ", " est ce qu'", " tu crois ", " tu penses ")

    private val OTHERS = Regex(
        " (ils|elles|celui|celle|ceux|lui|les autres|l'autre|le leader|la tete|l'adversaire|les adversaires|mon adversaire|" +
            "le concurrent|les concurrents|le gars|le mec|la voiture devant|la voiture derriere|la voiture de devant|" +
            "devant moi|derriere moi) " +
            "| il (a|est|va|vient|rentre|s'arrete|passe|s'est|sort|ressort|change|monte|met|fait son|fait sa|fait ses) ",
    )

    private val NEGATION = Regex(" (pas|jamais|aucun|aucune|n'ai|n'a|n'avons|ne|n'est|n'y|nan|negatif) ")

    private val UNCERTAIN = listOf(
        " peut etre ", " je crois ", " il me semble ", " probablement ", " sans doute ", " environ ", " a peu pres ",
        " je sais pas ", " je ne sais pas ", " normalement ", " je pense que je ", " genre ", " surement ", " j'imagine ",
    )

    private val FUTURE = Regex(
        " (je vais|j'vais|vais|on va|tu vas|je compte|je pense|je prevois|je prevoie|j'envisage|je m'apprete|prochain|prochaine|" +
            "tout a l'heure|plus tard|bientot|dans \\d+ tours?|dans (un|une|deux|trois|quatre|cinq) tours?|ferai|fera|ferons|" +
            "mettrai|mettra|rentrerai|rentrera|changerai|changera|m'arreterai|passerai|serai|irai|monterai|partirai|avant de) ",
    )

    private val WISH = listOf(
        " je veux ", " je voudrais ", " j'aimerais ", " je prefere ", " on pourrait ", " je souhaite ", " il faudrait ",
        " faudrait ", " ce serait bien ", " j'ai envie ",
    )

    /** Négations qui font partie du règlement (« pas d'arrêt obligatoire ») : pas une négation de la phrase. */
    private val RULE_NEGATIONS = listOf("pas d'arret obligatoire", "pas d arret obligatoire", "aucun arret obligatoire", "sans arret obligatoire", "pas d'arret")

    fun mode(text: String, ignoreRuleNegations: Boolean = false): Mode {
        val raw = DriverCommandParser.normalize(text)
        val question = raw.trimEnd().endsWith("?")
        var s = raw.replace("[unk]", " ").replace(Regex("[?!.,;:«»\"]"), " ")
        if (ignoreRuleNegations) RULE_NEGATIONS.forEach { s = s.replace(it, " ") }
        val t = " " + s.replace(Regex("\\s+"), " ").trim() + " "
        val trimmed = t.trim()
        return when {
            HYPOTHETICAL.containsMatchIn(trimmed) || HYPOTHETICAL.containsMatchIn(t) -> Mode.HYPOTHETICAL
            question || QUESTION_START.containsMatchIn(trimmed) || QUESTION_ANY.any { it in t } -> Mode.QUESTION
            OTHERS.containsMatchIn(t) -> Mode.ABOUT_OTHERS
            UNCERTAIN.any { it in t } -> Mode.UNCERTAIN
            NEGATION.containsMatchIn(t) -> Mode.NEGATED
            FUTURE.containsMatchIn(t) -> Mode.FUTURE
            WISH.any { it in t } -> Mode.WISH
            else -> Mode.DONE
        }
    }
}

/** D'où vient une commande vocale. */
enum class CommandSource {
    /** Reconnaissance vocale (Vosk ou Google) puis analyseur. */
    VOICE,

    /** Phrase reformulée par Gemini (« COMMANDE: … ») : jamais exécutée sans confirmation du pilote. */
    GEMINI,

    /**
     * Phrase entendue pendant la conversation continue, sans « Radio » devant : elle peut venir du jeu ou d'un autre
     * participant. Une modification de la course attend toujours le « oui » du pilote.
     */
    CONVERSATION,
}

/** Ce que l'on sait de la course au moment de la phrase. */
data class GateContext(
    val telemetryLive: Boolean,
    /** Dernier arrêt déclaré par le pilote (et non détecté), récent : une négation peut vouloir le retirer. */
    val recentDeclaredStop: Int? = null,
    /** L'ingénieur vient de demander « As-tu changé les pneus ? ». */
    val awaitingTyreAnswer: Boolean = false,
    /** Aucun arrêt ni changement de pneus depuis le départ : « je suis en durs » déclare les pneus de départ. */
    val noStopYet: Boolean = true,
    /** Position actuellement retenue. */
    val position: Int? = null,
)

/** Décision avant d'appliquer une commande vocale. */
sealed interface Vetting {
    /** Commande claire, contexte valide. */
    data class Execute(val command: DriverCommand) : Vetting

    /** Question sur le sujet : on répond, sans rien modifier. */
    data class Redirect(val command: DriverCommand) : Vetting

    /** Déclaration plausible mais incertaine : « Tu confirmes… ? », « oui » l'applique, « non » l'annule. */
    data class Confirm(val command: DriverCommand, val question: String) : Vetting

    /** Rien n'est modifié : intention, hypothèse, adversaire, négation… [intention] alimente la stratégie. */
    data class Discuss(val mode: StatementGuard.Mode, val reply: String, val intention: String? = null) : Vetting
}

/**
 * Validation commune devant toute commande vocale qui modifie les données de course (arrêts, pneus, règlement,
 * position, météo), quelle que soit la reconnaissance qui l'a produite. Les commandes sans effet sur la course
 * (questions, radio, « répète ») passent. Une commande proposée par Gemini n'est jamais appliquée sans « oui ».
 */
object CommandGate {
    /** Une commande de radio est une phrase courte (« silence », « radio essentielle », « tu peux parler »). */
    private const val RADIO_COMMAND_MAX_WORDS = 4

    private fun sensitive(c: DriverCommand) = when (c) {
        DriverCommand.DeclareStop, is DriverCommand.StopAndTyres, DriverCommand.CancelStop, is DriverCommand.Tyres,
        DriverCommand.TyresChanged, is DriverCommand.Rule, is DriverCommand.ReportPosition, DriverCommand.ReportLast,
        DriverCommand.Overtook, DriverCommand.Overtaken, is DriverCommand.SeveralPlaces, DriverCommand.Rain, DriverCommand.Dry,
        -> true
        else -> false
    }

    /** Formulation explicite d'un arrêt fait (« j'ai fait », « arrêt fait », « je suis passé »…). */
    private val EXPLICIT_STOP = Regex(
        "(^|\\s)(j'ai|jai|je suis|je me suis|je sors|je sort|on a|c'est fait|arret fait|arrets faits|plein fait|stop fait|ravitaille)($|\\s)",
    )

    /** Règlement dicté en tête de phrase (« course de 12 tours », « 2 arrêts obligatoires »). */
    private val DIRECT_RULE = Regex(
        "^(la |une |les |le |l')?(course|consommation|essence|carburant|usure|pneus?|gommes?|arrets?|\\d+|un|une|deux|trois|quatre|cinq|six|pas d'arret|" +
            "aucun arret|relais)( |$)",
    )

    fun vet(command: DriverCommand, text: String, source: CommandSource, ctx: GateContext): Vetting {
        // « Silence », « radio essentielle », « tu peux parler » : jamais tirés d'une question ou d'une phrase mal reconnue
        // (essai réel : « est-ce que je suis premier » entendu « est ce que je silence tu premier »).
        if (command == DriverCommand.Quiet || command == DriverCommand.Essential || command == DriverCommand.Talk) {
            val mode = StatementGuard.mode(text)
            val t = DriverCommandParser.normalize(text)
            if (mode == StatementGuard.Mode.QUESTION || mode == StatementGuard.Mode.HYPOTHETICAL || "[unk]" in t ||
                t.split(' ').size > RADIO_COMMAND_MAX_WORDS
            ) {
                return Vetting.Discuss(mode, "Je garde la radio comme elle est. Dis simplement « silence » ou « tu peux parler ».")
            }
            return Vetting.Execute(command)
        }
        if (!sensitive(command)) return Vetting.Execute(command)
        val mode = StatementGuard.mode(text, ignoreRuleNegations = command is DriverCommand.Rule || command == DriverCommand.CancelStop)
        val t = DriverCommandParser.normalize(text)
        val garbled = "[unk]" in t
        val v = when (command) {
            DriverCommand.DeclareStop, is DriverCommand.StopAndTyres -> stop(command, mode, t, garbled, ctx)
            DriverCommand.CancelStop -> when (mode) {
                StatementGuard.Mode.QUESTION, StatementGuard.Mode.HYPOTHETICAL, StatementGuard.Mode.FUTURE, StatementGuard.Mode.WISH,
                StatementGuard.Mode.ABOUT_OTHERS,
                -> Vetting.Discuss(mode, "D'accord, je ne retire aucun arrêt.")
                else -> Vetting.Execute(command)
            }
            is DriverCommand.Tyres, DriverCommand.TyresChanged -> tyres(command, mode, garbled, ctx)
            is DriverCommand.Rule -> rule(command, mode, t, garbled)
            is DriverCommand.ReportPosition, DriverCommand.ReportLast -> position(command, mode)
            DriverCommand.Rain, DriverCommand.Dry -> when (mode) {
                // « Il pleut » : un fait. « Il va pleuvoir », « il pleut pas », « s'il pleut » : rien ne change.
                StatementGuard.Mode.DONE -> Vetting.Execute(command)
                StatementGuard.Mode.UNCERTAIN -> Vetting.Confirm(command, confirmation(command))
                else -> Vetting.Discuss(mode, "D'accord, je ne change pas la météo.")
            }
            else -> when (mode) {
                // Dépassements : « ils m'ont doublé » parle des autres mais concerne ta place.
                StatementGuard.Mode.DONE, StatementGuard.Mode.ABOUT_OTHERS -> Vetting.Execute(command)
                else -> Vetting.Discuss(mode, "D'accord, je garde ta position actuelle.")
            }
        }
        // Gemini ne fait que reformuler : une modification proposée par lui attend toujours le « oui » du pilote.
        // Pneus de départ déclarés pendant la conversation : pas une intervention de course, aucune confirmation de plus.
        val startTyres = command is DriverCommand.Tyres && (command.atStart || command.current) && ctx.noStopYet
        if (source == CommandSource.CONVERSATION && startTyres && v is Vetting.Execute) return v
        if ((source == CommandSource.GEMINI || source == CommandSource.CONVERSATION) && v is Vetting.Execute) {
            return Vetting.Confirm(command, confirmation(command))
        }
        return v
    }

    private fun stop(command: DriverCommand, mode: StatementGuard.Mode, t: String, garbled: Boolean, ctx: GateContext): Vetting = when (mode) {
        StatementGuard.Mode.QUESTION -> Vetting.Redirect(DriverCommand.Ask(DriverQuery.PLAN))
        StatementGuard.Mode.FUTURE, StatementGuard.Mode.WISH -> Vetting.Discuss(
            mode,
            "Compris, tu envisages de rentrer : je n'enregistre rien tant que ce n'est pas fait. Dis « arrêt fait » une fois l'arrêt effectué.",
            intention = "le pilote envisage de rentrer aux stands",
        )
        StatementGuard.Mode.HYPOTHETICAL -> Vetting.Discuss(mode, "C'est une hypothèse : je ne change rien à la stratégie.")
        StatementGuard.Mode.ABOUT_OTHERS -> Vetting.Discuss(
            mode,
            "Noté pour l'adversaire. GT7 ne me transmet pas ses arrêts : je ne change rien de notre côté.",
            intention = "un adversaire serait passé aux stands (déclaration du pilote)",
        )
        StatementGuard.Mode.NEGATED -> ctx.recentDeclaredStop?.let {
            Vetting.Confirm(DriverCommand.CancelStop, "Tu veux que je retire l'arrêt $it ?")
        } ?: Vetting.Discuss(mode, "D'accord, je n'enregistre aucun arrêt.")
        StatementGuard.Mode.UNCERTAIN -> Vetting.Confirm(command, "Tu confirmes que tu as effectué ton arrêt ?")
        StatementGuard.Mode.DONE -> when {
            // Sans télémétrie, aucune course n'est suivie : pas d'arrêt réel à enregistrer.
            !ctx.telemetryLive -> Vetting.Discuss(
                mode,
                "Pas de télémétrie : aucune course n'est suivie, je n'enregistre pas d'arrêt réel.",
            )
            garbled || !EXPLICIT_STOP.containsMatchIn(t) -> Vetting.Confirm(command, "Tu confirmes que tu as effectué ton arrêt ?")
            else -> Vetting.Execute(command)
        }
    }

    private fun tyres(command: DriverCommand, mode: StatementGuard.Mode, garbled: Boolean, ctx: GateContext): Vetting {
        val atStart = command is DriverCommand.Tyres && (command.atStart || command.current)
        return when (mode) {
            StatementGuard.Mode.QUESTION -> Vetting.Redirect(DriverCommand.Ask(DriverQuery.TYRE_CHANGE))
            StatementGuard.Mode.FUTURE, StatementGuard.Mode.WISH -> Vetting.Discuss(
                mode,
                "Compris, c'est prévu mais pas encore fait : je ne change rien. Dis-le-moi une fois les pneus montés.",
                intention = "le pilote prévoit de changer de pneus",
            )
            StatementGuard.Mode.HYPOTHETICAL -> Vetting.Discuss(mode, "C'est une hypothèse : je ne change rien aux pneus.")
            StatementGuard.Mode.ABOUT_OTHERS -> Vetting.Discuss(mode, "Noté pour l'adversaire : je ne change rien à tes pneus.")
            // « Non, je n'ai pas changé les pneus » : la réponse à « As-tu changé les pneus ? ».
            StatementGuard.Mode.NEGATED ->
                if (ctx.awaitingTyreAnswer) Vetting.Execute(DriverCommand.No) else Vetting.Discuss(mode, "D'accord, je ne note aucun changement de pneus.")
            StatementGuard.Mode.UNCERTAIN -> Vetting.Confirm(command, confirmation(command))
            StatementGuard.Mode.DONE -> when {
                // Les pneus de départ se déclarent avant la course ; un changement, seulement pendant une course suivie.
                !atStart && !ctx.telemetryLive -> Vetting.Discuss(mode, "Pas de télémétrie : aucune course n'est suivie, je ne change rien aux pneus.")
                garbled -> Vetting.Confirm(command, confirmation(command))
                else -> Vetting.Execute(command)
            }
        }
    }

    private fun rule(command: DriverCommand.Rule, mode: StatementGuard.Mode, t: String, garbled: Boolean): Vetting = when (mode) {
        StatementGuard.Mode.QUESTION -> Vetting.Redirect(DriverCommand.Ask(DriverQuery.OBLIGATIONS))
        StatementGuard.Mode.HYPOTHETICAL, StatementGuard.Mode.ABOUT_OTHERS, StatementGuard.Mode.FUTURE, StatementGuard.Mode.WISH,
        StatementGuard.Mode.NEGATED,
        -> Vetting.Discuss(mode, "Je ne change pas le règlement. Pour le modifier, dicte-le, par exemple « course de 12 tours ».")
        StatementGuard.Mode.UNCERTAIN -> Vetting.Confirm(command, confirmation(command))
        StatementGuard.Mode.DONE ->
            if (!garbled && DIRECT_RULE.containsMatchIn(t)) Vetting.Execute(command) else Vetting.Confirm(command, confirmation(command))
    }

    private fun position(command: DriverCommand, mode: StatementGuard.Mode): Vetting = when (mode) {
        StatementGuard.Mode.DONE -> Vetting.Execute(command)
        StatementGuard.Mode.QUESTION -> Vetting.Redirect(DriverCommand.Ask(DriverQuery.POSITION))
        StatementGuard.Mode.UNCERTAIN -> Vetting.Confirm(command, confirmation(command))
        else -> Vetting.Discuss(mode, "D'accord, je garde ta position actuelle.")
    }

    /** Question de confirmation courte, à dire à la radio. */
    fun confirmation(command: DriverCommand): String = when (command) {
        DriverCommand.DeclareStop, is DriverCommand.StopAndTyres -> "Tu confirmes que tu as effectué ton arrêt ?"
        DriverCommand.CancelStop -> "Tu confirmes que je retire le dernier arrêt ?"
        is DriverCommand.Tyres -> if (command.atStart || command.current) "Tu confirmes tes pneus ?" else "Tu confirmes ce changement de pneus ?"
        DriverCommand.TyresChanged -> "Tu confirmes que tu as changé les pneus ?"
        is DriverCommand.SetLaps -> "Tu confirmes : course de ${command.laps} tours ?"
        is DriverCommand.SetDuration -> "Tu confirmes : course de ${command.minutes} minutes ?"
        is DriverCommand.SetMandatoryStops ->
            if (command.stops == 0) "Tu confirmes : aucun arrêt obligatoire ?"
            else "Tu confirmes : ${command.stops} arrêt${if (command.stops > 1) "s" else ""} obligatoire${if (command.stops > 1) "s" else ""} ?"
        is DriverCommand.SetFuelMultiplier -> "Tu confirmes : consommation fois ${command.factor} ?"
        is DriverCommand.SetTyreMultiplier -> "Tu confirmes : usure fois ${command.factor} ?"
        is DriverCommand.SetTyreStint -> "Tu confirmes : les pneus tiennent ${command.laps} tours ?"
        is DriverCommand.ReportPosition -> "Tu es P${command.position}, je le note ?"
        DriverCommand.ReportLast -> "Tu es dernier, je le note ?"
        DriverCommand.Rain -> "Tu confirmes qu'il pleut ?"
        DriverCommand.Dry -> "Tu confirmes que la piste est sèche ?"
        else -> "Tu confirmes ?"
    }
}
