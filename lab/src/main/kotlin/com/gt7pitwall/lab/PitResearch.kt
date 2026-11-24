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
 * - retour en piste : premier point après l'arrêt revenu à moins de [REJOIN_M] de la référence, au-dessus de la vitesse
 *   de voie, pendant 2 s ; une visite à plusieurs immobilisations (28/10) est comptée une seule fois ;
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
                var visitEndT = Long.MIN_VALUE
                for (idx in race.laps.indices) {
                    val lap = race.laps[idx]
                    if (!lap.stopped) continue
                    val next = race.laps.getOrNull(idx + 1)
                    val prev = race.laps.getOrNull(idx - 1)
                    // L'approche peut être dans le tour précédent (arrêt juste après la ligne : observé le 06/11).
                    val pts = (prev?.samples?.map { P(it.tMs, it.p.positionX, it.p.positionZ, it.p.speedKmh, it.p.fuelLevel, it.dist - prev.length, prev.n) } ?: emptyList()) + (lap.samples.map { P(it.tMs, it.p.positionX, it.p.positionZ, it.p.speedKmh, it.p.fuelLevel, it.dist, lap.n) } +
                        (next?.samples?.map { P(it.tMs, it.p.positionX, it.p.positionZ, it.p.speedKmh, it.p.fuelLevel, it.dist + lap.length, next.n) } ?: emptyList()))
                    val stopStart = firstStop(pts, lap.n, visitEndT)
                    if (stopStart < 0) continue // immobilisation déjà comptée dans la visite précédente (28/10 : T6 puis T7)
                    val stopEnd = (stopStart until pts.size).firstOrNull { pts[it].v >= 1f } ?: continue
                    val dev = pts.map { deviation(it.x, it.z) }
                    // Entrée : écart à la trajectoire de référence, OU approche continue à vitesse de voie (≤ 105 km/h)
                    // jusqu'à l'arrêt (voie parallèle à la piste, à moins de 8 m : observé le 06/11). Si les deux
                    // détecteurs répondent, le plus proche de l'arrêt est retenu (l'écart seul remontait en piste le 28/10).
                    var byDev = stopStart
                    while (byDev > 0 && dev[byDev - 1] > ENTRY_M) byDev--
                    var bySpeed = stopStart
                    while (bySpeed > 0 && pts[bySpeed - 1].v <= LANE_MAX_KMH) bySpeed--
                    val entry = when {
                        byDev < stopStart && bySpeed < stopStart -> maxOf(byDev, bySpeed)
                        else -> minOf(byDev, bySpeed)
                    }
                    var rejoin: Int? = null
                    for (i in stopEnd until pts.size) {
                        if (dev[i] < REJOIN_M && pts[i].v > LANE_MAX_KMH) { // retour en piste : proche de la trajectoire ET hors vitesse de voie
                            val j = (i until pts.size).takeWhile { dev[it] < REJOIN_M }.lastOrNull() ?: i
                            if (pts[j].tMs - pts[i].tMs >= 2_000) { rejoin = i; break }
                        }
                    }
                    // Visite entière : toutes les immobilisations entre l'arrêt et le retour (ou la fin des points).
                    val visitLast = rejoin ?: (pts.size - 1)
                    var immobileMs = 0L
                    var immobilisations = 0
                    var wasStill = false
                    for (i in stopStart until visitLast) {
                        val still = pts[i].v < 1f
                        if (still) immobileMs += (pts[i + 1].tMs - pts[i].tMs).coerceIn(0, 1_000)
                        if (still && !wasStill) immobilisations++
                        wasStill = still
                    }
                    visitEndT = pts[visitLast].tMs
                    val refuel = pts.subList(stopStart, visitLast + 1).let { s -> (s.maxOf { it.fuel } - s.first().fuel) }
                    val o = JSONObject().put("trace", f.name).put("course", ri + 1).put("tour", lap.n)
                        .put("immobile_s_MESURE", r1(immobileMs / 1000.0))
                        .put("immobilisations", immobilisations)
                        .put("carburant_ajoute_MESURE", r1(refuel.toDouble()))
                        .put("entree_voie_detectee", entry < stopStart)
                    if (entry < stopStart) {
                        val laneSpeed = RaceLaps.median(pts.subList(entry, stopStart).map { it.v.toDouble() })
                        o.put("entree_a_m_du_debut_du_tour", r1(pts[entry].gdist.toDouble()))
                            .put("entree_fraction_tour_reference", r2((refDist(pts[entry].x, pts[entry].z) / refLen).toDouble()))
                            .put("trajet_avant_arret_s", r1((pts[stopStart].tMs - pts[entry].tMs) / 1000.0))
                            .put("vitesse_mediane_voie_kmh", laneSpeed?.let(::r1))
                            .put("entree_douteuse", (laneSpeed ?: 0.0) > LANE_MAX_KMH)
                            .put("arret_fraction_tour_reference", r2((refDist(pts[stopStart].x, pts[stopStart].z) / refLen).toDouble()))
                    }
                    if (rejoin != null) {
                        o.put("sortie_vers_retour_s", r1((pts[rejoin].tMs - pts[stopEnd].tMs) / 1000.0))
                            .put("retour_fraction_tour_reference", r2((refDist(pts[rejoin].x, pts[rejoin].z) / refLen).toDouble()))
                    }
                    // Recoupement indépendant par les chronos GT7 : somme des tours touchés par la visite moins autant de
                    // tours propres médians (MESURE des chronos, CALCUL de la différence).
                    val touched = (pts[if (entry < stopStart) entry else stopStart].lap..pts[visitLast].lap).mapNotNull { n -> race.laps.firstOrNull { it.n == n } }
                    val medClean = RaceLaps.median(clean.map { it.timeMs!!.toDouble() })
                    if (medClean != null && touched.isNotEmpty() && touched.all { it.timeMs != null }) {
                        o.put("tours_touches", JSONArray(touched.map { it.n }))
                            .put("temps_perdu_chronos_GT7_s_CALCUL", r1((touched.sumOf { it.timeMs!!.toDouble() } - touched.size * medClean) / 1000.0))
                    }
                    // Temps perdu : portion [entrée - marge, retour + marge] comparée aux tours propres sur les mêmes fractions.
                    if (entry < stopStart && rejoin != null) {
                        val fromFrac = ((refDist(pts[entry].x, pts[entry].z) - MARGIN_M) / refLen)
                        val toFrac = ((refDist(pts[rejoin].x, pts[rejoin].z) + MARGIN_M) / refLen)
                        val ai = pts.indexOfFirst { it.gdist >= pts[entry].gdist - MARGIN_M }
                        val bi = pts.indexOfFirst { it.gdist >= pts[rejoin].gdist + MARGIN_M }
                        // Fenêtre tronquée (tour précédent ou suivant absent) : le temps perdu serait sous-estimé, il n'est pas donné.
                        val truncated = ai <= 0 && pts.first().gdist > pts[entry].gdist - MARGIN_M || bi < 0
                        val pitTime = if (truncated) null else (pts[bi].tMs - pts[ai].tMs) / 1000.0
                        val normal = race.laps.zipWithNext().filter { (x, y) -> x.clean && y.clean && y.n == x.n + 1 }
                            .mapNotNull { (x, y) -> windowTime(x, y, fromFrac, toFrac) }
                        val med = RaceLaps.median(normal)
                        o.put("portion_fraction_tour", JSONArray(listOf(r2(fromFrac.toDouble()), r2(toFrac.toDouble()))))
                            .put("fenetre_tronquee", truncated)
                            .put("temps_portion_arret_s", pitTime?.let(::r1) ?: JSONObject.NULL)
                            .put("temps_portion_normal_median_s", med?.let(::r1) ?: JSONObject.NULL)
                            .put("tours_de_reference", normal.size)
                            .put("temps_perdu_s_CALCUL", if (med != null && pitTime != null) r1(pitTime - med) else JSONObject.NULL)
                            .put("temps_perdu_hors_immobilisation_s_CALCUL", if (med != null && pitTime != null) r1(pitTime - med - immobileMs / 1000.0) else JSONObject.NULL)
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
    internal fun windowTime(a: RaceLaps.Lap, b: RaceLaps.Lap, from: Float, to: Float): Double? {
        // Portion qui commence avant la ligne (entrée à moins de [MARGIN_M] après elle) : lue de la fin de [a] au début de [b].
        if (from < 0f) return if (from + 1f >= 0f) windowTime(a, b, from + 1f, to + 1f) else null
        fun at(l: RaceLaps.Lap, f: Float) = l.samples.firstOrNull { it.dist >= f * l.length }?.tMs
        val start = at(a, from) ?: return null
        val end = if (to > 1f) at(b, to - 1f) else if (to < from) at(b, to) else at(a, to)
        return end?.let { (it - start) / 1000.0 }?.takeIf { it > 0 }
    }

    /** Premier point immobile (< 1 km/h) du tour [lap] tenu au moins 3 s, après [after] ; -1 sinon. Linéaire. */
    private fun firstStop(pts: List<P>, lap: Int, after: Long): Int {
        var runStart = -1
        for (i in pts.indices) {
            val q = pts[i]
            if (q.v < 1f) {
                if (runStart < 0) runStart = i
                val s = pts[runStart]
                if (s.lap == lap && s.tMs > after && q.tMs - s.tMs >= 3_000) return runStart
            } else runStart = -1
        }
        return -1
    }

    private fun r1(x: Double) = Math.round(x * 10) / 10.0
    private fun r2(x: Double) = Math.round(x * 100) / 100.0
}
