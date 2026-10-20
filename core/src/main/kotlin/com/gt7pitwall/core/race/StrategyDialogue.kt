package com.gt7pitwall.core.race

import com.gt7pitwall.core.radio.DriverCommandParser
import com.gt7pitwall.core.radio.StatementGuard

/**
 * Questions stratégiques et observations du pilote à la radio, reconnues sans IA. Elles sont répondues par le moteur
 * (plan, comparaison des stratégies, carburant) : jamais calculées par Gemini. Rien ici ne modifie la course :
 * arrêts, pneus et règlement restent l'affaire de l'analyseur et du garde des commandes.
 */
object StrategyDialogue {
    sealed interface Ask {
        /** « Quelle est notre meilleure stratégie ? », « tu me conseilles quoi ? » */
        data object Best : Ask

        /** « Pourquoi on rentre au tour 5 ? » ([lap] : le tour cité, s'il y en a un). */
        data class WhyStop(val lap: Int?) : Ask

        /** « On peut prolonger d'un tour ? », « on peut prolonger de deux tours ? » ([laps] : tours de plus demandés). */
        data class Extend(val laps: Int = 1) : Ask

        /** « Radio, comment vont mes pneus ? », « ils sont encore bons ? » */
        data object TyreStatus : Ask

        /** « Il me reste combien de tours avec ces gommes ? » */
        data object TyreLife : Ask

        /** « Est-ce qu'on doit changer les pneus ? » */
        data object TyreChange : Ask

        /** « Est-ce qu'on gagnerait du temps en rentrant maintenant ? » */
        data object GainPitNow : Ask

        /** « Pourquoi tu veux me faire rentrer alors que j'ai assez d'essence ? » */
        data object WhyPitWithFuel : Ask

        /** « Je perds l'avant, tu vois quelque chose ? », « je patine en sortie, pourquoi ? » : observation et question. */
        data class GripQuestion(val kind: ObservationKind) : Ask

        /** « Je change seulement les pneus » : intention pour le prochain arrêt, jamais un changement enregistré. */
        data object TyresOnlyIntent : Ask

        /** « On peut avancer l'arrêt ? », « je rentre maintenant ? » */
        data object Early : Ask

        /** « Devant moi ils rentrent, on fait quoi ? » */
        data object OthersPitting : Ask

        /** « Combien de carburant pour finir ? », « j'ai assez d'essence pour finir ? » */
        data object FuelToFinish : Ask

        /** « Qu'est-ce qui a changé depuis le plan initial ? », « pourquoi tu changes la stratégie ? » */
        data object WhatChanged : Ask

        /** « Pourquoi ? », « tu es sûr ? », « sur quoi tu te bases ? » juste après une réponse stratégique. */
        data object FollowUp : Ask

        /** Observation du pilote (déclarée, jamais une mesure). [negated] : « je ne perds pas l'avant ». */
        data class Observe(val kind: ObservationKind, val negated: Boolean) : Ask

        /** Sujet stratégique reconnu mais phrase incomplète ou mal reconnue : on demande de préciser. */
        data object Unclear : Ask
    }

    enum class ObservationKind(val label: String) {
        GRIP_FRONT("perte d'adhérence à l'avant"),
        GRIP_REAR("perte d'adhérence à l'arrière"),
        TYRES_WORN("pneus qui fatiguent"),
        TRAFFIC("bloqué dans le trafic"),
        WHEELSPIN("patinage en sortie de virage"),
        DAMAGE("voiture abîmée ou comportement anormal"),
    }

    private fun has(t: String, vararg patterns: String) = patterns.any { Regex(it).containsMatchIn(t) }

