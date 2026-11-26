package com.gt7pitwall.lab

import com.gt7pitwall.core.blackbox.BlackBoxPack
import com.gt7pitwall.core.race.SessionKind
import com.gt7pitwall.core.race.Series
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * `lab import-course <pack.zip> [rapport.md]` : importe un pack de course Black Box.
 *
 * 1. Vérifie le pack (manifeste, fichiers obligatoires, SHA-256, trace relue en entier).
 * 2. Lit le manifeste et, pour chaque course, rejoue `telemetry.gt7trace` dans le moteur ACTUEL ([PitwallLab.replay]).
 * 3. Compare ce qui a été réellement émis en course (`EN_COURSE` : journal, stratégie, radio, archive) à ce que le
 *    moteur actuel recalcule (`SIMULATION`) : tours, arrêts, verdicts de stratégie, messages, résultat.
 * 4. Signale les différences et les informations manquantes, dans un rapport JSON (et Markdown si demandé), lisible
 *    par Claude ou ChatGPT.
 *
 * Les deux origines ne sont JAMAIS mélangées : chaque bloc du rapport porte `origine`.
 */
object ImportCourse {
    const val EN_COURSE = "EN_COURSE"
    const val SIMULATION = "SIMULATION"

    fun run(zip: File, reportMd: File? = null): JSONObject {
        require(zip.isFile) { "pack introuvable : ${zip.name}" }
        val v = BlackBoxPack.verify(zip)
        val out = JSONObject().put("pack", zip.name)
            .put("verification", JSONObject().put("ok", v.ok).put("statut", v.status).put("fichiers", v.files).put("problemes", JSONArray(v.problems)))
        val courses = JSONArray()
        ZipFile(zip).use { z ->
            fun text(path: String): String? = z.getEntry(path)?.let { e -> z.getInputStream(e).readBytes().toString(Charsets.UTF_8) }
            fun lines(path: String): List<JSONObject> = text(path)?.lines()?.filter { it.isNotBlank() }?.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }.orEmpty()
            val manifest = JSONObject(text("manifest.json") ?: error("manifest.json absent : ce n'est pas un pack Black Box"))
            require(manifest.optString("format") == BlackBoxPack.FORMAT) { "format inconnu : ${manifest.optString("format")}" }
            out.put("manifeste", JSONObject().put("format", manifest.getString("format")).put("version", manifest.getInt("formatVersion"))
                .put("type", manifest.optString("kind")).put("cree", manifest.optString("createdAt")).put("generateur", manifest.optJSONObject("generator")))
            out.put("manquants_declares", manifest.optJSONArray("missing") ?: JSONArray())
            val sessions = manifest.getJSONArray("sessions")
            for (i in 0 until sessions.length()) {
                val s = sessions.getJSONObject(i)
                val folder = s.getString("folder").let { if (it == ".") "" else it }
                val race = text(folder + "race.json")?.let(::JSONObject) ?: JSONObject()
                val strategy = lines(folder + "strategy.jsonl")
                val radio = lines(folder + "radio.jsonl")
                val timeline = lines(folder + "timeline.jsonl")
                courses.put(course(z, folder, s, race, strategy, radio, timeline))
            }
        }
        out.put("courses", courses)
        out.put("regle", "« EN_COURSE » = réellement émis ou mesuré pendant la course (pack) ; « SIMULATION » = recalculé par le moteur actuel lors du rejeu. Jamais confondus.")
        reportMd?.let { it.writeText(markdown(out)); out.put("rapport_markdown", it.name) }
        return out
    }

    private fun course(z: ZipFile, folder: String, s: JSONObject, race: JSONObject, strategy: List<JSONObject>, radio: List<JSONObject>, timeline: List<JSONObject>): JSONObject {
        val o = JSONObject().put("session", s.getString("sessionId")).put("titre", s.optString("title")).put("dossier", folder.ifEmpty { "." })
        val missing = mutableListOf<String>()

        // EN COURSE : ce que le pack contient, sans recalcul.
        val laps = race.optJSONObject("tours")?.optJSONArray("liste")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
        val stops = race.optJSONObject("stands")?.optJSONArray("arrets_archives")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).getInt("tour") } }.orEmpty()
        val verdicts = strategy.filter { it.optString("categorie") == "VERDICT_STRATEGY_ASSURANCE" }.map { it.optString("texte") }
        val vegaSaid = radio.filter { it.optString("role") == "Véga" }.map { it.optString("texte") }
        val counts = timeline.groupingBy { it.optString("type") }.eachCount()
        val recorded = JSONObject().put("origine", EN_COURSE)
            .put("issue", race.optString("issue", "INCONNU"))
            .put("tours", laps.size).put("temps_ms", JSONArray(laps.map { it.opt("temps_ms") }))
            .put("arrets", JSONArray(stops))
            .put("ravitaillements_mesures", race.optJSONObject("stands")?.optJSONArray("ravitaillements_mesures")?.length() ?: 0)
            .put("changements_de_position_mesures", race.optJSONObject("changements_de_position")?.optJSONArray("liste")?.length() ?: 0)
            .put("verdicts_strategie", JSONArray(verdicts))
            .put("plans", strategy.count { it.optString("categorie") == "PLAN" })
            .put("questions_pilote", radio.count { it.optString("role") == "pilote" })
            .put("messages_vega", vegaSaid.size)
            .put("evenements_par_type", JSONObject(counts))
            .put("resultat", race.optJSONObject("arrivee") ?: JSONObject.NULL)
        o.put("en_course", recorded)

        // SIMULATION : rejeu de la trace dans le moteur actuel.
        val entry = z.getEntry(folder + BlackBoxPack.TRACE)
        if (entry == null) {
            missing += "pas de télémétrie dans le pack : rejeu impossible, aucune comparaison avec le moteur actuel"
            o.put("rejeu", JSONObject().put("origine", SIMULATION).put("fait", false))
        } else {
            val tmp = File.createTempFile("pitwall-import-", ".gt7trace")
            try {
                z.getInputStream(entry).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                val ep = race.optJSONObject("epreuve")
                val series = ep?.optString("serie")?.let { n -> Series.entries.firstOrNull { it.name == n } }
                val kind = ep?.optString("type")?.let { n -> SessionKind.entries.firstOrNull { it.name == n } }
                if (series == null || kind == null) missing += "épreuve inconnue dans le pack : rejeu avec ${Series.DAILY_B.name} ${SessionKind.RACE.name} (choisi, pas détecté)"
                val rep = PitwallLab.replay(tmp, series ?: Series.DAILY_B, kind ?: SessionKind.RACE)
                val rs = rep.getJSONArray("sessions").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
                val main = rs.maxByOrNull { it.getJSONObject("tours").getJSONArray("valeur").length() }
                val rLaps = main?.getJSONObject("tours")?.getJSONArray("valeur")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
                val rStops = main?.getJSONObject("arrets")?.getJSONArray("valeur")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).getInt("tour") } }.orEmpty()
                val journal = rep.getJSONObject("journal").getJSONArray("valeur").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
                val rVerdicts = journal.filter { it.optString("texte").startsWith("Stratégie V515") }.map { it.optString("texte") }
                val rSaid = rep.getJSONObject("messages").getJSONArray("valeur").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("texte") } }
                o.put("rejeu", JSONObject().put("origine", SIMULATION).put("fait", true)
                    .put("note", "recalculé par le moteur actuel à partir de la trace ; ces messages n'ont PAS été émis en course")
                    .put("sessions", rs.size).put("tours", rLaps.size).put("temps_ms", JSONArray(rLaps.map { it.opt("temps_ms") }))
                    .put("arrets", JSONArray(rStops)).put("verdicts_strategie", JSONArray(rVerdicts)).put("messages", rSaid.size)
                    .put("resultat", main?.opt("resultat_archive") ?: JSONObject.NULL))

                // Différences.
                val diffs = JSONArray()
                fun diff(what: String, a: Any?, b: Any?) { if (a.toString() != b.toString()) diffs.put(JSONObject().put("sujet", what).put(EN_COURSE, a ?: JSONObject.NULL).put(SIMULATION, b ?: JSONObject.NULL)) }
                diff("nombre de tours", laps.size, rLaps.size)
                diff("chronos (ms)", laps.map { it.opt("temps_ms") }, rLaps.map { it.opt("temps_ms") })
                diff("tours d'arrêt", stops, rStops)
                diff("suite des verdicts de stratégie", verdicts, rVerdicts)
                val onlyLive = vegaSaid.filter { it !in rSaid }
                val onlyReplay = rSaid.filter { it !in vegaSaid }
                if (onlyLive.isNotEmpty() || onlyReplay.isNotEmpty()) diffs.put(JSONObject().put("sujet", "messages de Véga")
                    .put("seulement_$EN_COURSE", JSONArray(onlyLive.take(30))).put("seulement_$SIMULATION", JSONArray(onlyReplay.take(30)))
                    .put("note", "les réponses aux questions du pilote ne sont pas rejouées (aucun son, aucune question au rejeu)"))
                o.put("differences", diffs)
            } finally {
                tmp.delete()
            }
        }
        if (laps.isEmpty()) missing += "aucun tour archivé dans le pack"
        if (radio.isEmpty()) missing += "aucun échange radio dans le pack"
        o.put("manquants", JSONArray(missing))
        o.put("evenements_retrouves", JSONObject()
            .put("ravitaillements", counts["RAVITAILLEMENT"] ?: 0).put("immobilisations", counts["IMMOBILISATION"] ?: 0)
            .put("changements_de_position", counts["POSITION_MESUREE"] ?: 0).put("coupures_flux", counts["COUPURE_FLUX"] ?: 0)
            .put("evenements_journal", timeline.count { it.opt("seq") != JSONObject.NULL }))
        return o
    }

    private fun markdown(r: JSONObject): String = buildString {
        appendLine("# Pitwall Lab — import du pack `${r.getString("pack")}`")
        appendLine()
        val v = r.getJSONObject("verification")
        appendLine("**Vérification :** ${v.getString("statut")}")
        appendLine()
        appendLine("> ${r.getString("regle")}")
        appendLine()
        val c = r.getJSONArray("courses")
        for (i in 0 until c.length()) {
            val o = c.getJSONObject(i)
            appendLine("## ${o.optString("titre").ifEmpty { o.getString("session") }}")
            val live = o.getJSONObject("en_course")
            appendLine("### En course (`EN_COURSE`)")
            appendLine("- Issue ${live.getString("issue")}, ${live.getInt("tours")} tour(s), arrêts ${live.getJSONArray("arrets")}")
            appendLine("- ${live.getInt("ravitaillements_mesures")} ravitaillement(s) mesuré(s), ${live.getInt("changements_de_position_mesures")} changement(s) de classement mesuré(s)")
            appendLine("- ${live.getJSONArray("verdicts_strategie").length()} verdict(s) de stratégie, ${live.getInt("plans")} plan(s), ${live.getInt("questions_pilote")} question(s), ${live.getInt("messages_vega")} message(s) de Véga")
            val rep = o.getJSONObject("rejeu")
            appendLine("### Rejeu dans le moteur actuel (`SIMULATION`)")
            if (!rep.getBoolean("fait")) appendLine("- Non fait (pas de télémétrie).") else {
                appendLine("- ${rep.getInt("tours")} tour(s), arrêts ${rep.getJSONArray("arrets")}, ${rep.getJSONArray("verdicts_strategie").length()} verdict(s), ${rep.getInt("messages")} message(s) recalculés")
                val d = o.optJSONArray("differences") ?: JSONArray()
                appendLine("### Différences (${d.length()})")
                for (k in 0 until d.length()) {
                    val x = d.getJSONObject(k)
                    appendLine("- **${x.getString("sujet")}** : " + x.keys().asSequence().filter { it != "sujet" }.joinToString(" ; ") { "$it = ${x.get(it)}" })
                }
            }
            val m = o.getJSONArray("manquants")
            if (m.length() > 0) {
                appendLine("### Manquants")
                for (k in 0 until m.length()) appendLine("- ${m.getString(k)}")
            }
            appendLine()
        }
        val md = r.getJSONArray("manquants_declares")
        if (md.length() > 0) {
            appendLine("## Manquants déclarés par le pack")
            for (k in 0 until md.length()) appendLine("- ${md.getString(k)}")
        }
    }
}
