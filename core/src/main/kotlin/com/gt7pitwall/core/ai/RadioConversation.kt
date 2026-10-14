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
 * Un tour de radio : aiguillage (analyseur, IA ou fragment ignoré), question à l'IA avec les faits de la course,
 * contrôle de la réponse, puis enregistrement dans la conversation. Le modèle tourne hors du verrou : la
 * télémétrie continue d'être traitée pendant qu'il réfléchit.
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
            RadioAi.route(text, freeText, race.inAiConversation(clock()), race.awaitingDriverAnswer)
        }
        return when {
            route is RadioAi.Route.Ignore -> emptyList()
            route is RadioAi.Route.Ai && model != null && model.ready -> ask(model, route.question, onThinking)
            route is RadioAi.Route.Ai && model != null && DriverCommandParser.parse(text) == null -> listOf(
                EngineerMessage("Toi : ${route.question}", Priority.INFO, MessageCategory.DRIVER),
                EngineerMessage("Gemini n'est pas prêt : ${model.status}", Priority.HIGH, MessageCategory.ANSWER),
            )
            else -> mutex.withLock { race.utterance(text) }
        }
    }

    /** Question posée à l'IA (radio ou écran). */
    suspend fun ask(model: LanguageModel, question: String, onThinking: (EngineerMessage) -> Unit = {}): List<EngineerMessage> {
        onThinking(EngineerMessage("Gemini réfléchit : « $question »", Priority.INFO, MessageCategory.DRIVER))
        val (facts, history) = mutex.withLock { race.aiFacts() to race.aiHistory }
        val started = clock()
        val raw = withTimeoutOrNull(timeoutMs) { model.generate(RadioAi.systemInstruction, RadioAi.prompt(question, facts, history)) }
        val reply = RadioAi.parse(raw, question, facts, history)
        lastTrace = AiTrace(question, raw, verdict(reply, raw), clock() - started)
        return mutex.withLock { race.aiReply(question, reply, clock()) }
    }

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
            AiReply.Reason.EMPTY -> if (raw == null) "pas de réponse (délai ou erreur)" else "réponse vide"
        }
    }
}
