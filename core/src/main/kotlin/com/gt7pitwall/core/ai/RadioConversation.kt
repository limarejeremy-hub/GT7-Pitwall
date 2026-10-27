package com.gt7pitwall.core.ai

import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.engineer.Priority
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.StrategyDialogue
import com.gt7pitwall.core.radio.DriverCommand
import com.gt7pitwall.core.radio.CommandSource
import com.gt7pitwall.core.radio.DriverCommandParser
import com.gt7pitwall.core.radio.StatementGuard
import com.gt7pitwall.core.radio.Vetting
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Dernière question envoyée à l'IA, sa réponse brute et ce qu'on en a fait (diagnostic à l'écran et journal). */
data class AiTrace(
    val question: String,
    val raw: String?,
    val verdict: String,
    val latencyMs: Long,
    /** Qui a répondu au pilote : un modèle, ou le moteur local (repli). */
    val backend: AiBackend = AiBackend.NANO,
    /** Modèle réellement interrogé, s'il y en a un. */
    val modelId: String? = null,
    /** Essais successifs (modèle, erreur, délai). */
    val attempts: List<AiRouter.Attempt> = emptyList(),
    /** La réponse dite vient du moteur local, pas de l'IA. */
    val fallback: Boolean = false,
)

/**
 * Un tour de radio : comprendre l'intention, puis répondre par le bon chemin — l'ingénieur lui-même pour les
 * messages simples (test radio, salut, merci), l'analyseur pour les commandes et les calculs, l'IA pour le reste,
 * avec les faits de la course et le sujet de la question. La réponse de l'IA est contrôlée puis enregistrée dans la
 * conversation. Le modèle tourne hors du verrou : la télémétrie continue d'être traitée pendant qu'il réfléchit.
 */
class RadioConversation(
    private val race: RaceController,
    private val mutex: Mutex = Mutex(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeoutMs: Long = 10_000L,
) {
    var lastTrace: AiTrace? = null
        private set

    /**
     * Tour de parole : chaque phrase du pilote et chaque interruption ouvrent un nouveau tour. Une réponse préparée pour un
     * tour dépassé (nouvelle question, interruption, nouvelle session) n'est jamais dite.
     */
    @Volatile
    var turn: Long = 0
        private set

    /** Le pilote a coupé l'ingénieur : la réponse en préparation devient périmée. */
    fun interrupt() {
        turn++
    }

    /** Une réponse de l'IA est en préparation (la conversation ne se referme pas pendant ce temps). */
    @Volatile
    var thinking: Boolean = false
        private set

    /** Modèles cloud (Flash-Lite, Flash 3.8) ; null ou non configurés : la radio fonctionne comme avant, sans cloud. */
    @Volatile
    var cloud: AiRouter? = null

    /** Phrase entendue à la radio. [onThinking] reçoit « Gemini réfléchit… » avant l'appel au modèle. */
    suspend fun onUtterance(
        text: String,
        freeText: String?,
        model: LanguageModel?,
        onThinking: (EngineerMessage) -> Unit = {},
        /** Faux : phrase de la conversation continue sans « Radio » devant (bruit possible) : modifications à confirmer. */
        addressed: Boolean = true,
    ): List<EngineerMessage> {
        val source = if (addressed) CommandSource.VOICE else CommandSource.CONVERSATION
        val heardFree = freeText?.takeIf { it.isNotBlank() } ?: text
        val myTurn = ++turn
        // « Non attends, finalement on reste en piste » : la déclaration précédente est retirée, la suite est traitée seule.
        (DialogueActs.classify(heardFree) as? DialogueActs.Act.Correction)?.let { c ->
            mutex.withLock { race.raceMemory.retractLast(clock()) }
            return onUtterance(c.rest, c.rest, model, onThinking, addressed)
        }
        // Réponse à une question en attente (« Tu as monté quelle gomme ? » → « medium ») : au moteur, sans détour.
        // Course du 26/10 : « medium » était écarté comme bout de phrase et la gomme n'était jamais enregistrée.
        if (mutex.withLock { race.answersPendingQuestion(heardFree) || race.answersPendingQuestion(text) }) {
            return mutex.withLock { race.utterance(text, heardFree, source) }
        }
        // Actes de dialogue (oubli, identité, préférence, désaccord, décision, hypothèse) : l'ingénieur répond lui-même.
        val cloudReady = cloud?.let { it.mode.cloudOrder(false).isNotEmpty() && it.available(false) } == true
        mutex.withLock { race.dialogue(heardFree, clock(), cloudReady) }?.let { return it }
        // « D'accord, on fait comme ça » juste après une discussion de stratégie : décision prise ensemble, sans IA.
        mutex.withLock { race.agreement(heardFree) }?.let { return it }
        // Course réelle du 26/10 : « pourquoi je m'arrête au prochain tour ? » partait vers le cloud,
        // expirait deux fois après 12 s, puis revenait au moteur qui savait déjà répondre.
        // Les questions déterministes sur le plan, le carburant, les pneus et la position ont priorité sur Gemini.
        // Question de pilotage : reconnue aussi sur la phrase libre (le vocabulaire radio la déforme).
        val drivingQuery = com.gt7pitwall.core.engineer.DrivingAnalysis.classify(heardFree)
            ?: com.gt7pitwall.core.engineer.DrivingAnalysis.classify(text)
        // Phrase libre d'abord pour les questions d'état du plan (« as tu enlever les arret aux stands »), que le
        // vocabulaire radio déforme ; sinon le vocabulaire radio.
        val parsedQuick = DriverCommandParser.parse(text).let { p ->
            val free = DriverCommandParser.parse(heardFree)
            if (free is DriverCommand.Ask && free.query == com.gt7pitwall.core.radio.DriverQuery.STOP_STATUS) free else p ?: free?.takeIf { it is DriverCommand.Ask }
        }
        val quick = if (drivingQuery != null && (parsedQuick == null || parsedQuick is DriverCommand.Ask)) DriverCommand.Ask(drivingQuery) else parsedQuick
        val localQueries = setOf(
            com.gt7pitwall.core.radio.DriverQuery.PLAN,
            com.gt7pitwall.core.radio.DriverQuery.FUEL,
            com.gt7pitwall.core.radio.DriverQuery.TYRES,
            com.gt7pitwall.core.radio.DriverQuery.TYRE_CHANGE,
            com.gt7pitwall.core.radio.DriverQuery.NEXT_TYRE,
            com.gt7pitwall.core.radio.DriverQuery.POSITION,
            // « As-tu enlevé l'arrêt ? » (27/10) : l'état du plan, instantané, jamais une nouvelle annulation.
            com.gt7pitwall.core.radio.DriverQuery.STOP_STATUS,
            com.gt7pitwall.core.radio.DriverQuery.OBLIGATIONS,
        ) + com.gt7pitwall.core.engineer.DrivingAnalysis.QUERIES
        // Sauf une question sur l'aide de l'ingénieur (« tu vas m'aider pour l'essence ? ») ou une demande d'avis
        // (« tu penses que ma stratégie tient la route ? ») : ce ne sont pas des demandes de chiffre.
        val normalized = com.gt7pitwall.core.radio.DriverCommandParser.normalize(heardFree)
        val opinion = OPINION.containsMatchIn(normalized) || IntentClassifier.helpTopic(heardFree) != null
        // Une question de pilotage (« tu trouves que je conduis bien ? ») reste mesurée par le moteur, même posée en avis.
        if (quick is DriverCommand.Ask && quick.query in localQueries && (!opinion || quick.query in com.gt7pitwall.core.engineer.DrivingAnalysis.QUERIES)) {
            strategyDialogue(text, freeText)?.let { return it }
            return mutex.withLock { race.utterance(text, heardFree, source) }
        }
        // Conversation : questions ouvertes, conseils, analyses et relances vont au modèle cloud s'il est disponible,
        // avec le calcul du moteur sur le sujet. Sinon (non configuré, quota, panne), tout continue comme avant.
        var notice: EngineerMessage? = null
        cloud?.takeIf { it.mode.cloudOrder(false).isNotEmpty() }?.let { router ->
            val turn = mutex.withLock {
                val now = clock()
                ConversationRouter.decide(
                    text, freeText, race.inConversation(now), race.conversationTopic(now), race.awaitingDriverAnswer, race.afterStrategyAnswer(now),
                )
            }
            if (turn != null) {
                if (router.available(turn.complex)) {
                    when (val r = converse(router, turn, onThinking, myTurn)) {
                        is Converse.Done -> return r.messages
                        is Converse.Failed -> notice = r.notice
                        Converse.Stale -> return emptyList()
                    }
                } else {
                    val why = router.mode.cloudOrder(turn.complex).joinToString(" ; ") { b ->
                        "${b.label} : " + (router.provider(b)?.takeIf { !it.ready }?.status ?: router.usage.blockedReason(b) ?: "indisponible")
                    }
                    lastTrace = AiTrace(turn.question, null, "moteur local ($why)", 0, AiBackend.ENGINE, fallback = true)
                }
            }
        }
        return (listOfNotNull(notice) + legacy(text, freeText, model, onThinking, source))
    }

    /** Chemin sans modèle cloud : moteur, analyseur, garde des commandes et Gemini Nano (inchangé). */
    private suspend fun legacy(
        text: String,
        freeText: String?,
        model: LanguageModel?,
        onThinking: (EngineerMessage) -> Unit,
        source: CommandSource,
    ): List<EngineerMessage> {
        // Question stratégique ou observation : le moteur répond lui-même (plan, comparaison, carburant), jamais Gemini,
        // et rien n'est enregistré (ni arrêt, ni pneus). Les commandes qui changent l'état gardent leur chemin.
        strategyDialogue(text, freeText)?.let { return it }
        val route = mutex.withLock {
            RadioAi.route(text, freeText, race.inAiConversation(clock()), race.awaitingDriverAnswer, race.telemetryLive())
        }
        return when {
            route is RadioAi.Route.Ignore -> emptyList()
            route is RadioAi.Route.Local -> mutex.withLock {
                race.localReply(route.question, LocalReplies.text(route.intent, race.telemetryLive()))
            }
            route is RadioAi.Route.NoRaceData -> mutex.withLock { race.localReply(route.question, LocalReplies.NO_RACE_DATA) }
            route is RadioAi.Route.Help -> mutex.withLock { help(route.question, route.topic) }
            route is RadioAi.Route.Ai && model != null && model.ready -> ask(model, route.question, onThinking)
            // Sans Gemini, une intention ou une hypothèse sur la stratégie reçoit quand même une réponse honnête.
            route is RadioAi.Route.Ai && DriverCommandParser.parse(text) == null && strategyDiscussion(route.question) != null ->
                mutex.withLock {
                    val (reply, intention) = strategyDiscussion(route.question)!!
                    intention?.let(race::noteIntention)
                    race.localReply(route.question, reply)
                }
            route is RadioAi.Route.Ai && model != null && DriverCommandParser.parse(text) == null -> listOf(
                EngineerMessage("Toi : ${route.question}", Priority.INFO, MessageCategory.DRIVER),
                EngineerMessage("Gemini n'est pas prêt : ${model.status}", Priority.HIGH, MessageCategory.ANSWER),
            )
            else -> {
                // Commande vocale : sa forme (négation, intention, question…) décide si elle s'applique. Une intention
                // ou une hypothèse sur la stratégie devient une discussion avec Gemini quand il est prêt.
                val original = freeText?.takeIf { it.isNotBlank() } ?: text
                // L'analyseur peut avoir retenu la phrase libre plutôt que le vocabulaire radio.
                val heard = (route as? RadioAi.Route.Parser)?.text ?: text
                val cmd = DriverCommandParser.parse(heard)
                val discuss = cmd?.let { mutex.withLock { race.vet(it, original, source) } } as? Vetting.Discuss
                val router = cloud?.takeIf { discuss != null && discuss.mode in DISCUSSED_WITH_AI && it.available(complex = true) }
                val discussed = router?.let {
                    mutex.withLock { discuss?.intention?.let(race::noteIntention) }
                    val turn = ConversationRouter.Turn(original, Topic.PLAN, complex = true, ask = StrategyDialogue.Ask.Best)
                    (converse(it, turn, onThinking) as? Converse.Done)?.messages
                }
                if (discussed != null) {
                    discussed
                } else if (discuss != null && discuss.mode in DISCUSSED_WITH_AI && model != null && model.ready) {
                    mutex.withLock { discuss.intention?.let(race::noteIntention) }
                    ask(model, original, onThinking)
                } else {
                    mutex.withLock { race.utterance(heard, original, source) }
                }
            }
        }
    }

    private sealed interface Converse {
        data class Done(val messages: List<EngineerMessage>) : Converse

        /** Aucun modèle n'a répondu et le moteur n'a pas de calcul sur ce sujet : le chemin habituel répond. */
        data class Failed(val notice: EngineerMessage) : Converse

        /** Le pilote a posé une autre question, coupé l'ingénieur, ou la session a changé : rien n'est dit. */
        data object Stale : Converse
    }

    /**
     * Une intervention du pilote, formulée par le modèle cloud à partir des faits, du calcul du moteur et de la
     * mémoire. La réponse est vérifiée (chiffres, autres voitures, conseils) ; une commande passe par le garde. Si le
     * modèle ne répond pas, le calcul du moteur est dit tel quel.
     */
    private suspend fun converse(router: AiRouter, turn: ConversationRouter.Turn, onThinking: (EngineerMessage) -> Unit, myTurn: Long = this.turn): Converse {
        val (analysis, ctx) = mutex.withLock {
            val a = race.engineAnalysis(turn)
            a to race.conversationContext(turn, a)
        }
        val session = race.currentSessionId
        router.candidates(turn.complex).firstOrNull()?.let { p ->
            onThinking(EngineerMessage("${p.backend.label} réfléchit : « ${turn.question} »", Priority.INFO, MessageCategory.DRIVER))
        }
        thinking = true
        val outcome = try {
            router.complete(EngineerPrompt.system, EngineerPrompt.build(turn.question, ctx), turn.complex)
        } finally {
            thinking = false
        }
        // Réponse arrivée trop tard : une autre question, une interruption ou une autre session l'ont rendue périmée.
        if (myTurn != this.turn || race.currentSessionId != session) {
            lastTrace = AiTrace(turn.question, outcome.text, "réponse périmée, non dite", outcome.attempts.sumOf { it.latencyMs }, outcome.backend ?: AiBackend.ENGINE, outcome.modelId, outcome.attempts, fallback = true)
            return Converse.Stale
        }
        val history = ctx.recent.map { RadioAi.Exchange(it.question, it.answer) }
        val reply = outcome.text?.let {
            RadioAi.parse(
                it, turn.question, ctx.facts, history, previousAnswers = emptyList(), extraSource = EngineerPrompt.source(ctx),
                // Question simple : la conclusion seule ; l'explication quand le pilote la demande (essai PS5).
                maxSentences = if (com.gt7pitwall.core.radio.Brevity.wantsDetail(turn.question)) CLOUD_SENTENCES else com.gt7pitwall.core.radio.Brevity.SIMPLE_SENTENCES,
                maxChars = CLOUD_CHARS,
            )
        }
        val latency = outcome.attempts.sumOf { it.latencyMs }
        val failure = outcome.failure
        val verdict = when {
            reply != null -> verdict(reply, outcome.text)
            failure != null -> "échec : ${failure.kind.label}"
            else -> "aucun modèle disponible"
        }
        val answered = reply is AiReply.Answer || reply is AiReply.Command
        lastTrace = AiTrace(
            turn.question, outcome.text, verdict, latency, outcome.backend ?: AiBackend.ENGINE, outcome.modelId, outcome.attempts, fallback = !answered,
        )
        val why = if (reply == null) "IA cloud : ${failure?.kind?.label ?: "pas de réponse"}" else "IA cloud : réponse écartée ($verdict)"
        return mutex.withLock {
            when {
                reply is AiReply.Answer -> Converse.Done(
                    race.conversationReply(turn, Persona.polish(reply.text, ctx.recent.map { it.answer }).ifBlank { reply.text }, outcome.modelId ?: "IA", analysis, fromAi = true),
                )
                // Gemini ne modifie rien : sa commande passe par le même garde que la voix, avec confirmation au besoin.
                reply is AiReply.Command -> Converse.Done(race.aiReply(turn.question, reply, clock()))
                analysis != null -> Converse.Done(
                    race.conversationReply(turn, analysis, RaceMemory.ENGINE, analysis, fromAi = false, notice = "$why → réponse du moteur local."),
                )
                reply is AiReply.Rejected -> Converse.Done(race.aiReply(turn.question, reply, clock()))
                else -> Converse.Failed(EngineerMessage("$why → moteur local.", Priority.INFO, MessageCategory.DRIVER))
            }
        }
    }

    private suspend fun strategyDialogue(text: String, freeText: String?): List<EngineerMessage>? {
        val heard = freeText?.takeIf { it.isNotBlank() } ?: text
        if (DriverCommandParser.normalize(heard).startsWith("question")) return null
        return mutex.withLock {
            val ask = StrategyDialogue.classify(heard, race.afterStrategyAnswer(clock())) ?: return@withLock null
            val cmd = DriverCommandParser.parse(text)
            val stateful = cmd != null && cmd !is DriverCommand.Ask && cmd != DriverCommand.Repeat
            // Une observation ou une phrase mal reconnue qui est aussi une commande reste à l'analyseur et au garde.
            if (stateful && (ask is StrategyDialogue.Ask.Observe || ask is StrategyDialogue.Ask.Unclear)) return@withLock null
            // Hors course ou en pleine discussion de pilotage, « l'avant glisse en sortie » est une question de pilotage.
            if ((ask is StrategyDialogue.Ask.Observe || ask is StrategyDialogue.Ask.GripQuestion) &&
                (!race.telemetryLive() || race.inAiConversation(clock()))
            ) {
                return@withLock null
            }
            // « Je vais prolonger d'un tour » : intention notée, et l'ingénieur dit ce qu'il en pense.
            val intention = StatementGuard.mode(heard) in INTENTION_MODES
            if (intention) race.noteIntention("« ${heard.trim()} »")
            race.strategyAnswer(ask, heard.trim(), clock(), intention)
        }
    }

    /** Ce que l'ingénieur fera sur ce sujet ; en course, suivi de la valeur calculée du moment. */
    private fun help(question: String, topic: HelpTopic): List<EngineerMessage> {
        val live = race.telemetryLive()
        val out = race.localReply(question, LocalReplies.help(topic, live))
        val query = topic.query ?: return out
        if (!live) return out
        return out + race.command(com.gt7pitwall.core.radio.DriverCommand.Ask(query))
    }

    /** Question posée à l'IA (radio ou écran). Un message simple reçoit d'abord la réponse de l'ingénieur. */
    suspend fun ask(model: LanguageModel, question: String, onThinking: (EngineerMessage) -> Unit = {}): List<EngineerMessage> {
        IntentClassifier.helpTopic(question)?.let { topic -> return mutex.withLock { help(question, topic) } }
        val asked = IntentClassifier.classify(question)
        if (asked in IntentClassifier.LOCAL) {
            return mutex.withLock { race.localReply(question, LocalReplies.text(asked, race.telemetryLive())) }
        }
        onThinking(EngineerMessage("Gemini réfléchit : « $question »", Priority.INFO, MessageCategory.DRIVER))
        val context = mutex.withLock { Context(race.aiFacts(), race.aiHistory, race.telemetryLive(), race.lastAiIntent) }
        // Une relance (« pourquoi ? », « oui », « et au freinage ? ») garde le sujet et la conversation ; une nouvelle
        // question repart de zéro, pour ne pas ressortir l'ancien conseil.
        val followUp = context.lastIntent != null && RadioAi.isFollowUp(question)
        val intent = if (asked == Intent.OTHER && followUp) context.lastIntent!! else asked
        val sameSubject = followUp || (intent != Intent.OTHER && intent == context.lastIntent)
        val history = if (sameSubject) context.history else emptyList()
        val started = clock()
        val prompt = RadioAi.prompt(question, context.facts, history, intent, context.telemetryLive)
        val raw = withTimeoutOrNull(timeoutMs) { model.generate(RadioAi.systemInstruction, prompt) }
        val reply = RadioAi.parse(raw, question, context.facts, history, previousAnswers = context.history.map { it.answer })
        lastTrace = AiTrace(question, raw, verdict(reply, raw), clock() - started)
        return mutex.withLock { race.aiReply(question, reply, clock(), intent) }
    }

    /** Réponse sans IA à une intention, une hypothèse ou une remarque sur les autres, en stratégie ; rien n'est modifié. */
    private fun strategyDiscussion(question: String): Pair<String, String?>? {
        if (IntentClassifier.classify(question) != Intent.STRATEGY) return null
        return when (StatementGuard.mode(question)) {
            StatementGuard.Mode.FUTURE, StatementGuard.Mode.WISH ->
                "Compris, c'est noté comme intention : je n'enregistre rien tant que ce n'est pas fait." to "« ${question.trim()} »"
            StatementGuard.Mode.HYPOTHETICAL -> "C'est une hypothèse : je ne change rien à la stratégie." to null
            StatementGuard.Mode.ABOUT_OTHERS -> "Noté pour l'adversaire : GT7 ne me transmet pas ses arrêts, je ne change rien de notre côté." to null
            else -> null
        }
    }

    private companion object {
        /** Demande d'avis : la conversation (modèle cloud s'il est disponible), pas le chiffre seul. */
        val OPINION = Regex("(^|\\s)(tu penses|ton avis|tu trouves|tu crois|tient la route|tiennent la route)(\\s|$)")

        /** En course, une réponse courte : la conclusion puis l'explication utile. */
        const val CLOUD_SENTENCES = 4
        const val CLOUD_CHARS = 520
        val INTENTION_MODES = setOf(StatementGuard.Mode.FUTURE, StatementGuard.Mode.WISH)
        val DISCUSSED_WITH_AI = setOf(
            StatementGuard.Mode.FUTURE, StatementGuard.Mode.WISH, StatementGuard.Mode.HYPOTHETICAL, StatementGuard.Mode.ABOUT_OTHERS,
        )
    }

    private class Context(val facts: AiFacts, val history: List<RadioAi.Exchange>, val telemetryLive: Boolean, val lastIntent: Intent?)

    private fun verdict(reply: AiReply, raw: String?): String = when (reply) {
        is AiReply.Answer -> "réponse dite"
        is AiReply.Command -> "commande : ${reply.phrase}"
        is AiReply.Rejected -> when (reply.reason) {
            AiReply.Reason.UNKNOWN -> "Gemini ne sait pas"
            AiReply.Reason.INVENTED_NUMBER -> "refusée : chiffre absent des données"
            AiReply.Reason.NOT_TRANSMITTED -> "refusée : parle des autres voitures"
            AiReply.Reason.NONSENSE -> "refusée : conseil de pilotage faux"
            AiReply.Reason.BANALITY -> "refusée : banalité sans geste concret"
            AiReply.Reason.UNPROVEN_DEFECT -> "refusée : défaut non prouvé par la télémétrie"
            AiReply.Reason.BAD_COMMAND -> "refusée : commande inconnue"
            AiReply.Reason.REPEAT -> "refusée : conseil déjà donné"
            AiReply.Reason.EMPTY -> if (raw == null) "pas de réponse (délai ou erreur)" else "réponse vide"
        }
    }
}
