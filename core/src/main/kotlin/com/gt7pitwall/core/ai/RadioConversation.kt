package com.gt7pitwall.core.ai

import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.engineer.Priority
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.radio.DriverCommandParser
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Dernière question envoyée à l'IA, sa réponse brute et ce qu'on en a fait (diagnostic à l'écran). */
data class AiTrace(val question: String, val raw: String?, val verdict: String, val latencyMs: Long)

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

    /** Phrase entendue à la radio. [onThinking] reçoit « Gemini réfléchit… » avant l'appel au modèle. */
    suspend fun onUtterance(
        text: String,
        freeText: String?,
        model: LanguageModel?,
        onThinking: (EngineerMessage) -> Unit = {},
    ): List<EngineerMessage> {
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
            route is RadioAi.Route.Ai && model != null && DriverCommandParser.parse(text) == null -> listOf(
                EngineerMessage("Toi : ${route.question}", Priority.INFO, MessageCategory.DRIVER),
                EngineerMessage("Gemini n'est pas prêt : ${model.status}", Priority.HIGH, MessageCategory.ANSWER),
            )
            else -> mutex.withLock { race.utterance(text) }
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
