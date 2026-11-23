package com.gt7pitwall.lab

import org.json.JSONArray
import org.json.JSONObject

/** Rapports lisibles de `vega-golden` (une version) et comparaison de deux versions sur les mêmes scénarios. */
object VegaReport {
    private fun scenarios(r: JSONObject): Map<String, JSONObject> =
        r.getJSONArray("scenarios").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.associateBy { it.getString("id") } }

    private fun answers(s: JSONObject): List<String> =
        s.optJSONArray("echanges")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("reponse") } } ?: emptyList()

    private fun failed(s: JSONObject): List<String> =
        s.optJSONArray("verifs")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.filter { it.getString("resultat") == "ECHEC" }.map { "${it.getString("verif")} : ${it.getString("detail")}" } } ?: emptyList()

    /** Différences scénario par scénario : amélioration (échec → réussi), régression (réussi → échec), réponse changée. */
    fun diff(a: JSONObject, b: JSONObject, la: String, lb: String): JSONObject {
        require(a.optString("trace") == b.optString("trace")) { "les deux rapports ne portent pas sur la même trace" }
        val sa = scenarios(a)
        val sb = scenarios(b)
        val improvements = JSONArray()
        val regressions = JSONArray()
        val changed = JSONArray()
        var same = 0
        for (id in (sa.keys + sb.keys).distinct()) {
            val x = sa[id]
            val y = sb[id]
            if (x == null || y == null) { changed.put(JSONObject().put("id", id).put("note", "scénario présent dans une seule version")); continue }
            val vx = x.getString("verdict")
            val vy = y.getString("verdict")
            val entry = JSONObject().put("id", id).put("categorie", y.getString("categorie")).put("verdict_$la", vx).put("verdict_$lb", vy)
                .put("reponses_$la", JSONArray(answers(x))).put("reponses_$lb", JSONArray(answers(y)))
                .put("echecs_$la", JSONArray(failed(x))).put("echecs_$lb", JSONArray(failed(y)))
            when {
                vx != "REUSSI" && vy == "REUSSI" -> improvements.put(entry)
                vx == "REUSSI" && vy == "ECHOUE" -> regressions.put(entry)
                answers(x) != answers(y) || vx != vy -> changed.put(entry)
                else -> same++
            }
        }
        return JSONObject()
            .put("trace", a.optString("trace"))
            .put("versions", JSONObject().put("A", la).put("B", lb))
            .put("nature", "Réponses du moteur DÉTERMINISTE de deux versions de Pitwall, mêmes questions, mêmes états reconstitués. Aucun modèle cloud évalué.")
            .put("resume", JSONObject().put("ameliorations", improvements.length()).put("regressions", regressions.length())
                .put("reponses_changees", changed.length()).put("identiques", same)
                .put("score_$la", a.getJSONObject("resume")).put("score_$lb", b.getJSONObject("resume")))
            .put("ameliorations", improvements)
            .put("regressions", regressions)
            .put("reponses_changees", changed)
            .also { r -> a.optString("avertissement").takeIf { it.isNotBlank() }?.let { r.put("avertissement", it) } }
    }

    fun markdown(r: JSONObject): String = buildString {
        appendLine("# Pitwall Lab — vega-golden")
        appendLine()
        appendLine("Trace : `${r.getString("trace")}` · ${r.optString("profil")}")
        appendLine()
        appendLine("> ${r.getString("nature")}")
        r.optString("avertissement").takeIf { it.isNotBlank() }?.let { appendLine(); appendLine("**$it**") }
        val s = r.getJSONObject("resume")
        appendLine()
        appendLine("**${s.getInt("reussis")} réussis · ${s.getInt("echoues")} échoués · ${s.getInt("non_evaluables")} non évaluables**")
        appendLine()
        appendLine("| Catégorie | Réussis | Échoués | Non évaluables |")
        appendLine("|---|---|---|---|")
        val cats = s.getJSONObject("par_categorie")
        cats.keySet().sorted().forEach { k -> cats.getJSONObject(k).let { appendLine("| $k | ${it.getInt("reussis")} | ${it.getInt("echoues")} | ${it.getInt("non_evaluables")} |") } }
        val a = r.getJSONArray("scenarios")
        for (i in 0 until a.length()) {
            val sc = a.getJSONObject(i)
            val mark = when (sc.getString("verdict")) { "REUSSI" -> "✅"; "ECHOUE" -> "❌"; else -> "⚪" }
            appendLine()
            appendLine("### $mark ${sc.getString("id")} — ${sc.getString("categorie")} (${sc.optString("etat_course", sc.getString("point"))})")
            sc.optString("raison").takeIf { it.isNotBlank() }?.let { appendLine("- $it") }
            sc.optJSONArray("echanges")?.let { e -> for (j in 0 until e.length()) e.getJSONObject(j).let {
                appendLine("- **Pilote** : « ${it.getString("question")} »")
                appendLine("- **Véga** (${it.getString("origine")}) : « ${it.getString("reponse").ifBlank { "—" }} »")
            } }
            sc.optJSONArray("verifs")?.let { v -> for (j in 0 until v.length()) v.getJSONObject(j).let {
                if (it.getString("resultat") != "OK") appendLine("- ${it.getString("resultat")} `${it.getString("verif")}` : ${it.getString("detail")}")
            } }
        }
        appendLine()
        appendLine("## Limites")
        r.getJSONArray("limites").let { l -> for (j in 0 until l.length()) appendLine("- ${l.getString(j)}") }
    }

    fun diffMarkdown(d: JSONObject): String = buildString {
        val v = d.getJSONObject("versions")
        val la = v.getString("A")
        val lb = v.getString("B")
        val s = d.getJSONObject("resume")
        appendLine("# Pitwall Lab — vega-golden `$la` → `$lb`")
        appendLine()
        appendLine("Trace : `${d.getString("trace")}`")
        appendLine()
        appendLine("> ${d.getString("nature")}")
        d.optString("avertissement").takeIf { it.isNotBlank() }?.let { appendLine(); appendLine("**$it**") }
        fun score(k: String) = s.getJSONObject(k).let { "${it.getInt("reussis")} réussis, ${it.getInt("echoues")} échoués, ${it.getInt("non_evaluables")} non évaluables" }
        appendLine()
        appendLine("| | `$la` | `$lb` |")
        appendLine("|---|---|---|")
        appendLine("| Score | ${score("score_$la")} | ${score("score_$lb")} |")
        appendLine()
        appendLine("**${s.getInt("ameliorations")} amélioration(s) · ${s.getInt("regressions")} régression(s) · ${s.getInt("reponses_changees")} réponse(s) changée(s) à relire · ${s.getInt("identiques")} identique(s)**")
        for ((key, title) in listOf("regressions" to "Régressions", "ameliorations" to "Améliorations", "reponses_changees" to "Réponses changées (même verdict)")) {
            val a = d.getJSONArray(key)
            if (a.length() == 0) continue
            appendLine()
            appendLine("## $title")
            for (i in 0 until a.length()) {
                val e = a.getJSONObject(i)
                appendLine()
                appendLine("### ${e.getString("id")}" + (e.optString("categorie").takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""))
                e.optString("note").takeIf { it.isNotBlank() }?.let { appendLine("- $it") }
                if (e.has("verdict_$la")) appendLine("- Verdict : ${e.getString("verdict_$la")} → ${e.getString("verdict_$lb")}")
                e.optJSONArray("reponses_$la")?.let { r -> for (j in 0 until r.length()) appendLine("- ➖ `$la` : « ${r.getString(j).ifBlank { "—" }} »") }
                e.optJSONArray("reponses_$lb")?.let { r -> for (j in 0 until r.length()) appendLine("- ➕ `$lb` : « ${r.getString(j).ifBlank { "—" }} »") }
                e.optJSONArray("echecs_$lb")?.let { r -> for (j in 0 until r.length()) appendLine("- échec `$lb` : ${r.getString(j)}") }
            }
        }
    }
}
