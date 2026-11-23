package com.gt7pitwall.lab

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pitwall Lab — `compare` : différences entre deux rejeux de la MÊME trace par deux versions du moteur (rapports
 * `replay`). Les deux versions lisent les mêmes paquets : toute différence vient du moteur, pas de GT7.
 *
 * Catégories :
 * - résultats  : sessions archivées (ligne, résultat, consolidation, révisions, arrêts, tours) et compteurs ;
 * - messages   : ce que Véga dit (texte), avec les alertes (priorité CRITICAL ou HIGH) à part ;
 * - décisions  : journal DECISION, STRATEGY, PIT (plans, arrêts, garde des commandes) ;
 * - positions  : journal POSITION (ligne, révisions, consolidation).
 * Les listes sont comparées comme des séquences (ordre conservé) : retirés (dans A seulement) et ajoutés (dans B).
 */
object Compare {
    private val DECISION_TYPES = setOf("DECISION", "STRATEGY", "PIT")
    private const val MAX_CELLS = 25_000_000L
    private const val MAX_LISTED = 200

    data class SeqDiff(val removed: List<String>, val added: List<String>)

    /** Différence de séquences par plus longue sous-suite commune ; au-delà d'une taille raisonnable, par multiensemble. */
    fun seqDiff(a: List<String>, b: List<String>): SeqDiff {
        if (a.size.toLong() * b.size > MAX_CELLS) {
            val counts = HashMap<String, Int>()
            b.forEach { counts.merge(it, 1, Int::plus) }
            val removed = a.filter { x -> (counts[x] ?: 0).let { if (it > 0) { counts[x] = it - 1; false } else true } }
            val ca = HashMap<String, Int>()
            a.forEach { ca.merge(it, 1, Int::plus) }
            val added = b.filter { x -> (ca[x] ?: 0).let { if (it > 0) { ca[x] = it - 1; false } else true } }
            return SeqDiff(removed, added)
        }
        val n = a.size
        val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        val removed = ArrayList<String>()
        val added = ArrayList<String>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> removed += a[i++]
                else -> added += b[j++]
            }
        }
        while (i < n) removed += a[i++]
        while (j < m) added += b[j++]
        return SeqDiff(removed, added)
    }

    private fun values(r: JSONObject, key: String): JSONArray = r.optJSONObject(key)?.optJSONArray("valeur") ?: JSONArray()

    private fun messages(r: JSONObject, alertsOnly: Boolean): List<String> = values(r, "messages").let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }
            .filter { !alertsOnly || it.optString("priorite") in setOf("CRITICAL", "HIGH") }
            .map { "[${it.optString("priorite")}] ${it.optString("texte")}" }
    }

    private fun journal(r: JSONObject, types: Set<String>): List<String> = values(r, "journal").let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("type") in types }
            .map { o -> "T${o.opt("tour").takeIf { it != JSONObject.NULL } ?: "?"} ${o.optString("type")} : ${o.optString("texte")}" + (o.optString("decision").takeIf { it.isNotBlank() && it != "null" }?.let { " → $it" } ?: "") }
    }

    /** Sessions résumées en champs comparables (dans l'ordre de la trace). */
    private fun sessions(r: JSONObject): List<Map<String, String>> {
        val a = r.optJSONArray("sessions") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { s ->
            fun v(k: String) = s.optJSONObject(k)?.opt("valeur")?.toString() ?: "—"
            mapOf(
                "tours" to (s.optJSONObject("tours")?.optJSONArray("valeur")?.length() ?: 0).toString(),
                "issue" to s.optString("issue"),
                "grille" to v("grille"),
                "ligne" to v("ligne"),
                "resultat_archive" to v("resultat_archive"),
                "consolide" to (s.optJSONObject("resultat_archive")?.opt("consolide")?.toString() ?: "—"),
                "revisions_apres_ligne" to v("revisions_apres_ligne"),
                "arrets" to v("arrets"),
            )
        }
    }

    private fun listed(xs: List<String>) = JSONArray(xs.take(MAX_LISTED))

    fun diff(a: JSONObject, b: JSONObject, labelA: String = "A", labelB: String = "B"): JSONObject {
        require(a.optString("trace") == b.optString("trace")) { "les deux rapports ne portent pas sur la même trace" }
        // Résultats : sessions champ par champ, puis compteurs.
        val sa = sessions(a)
        val sb = sessions(b)
        val results = JSONArray()
        for (i in 0 until maxOf(sa.size, sb.size)) {
            val x = sa.getOrNull(i)
            val y = sb.getOrNull(i)
            if (x == null || y == null) {
                results.put(JSONObject().put("session", i + 1).put("champ", "session").put(labelA, if (x == null) "absente" else "présente").put(labelB, if (y == null) "absente" else "présente"))
                continue
            }
            for (k in x.keys) if (x[k] != y[k]) results.put(JSONObject().put("session", i + 1).put("champ", k).put(labelA, x[k]).put(labelB, y[k]))
        }
        val ca = a.optJSONObject("compteurs")?.optJSONObject("valeur") ?: JSONObject()
        val cb = b.optJSONObject("compteurs")?.optJSONObject("valeur") ?: JSONObject()
        val counters = JSONArray()
        (ca.keySet() + cb.keySet()).sorted().forEach { k -> if (ca.opt(k)?.toString() != cb.opt(k)?.toString()) counters.put(JSONObject().put("compteur", k).put(labelA, ca.opt(k) ?: JSONObject.NULL).put(labelB, cb.opt(k) ?: JSONObject.NULL)) }

        val msg = seqDiff(messages(a, false), messages(b, false))
        val alerts = seqDiff(messages(a, true), messages(b, true))
        val decisions = seqDiff(journal(a, DECISION_TYPES), journal(b, DECISION_TYPES))
        val positions = seqDiff(journal(a, setOf("POSITION")), journal(b, setOf("POSITION")))
        fun block(d: SeqDiff) = JSONObject().put("retires_$labelA", listed(d.removed)).put("ajoutes_$labelB", listed(d.added))
            .put("nombre", d.removed.size + d.added.size)
        val total = results.length() + counters.length() + msg.removed.size + msg.added.size + decisions.removed.size + decisions.added.size + positions.removed.size + positions.added.size
        return JSONObject()
            .put("trace", a.optString("trace"))
            .put("versions", JSONObject().put("A", labelA).put("B", labelB))
            .put("nature", "Différences CALCULÉES par deux versions du moteur sur les mêmes mesures GT7.")
            .put("identique", total == 0)
            .put("resume", JSONObject()
                .put("resultats", results.length() + counters.length())
                .put("messages", msg.removed.size + msg.added.size)
                .put("alertes", alerts.removed.size + alerts.added.size)
                .put("decisions", decisions.removed.size + decisions.added.size)
                .put("positions", positions.removed.size + positions.added.size))
            .put("resultats", results)
            .put("compteurs", counters)
            .put("alertes", block(alerts))
            .put("messages", block(msg))
            .put("decisions", block(decisions))
            .put("positions", block(positions))
            .also { r -> a.optString("avertissement").takeIf { it.isNotBlank() }?.let { r.put("avertissement", it) } }
    }

    /** Résumé Markdown lisible sur le Fold. */
    fun markdown(d: JSONObject): String = buildString {
        val v = d.getJSONObject("versions")
        val la = v.getString("A")
        val lb = v.getString("B")
        appendLine("# Pitwall Lab — comparaison `$la` → `$lb`")
        appendLine()
        appendLine("Trace : `${d.getString("trace")}`. ${d.getString("nature")}")
        d.optString("avertissement").takeIf { it.isNotBlank() }?.let { appendLine(); appendLine("**$it**") }
        appendLine()
        if (d.getBoolean("identique")) {
            appendLine("**Aucune différence** : résultats, messages, décisions et positions identiques.")
            return@buildString
        }
        val r = d.getJSONObject("resume")
        appendLine("| Catégorie | Différences |")
        appendLine("|---|---|")
        listOf("resultats" to "Résultats et compteurs", "alertes" to "Alertes (CRITICAL/HIGH)", "messages" to "Messages de Véga", "decisions" to "Décisions (stratégie, stands)", "positions" to "Positions").forEach { (k, l) -> appendLine("| $l | ${r.getInt(k)} |") }
        val res = d.getJSONArray("resultats")
        if (res.length() > 0) {
            appendLine(); appendLine("## Résultats")
            for (i in 0 until res.length()) res.getJSONObject(i).let { appendLine("- Session ${it.get("session")}, ${it.getString("champ")} : `${it.opt(la)}` → `${it.opt(lb)}`") }
        }
        val cnt = d.getJSONArray("compteurs")
        for (i in 0 until cnt.length()) cnt.getJSONObject(i).let { appendLine("- Compteur ${it.getString("compteur")} : ${it.opt(la)} → ${it.opt(lb)}") }
        for ((k, title) in listOf("alertes" to "Alertes", "messages" to "Messages de Véga", "decisions" to "Décisions", "positions" to "Positions")) {
            val b = d.getJSONObject(k)
            if (b.getInt("nombre") == 0) continue
            appendLine(); appendLine("## $title")
            val rem = b.getJSONArray("retires_$la")
            val add = b.getJSONArray("ajoutes_$lb")
            for (i in 0 until rem.length()) appendLine("- ➖ ($la) ${rem.getString(i)}")
            for (i in 0 until add.length()) appendLine("- ➕ ($lb) ${add.getString(i)}")
        }
    }
}
