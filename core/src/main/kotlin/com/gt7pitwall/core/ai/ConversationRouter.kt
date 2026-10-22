package com.gt7pitwall.core.ai

import com.gt7pitwall.core.race.StrategyDialogue
import com.gt7pitwall.core.radio.DriverCommand
import com.gt7pitwall.core.radio.DriverCommandParser
import com.gt7pitwall.core.radio.DriverQuery
import com.gt7pitwall.core.radio.StatementGuard

/**
 * Quelles phrases du pilote vont au modèle conversationnel (cloud) quand il est disponible. Les questions ouvertes,
 * les conseils, les analyses et les relances y vont, accompagnées du calcul du moteur sur le sujet ; le moteur garde
 * les commandes qui modifient la course, les réponses attendues par l'ingénieur, les questions de chiffre simples
 * (instantanées, sans quota) et les messages radio courants.
 */
object ConversationRouter {

    /** Une intervention pour le modèle : la question, son sujet, et le calcul du moteur à lui fournir. */
    data class Turn(
        val question: String,
        val topic: Topic,
        /** Question stratégique complexe : Flash 3.8 en mode automatique, si son quota le permet. */
        val complex: Boolean,
        val ask: StrategyDialogue.Ask? = null,
        val query: DriverQuery? = null,
        /** Relance de l'échange précédent (« pourquoi ? », « et si… », « bon, on fait quoi ? »). */
        val followUp: Boolean = false,
    )

    private val OPEN = Regex(
        "(^|\\s)(comment|pourquoi|conseils?|conseilles?|analyse|resume|explique|tu penses|ton avis|tu trouves|risque|risques|" +
            "compare|mieux|on fait quoi|qu'est ce qu'on fait|tu proposes|tu ferais|si je|si on|et si)(\\s|$)",
    )

    /** « Bon, on fait quoi alors ? » : on demande la recommandation, après en avoir discuté. */
    private val DECIDE = Regex("(on fait quoi|qu'est ce qu'on fait|tu proposes quoi|tu ferais quoi|ta recommandation|tu recommandes|on part sur quoi)")

    private val COMPLEX_ASKS = setOf(
        StrategyDialogue.Ask.Best, StrategyDialogue.Ask.Early, StrategyDialogue.Ask.GainPitNow, StrategyDialogue.Ask.WhyPitWithFuel,
        StrategyDialogue.Ask.WhatChanged, StrategyDialogue.Ask.SamePlan, StrategyDialogue.Ask.CompareStints,
        StrategyDialogue.Ask.AttackRisk, StrategyDialogue.Ask.TyreChange, StrategyDialogue.Ask.OthersPitting,
    )

