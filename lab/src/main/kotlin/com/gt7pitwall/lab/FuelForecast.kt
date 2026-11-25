package com.gt7pitwall.lab

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pitwall Lab — `fuel-forecast <trace...>` (V514-B) : prévision du carburant à l'arrivée, modèle V512 lot 2 contre
 * candidat V514, sur traces RÉELLES, évaluée en laissant de côté un JOUR entier (le jour testé ne sert jamais à la
 * calibration). Courses complètes seulement, aucun plein après la prévision.
 *
 * V512 lot 2 (moteur actuel, [com.gt7pitwall.core.strategy.FuelStrategy]) : moyenne des 3 derniers tours hors voie des
 * stands ; arrivée = carburant − moyenne × tours restants. Pas d'intervalle.
 *
 * Candidat V514 :
 * 1. consommation de référence = médiane des tours comparables (hors départ, hors tours d'entrée et d'arrêt) ;
 * 2. changement durable de régime : deux tours de suite à plus de max(3 σ, 4 %) de la référence, du même côté →
 *    la référence repart des tours depuis le changement ;
 * 3. tours d'entrée et d'arrêt d'un arrêt PRÉVU : consommation = r × 2 tours normaux, r appris sur les AUTRES jours ;
 * 4. incertitude : σ √tours × multiplicateur appris sur les erreurs normalisées des AUTRES jours (quantile 90 %).
 *
 * Danger : une prévision qui SURESTIME le carburant restant de plus d'un demi-tour de consommation (risque de panne).
 * Le candidat ne remplace V512 que s'il fait mieux sur chaque jour indépendant ET n'ajoute aucun danger.
 */
object FuelForecast {
    private class Race(val day: String, val trace: String, val idx: Int, val laps: List<RaceLaps.Lap>, val total: Int)
    private class Point(val day: String, val race: String, val afterLap: Int, val actual: Double, val perLapRef: Double,
                        val v512: Double?, val v514: Double?, val sigmaScale: Double?, val stopAhead: Boolean, val regimeReset: Boolean)

    private fun used(l: RaceLaps.Lap) = (l.fuelStart - l.fuelEnd).toDouble()
    private fun pitLane(l: RaceLaps.Lap) = l.stopped || l.beforeStop || l.refuelled

    private fun sd(x: List<Double>): Double {
        if (x.size < 2) return 0.0
        val m = x.average()
        return sqrt(x.sumOf { (it - m) * (it - m) } / (x.size - 1))
    }

    /** Rapport de consommation des tours d'entrée + d'arrêt sur 2 tours normaux, par arrêt sans plein mesurable. */
    private fun pitRatios(r: Race): List<Double> {
        val normal = RaceLaps.median(r.laps.filter { it.clean }.map(::used)) ?: return emptyList()
        return r.laps.filter { it.stopped && !it.refuelled }.mapNotNull { s ->
            val before = r.laps.firstOrNull { it.n == s.n - 1 && it.beforeStop } ?: return@mapNotNull null
            (used(before) + used(s)) / (2 * normal)
        }
    }

