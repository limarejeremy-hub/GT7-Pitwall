package com.gt7pitwall.core.ai.chatgpt

import org.json.JSONArray
import org.json.JSONObject

/**
 * Revue #69 (P0 du 05/11) : étapes de la connexion ChatGPT, gardées sur le téléphone pour savoir OÙ elle bloque.
 * Ne contient que des étapes, des codes HTTP et des codes d'erreur OAuth officiels : jamais de code d'autorisation,
 * jeton, JWT, adresse de retour complète, e-mail, identifiant d'hôte ni UUID ([redact] le garantit à l'écriture).
 */
data class SignInTrace(val entries: List<Entry> = emptyList()) {

    enum class Step(val label: String) {
        BROWSER_OPENED("Navigateur lancé"),
        CALLBACK_RECEIVED("Retour OAuth reçu"),
        CODE_EXCHANGED("Code échangé"),
        ID_TOKEN_VERIFIED("ID token vérifié"),
        PLAN_GRANTED("Permissions accordées"),
        CREDENTIALS_SAVED("Credentials enregistrés"),
        MODEL_FOUND("Modèle trouvé"),
        GPT_CONFIRMED("Premier appel GPT confirmé"),
    }

    /** [ok] faux : l'étape a échoué, [detail] dit pourquoi (non sensible). */
    data class Entry(val step: Step, val ok: Boolean, val atMs: Long, val detail: String? = null)

    val last: Entry? get() = entries.lastOrNull()

    /** Étape la plus avancée réussie. */
    val reached: Step? get() = entries.filter { it.ok }.maxByOrNull { it.step.ordinal }?.step

    val gptConfirmed: Boolean get() = entries.any { it.ok && it.step == Step.GPT_CONFIRMED }

    fun ok(step: Step, atMs: Long, detail: String? = null) = add(Entry(step, true, atMs, detail?.let(::redact)))
    fun failed(step: Step, atMs: Long, detail: String) = add(Entry(step, false, atMs, redact(detail)))

    /** Nouvelle tentative : on repart de zéro (l'appel GPT confirmé d'avant ne vaut plus pour ces jetons). */
    fun restart(atMs: Long) = SignInTrace(listOf(Entry(Step.BROWSER_OPENED, true, atMs)))

    private fun add(e: Entry) = SignInTrace((entries + e).takeLast(MAX_ENTRIES))

    /** Texte à copier pour le diagnostic : étapes et codes seulement. */
    fun diagnostic(nowMs: Long, appVersion: String): String = buildString {
        append("Pitwall ").append(appVersion).append(" · diagnostic connexion ChatGPT (sans secrets)\n")
        Step.entries.forEach { s ->
            val e = entries.lastOrNull { it.step == s }
            append(if (e == null) "·" else if (e.ok) "✓" else "✗").append(' ').append(s.label)
            e?.let { append(" (il y a ").append(((nowMs - it.atMs) / 1000).coerceAtLeast(0)).append(" s)") }
            e?.detail?.let { append(" : ").append(it) }
            append('\n')
        }
    }.let(::redact)

    fun toJson(): String = JSONArray().also { a ->
        entries.forEach { e -> a.put(JSONObject().put("s", e.step.name).put("ok", e.ok).put("t", e.atMs).apply { e.detail?.let { put("d", it) } }) }
    }.toString()

    companion object {
        const val MAX_ENTRIES = 24

        fun fromJson(json: String?): SignInTrace = runCatching {
            val a = JSONArray(json ?: return SignInTrace())
            SignInTrace((0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                val s = runCatching { Step.valueOf(o.getString("s")) }.getOrNull() ?: return@mapNotNull null
                Entry(s, o.getBoolean("ok"), o.getLong("t"), o.optString("d").ifEmpty { null })
            })
        }.getOrDefault(SignInTrace())

        private val JWT = Regex("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]*)?")
        private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
        private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val QUERY = Regex("[?&](code|state|nonce|client_id|id_token_hint|login_hint|code_verifier|refresh_token|access_token|token)=[^&\\s]*")
        private val LOOPBACK_URL = Regex("https?://127\\.0\\.0\\.1:\\d+\\S*")
        private val LONG_TOKEN = Regex("[A-Za-z0-9_\\-]{40,}")

        /** Retire tout ce qui pourrait être un secret ou une donnée personnelle. */
        fun redact(s: String): String = s
            .replace(JWT, "[jwt]")
            .replace(LOOPBACK_URL, "[adresse de retour]")
            .replace(QUERY) { m -> m.value.first() + m.groupValues[1] + "=[masqué]" }
            .replace(EMAIL, "[e-mail]")
            .replace(UUID, "[uuid]")
            .replace(LONG_TOKEN, "[masqué]")
    }
}
