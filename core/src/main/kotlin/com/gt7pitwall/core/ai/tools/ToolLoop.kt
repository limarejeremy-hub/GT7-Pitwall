package com.gt7pitwall.core.ai.tools

import com.gt7pitwall.core.ai.AiResult
import com.gt7pitwall.core.ai.FailureKind
import com.gt7pitwall.core.ai.chatgpt.ResponsesStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Issue #70 : boucle d'appels d'outils sur la Responses API du forfait (sans `previous_response_id` : l'historique
 * — question, appels du modèle, résultats des outils — est renvoyé dans `input` à chaque tour, comme l'exige l'aperçu).
 *
 * Bornée : [maxRounds] allers-retours et [maxCalls] appels au plus ; au-delà, échec → réponse locale. Un outil inconnu
 * reçoit une erreur. La réponse finale porte les faits réellement rendus ([AiResult.Text.evidence]) pour le contrôle
 * des chiffres. Un refus des outils par le serveur (400) est signalé : l'appelant repasse sans outils.
 */
class ToolLoop(
    private val send: suspend (body: String) -> ResponsesStream.Turn,
    private val maxRounds: Int = MAX_ROUNDS,
    private val maxCalls: Int = MAX_CALLS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Call(val name: String, val known: Boolean)

    /** Un aller-retour : durée totale, premier octet, taille de la requête (caractères), appels demandés. */
    data class Round(val ms: Long, val ttfbMs: Long?, val requestChars: Int, val calls: Int)

    data class Outcome(val result: AiResult, val calls: List<Call>, val rounds: Int, val stats: List<Round> = emptyList()) {
        val totalMs: Long get() = stats.sumOf { it.ms }

        /** Résumé lisible (latence par aller-retour), sans aucun contenu. */
        val timing: String
            get() = stats.mapIndexed { i, r ->
                "aller-retour ${i + 1} : ${r.ms} ms" + (r.ttfbMs?.let { " (1er octet $it ms)" } ?: "") + ", ${r.requestChars} car." +
                    (if (r.calls > 0) ", ${r.calls} outil(s)" else "")
            }.joinToString(" ; ")

        /** Le serveur a refusé la requête AVEC outils : à retenter sans outils. */
        val toolsRejected: Boolean get() = rounds == 1 && calls.isEmpty() && (result as? AiResult.Failure)?.kind == FailureKind.NOT_CONFIGURED
    }

    suspend fun run(model: String, instructions: String, prompt: String, snapshot: ToolSnapshot): Outcome {
        val input = JSONArray().put(JSONObject().put("role", "user").put("content", prompt))
        val tools = JSONArray().put(PitwallDataTools.namespace())
        val evidence = StringBuilder()
        val calls = mutableListOf<Call>()
        val stats = mutableListOf<Round>()
        for (round in 1..maxRounds) {
            val body = ResponsesStream.requestBody(model, instructions, input, tools)
            val t0 = clock()
            val turn = send(body)
            stats += Round(clock() - t0, turn.ttfbMs, body.length, turn.calls.size)
            turn.failure?.let { return Outcome(it, calls, round, stats) }
            if (turn.calls.isEmpty()) {
                val text = turn.text.trim()
                val r = if (text.isEmpty()) AiResult.Failure(FailureKind.EMPTY) else AiResult.Text(text, evidence.toString().trim(), calls.map { it.name })
                return Outcome(r, calls, round, stats)
            }
            for (fc in turn.calls) {
                if (calls.size >= maxCalls) return Outcome(AiResult.Failure(FailureKind.OTHER, "trop d'appels d'outils (${calls.size})"), calls, round, stats)
                val res = PitwallDataTools.call(fc.name, snapshot, clock())
                input.put(fc.toInputItem())
                input.put(JSONObject().put("type", "function_call_output").put("call_id", fc.callId).put("output", res.toJson()))
                if (res.known) evidence.append(res.evidenceText).append('\n')
                calls += Call(fc.name.take(60), res.known)
            }
        }
        return Outcome(AiResult.Failure(FailureKind.OTHER, "trop d'allers-retours d'outils ($maxRounds)"), calls, maxRounds, stats)
    }

    companion object {
        const val MAX_ROUNDS = 3
        const val MAX_CALLS = 6
    }
}
