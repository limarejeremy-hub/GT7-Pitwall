package com.gt7pitwall.lab

import com.gt7pitwall.core.strategy.FuelIntelligence
import com.gt7pitwall.core.strategy.RacePlanner
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Pitwall Lab — `carburant <journal.json>` : rejoue, tour par tour, la décision carburant V512 sur les MESURES d'un
 * journal de course exporté (tours, consommations, carburant à bord, ravitaillements, immobilisations), et la compare
 * aux plans annoncés par la version qui a couru (journal).
 *
 * Ce n'est PAS un rejeu de paquets : la décision est prise en fin de tour (le direct décide aussi en cours de tour), sur
 * la consommation médiane des tours propres mesurés. Natures : MESURE (journal), CALCUL (Lab), ESTIMATION (coûts).
 */
object FuelReplay {
    private data class Lap(val n: Int, val timeMs: Int?, val used: Float?, val after: Float?, val refuelled: Boolean)

    fun run(journal: File): JSONObject {
        require(journal.isFile) { "journal introuvable : ${journal.name}" }
        val root = JSONObject(journal.readText())
        val a = root.getJSONObject("archive")
        val ctx = a.getJSONObject("context")
        val tank = ctx.optDouble("fuelCapacity", 100.0).toFloat()
        val total = ctx.optInt("gameTotalLaps", 0).takeIf { it > 0 } ?: ctx.getJSONObject("rules").optInt("totalLaps")
        val laps = a.getJSONArray("laps").let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }.map {
            Lap(it.getInt("lap"), it.optInt("timeMs", -1).takeIf { t -> t > 0 },
                it.optDouble("fuelUsed", -1.0).toFloat().takeIf { u -> u > 0f && !it.optBoolean("refuelled") },
                it.optDouble("fuelAfter", -1.0).toFloat().takeIf { f -> f >= 0f }, it.optBoolean("refuelled"))
        }
        val stops = a.optJSONArray("stops") ?: JSONArray()
        // Immobilisation de chaque arrêt : événement PIT « immobile 35,0 s » du journal (MESURE).
        val events = root.optJSONArray("events") ?: JSONArray()
        val stopped = mutableMapOf<Int, Float>()
        for (i in 0 until events.length()) {
            val e = events.getJSONObject(i)
            if (e.optString("type") != "PIT") continue
            Regex("immobile (\\d+),(\\d) s").find(e.optString("text"))?.let { m ->
                stopped[e.optInt("lap")] = "${m.groupValues[1]}.${m.groupValues[2]}".toFloat()
            }
        }
        val added = (0 until stops.length()).associate { stops.getJSONObject(it).let { s -> s.getInt("lap") to s.optDouble("fuelAdded", 0.0).toFloat() } }
        val plans = a.optJSONArray("plans") ?: JSONArray()
        val announced = (0 until plans.length()).map { plans.getJSONObject(it) }.map { p ->
            val s = p.optJSONArray("stops") ?: JSONArray()
            JSONObject().put("tour", p.optInt("lap")).put("arrets", JSONArray((0 until s.length()).map { s.getInt(it) }))
                .put("raison", p.optString("reason"))
        }