    fun run(traces: List<File>): JSONObject {
        val races = traces.flatMap { f ->
            val day = Regex("trace-(20\\d{6})").find(f.name)?.groupValues?.get(1) ?: f.name
            RaceLaps.load(f).mapIndexedNotNull { i, r -> if (r.complete) Race(day, f.name, i + 1, r.laps, r.totalLaps) else null }
        }
        val days = races.map { it.day }.distinct()
        val points = mutableListOf<Point>()
        val ratioByDay = races.groupBy { it.day }.mapValues { (_, rs) -> rs.flatMap(::pitRatios) }
        for (r in races) {
            val otherRatios = ratioByDay.filterKeys { it != r.day }.values.flatten()
            val ratio = RaceLaps.median(otherRatios)
            val refuels = r.laps.filter { it.refuelled }.map { it.n }
            for (k in 3 until r.total) {
                if (refuels.any { it > k }) continue
                val done = r.laps.filter { it.n <= k }
                val fuelNow = done.last().fuelEnd.toDouble()
                val actual = r.laps.last().fuelEnd.toDouble()
                val remaining = r.total - k
                // V512 lot 2 : moyenne des 3 derniers tours hors voie des stands (départ compris, comme le moteur).
                val v512Samples = done.filter { !pitLane(it) && used(it) > 0.0 }.takeLast(3).map(::used)
                val v512 = if (v512Samples.isEmpty()) null else fuelNow - v512Samples.average() * remaining
                // Candidat V514.
                val comparable = done.filter { it.clean }
                var ref = comparable.map(::used)
                var reset = false
                if (ref.size >= 4) {
                    val base = ref.dropLast(2)
                    val m = RaceLaps.median(base)!!
                    val tol = maxOf(3 * sd(base), 0.04 * m)
                    val last2 = ref.takeLast(2)
                    if (last2.all { it - m > tol } || last2.all { m - it > tol }) { ref = last2; reset = true }
                }
                val m = RaceLaps.median(ref)
                val v514 = m?.let {
                    val future = r.laps.filter { it.n > k }
                    val stop = future.firstOrNull { it.stopped && !it.refuelled }
                    var need = it * remaining
                    // Arrêt prévu (connu du plan) : tours d'entrée et d'arrêt au rapport appris ailleurs.
                    if (stop != null && ratio != null && future.any { f -> f.n == stop.n - 1 }) need -= 2 * it * (1 - ratio)
                    fuelNow - need
                }
                val s = sd(ref).coerceAtLeast(0.02 * (m ?: 0.0))
                points += Point(r.day, "${r.trace}#${r.idx}", k, actual, m ?: 0.0, v512, v514,
                    if (m != null) s * sqrt(remaining.toDouble()) else null,
                    r.laps.any { it.n > k && it.stopped }, reset)
            }
        }
        // Multiplicateur d'incertitude : quantile 90 % des erreurs normalisées des AUTRES jours.
        fun mult(day: String): Double? {
            val z = points.filter { it.day != day && it.v514 != null && it.sigmaScale != null && it.sigmaScale > 0 }
                .map { abs(it.v514!! - it.actual) / it.sigmaScale!! }.sorted()
            return if (z.size < 5) null else z[((z.size - 1) * 0.9).toInt()]
        }
        val perDay = JSONArray()
        var wins = 0
        var dangerAdded = 0
        for (d in days) {
            val ps = points.filter { it.day == d && it.v512 != null && it.v514 != null }
            if (ps.isEmpty()) continue
            val mul = mult(d)
            fun mae(f: (Point) -> Double) = ps.map { abs(f(it) - it.actual) }.average()
            fun bias(f: (Point) -> Double) = ps.map { f(it) - it.actual }.average()
            fun danger(f: (Point) -> Double) = ps.count { f(it) - it.actual > 0.5 * it.perLapRef }
            val m512 = mae { it.v512!! }
            val m514 = mae { it.v514!! }
            val d512 = danger { it.v512!! }
            val d514 = danger { it.v514!! }
            if (m514 < m512) wins++
            if (d514 > d512) dangerAdded += d514 - d512
            val covered = if (mul == null) null else ps.count { abs(it.v514!! - it.actual) <= mul * it.sigmaScale!! }
            perDay.put(JSONObject().put("jour", d).put("previsions", ps.size)
                .put("V512_erreur_moyenne", r3(m512)).put("V512_biais", r3(bias { it.v512!! })).put("V512_dangers", d512)
                .put("V514_erreur_moyenne", r3(m514)).put("V514_biais", r3(bias { it.v514!! })).put("V514_dangers", d514)
                .put("V514_meilleur", m514 < m512)
                .put("multiplicateur_appris_ailleurs", mul?.let(::r2) ?: JSONObject.NULL)
                .put("couverture_V514", covered?.let { r2(it.toDouble() / ps.size) } ?: JSONObject.NULL)
                .put("changements_de_regime", ps.count { it.regimeReset })
                .put("rapport_tours_arret_appris_ailleurs", RaceLaps.median(ratioByDay.filterKeys { it != d }.values.flatten())?.let(::r3) ?: JSONObject.NULL))
        }
        val evaluated = perDay.length()
        val replace = evaluated >= 3 && wins == evaluated && dangerAdded == 0
        return JSONObject()
            .put("nature", "Prévision carburant à l'arrivée sur traces RÉELLES : MESURE (niveau GT7) et CALCUL. Évaluation jour laissé de côté.")
            .put("jours", JSONArray(days))
            .put("par_jour", perDay)
            .put("decision", JSONObject().put("jours_evalues", evaluated).put("jours_V514_meilleur", wins)
                .put("dangers_ajoutes_par_V514", dangerAdded)
                .put("remplacer_V512", replace)
                .put("regle", "remplacer seulement si V514 est meilleur sur CHAQUE jour indépendant (au moins 3) et n'ajoute aucune surestimation dangereuse"))
            .put("detail", JSONArray(points.map { p ->
                JSONObject().put("jour", p.day).put("course", p.race).put("apres_tour", p.afterLap).put("mesure_arrivee", r2(p.actual))
                    .put("V512", p.v512?.let(::r2) ?: JSONObject.NULL).put("V514", p.v514?.let(::r2) ?: JSONObject.NULL)
                    .put("arret_a_venir", p.stopAhead).put("changement_regime", p.regimeReset)
            }))
    }

    private fun r2(x: Double) = Math.round(x * 100) / 100.0
    private fun r3(x: Double) = Math.round(x * 1000) / 1000.0
}