    fun decide(
        text: String,
        freeText: String?,
        /** Un échange vient d'avoir lieu (la phrase peut le continuer). */
        inConversation: Boolean,
        /** Sujet de cet échange. */
        lastTopic: Topic?,
        /** L'ingénieur attend un oui / non ou une gomme : la réponse lui revient. */
        engineAwaitsAnswer: Boolean,
        afterStrategyAnswer: Boolean = false,
    ): Turn? {
        val clean = DriverCommandParser.normalize(text).replace("[unk]", " ").replace(Regex("\\s+"), " ").trim()
        var question = freeText?.let { DriverCommandParser.normalize(it) }?.takeIf { it.isNotBlank() } ?: clean
        Regex("^question\\s*:?\\s*").find(question)?.let { question = question.substring(it.range.last + 1) }
        if (question.isBlank() || RadioAi.isFragment(question, inConversation)) return null

        val cmds = listOfNotNull(DriverCommandParser.parse(clean), DriverCommandParser.parse(question))
        val yesNo = cmds.any { it == DriverCommand.Yes || it == DriverCommand.No }
        if (engineAwaitsAnswer && (yesNo || cmds.any(::stateful))) return null
        // Arrêt, pneus, règlement, position… : l'analyseur et le garde des commandes décident (confirmation au besoin).
        if (cmds.any(::stateful) || cmds.any { it == DriverCommand.Repeat }) return null
        if (IntentClassifier.helpTopic(question) != null) return null
        val intent = IntentClassifier.classify(question)
        if (intent in IntentClassifier.LOCAL) return null

        val followUp = inConversation && lastTopic != null && (yesNo || RadioAi.isFollowUp(question))
        val query = (cmds.firstOrNull { it is DriverCommand.Ask } as? DriverCommand.Ask)?.query
        // « Tu me conseilles quels pneus ? » : le choix de la gomme, pas la stratégie générale.
        if (query == DriverQuery.NEXT_TYRE || query == DriverQuery.TYRE_CHANGE) {
            return Turn(question, Topic.TYRES, complex = true, query = query, followUp = followUp)
        }
        val ask = StrategyDialogue.classify(question, afterStrategyAnswer || inConversation)
            ?: StrategyDialogue.Ask.Best.takeIf { DECIDE.containsMatchIn(question) && (followUp || intent == Intent.STRATEGY || inConversation) }
        when (ask) {
            // Constats et intentions du pilote : le moteur les enregistre comme déclarations, sans IA.
            is StrategyDialogue.Ask.Observe, StrategyDialogue.Ask.Unclear, StrategyDialogue.Ask.TyresOnlyIntent -> return null
            // État des pneus, carburant pour finir : une mesure, dite tout de suite par le moteur (sauf en pleine discussion).
            in STATUS_ASKS -> if (!followUp) return null else return Turn(question, topicOf(ask!!), complex = false, ask = ask, followUp = true)
            null -> Unit
            StrategyDialogue.Ask.FollowUp -> {
                val topic = lastTopic ?: Topic.PLAN
                return Turn(question, topic, complex = topic in COMPLEX_TOPICS, ask = null, followUp = true)
            }
            else -> return Turn(question, topicOf(ask), complex = complex(ask), ask = ask, followUp = followUp)
        }

        if (query == DriverQuery.PLAN && (OPEN.containsMatchIn(question) || followUp)) {
            return Turn(question, Topic.PLAN, complex = true, query = query, followUp = followUp)
        }
        // Question de chiffre simple (essence, dernier tour, position…) : le moteur répond tout de suite, sans quota.
        if (query != null && !followUp && (!OPEN.containsMatchIn(question) || STATUS.containsMatchIn(question))) return null

        // Bout de phrase mal reconnu (essai réel : « deux sorti », « deux encore », « l'arret position ») : le moteur répond
        // gratuitement plutôt que d'envoyer une requête sur une phrase sans sens.
        val words = question.split(' ', '\'').filter { it.isNotBlank() }
        if (!yesNo && words.size <= SHORT_WORDS && !SHORT_OK.containsMatchIn(question)) return null
        if (followUp) return Turn(question, lastTopic!!, complex = lastTopic in COMPLEX_TOPICS, query = query, followUp = true)
        val topic = when (intent) {
            Intent.COACHING -> Topic.COACHING
            Intent.STRATEGY -> query?.let(::topicOf) ?: Topic.PLAN
            else -> query?.let(::topicOf) ?: (if (inConversation) lastTopic else null) ?: Topic.OTHER
        }
        val hypothetical = StatementGuard.mode(question) == StatementGuard.Mode.HYPOTHETICAL
        val complex = intent == Intent.STRATEGY && (hypothetical || OPEN.containsMatchIn(question))
        return Turn(question, topic, complex, query = query, followUp = inConversation)
    }

    private val STATUS_ASKS = setOf(StrategyDialogue.Ask.TyreStatus, StrategyDialogue.Ask.TyreLife, StrategyDialogue.Ask.FuelToFinish)

    /** « Comment sont les pneus ? », « combien d'essence ? » : une mesure, pas une discussion. */
    private val STATUS = Regex("^(radio )?(comment (sont|vont|va)|ca va|est ce que (mes|les|j'ai)|il me reste|combien)")

    private const val SHORT_WORDS = 3

    /** Phrases courtes qui ont un sens : relance, question, accord. */
    private val SHORT_OK = Regex(
        "(^(et|mais|alors|donc|bon|pourquoi|comment|explique|precise|detaille|continue|vas y|ok|d'accord)(\\s|$))|" +
            "(^|\\s)(quoi|combien|quel|quelle|quels|quelles|pourquoi|comment|ou|quand|conseil\\w*|avis)(\\s|$)|\\?",
    )

    private val COMPLEX_TOPICS = setOf(Topic.PLAN, Topic.TYRES, Topic.FUEL)

    private fun stateful(c: DriverCommand) = c !is DriverCommand.Ask && c != DriverCommand.Repeat && c != DriverCommand.Yes && c != DriverCommand.No

    private fun complex(ask: StrategyDialogue.Ask): Boolean =
        ask in COMPLEX_ASKS || ask is StrategyDialogue.Ask.Extend || ask is StrategyDialogue.Ask.WhyStop

    fun topicOf(ask: StrategyDialogue.Ask): Topic = when (ask) {
        StrategyDialogue.Ask.TyreStatus, StrategyDialogue.Ask.TyreLife, StrategyDialogue.Ask.TyreChange, is StrategyDialogue.Ask.GripQuestion -> Topic.TYRES
        StrategyDialogue.Ask.FuelToFinish -> Topic.FUEL
        StrategyDialogue.Ask.CompareStints -> Topic.PACE
        StrategyDialogue.Ask.Recall -> Topic.MEMORY
        else -> Topic.PLAN
    }

    fun topicOf(q: DriverQuery): Topic = when (q) {
        DriverQuery.TYRES, DriverQuery.NEXT_TYRE, DriverQuery.TYRE_CHANGE -> Topic.TYRES
        DriverQuery.FUEL -> Topic.FUEL
        DriverQuery.LAST_LAP, DriverQuery.BEST_LAP, DriverQuery.PACE, DriverQuery.SECTORS, DriverQuery.DETAILS -> Topic.PACE
        else -> Topic.PLAN
    }
}