        val mandatory = ctx.getJSONObject("rules").optInt("mandatoryStops", 0)
        val rows = JSONArray()
        for (k in 1 until total) {
            val lap = laps.firstOrNull { it.n == k } ?: break
            val fuelNow = lap.after ?: continue
            // Comme le moteur : tant qu'un arrêt obligatoire reste à faire, l'arrêt prévu n'est pas arbitré.
            if (added.keys.count { it < k } < mandatory) {
                rows.put(JSONObject().put("apres_tour", k).put("carburant_MESURE", round1(fuelNow)).put("decision_V512", "ARRET_OBLIGATOIRE_A_FAIRE"))
                continue
            }
            val samples = laps.filter { it.n in 2..k && it.used != null && it.n - 1 !in added.keys && it.n !in added.keys }.mapNotNull { it.used }.sorted()
            if (samples.isEmpty()) continue
            val perLap = samples[samples.size / 2]
            val toGo = (total - k).toFloat()
            // Coût d'un arrêt court : d'après le dernier arrêt de la course dont le tour de sortie est bouclé (même règle que le moteur).
            val clean = laps.filter { it.n in 2..k && it.n !in added.keys && it.n - 1 !in added.keys && it.timeMs != null }.mapNotNull { it.timeMs }.sorted()
            val typical = clean.getOrNull(clean.size / 2)
            val deficitGuess = ((toGo + RacePlanner.SAFETY_LAPS) * perLap - fuelNow).coerceAtLeast(0f)
            val own = added.keys.filter { it + 1 <= k }.maxOrNull()?.let { s ->
                val inLap = laps.firstOrNull { it.n == s }?.timeMs
                val outLap = laps.firstOrNull { it.n == s + 1 }?.timeMs
                val st = stopped[s]
                val add = added.getValue(s)
                if (inLap == null || outLap == null || typical == null || st == null || st < 3f || clean.size < 3) null else {
                    val loss = inLap + outLap - 2.0 * typical
                    if (loss < st * 1000.0) return@let null // incohérent : perte plus courte que l'immobilisation
                    val avoided = ((add - (deficitGuess + perLap * 0.5f)).coerceAtLeast(0f) / (add / st)) * 1000.0
                    (loss - avoided).coerceAtLeast(3_000.0)
                }
            }
            val r = FuelIntelligence.decide(FuelIntelligence.Input(fuelNow, perLap, toGo, stopPossible = toGo >= 2f, pitLossMs = own ?: 30_000.0, tank = tank))
            rows.put(JSONObject()
                .put("apres_tour", k)
                .put("carburant_MESURE", round1(fuelNow))
                .put("conso_mediane_MESURE", round2(perLap))
                .put("deficit_avec_marge_CALCUL", round1(r.deficit))
                .put("deficit_sans_marge_CALCUL", round1(r.deficitNoMargin))
                .put("decision_V512", r.kind.name)
                .put("cible_par_tour_CALCUL", r.targetPerLap?.let(::round2) ?: JSONObject.NULL)
                .put("economie_pct_CALCUL", round1((r.savingFraction * 100).toFloat()))
                .put("cout_economie_s_ESTIMATION", r.liftLossMs?.let { round1((it / 1000).toFloat()) } ?: JSONObject.NULL)
                .put("cout_arret_s_ESTIMATION", round1(((own ?: 30_000.0) / 1000).toFloat()))
                .put("source_cout_arret", if (own != null) "arrêt mesuré de cette course" else "30 s par défaut (aucun arrêt mesuré)")
                .put("phrase", FuelIntelligence.sentence(r) ?: JSONObject.NULL))
        }
        val last = laps.lastOrNull()
        return JSONObject()
            .put("journal", journal.name)
            .put("nature", "Décisions CALCULÉES par la règle V512 sur les MESURES d'un journal exporté (pas un rejeu de paquets) ; coûts : ESTIMATION.")
            .put("tours_gt7", total)
            .put("ravitaillements_MESURE", JSONArray(added.map { (l, f) -> JSONObject().put("tour", l).put("ajoute", round1(f)).put("immobile_s", stopped[l] ?: JSONObject.NULL) }))
            .put("plans_annonces_version_courue", JSONArray(announced))
            .put("issue", a.optString("outcome"))
            .put("carburant_a_l_arrivee_MESURE", last?.after?.let(::round1) ?: JSONObject.NULL)
            .put("decisions", rows)
            .put("limites", JSONArray(listOf(
                "Décision en fin de tour seulement ; le moteur décide aussi en cours de tour.",
                "Coût de l'économie : 3,2 s par unité, estimé sur un seul tour réel (23/11, tour 14).",
                "Coût d'un arrêt court : déduit d'un arrêt mesuré (perte − remplissage évité) ; 30 s par défaut avant tout arrêt.",
            )))
    }

    private fun round1(x: Float) = Math.round(x * 10f) / 10.0
    private fun round2(x: Float) = Math.round(x * 100f) / 100.0
}