    /**
     * Ce que la phrase demande ou signale, ou null si ce n'est ni une question stratégique ni une observation (elle
     * suit alors son chemin habituel : commande, aide, IA). [afterStrategyAnswer] : une réponse stratégique vient d'être
     * donnée, une relance courte (« pourquoi ? ») s'y rapporte.
     */
    fun classify(text: String, afterStrategyAnswer: Boolean = false): Ask? {
        val raw = DriverCommandParser.normalize(text)
        val garbled = Regex("\\[unk\\]").findAll(raw).count()
        val t = " " + raw.replace("[unk]", " ").replace(Regex("[?!.,;:«»\"]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        val mode = StatementGuard.mode(text)
        // Vosk ne transcrit pas l'intonation : « on peut prolonger d'un tour » est une question même sans « ? ».
        val asking = mode == StatementGuard.Mode.QUESTION || mode == StatementGuard.Mode.HYPOTHETICAL ||
            raw.trimEnd().endsWith("?") || has(
                t, " on fait quoi ", " tu (me )?conseilles ", " tu en penses quoi ", " (on|je) (peut|peux|pourrait|pourrais|pourra|pourrai) ",
                " possible ", " faisable ", " ca passe ", " ca vaut le coup ",
                " (quel|quelle|quels|quelles|combien|comment|pourquoi|est ce que|c'est quoi|qu'est ce) ",
            )
        val negated = mode == StatementGuard.Mode.NEGATED

        // Relance courte juste après une réponse stratégique.
        if (afterStrategyAnswer && has(
                t, "^ (et )?pourquoi( ca| donc)? $", "^ (t'es|tu es) sur ", " sur quoi tu te bases ", "^ (et )?(explique|precise|detaille)",
                "^ (et )?comment (tu sais|ca se fait) ", "^ c'est sur ",
            )
        ) {
            return Ask.FollowUp
        }

        // Pneus : questions avant les observations (« ils sont encore bons ? » n'est pas un constat).
        val tyreWord = has(t, " pneu", " gomme", " train ")
        if (has(t, " (je change|on change|je fais|on fait) (seulement|uniquement|juste) (les )?pneus", " (seulement|uniquement|juste) les pneus ") && !asking) {
            return Ask.TyresOnlyIntent
        }
        if (has(t, " pourquoi .*(rentr|arret|stand|box).*(assez|suffisamment) d'?( )?(essence|carburant)", " (assez|suffisamment) d'?( )?(essence|carburant).*pourquoi")) {
            return Ask.WhyPitWithFuel
        }
        if (has(t, " (gagner|gagnerait|gagnerais|gagne) du temps en (rentrant|s'arretant|m'arretant|changeant)", " rentrer maintenant .*(gagn|rentable|interet)")) {
            return Ask.GainPitNow
        }
        if (tyreWord && has(t, " combien de tours", " tiennent encore", " tenir encore", " encore combien")) return Ask.TyreLife
        if (tyreWord && has(t, " (doit|faut|faudrait|devrait|devrais|dois)( il| on| je)? (changer|change)", " on change (les )?pneus", " changer (les )?pneus ")) {
            if (asking || has(t, " est ce qu", " faut il ", " faut ")) return Ask.TyreChange
        }
        if (has(t, " comment (vont|va|sont) (mes |les )?(pneus|gommes)", " (mes |les )?pneus (ca va|vont bien|sont bons|tiennent)", " (ils|elles) sont encore bon",
                " etat (de mes|des) pneus", " pneus encore bons",
            )
        ) {
            return Ask.TyreStatus
        }

        // Observations du pilote (constat, pas une question sur le sujet).
        observation(t)?.let { kind ->
            if (mode != StatementGuard.Mode.QUESTION && mode != StatementGuard.Mode.HYPOTHETICAL) {
                // « Je perds l'avant, tu vois quelque chose ? » : un constat suivi d'une question.
                if (has(t, " tu vois quelque chose", " tu vois quoi", " pourquoi ", " c'est normal", " tu as vu", " t'as vu", " qu'est ce qui se passe")) {
                    return Ask.GripQuestion(kind)
                }
                return Ask.Observe(kind, negated = mode == StatementGuard.Mode.NEGATED)
            }
            // Question sur un constat (« je patine beaucoup en sortie, pourquoi ? ») : la mesure répond.
            if (mode == StatementGuard.Mode.QUESTION && kind != ObservationKind.TRAFFIC) return Ask.GripQuestion(kind)
        }

        if (has(t, " qu'est ce qui (a|as) change", " (quoi|qu'est ce qui) de neuf ", " depuis (le|notre) plan", " plan initial ",
                " pourquoi (tu changes|tu as change|t'as change|on change|on a change|ca change)", " change(r|s)? (la|notre) strategie ",
            ) && (asking || has(t, " pourquoi "))
        ) {
            return Ask.WhatChanged
        }
        if (has(t, " (devant|derriere) (moi )?(ils|elles|il|ca) (commencent a |vont |sont en train de )?(rentr|s'arret|pit)",
                " (les autres|ceux de devant|ceux devant|les voitures devant|la voiture devant|le leader|mes adversaires|ils) " +
                    "(commencent a |vont |sont en train de )?(rentr|s'arret|vont aux stands|font leur arret|passent aux stands)",
                " (tout le monde|beaucoup) (rentre|s'arrete)",
            )
        ) {
            return Ask.OthersPitting
        }
        if (has(t, " (combien|quelle quantite|quel niveau)( d'| de )?(carburant|essence|fuel)", " (assez|suffisamment) d'?( )?(carburant|essence)",
                " (carburant|essence) pour (finir|aller au bout|terminer)", " (je|on) (peux|peut|pourra|pourrai) finir ", " ca passe (sans|jusqu)",
            ) && has(t, " finir ", " terminer ", " (aller )?au bout ", " (l')?arrivee ", " la fin ", " ca passe ") && !negated
        ) {
            return Ask.FuelToFinish
        }
        if (has(t, " prolong", " rester (dehors )?(un|1|deux|2) tours? de plus", " un tour de plus ", " retarder (l'|mon |notre )?arret",
                " repousser (l'|mon |notre )?arret", " rentrer plus tard ", " rester dehors ",
            ) && !negated && (asking || has(t, " prolong"))
        ) {
            val n = Regex(" (de |d')(deux|2|trois|3) tours").find(t)?.groupValues?.get(2)?.let { if (it == "deux" || it == "2") 2 else 3 } ?: 1
            return Ask.Extend(n)
        }
        if (has(t, " avancer (l'|mon |notre )?arret", " anticip", " rentrer plus tot ", " rentrer (maintenant|tout de suite|ce tour)",
                " rentre (maintenant|tout de suite) ", " (je|on) rentre (au|a la fin du) (prochain|ce) tour ",
            ) && asking && !has(t, " pourquoi ")
        ) {
            return Ask.Early
        }
        if (has(t, " pourquoi (on |je |tu veux qu'on |tu veux que je |faut il |il faut )?(rentr|s'arret|m'arret|arret|stand|box)",
                " pourquoi (au |le )?tour \\d+", " pourquoi (cet|ce|l') ?arret",
            )
        ) {
            val lap = Regex(" tour (\\d{1,2}) ").find(t)?.groupValues?.get(1)?.toIntOrNull()
            return Ask.WhyStop(lap)
        }
        if (has(t, " (meilleure|bonne|quelle|notre|la|ta) strategie", " tu (me )?conseilles", " on fait quoi (comme strategie|pour la course)? $",
                " quel (est le |est notre )?plan", " c'est quoi le plan ", " on part sur quoi ",
            ) && (asking || has(t, " conseill"))
        ) {
            return Ask.Best
        }
        // Sujet stratégique deviné mais phrase incomplète ou trop mal reconnue : on demande plutôt que de deviner.
        if (garbled >= 1 && has(t, " strateg", " arret", " stand", " carburant", " essence", " prolong", " rentr")) return Ask.Unclear
        return null
    }

    /** Perte d'adhérence : « je perds (de l'adhérence à) l'avant », « je n'ai plus d'avant », « je manque de grip à l'arrière ». */
    private const val LOSS = " (perd\\w*|manque\\w*|plus d'|plus de|pas d')"

    private fun observation(t: String): ObservationKind? = when {
        Regex("$LOSS[^.]{0,30}l'avant | sous ?vir| l'avant (glisse|decroche|part|ne tourne)| ca pousse ").containsMatchIn(t) ->
            ObservationKind.GRIP_FRONT
        Regex("$LOSS[^.]{0,30}l'arriere | survir| l'arriere (glisse|decroche|part|chasse)").containsMatchIn(t) ->
            ObservationKind.GRIP_REAR
        Regex(" (mes |les )?pneus (sont )?(finis|morts|uses|cuits|a l'agonie|fatiguent|s'usent|lachent|commencent a lacher)| plus de (grip|gomme|pneus) | (je|on) (perds|perd) (de l')?(adherence|grip) ").containsMatchIn(t) ->
            ObservationKind.TYRES_WORN
        Regex(" (je |ca )?patine| patinage| (l'arriere|les roues) (patine|patinent)| je perds la motricite| pas de motricite").containsMatchIn(t) ->
            ObservationKind.WHEELSPIN
        Regex(" (bloque|coince|gene|ralenti|englue) (derriere|dans le trafic|par)| dans le trafic | trafic ").containsMatchIn(t) ->
            ObservationKind.TRAFFIC
        Regex(" (la )?voiture (tire|part|vibre|est abimee|est endommagee)| degats | j'ai (tape|touche|abime)| (aileron|suspension) ").containsMatchIn(t) ->
            ObservationKind.DAMAGE
        else -> null
    }
}
