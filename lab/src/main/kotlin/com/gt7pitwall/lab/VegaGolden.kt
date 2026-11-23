package com.gt7pitwall.lab

import com.gt7pitwall.core.ai.RadioConversation
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.race.ProfileId
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.SessionKind
import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.telemetry.TelemetryPacket
import com.gt7pitwall.core.telemetry.TelemetryParser
import com.gt7pitwall.core.telemetry.TelemetryTrace
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import kotlin.math.abs

/**
 * Pitwall Lab — `vega-golden` : banc d'évaluation des réponses de Véga sur des états de course RECONSTITUÉS à partir
 * d'une trace GT7 réelle.
 *
 * Pour chaque scénario : la trace est rejouée dans le vrai moteur jusqu'au point demandé (« tour:N » = 5 s après le
 * début du tour N, « arrivee+X » = X ms après la ligne), puis les questions sont posées dans l'ordre à la vraie
 * conversation radio (`RadioConversation`), comme une transcription reçue. Chaque scénario a son propre rejeu : aucun
 * contexte ne passe d'un scénario à l'autre.
 *
 * Origine des réponses : le banc n'a AUCUN modèle cloud ni Gemini. Toute réponse vient donc du moteur déterministe
 * de Pitwall (y compris ses replis quand, en course, une question irait à l'IA). Une réponse marquée IA serait
 * signalée, mais ce banc ne valide JAMAIS un modèle cloud.
 *
 * Faits de référence au point du scénario (pour contrôler les nombres des réponses) :
 * - MESURE : tours restants (tours GT7), carburant (niveau GT7), position (0x84 brut cohérent) ;
 * - MESURE (différences de niveaux) : consommation par tour des derniers tours sans plein ;
 * - CALCUL du Lab : tours de carburant = carburant / consommation ; position archivée = classement d'arrivée du moteur.
 */
object VegaGolden {
    const val LAP_OFFSET_MS = 5_000L

    data class Fact(val value: Double, val source: String)

    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /** Chronos d'une réponse en secondes : « 1 minute 32 secondes 4 », « 1:32.456 », « 1:32,4 ». */
    fun chronos(s: String): List<Double> {
        val out = ArrayList<Double>()
        Regex("(\\d+)\\s*minutes?\\s*(\\d+)\\s*secondes?(?:\\s*(\\d))?").findAll(s).forEach { m ->
            out += m.groupValues[1].toDouble() * 60 + m.groupValues[2].toDouble() + (m.groupValues[3].toDoubleOrNull() ?: 0.0) / 10
        }
        Regex("(\\d+):(\\d{2})[.,](\\d{1,3})").findAll(s).forEach { m ->
            out += m.groupValues[1].toDouble() * 60 + m.groupValues[2].toDouble() + ("0." + m.groupValues[3]).toDouble()
        }
        return out
    }

    fun numbers(s: String): List<Double> =
        Regex("(?<![\\d:])(\\d+(?:[.,]\\d+)?)(?![\\d:])").findAll(s).mapNotNull { it.groupValues[1].replace(',', '.').toDoubleOrNull() }.toList()

    /** Signature de l'état de course modifiable par la radio : plan, arrêts, pneus, règlement, place déclarée. */
    private fun stateSignature(c: RaceController): String =
        listOf(c.plan?.stops?.toString(), c.ledger.stopList.toString(), c.ledger.changes.toString(), c.rules.toString(), c.engineer.driverPosition.toString())
            .joinToString(" | ")

