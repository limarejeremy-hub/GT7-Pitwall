package com.gt7pitwall.core.ai.chatgpt

import com.gt7pitwall.core.ai.AiFacts
import com.gt7pitwall.core.ai.AiReply
import com.gt7pitwall.core.ai.AiResult
import com.gt7pitwall.core.ai.EngineerPrompt
import com.gt7pitwall.core.ai.RadioAi
import com.gt7pitwall.core.ai.Topic

/**
 * Issue #67, jalon 1 : question texte « Quel est mon plan carburant ? » posée à ChatGPT sur des faits de SIMULATION
 * (aucune course réelle, aucune donnée inventée : les chiffres ci-dessous sont un scénario d'essai affiché comme tel).
 * La réponse passe par le même contrôle que la radio ([RadioAi.parse]) : une phrase dont un chiffre n'est pas dans
 * les faits est retirée. Sans réseau, sans forfait ou si tout est rejeté, la réponse locale du moteur est donnée.
 */
object FuelPlanCheck {
    const val QUESTION = "Quel est mon plan carburant ?"

    /** Faits simulés (scénario d'essai). */
    val facts = AiFacts(
        listOf(
            "SIMULATION (essai de la connexion, pas une course réelle).",
            "Course de 12 tours, tour 4 en cours.",
            "Carburant restant : 38 %.",
            "Consommation mesurée : 7,1 % par tour sur les 3 derniers tours.",
            "Arrêt obligatoire : 1, pas encore fait.",
            "Plan du moteur : arrêt à la fin du tour 6, plein de 85 % pour finir avec 2 % de marge.",
        ),
    )

    /** Réponse locale (moteur), sans IA : reprise mot pour mot du plan simulé. */
    const val LOCAL_ANSWER = "Simulation : arrêt à la fin du tour 6, plein de 85 % pour finir avec 2 % de marge."

    private val context = EngineerPrompt.Context(
        telemetryLive = true, lap = 4, topic = Topic.FUEL, facts = facts, analysis = null, memory = "", recent = emptyList(),
    )

    val instructions: String get() = EngineerPrompt.system
    val prompt: String get() = EngineerPrompt.build(QUESTION, context)

    data class Outcome(val answer: String, val fromChatGpt: Boolean, val verdict: String)

    /** Contrôle de la réponse du modèle (ou de son échec) ; ne renvoie jamais un chiffre absent des faits. */
    fun check(result: AiResult): Outcome = when (result) {
        is AiResult.Failure -> Outcome(LOCAL_ANSWER, false, "réponse locale : ${result.kind.label}${result.detail?.let { " ($it)" } ?: ""}")
        is AiResult.Text -> when (val r = RadioAi.parse(result.text, QUESTION, facts, extraSource = EngineerPrompt.source(context))) {
            is AiReply.Answer -> Outcome(r.text, true, "réponse ChatGPT vérifiée (chiffres présents dans les faits)")
            is AiReply.Rejected -> Outcome(LOCAL_ANSWER, false, "réponse ChatGPT écartée (${r.reason}) : réponse locale")
            is AiReply.Command -> Outcome(LOCAL_ANSWER, false, "commande proposée ignorée dans ce test : réponse locale")
        }
    }
}
