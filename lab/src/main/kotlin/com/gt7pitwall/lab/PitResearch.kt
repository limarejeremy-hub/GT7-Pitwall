package com.gt7pitwall.lab

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.hypot

/**
 * Pitwall Lab — `pit-research <trace...>` : phases d'un passage au stand et temps réellement perdu, sur traces RÉELLES.
 *
 * Référence : la trajectoire X/Z d'un tour propre de la même course (MESURE). Pour chaque arrêt (immobile ≥ 3 s) :
 * - entrée de voie : premier point où la voiture s'écarte de plus de [ENTRY_M] de la trajectoire de référence, en
 *   continu jusqu'à l'arrêt ;
 * - trajet dans la voie, immobilisation (vitesse < 1 km/h), ravitaillement (niveau qui monte) ;
 * - retour en piste : premier point après l'arrêt revenu à moins de [REJOIN_M] de la référence pendant 2 s.
 * Temps perdu (CALCUL) : temps entre [MARGIN_M] avant l'entrée et [MARGIN_M] après le retour, moins le temps médian
 * des tours propres sur la même portion de distance. Aucun point n'est deviné : sans tour de référence, rien n'est
 * calculé.
 */
object PitResearch {
    const val ENTRY_M = 8f
    const val REJOIN_M = 4f
    const val MARGIN_M = 200f
    const val LANE_MAX_KMH = 105f

    private class P(val tMs: Long, val x: Float, val z: Float, val v: Float, val fuel: Float, val gdist: Float, val lap: Int)

