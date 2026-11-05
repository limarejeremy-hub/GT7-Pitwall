package com.gt7pitwall.core.ai.chatgpt

import com.gt7pitwall.core.ai.AiResult
import com.gt7pitwall.core.ai.FailureKind
import org.json.JSONArray
import org.json.JSONObject

/**
 * Issue #67 : requête Responses éligible au forfait ChatGPT (documentation officielle, « Models and inference » et
 * « Preview limitations ») : `POST https://api.openai.com/v1/responses`, `store: false`, `stream: true`, consigne dans
 * `instructions` (un message `system` explicite est refusé), aucun des champs non pris en charge (température, nombre
 * maximal de jetons…). Une réponse n'est acceptée qu'après `response.completed`.
 */
object ResponsesStream {
    const val RESPONSES_URL = "https://api.openai.com/v1/responses"
    const val MODELS_URL = "https://api.openai.com/v1/models"

    /** Corps JSON d'une question ; [instructions] : consigne de l'ingénieur, [prompt] : faits et question du pilote. */
    fun requestBody(model: String, instructions: String, prompt: String): String = JSONObject().apply {
        put("model", model)
        put("instructions", instructions)
        put("input", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        put("store", false)
        put("stream", true)
    }.toString()

    /** Modèles affichables du compte (visibility « list »), dans l'ordre du serveur : (slug, nom affiché). */
    fun listedModels(json: String): List<Pair<String, String>> {
        val arr = JSONObject(json).optJSONArray("models") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optString("visibility") == "list" }
            .mapNotNull { m -> m.optString("slug").takeIf { it.isNotBlank() }?.let { it to m.optString("display_name").ifBlank { it } } }
    }

    /** Lecture incrémentale des événements (Server-Sent Events) ; [result] n'est défini qu'une fois le flux terminé. */
    class Reader {
        private val text = StringBuilder()
        private var data = StringBuilder()
        var completed = false
            private set
        var failure: AiResult.Failure? = null
            private set

        /** Une ligne du flux (sans le saut de ligne). */
        fun line(l: String) {
            when {
                l.startsWith("data:") -> data.append(l.removePrefix("data:").trimStart())
                l.isEmpty() -> flush()
                else -> Unit // « event: … », commentaires : le type est aussi dans les données.
            }
        }

        fun end() {
            flush()
            if (!completed && failure == null) failure = AiResult.Failure(FailureKind.OTHER, "flux interrompu avant response.completed")
        }

        private fun flush() {
            val d = data.toString()
            data = StringBuilder()
            if (d.isBlank() || d == "[DONE]") return
            val o = runCatching { JSONObject(d) }.getOrNull() ?: return
            when (o.optString("type")) {
                "response.output_text.delta" -> text.append(o.optString("delta"))
                "response.completed" -> completed = true
                "response.failed" -> failure = errorOf(o.optJSONObject("response")?.optJSONObject("error"), null)
                "response.incomplete" -> failure = AiResult.Failure(FailureKind.EMPTY, "réponse incomplète")
                "error" -> failure = errorOf(o.optJSONObject("error") ?: o, null)
            }
        }

        val result: AiResult
            get() = failure ?: if (completed) {
                text.toString().trim().takeIf { it.isNotEmpty() }?.let { AiResult.Text(it) } ?: AiResult.Failure(FailureKind.EMPTY)
            } else {
                AiResult.Failure(FailureKind.OTHER, "flux non terminé")
            }
    }

    /** Ce que l'application doit faire après une erreur du forfait (documentation « Errors and recovery »). */
    enum class Recovery(val label: String) {
        RETRY_LATER("réessayer plus tard"),
        PAUSE_LIMIT("limite d'usage atteinte : pause, voir ChatGPT → Paramètres → Utilisation"),
        NOT_ELIGIBLE("forfait non utilisable pour ce compte"),
        SIGN_IN_AGAIN("se reconnecter à ChatGPT"),
        FIX_REQUEST("requête refusée : corriger l'application"),
    }

    /** Code d'erreur officiel → échec du routeur (repli local immédiat) et action à mener. Jamais de bascule payante. */
    fun recovery(code: String?, status: Int?): Recovery = when (code) {
        "subscription_sharing_usage_limit_exceeded" -> Recovery.PAUSE_LIMIT
        "subscription_sharing_user_not_eligible" -> Recovery.NOT_ELIGIBLE
        "subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable" -> Recovery.RETRY_LATER
        "subscription_sharing_invalid_user" -> Recovery.SIGN_IN_AGAIN
        "subscription_sharing_unsupported_capability", "subscription_sharing_route_not_supported",
        "chatpass_v2_scope_not_authorized", "chatpass_v2_invalid_authorization_context" -> Recovery.FIX_REQUEST
        else -> when (status) {
            401 -> Recovery.SIGN_IN_AGAIN
            429 -> Recovery.PAUSE_LIMIT
            503 -> Recovery.RETRY_LATER
            400, 403 -> Recovery.FIX_REQUEST
            else -> Recovery.RETRY_LATER
        }
    }

    /** Erreur HTTP avant l'ouverture du flux (corps « error » structuré ou « detail » libre). */
    fun httpFailure(status: Int, body: String?): AiResult.Failure {
        val o = body?.let { runCatching { JSONObject(it) }.getOrNull() }
        return errorOf(o?.optJSONObject("error"), status, o?.optString("detail")?.ifEmpty { null })
    }

    private fun errorOf(e: JSONObject?, status: Int?, detail: String? = null): AiResult.Failure {
        val code = e?.optString("code")?.ifEmpty { null }
        val kind = when (recovery(code, status)) {
            Recovery.PAUSE_LIMIT -> FailureKind.QUOTA
            Recovery.SIGN_IN_AGAIN -> FailureKind.AUTH
            Recovery.NOT_ELIGIBLE, Recovery.FIX_REQUEST -> FailureKind.NOT_CONFIGURED
            Recovery.RETRY_LATER -> if (status == 503 || code?.endsWith("_unavailable") == true) FailureKind.OVERLOADED else FailureKind.OTHER
        }
        val text = listOfNotNull(code, status?.let { "HTTP $it" }, detail?.take(80)).joinToString(" · ").ifEmpty { null }
        return AiResult.Failure(kind, text)
    }
}