    fun run(trace: File, scenarios: File, series: Series = Series.DAILY_B, kind: SessionKind = SessionKind.RACE): JSONObject {
        require(trace.isFile) { "trace introuvable : ${trace.name}" }
        val spec = JSONObject(scenarios.readText())
        val list = spec.getJSONArray("scenarios")
        val results = JSONArray()
        for (i in 0 until list.length()) results.put(runOne(trace, list.getJSONObject(i), series, kind))
        val byCat = JSONObject()
        var ok = 0
        var ko = 0
        var na = 0
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            val cat = r.getString("categorie")
            val c = byCat.optJSONObject(cat) ?: JSONObject().put("reussis", 0).put("echoues", 0).put("non_evaluables", 0).also { byCat.put(cat, it) }
            when (r.getString("verdict")) {
                "REUSSI" -> { ok++; c.increment("reussis") }
                "ECHOUE" -> { ko++; c.increment("echoues") }
                else -> { na++; c.increment("non_evaluables") }
            }
        }
        return JSONObject()
            .put("trace", trace.name)
            .put("profil", "${series.name} ${kind.name} (choisi pour le rejeu, pas une détection)")
            .put("nature", "Réponses du moteur DÉTERMINISTE de Pitwall (aucun modèle cloud ni Gemini dans le banc) sur des états reconstitués d'une trace GT7.")
            .put("resume", JSONObject().put("reussis", ok).put("echoues", ko).put("non_evaluables", na).put("par_categorie", byCat))
            .put("scenarios", results)
            .put("limites", JSONArray(listOf(
                "Aucun modèle IA n'est appelé : les questions qui iraient à GPT en course reçoivent ici la réponse locale de repli.",
                "Les questions sont des transcriptions propres : la reconnaissance vocale (erreurs Vosk/Google) n'est pas testée.",
                "Trace échantillonnée (souvent 20 Hz) et sans historique du pilote : le coaching peut différer du direct.",
                "Les vérifications sont des règles écrites dans scenarios.json : elles ne jugent pas le ton ni la concision.",
            )))
            .also { if (Gabarit.MARK in trace.name) it.put("avertissement", "Trace GABARIT fabriquée pour les tests : aucune valeur n'est une mesure réelle de GT7.") }
    }

    private fun JSONObject.increment(k: String) = put(k, getInt(k) + 1)

    private fun runOne(trace: File, sc: JSONObject, series: Series, kind: SessionKind): JSONObject {
        val point = sc.getString("point")
        val clock = TelemetryTrace.VirtualClock(0)
        val c = RaceController(storage = MemoryStorage(), clock = clock::now)
        c.selectProfile(ProfileId(series, kind))
        val conv = RadioConversation(c, clock = clock::now)
        var lapStart: Long? = null
        var finishAt: Long? = null
        var asked: JSONObject? = null
        TelemetryTrace.stream(trace.inputStream()) { frame ->
            clock.nowMs = frame.atMs.toLong()
            val p = TelemetryParser.parseDecrypted(frame.plain) ?: return@stream true
            c.onPacket(p)
            val now = clock.nowMs
            val due = when {
                point.startsWith("tour:") -> {
                    val n = point.removePrefix("tour:").toInt()
                    if (p.totalLaps > 0 && p.currentLap == n && !p.isLoading && !p.isPaused) {
                        if (lapStart == null) lapStart = now
                        now - lapStart!! >= LAP_OFFSET_MS
                    } else {
                        if (lapStart != null && p.currentLap != n) lapStart = null
                        false
                    }
                }
                point.startsWith("arrivee+") -> {
                    if (finishAt == null && p.totalLaps > 0 && p.currentLap > p.totalLaps) finishAt = now
                    finishAt?.let { now - it >= point.removePrefix("arrivee+").toLong() } == true
                }
                else -> error("point inconnu : $point")
            }
            if (due) {
                asked = ask(c, conv, p, sc, now)
                false
            } else {
                true
            }
        }
        return asked ?: JSONObject().put("id", sc.getString("id")).put("categorie", sc.getString("categorie")).put("point", point)
            .put("verdict", "NON_EVALUABLE").put("raison", "point « $point » non atteint dans cette trace")
    }

    private fun facts(c: RaceController, p: TelemetryPacket, now: Long): Map<String, Fact> = buildMap {
        if (p.totalLaps > 0 && p.currentLap in 1..p.totalLaps) put("tours_restants", Fact((p.totalLaps - p.currentLap + 1).toDouble(), "MESURE (tours GT7 : ${p.currentLap}/${p.totalLaps}, tour en cours compris)"))
        if (p.fuelCapacity > 0f) put("carburant", Fact(p.fuelLevel.toDouble(), "MESURE (niveau GT7)"))
        val used = c.snapshot.laps.filter { !it.refuelled && (it.fuelUsed ?: 0f) > 0f }.takeLast(3).mapNotNull { it.fuelUsed?.toDouble() }
        if (used.isNotEmpty() && p.fuelCapacity > 0f) {
            val per = used.average()
            put("conso_par_tour", Fact(per, "MESURE (différence de niveaux sur ${used.size} tour(s) sans plein)"))
            put("tours_de_carburant", Fact(p.fuelLevel / per, "CALCUL du Lab (carburant ÷ consommation)"))
        }
        if (p.preRaceCarCount in 1..40 && p.preRacePosition in 1..p.preRaceCarCount) put("position", Fact(p.preRacePosition.toDouble(), "MESURE (0x84 brut cohérent)"))
        c.positionTracker.finalPosition?.let { put("position_archivee", Fact(it.toDouble(), "CALCUL du moteur (classement d'arrivée)")) }
        if (p.lastLapMs > 0) put("dernier_tour_s", Fact(p.lastLapMs / 1000.0, "MESURE (dernier temps GT7, 0x7C)"))
    }

    private fun ask(c: RaceController, conv: RadioConversation, p: TelemetryPacket, sc: JSONObject, now: Long): JSONObject {
        val f = facts(c, p, now)
        val before = stateSignature(c)
        val qs = sc.getJSONArray("questions")
        val turns = JSONArray()
        var lastText = ""
        val allTexts = mutableListOf<String>()
        var anyAi = false
        for (i in 0 until qs.length()) {
            val q = qs.getString(i)
            val msgs = runBlocking { conv.onUtterance(q, q, null) }.filter { it.category != MessageCategory.DRIVER }
            val text = msgs.joinToString(" ") { it.text }.trim()
            val ai = msgs.any { it.fromAi }
            anyAi = anyAi || ai
            lastText = text
            allTexts += text
            turns.put(JSONObject().put("question", q).put("reponse", text)
                .put("origine", if (ai) "IA" else if (text.isEmpty()) "aucune réponse" else "moteur déterministe")
                .put("route", conv.lastRoute?.text ?: JSONObject.NULL))
        }
        val after = stateSignature(c)
        val checks = JSONArray()
        var failed = false
        var evaluable = 0
        fun check(name: String, ok: Boolean?, detail: String) {
            checks.put(JSONObject().put("verif", name).put("resultat", when (ok) { true -> "OK"; false -> "ECHEC"; null -> "NON_EVALUABLE" }).put("detail", detail))
            if (ok == false) failed = true
            if (ok != null) evaluable++
        }
        val v = sc.getJSONObject("verifs")
        val answer = norm(lastText)
        if (v.optBoolean("non_vide")) check("non_vide", lastText.isNotBlank(), if (lastText.isBlank()) "aucune réponse dite" else "réponse présente")
        if (v.optBoolean("toutes_non_vides")) check("toutes_non_vides", allTexts.all { it.isNotBlank() }, "${allTexts.count { it.isNotBlank() }}/${allTexts.size} réponses")
        if (v.optBoolean("etat_inchange")) check("etat_inchange", before == after, if (before == after) "plan, arrêts, pneus, règlement et place déclarée inchangés" else "état modifié : $before → $after")
        v.optJSONArray("contient_un_de")?.let { a ->
            val words = (0 until a.length()).map { a.getString(it) }
            val hit = words.firstOrNull { norm(it) in answer }
            check("contient_un_de", hit != null, hit?.let { "contient « $it »" } ?: "aucun de ${words}")
        }
        v.optJSONArray("ne_contient_pas")?.let { a ->
            val words = (0 until a.length()).map { a.getString(it) }
            val hit = words.firstOrNull { norm(it) in answer }
            check("ne_contient_pas", hit == null, hit?.let { "contient « $it »" } ?: "aucun terme interdit")
        }
        v.optJSONObject("nombre_proche")?.let { n ->
            val name = n.getString("fait")
            val tol = n.getDouble("tolerance")
            val fact = f[name]
            if (fact == null) {
                check("nombre_proche:$name", null, "fait « $name » indisponible à ce point de la trace")
            } else {
                val nums = numbers(lastText) + chronos(lastText)
                val near = nums.firstOrNull { abs(it - fact.value) <= tol + 1e-9 }
                check("nombre_proche:$name", near != null, "référence ${"%.2f".format(fact.value)} (${fact.source}), tolérance $tol ; nombres de la réponse : $nums")
            }
        }
        v.optJSONObject("max_nombre_motif")?.let { m ->
            val rx = Regex(m.getString("motif"), RegexOption.IGNORE_CASE)
            val max = m.getDouble("max")
            val found = rx.findAll(lastText).mapNotNull { it.groupValues.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull() }.toList()
            val over = found.filter { it > max }
            check("max_nombre_motif", over.isEmpty(), "${m.optString("libelle", m.getString("motif"))} : valeurs $found, maximum $max")
        }
        val verdict = when {
            failed -> "ECHOUE"
            evaluable == 0 -> "NON_EVALUABLE"
            else -> "REUSSI"
        }
        val factsJson = JSONObject()
        f.forEach { (k, x) -> factsJson.put(k, JSONObject().put("valeur", "%.2f".format(x.value)).put("source", x.source)) }
        return JSONObject()
            .put("id", sc.getString("id"))
            .put("categorie", sc.getString("categorie"))
            .put("point", sc.getString("point"))
            .put("t_ms", now)
            .put("etat_course", "tour ${p.currentLap}/${p.totalLaps}, ${p.speedKmh.toInt()} km/h")
            .put("faits", factsJson)
            .put("echanges", turns)
            .put("origine", if (anyAi) "IA (signalée : non validée par ce banc)" else "moteur déterministe")
            .put("verifs", checks)
            .put("verdict", verdict)
    }
}
