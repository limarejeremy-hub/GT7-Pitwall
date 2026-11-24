package com.gt7pitwall.lab

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pitwall Lab — `fuel-performance <trace...>` : carburant sur traces RÉELLES.
 *
 * 1. Fuel delta : consommation de référence par portion de tour (20 segments de distance, médiane des tours propres)
 *    et écart de chaque tour, segment par segment (MESURE + CALCUL).
 * 2. Roue libre observée : par tour, temps total « ni gaz ni frein, > 100 km/h », compté sur tout le tour (pas seulement
 *    avant les freinages : ce n'est donc qu'un indicateur grossier du lift-and-coast) ; lien tour par tour entre ce temps,
 *    la consommation et le chrono (CALCUL).
 * 3. Coût temps du carburant : pente chrono ↔ consommation sur les tours propres, calibrée sur une course et évaluée
 *    sur les autres (même trace, même voiture). Comparée à l'estimation V512 de 3,2 s par unité (un seul tour réel).
 * 4. Prévision du carburant à l'arrivée après k tours, avec intervalle, comparée au carburant RÉELLEMENT mesuré à
 *    l'arrivée (courses complètes seulement) : erreur et taux de couverture. Deux intervalles : V513 (± 2 écarts-types ×
 *    √tours restants) et CALIBRÉ (multiplicateur = quantile 90 % des erreurs normalisées des AUTRES courses : aucune
 *    course n'évalue son propre calibrage).
 */
object FuelPerformance {
    const val SEGMENTS = 20

    private class LapFuel(val race: Int, val lap: RaceLaps.Lap, val used: Double, val seg: DoubleArray, val coastS: Double, val timeMs: Int)

    fun run(traces: List<File>): JSONObject {
        val perTrace = JSONArray()
        val costEval = JSONArray()
        val forecast = mutableListOf<JSONObject>()
        val perTraceLaps = JSONObject()
        for (f in traces) {
            val races = RaceLaps.load(f)
            val laps = mutableListOf<LapFuel>()
            perTraceLaps.put(f.name, JSONArray(races.map { r -> JSONArray(r.laps.map { it.n }) }))
            races.forEachIndexed { ri, race ->
                for (lap in race.laps.filter { it.clean }) {
                    val len = lap.length.takeIf { it > 500f } ?: continue
                    val seg = DoubleArray(SEGMENTS)
                    lap.samples.zipWithNext().forEach { (a, b) ->
                        val d = b.p.fuelLevel - a.p.fuelLevel
                        if (d <= 0f) seg[((a.dist / len) * SEGMENTS).toInt().coerceIn(0, SEGMENTS - 1)] += (-d).toDouble()
                    }
                    var coast = 0.0
                    lap.samples.zipWithNext().forEach { (a, b) ->
                        if (a.p.throttle == 0 && a.p.brake == 0 && a.p.speedKmh > 100f) coast += (b.tMs - a.tMs).coerceIn(0, 200) / 1000.0
                    }
                    laps += LapFuel(ri + 1, lap, (lap.fuelStart - lap.fuelEnd).toDouble(), seg, coast, lap.timeMs!!)
                }
            }
            if (laps.size < 3) continue
            // 1. Référence par segment.
            val ref = DoubleArray(SEGMENTS) { s -> RaceLaps.median(laps.map { it.seg[s] }) ?: 0.0 }
            val hungry = ref.indices.sortedByDescending { ref[it] }.take(3)
            // 2. Lien roue libre / consommation / chrono (tours propres, par course).
            val links = laps.groupBy { it.race }.map { (race, ls) ->
                JSONObject().put("course", race).put("tours", ls.size)
                    .put("spearman_roue_libre_consommation", RaceLaps.spearman(ls.map { it.coastS }, ls.map { it.used })?.let(::r2) ?: JSONObject.NULL)
                    .put("spearman_consommation_chrono", RaceLaps.spearman(ls.map { it.used }, ls.map { it.timeMs.toDouble() })?.let(::r2) ?: JSONObject.NULL)
                    .put("consommation_mediane", RaceLaps.median(ls.map { it.used })?.let(::r3))
                    .put("consommation_ecart_type", r3(sd(ls.map { it.used })))
                    .put("roue_libre_s_mediane", RaceLaps.median(ls.map { it.coastS })?.let(::r1))
            }
            perTrace.put(JSONObject().put("trace", f.name).put("tours_propres", laps.size)
                .put("reference_par_segment_CALCUL", JSONArray(ref.map(::r3)))
                .put("segments_les_plus_gourmands", JSONArray(hungry.map { "segment ${it + 1}/$SEGMENTS : ${r3(ref[it])}" }))
                .put("par_course", JSONArray(links)))
            // 3. Coût temps par unité : pente chrono = a + b·consommation, calibrée sur une course, testée sur les autres.
            val byRace = laps.groupBy { it.race }
            for ((race, test) in byRace) {
                val train = byRace.filterKeys { it != race }.values.flatten()
                if (train.size < 5 || test.size < 3) continue
                val b = RaceLaps.slope(train.map { it.used }, train.map { it.timeMs.toDouble() }) ?: continue
                val bTest = RaceLaps.slope(test.map { it.used }, test.map { it.timeMs.toDouble() })
                costEval.put(JSONObject().put("trace", f.name).put("course_test", race)
                    .put("s_par_unite_calibre_ailleurs", r2(-b / 1000))
                    .put("s_par_unite_course_test", bTest?.let { r2(-it / 1000) } ?: JSONObject.NULL)
                    .put("signe_coherent", bTest?.let { (it < 0) == (b < 0) } ?: JSONObject.NULL)
                    .put("lecture", "positif = économiser une unité coûte ce temps ; négatif ou incohérent = le chrono ne dépend pas du carburant dans ces tours (trafic, rythme)"))
            }
            // 4. Prévision du carburant à l'arrivée après k tours (médiane des tours propres ± 2 écarts-types × √tours restants).
            races.forEachIndexed { ri, race ->
                // Course incomplète ou tour manquant : le carburant « à l'arrivée » n'est pas mesuré, rien n'est comparé.
                if (!race.complete) return@forEachIndexed
                val all = race.laps
                val actualEnd = all.last().fuelEnd.toDouble()
                val refuelLaps = all.filter { it.refuelled }.map { it.n }.toSet()
                for (idx in 3 until all.size - 1) {
                    val doneLap = all[idx - 1].n // dernier tour terminé au moment de la prévision
                    if (refuelLaps.any { it > doneLap }) continue // un plein plus tard fausserait la comparaison
                    val seen = all.take(idx).filter { it.clean }
                    if (seen.size < 2) continue
                    val per = seen.map { (it.fuelStart - it.fuelEnd).toDouble() }
                    val m = RaceLaps.median(per)!!
                    val s = sd(per).coerceAtLeast(0.02 * m)
                    val remainingLaps = race.totalLaps - doneLap
                    val fuelNow = all[idx - 1].fuelEnd.toDouble()
                    val pred = fuelNow - m * remainingLaps
                    val scale = s * sqrt(remainingLaps.toDouble())
                    val half = 2 * scale
                    val stopAhead = all.any { it.n > doneLap && it.stopped }
                    forecast += JSONObject().put("trace", f.name).put("course", ri + 1).put("apres_tour", doneLap)
                        .put("prevision_arrivee_CALCUL", r2(pred)).put("intervalle", r2(half))
                        .put("mesure_arrivee", r2(actualEnd)).put("erreur", r2(pred - actualEnd))
                        .put("dans_intervalle", abs(pred - actualEnd) <= half)
                        .put("arret_a_venir", stopAhead)
                        .put("erreur_normalisee", (pred - actualEnd) / scale)
                }
            }
        }
        val errs = forecast
        val coverage = if (errs.isEmpty()) null else errs.count { it.getBoolean("dans_intervalle") }.toDouble() / errs.size
        // Intervalle calibré : pour chaque course, multiplicateur appris sur les erreurs normalisées des AUTRES courses.
        fun raceKey(o: JSONObject) = o.getString("trace") + "#" + o.getInt("course")
        var calibratedIn = 0
        var calibratedN = 0
        for (o in errs) {
            val others = errs.filter { raceKey(it) != raceKey(o) }.map { abs(it.getDouble("erreur_normalisee")) }.sorted()
            if (others.size < 5) { o.put("intervalle_calibre", JSONObject.NULL); continue }
            val q = others[((others.size - 1) * 0.9).toInt()]
            val half = q * abs(o.getDouble("intervalle")) / 2
            o.put("multiplicateur_calibre", r2(q)).put("intervalle_calibre", r2(half))
                .put("dans_intervalle_calibre", abs(o.getDouble("erreur")) <= half)
            calibratedN++
            if (abs(o.getDouble("erreur")) <= half) calibratedIn++
        }
        errs.forEach { it.put("erreur_normalisee", r2(it.getDouble("erreur_normalisee"))) }
        val byTrace = errs.groupBy { it.getString("trace") }.map { (t, os) ->
            JSONObject().put("trace", t).put("previsions", os.size)
                .put("erreur_absolue_moyenne", r3(os.map { abs(it.getDouble("erreur")) }.average()))
                .put("couverture_V513", r2(os.count { it.getBoolean("dans_intervalle") }.toDouble() / os.size))
                .put("couverture_calibree", os.filter { it.has("dans_intervalle_calibre") }.let { c -> if (c.isEmpty()) JSONObject.NULL else r2(c.count { it.getBoolean("dans_intervalle_calibre") }.toDouble() / c.size) })
        }
        return JSONObject()
            .put("nature", "Carburant sur traces RÉELLES : MESURE (niveau GT7, pédales, vitesse) et CALCUL (Lab). Aucune trace simulée.")
            .put("tours_par_course", perTraceLaps)
            .put("par_trace", perTrace)
            .put("cout_temps_par_unite", JSONObject().put("estimation_V512_s_par_unite", 3.2).put("evaluations", costEval))
            .put("prevision_arrivee", JSONObject()
                .put("previsions", errs.size)
                .put("erreur_absolue_moyenne", if (errs.isEmpty()) JSONObject.NULL else r3(errs.map { abs(it.getDouble("erreur")) }.average()))
                .put("taux_couverture_intervalle", coverage?.let(::r2) ?: JSONObject.NULL)
                .put("taux_couverture_calibre_autres_courses", if (calibratedN == 0) JSONObject.NULL else r2(calibratedIn.toDouble() / calibratedN))
                .put("courses_evaluees", errs.map(::raceKey).distinct().size)
                .put("par_trace", JSONArray(byTrace))
                .put("limite", "prévisions d'une même course corrélées ; courses d'un même jour peu indépendantes : la couverture n'est pas un test à 95 %")
                .put("detail", JSONArray(errs)))
    }

    private fun sd(x: List<Double>): Double {
        if (x.size < 2) return 0.0
        val m = x.average()
        return sqrt(x.sumOf { (it - m) * (it - m) } / (x.size - 1))
    }

    private fun r1(x: Double) = Math.round(x * 10) / 10.0
    private fun r2(x: Double) = Math.round(x * 100) / 100.0
    private fun r3(x: Double) = Math.round(x * 1000) / 1000.0
}