    fun run(traces: List<File>): JSONObject {
        val stops = JSONArray()
        for (f in traces) {
            RaceLaps.load(f).forEachIndexed { ri, race ->
                val clean = race.laps.filter { it.clean }
                val ref = clean.minByOrNull { it.timeMs ?: Int.MAX_VALUE } ?: return@forEachIndexed
                val refPts = ref.samples.map { it.p.positionX to it.p.positionZ }
                val refLen = ref.length
                fun deviation(x: Float, z: Float): Float {
                    var best = Float.MAX_VALUE
                    for (i in 0 until refPts.size - 1 step 2) { val d = hypot(x - refPts[i].first, z - refPts[i].second); if (d < best) best = d }
                    return best
                }
                // Distance de référence le long du tour propre (projection du point le plus proche).
                fun refDist(x: Float, z: Float): Float = ref.samples.minByOrNull { hypot(x - it.p.positionX, z - it.p.positionZ) }!!.dist
                for (idx in race.laps.indices) {
                    val lap = race.laps[idx]
                    if (!lap.stopped) continue
                    val next = race.laps.getOrNull(idx + 1)
                    val prev = race.laps.getOrNull(idx - 1)
                    // L'approche peut être dans le tour précédent (arrêt juste après la ligne : observé le 06/11).
                    val pts = (prev?.samples?.map { P(it.tMs, it.p.positionX, it.p.positionZ, it.p.speedKmh, it.p.fuelLevel, it.dist - prev.length, prev.n) } ?: emptyList()) + (lap.samples.map { P(it.tMs, it.p.positionX, it.p.positionZ, it.p.speedKmh, it.p.fuelLevel, it.dist, lap.n) } +
                        (next?.samples?.map { P(it.tMs, it.p.positionX, it.p.positionZ, it.p.speedKmh, it.p.fuelLevel, it.dist + lap.length, next.n) } ?: emptyList()))
                    val stopStart = pts.indexOfFirst { it.lap == lap.n && it.v < 1f && pts.drop(pts.indexOf(it)).takeWhile { q -> q.v < 1f }.let { run -> run.size > 1 && run.last().tMs - it.tMs >= 3_000 } }
                    if (stopStart < 0) continue
                    val stopEnd = (stopStart until pts.size).firstOrNull { pts[it].v >= 1f } ?: continue
                    val dev = pts.map { deviation(it.x, it.z) }
                    // Entrée : écart à la trajectoire de référence, OU approche continue à vitesse de voie (≤ 105 km/h)
                    // jusqu'à l'arrêt (voie parallèle à la piste, à moins de 8 m : observé le 06/11).
                    var byDev = stopStart
                    while (byDev > 0 && dev[byDev - 1] > ENTRY_M) byDev--
                    var bySpeed = stopStart
                    while (bySpeed > 0 && pts[bySpeed - 1].v <= LANE_MAX_KMH) bySpeed--
                    val entry = minOf(byDev, bySpeed)
                    var rejoin: Int? = null
                    for (i in stopEnd until pts.size) {
                        if (dev[i] < REJOIN_M) {
                            val j = (i until pts.size).takeWhile { dev[it] < REJOIN_M }.lastOrNull() ?: i
                            if (pts[j].tMs - pts[i].tMs >= 2_000) { rejoin = i; break }
                        }
                    }
                    val refuel = pts.subList(stopStart, stopEnd + 1).let { s -> (s.maxOf { it.fuel } - s.first().fuel) }
                    val o = JSONObject().put("trace", f.name).put("course", ri + 1).put("tour", lap.n)
                        .put("immobile_s_MESURE", r1((pts[stopEnd].tMs - pts[stopStart].tMs) / 1000.0))
                        .put("carburant_ajoute_MESURE", r1(refuel.toDouble()))
                        .put("entree_voie_detectee", entry < stopStart)
                    if (entry < stopStart) {
                        o.put("entree_a_m_du_debut_du_tour", r1(pts[entry].gdist.toDouble()))
                            .put("entree_fraction_tour_reference", r2((refDist(pts[entry].x, pts[entry].z) / refLen).toDouble()))
                            .put("trajet_avant_arret_s", r1((pts[stopStart].tMs - pts[entry].tMs) / 1000.0))
                            .put("vitesse_mediane_voie_kmh", RaceLaps.median(pts.subList(entry, stopStart).map { it.v.toDouble() })?.let(::r1))
                            .put("arret_fraction_tour_reference", r2((refDist(pts[stopStart].x, pts[stopStart].z) / refLen).toDouble()))
                    }
                    if (rejoin != null) {
                        o.put("sortie_vers_retour_s", r1((pts[rejoin].tMs - pts[stopEnd].tMs) / 1000.0))
                            .put("retour_fraction_tour_reference", r2((refDist(pts[rejoin].x, pts[rejoin].z) / refLen).toDouble()))
                    }
                    // Temps perdu : portion [entrée - marge, retour + marge] comparée aux tours propres sur les mêmes fractions.
                    if (entry < stopStart && rejoin != null) {
                        val fromFrac = ((refDist(pts[entry].x, pts[entry].z) - MARGIN_M) / refLen)
                        val toFrac = ((refDist(pts[rejoin].x, pts[rejoin].z) + MARGIN_M) / refLen)
                        val a = pts.indexOfFirst { it.gdist >= pts[entry].gdist - MARGIN_M }.coerceAtLeast(0)
                        val b = pts.indexOfFirst { it.gdist >= pts[rejoin].gdist + MARGIN_M }.takeIf { it >= 0 } ?: (pts.size - 1)
                        val pitTime = (pts[b].tMs - pts[a].tMs) / 1000.0
                        val normal = race.laps.zipWithNext().filter { (x, y) -> x.clean && y.clean && y.n == x.n + 1 }
                            .mapNotNull { (x, y) -> windowTime(x, y, fromFrac, toFrac) }
                        val med = RaceLaps.median(normal)
                        o.put("portion_fraction_tour", JSONArray(listOf(r2(fromFrac.toDouble()), r2(toFrac.toDouble()))))
                            .put("temps_portion_arret_s", r1(pitTime))
                            .put("temps_portion_normal_median_s", med?.let(::r1) ?: JSONObject.NULL)
                            .put("tours_de_reference", normal.size)
                            .put("temps_perdu_s_CALCUL", med?.let { r1(pitTime - it) } ?: JSONObject.NULL)
                            .put("temps_perdu_hors_immobilisation_s_CALCUL", med?.let { r1(pitTime - it - (pts[stopEnd].tMs - pts[stopStart].tMs) / 1000.0) } ?: JSONObject.NULL)
                    }
                    stops.put(o)
                }
            }
        }
        return JSONObject()
            .put("nature", "Phases du stand et temps perdu sur traces RÉELLES : MESURE (X/Z, vitesse, carburant) et CALCUL (Lab).")
            .put("seuils", JSONObject().put("entree_m", ENTRY_M).put("retour_m", REJOIN_M).put("marge_m", MARGIN_M))
            .put("arrets", stops)
    }

    /**
     * Temps entre la fraction [from] et la fraction [to] de deux tours propres consécutifs [a] puis [b] : la portion peut
     * franchir la ligne (from > to, ou from < 0, ou to > 1).
     */
    private fun windowTime(a: RaceLaps.Lap, b: RaceLaps.Lap, from: Float, to: Float): Double? {
        fun at(l: RaceLaps.Lap, f: Float) = l.samples.firstOrNull { it.dist >= f * l.length }?.tMs
        val start = when {
            from < 0f -> null
            else -> at(a, from)
        } ?: return null
        val end = if (to > 1f) at(b, to - 1f) else if (to < from) at(b, to) else at(a, to)
        return end?.let { (it - start) / 1000.0 }?.takeIf { it > 0 }
    }

    private fun r1(x: Double) = Math.round(x * 10) / 10.0
    private fun r2(x: Double) = Math.round(x * 100) / 100.0
}
