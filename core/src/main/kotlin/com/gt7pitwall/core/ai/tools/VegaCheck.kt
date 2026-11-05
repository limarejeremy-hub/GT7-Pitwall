package com.gt7pitwall.core.ai.tools

import com.gt7pitwall.core.ai.AiFacts
import com.gt7pitwall.core.ai.AiReply
import com.gt7pitwall.core.ai.AiResult
import com.gt7pitwall.core.ai.RadioAi

/**
 * Contrôle d'une réponse obtenue avec les outils (essais hors course, V491/V492) : sans outil exécuté, ou avec un
 * chiffre absent des faits RENDUS par les outils, la réponse est écartée et la réponse locale est donnée.
 */
object VegaCheck {
    data class Outcome(val answer: String, val fromChatGpt: Boolean, val verdict: String)

    fun withTools(question: String, result: AiResult, localAnswer: String): Outcome = when (result) {
        is AiResult.Failure -> Outcome(localAnswer, false, "réponse locale : ${result.kind.label}${result.detail?.let { " ($it)" } ?: ""}")
        is AiResult.Text -> {
            if (result.evidence.isBlank()) {
                Outcome(localAnswer, false, "GPT a répondu SANS appeler d'outil : réponse écartée, réponse locale")
            } else {
                val toolFacts = AiFacts(result.evidence.lines().filter { it.isNotBlank() })
                when (val r = RadioAi.parse(result.text, question, toolFacts, extraSource = result.evidence)) {
                    is AiReply.Answer -> Outcome(r.text, true, "réponse ChatGPT vérifiée sur les faits rendus par les outils")
                    is AiReply.Rejected -> Outcome(localAnswer, false, "réponse ChatGPT écartée (${r.reason}) : réponse locale")
                    is AiReply.Command -> Outcome(localAnswer, false, "commande proposée ignorée dans cet essai : réponse locale")
                }
            }
        }
    }

    /** Question Sport (V492) : la réponse locale reprend la fiche en cache, ou dit qu'elle manque. */
    const val SPORT_QUESTION = "Quelle course Sport cette semaine ?"

    fun sportLocalAnswer(snapshot: ToolSnapshot): String {
        val r = snapshot.result(PitwallDataTools.Tool.SPORT)
        return r.facts.firstOrNull { it.text.startsWith("Course Sport") && "en cours" in it.text }?.text
            ?: r.missing.firstOrNull()?.let { "Programme Sport : $it." }
            ?: "Programme Sport inconnu."
    }

    /** Question allégée : le programme n'est pas recopié, le modèle doit appeler l'outil Sport. */
    val sportPrompt: String = "CONTEXTE : essai hors course. Les faits ne sont PAS dans ce message : appelle l'outil pitwall utile, " +
        "puis réponds en deux phrases au plus, en citant la source et en disant ce qui est inconnu ou contradictoire.\n\n" +
        "LE PILOTE DIT : « $SPORT_QUESTION »\n\nRÉPONSE :"
}
