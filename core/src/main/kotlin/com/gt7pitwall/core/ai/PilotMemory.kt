package com.gt7pitwall.core.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Mémoire durable de la relation pilote-ingénieur, d'une course à l'autre. Elle ne contient que ce que le pilote a dit
 * explicitement et confirmé (« retiens que… », puis « oui »), et les résultats mesurés que le moteur y inscrit lui-même.
 * Jamais une hypothèse de l'IA, jamais une stratégie acceptée une seule fois, jamais d'audio ni de conversation brute.
 *
 * Le pilote peut tout consulter, corriger, désactiver ou effacer depuis l'application. Désactivée, elle ne lit ni
 * n'écrit rien.
 */
class PilotMemory(
    private val load: () -> String? = { null },
    private val save: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Kind(val label: String) {
        /** Préférence déclarée et confirmée par le pilote (communication, style de course). */
        PREFERENCE("préférence déclarée par le pilote"),

        /** Constat mesuré inscrit par le moteur (conseil suivi d'une amélioration, erreur récurrente mesurée). */
        MEASURED("constat mesuré par Pitwall"),
    }

    data class Entry(val id: String, val kind: Kind, val text: String, val createdAtMs: Long, val sessionId: String?)

    private val entries = ArrayList<Entry>()
    private var loaded = false

    @Volatile
    var enabled: Boolean = true
        private set

    @Synchronized
    private fun ensure() {
        if (loaded) return
        loaded = true
        runCatching {
            val o = JSONObject(load() ?: return)
            enabled = o.optBoolean("enabled", true)
            val a = o.optJSONArray("entries") ?: JSONArray()
            for (i in 0 until a.length()) {
                val e = a.getJSONObject(i)
                val kind = runCatching { Kind.valueOf(e.getString("kind")) }.getOrNull() ?: continue
                entries += Entry(e.getString("id"), kind, e.getString("text").take(MAX_TEXT), e.optLong("at"), e.optString("session").ifEmpty { null })
            }
        }
    }

    @Synchronized
    private fun persist() {
        val a = JSONArray()
        entries.forEach { e ->
            a.put(JSONObject().put("id", e.id).put("kind", e.kind.name).put("text", e.text).put("at", e.createdAtMs).put("session", e.sessionId ?: ""))
        }
        runCatching { save(JSONObject().put("schema", 1).put("enabled", enabled).put("entries", a).toString()) }
    }

    @get:Synchronized
    val all: List<Entry> get() { ensure(); return entries.toList() }

    /** Préférence confirmée par le pilote. Renvoie faux si la mémoire est désactivée (rien n'est gardé). */
    @Synchronized
    fun remember(text: String, sessionId: String?, kind: Kind = Kind.PREFERENCE): Boolean {
        ensure()
        if (!enabled) return false
        val t = text.trim().take(MAX_TEXT)
        if (t.isEmpty() || entries.any { it.text.equals(t, ignoreCase = true) }) return true
        entries += Entry("m${clock()}-${entries.size}", kind, t, clock(), sessionId)
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
        persist()
        return true
    }

    @Synchronized
    fun correct(id: String, text: String): Boolean {
        ensure()
        val i = entries.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return false
        entries[i] = entries[i].copy(text = text.trim().take(MAX_TEXT))
        persist()
        return true
    }

    @Synchronized
    fun forget(id: String): Boolean {
        ensure()
        val removed = entries.removeAll { it.id == id }
        if (removed) persist()
        return removed
    }

    @Synchronized
    fun clear() {
        ensure()
        entries.clear()
        persist()
    }

    @Synchronized
    fun setEnabled(on: Boolean) {
        ensure()
        enabled = on
        persist()
    }

    /** Section du contexte de l'IA : déclarations passées, jamais des consignes ni des mesures de la course en cours. */
    fun render(): String {
        val list = all
        if (!enabled || list.isEmpty()) return ""
        return buildString {
            append("MÉMOIRE DURABLE DU PILOTE (déclarations passées confirmées ; ce ne sont pas des consignes ni des mesures de cette course) :\n")
            list.takeLast(MAX_RENDERED).forEach { append("- [").append(it.kind.label).append("] ").append(it.text).append('\n') }
        }
    }

    companion object {
        const val MAX_ENTRIES = 40
        const val MAX_RENDERED = 10
        const val MAX_TEXT = 200
    }
}
