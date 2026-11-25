package com.gt7pitwall.lab

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import com.gt7pitwall.core.analysis.SectorTrace
import kotlin.math.hypot

/**
 * Pitwall Lab — études V515.1 sur traces RÉELLES (MESURE des paquets, CALCUL du Lab). Aucune trace simulée.
 *
 * - `lap-progress` : position dans le tour. Référence = projection GÉOMÉTRIQUE sur la trajectoire X/Z du tour propre le
 *   plus rapide (positions mesurées). Méthodes comparées : distance du moteur (SectorTrace, rapportée au tour de
 *   référence du moteur : celle du rappel « Box ») et carburant consommé (rapporté à la consommation moyenne : celle de V515).
 * - `refuel-rate` : pleins observés (niveau qui monte à l'arrêt) : début, fin, quantité, durée, débit, linéarité.
 * - `ablation` : prédiction des chronos de fin de relais. Modèles : M0 sans pente, M1 pente des chronos (moindres
 *   carrés), M2 pente robuste (Theil-Sen) ; et utilité de la hauteur de caisse et des suspensions (laisser une course
 *   de côté).
 */
object PrecisionStudy {
    /** En dessous, une ablation des pentes n'est pas interprétable (deux ajustements indépendants ne prouvent rien). */
    private const val MIN_STINTS = 5
    private fun r1(x: Double) = Math.round(x * 10) / 10.0
    private fun r3(x: Double) = Math.round(x * 1000) / 1000.0
    private fun pct(xs: List<Double>, q: Double): Double = xs.sorted().let { if (it.isEmpty()) Double.NaN else it[((it.size - 1) * q).toInt()] }

    /**
     * Distance du MOTEUR dans le tour, échantillon par échantillon : le vrai [SectorTrace] de l'application (déplacements
     * 3D, tour invalidé par un saut de plus de 10 m par paquet, une pause ou un chargement). null : tour sans distance.
     */
    private fun engineDistance(lap: RaceLaps.Lap): FloatArray? {
        val tr = SectorTrace()
        tr.startLap(lap.samples.first().p)
        val out = FloatArray(lap.samples.size)
        for ((k, s) in lap.samples.withIndex()) {
            if (k > 0) tr.add(s.p)
            if (!tr.isComplete) return null
            out[k] = tr.lapDistance
        }
        return out
    }

    fun lapProgress(traces: List<File>): JSONObject {
        val rows = JSONArray()
        val allDist = mutableListOf<Double>()
        val allFuel = mutableListOf<Double>()
        val callDist = mutableListOf<Double>()
        val callFuel = mutableListOf<Double>()
        var missedDist = 0; var missedFuel = 0; var calls = 0
        for (f in traces) RaceLaps.load(f).forEachIndexed { ri, race ->
            val clean = race.laps.filter { it.clean }
            val ref = clean.minByOrNull { it.timeMs!! } ?: return@forEachIndexed
            val refPts = ref.samples.filterIndexed { i, _ -> i % 2 == 0 }
            fun geo(x: Float, z: Float): Double = refPts.minByOrNull { hypot(x - it.p.positionX, z - it.p.positionZ) }!!.dist.toDouble() / ref.length
            val dists = race.laps.associateWith { engineDistance(it) }
            val errD = mutableListOf<Double>()
            val errF = mutableListOf<Double>()
            val cD = mutableListOf<Double>()
            val cF = mutableListOf<Double>()
            var compared = 0
            for (lap in clean) {
                if (lap === ref) continue
                val own = dists[lap] ?: continue
                // Référence du moteur (SessionTracker.liveLapProgress) : dernier tour complet hors stands, jamais le tour 1,
                // d'au moins 1 000 m, dont la distance est valide.
                val baseline = race.laps.filter { it.n in 2 until lap.n && !it.refuelled && !it.stopped && !it.beforeStop }
                    .asReversed().firstNotNullOfOrNull { l -> dists[l]?.last()?.takeIf { it >= 1_000f } } ?: continue
                val per = race.laps.filter { it.clean && it.n < lap.n }.takeLast(3).map { (it.fuelStart - it.fuelEnd).toDouble() }
                    .takeIf { it.isNotEmpty() }?.average() ?: continue
                compared++
                var tGeo: Long? = null; var tD: Long? = null; var tF: Long? = null
                var dGeo = 0.0; var dD = 0.0; var dF = 0.0
                for ((k, s) in lap.samples.withIndex()) {
                    if (k % 3 != 0) continue
                    val g = geo(s.p.positionX, s.p.positionZ)
                    val d = own[k].toDouble() / baseline
                    val fu = (lap.fuelStart - s.p.fuelLevel).toDouble() / per
                    if (g in 0.60..0.95) { errD += abs(d - g) * ref.length; errF += abs(fu - g) * ref.length }
                    // Instant où chaque méthode atteint la fin de la fenêtre de rappel (0,90) ; référence géométrique bornée à
                    // 0,05–0,95 (au-delà, la projection confond fin et début de tour).
                    if (tGeo == null && g in 0.90..0.95) { tGeo = s.tMs; dGeo = s.dist.toDouble() }
                    if (tD == null && d >= 0.90) { tD = s.tMs; dD = s.dist.toDouble() }
                    if (tF == null && fu >= 0.90) { tF = s.tMs; dF = s.dist.toDouble() }
                }
                if (tGeo != null) {
                    calls++
                    // Rappel jamais atteint dans le tour : compté comme manqué (pas écarté).
                    if (tD != null) cD += dD - dGeo else missedDist++
                    if (tF != null) cF += dF - dGeo else missedFuel++
                }
            }
            allDist += errD; allFuel += errF; callDist += cD; callFuel += cF
            rows.put(JSONObject().put("trace", f.name.take(8)).put("course", ri + 1).put("longueur_tour_m", r1(ref.length.toDouble()))
                .put("tours_compares", compared)
                .put("distance_erreur_mediane_m", r1(pct(errD, 0.5))).put("distance_erreur_p95_m", r1(pct(errD, 0.95)))
                .put("carburant_erreur_mediane_m", r1(pct(errF, 0.5))).put("carburant_erreur_p95_m", r1(pct(errF, 0.95)))
                .put("rappel_0_90_distance_ecart_median_m", r1(pct(cD.map { abs(it) }, 0.5))).put("rappel_0_90_carburant_ecart_median_m", r1(pct(cF.map { abs(it) }, 0.5)))
                .put("rappel_0_90_distance_ecart_max_m", r1(cD.maxOfOrNull { abs(it) } ?: Double.NaN))
                .put("rappel_0_90_carburant_ecart_max_m", r1(cF.maxOfOrNull { abs(it) } ?: Double.NaN)))
        }
        return JSONObject()
            .put("nature", "Position dans le tour sur traces RÉELLES : référence géométrique (X/Z mesurés) ; distance = SectorTrace du moteur (déplacements 3D) rapportée au tour de référence du moteur ; erreurs en mètres dans la fenêtre 0,60–0,95 du tour (CALCUL). Rappels pris un échantillon sur 3 (≈ 0,15 s, ≈ 7 m à 170 km/h : résolution, pas précision).")
            .put("par_course", rows)
            .put("synthese", JSONObject()
                .put("distance_erreur_mediane_m", r1(pct(allDist, 0.5))).put("distance_erreur_p95_m", r1(pct(allDist, 0.95)))
                .put("carburant_erreur_mediane_m", r1(pct(allFuel, 0.5))).put("carburant_erreur_p95_m", r1(pct(allFuel, 0.95)))
                .put("rappels", calls).put("rappels_distance_manques", missedDist).put("rappels_carburant_manques", missedFuel)
                .put("rappel_distance_ecart_median_m", r1(pct(callDist.map { abs(it) }, 0.5)))
                .put("rappel_carburant_ecart_median_m", r1(pct(callFuel.map { abs(it) }, 0.5))))
    }

    fun refuelRate(traces: List<File>): JSONObject {
        val stops = JSONArray()
        for (f in traces) RaceLaps.load(f).forEachIndexed { ri, race ->
            for (lap in race.laps.filter { it.refuelled }) {
                val s = lap.samples
                val rise = s.zipWithNext().withIndex().filter { (_, ab) -> ab.second.p.fuelLevel - ab.first.p.fuelLevel > 0.001f }.map { it.index }
                if (rise.isEmpty()) continue
                val a = s[rise.first()]; val b = s[rise.last() + 1]
                val dur = (b.tMs - a.tMs) / 1000.0
                val added = (b.p.fuelLevel - a.p.fuelLevel).toDouble()
                // Linéarité : écart maximal du niveau à la droite début–fin pendant le plein.
                val dev = s.subList(rise.first(), rise.last() + 2).maxOf { x ->
                    val expected = a.p.fuelLevel + added * ((x.tMs - a.tMs) / 1000.0) / dur
                    abs(x.p.fuelLevel - expected)
                }
                val still = s.filter { it.p.speedKmh < 1f }
                val immobile = if (still.size > 1) (still.last().tMs - still.first().tMs) / 1000.0 else 0.0
                stops.put(JSONObject().put("trace", f.name.take(8)).put("course", ri + 1).put("tour", lap.n)
                    .put("carburant_ajoute_MESURE", r1(added)).put("duree_plein_s_MESURE", r1(dur))
                    .put("debit_unites_par_s_CALCUL", r3(added / dur)).put("ecart_max_a_la_droite_unites", r3(dev))
                    .put("immobile_s_MESURE", r1(immobile))
                    .put("plein_avant_fin_immobilisation_s", r1((still.lastOrNull()?.tMs?.let { it - b.tMs } ?: 0L) / 1000.0)))
            }
        }
        return JSONObject()
            .put("nature", "Pleins observés sur traces RÉELLES : niveau GT7 (MESURE) ; débit et linéarité (CALCUL).")
            .put("pleins", stops)
            .put("lecture", "un débit n'est reproductible que s'il est mesuré sur plusieurs pleins, voitures et circuits")
    }

    /** Moindres carrés ordinaires (petit système, élimination de Gauss). */
    private fun ols(x: List<DoubleArray>, y: List<Double>): DoubleArray? {
        val k = x.first().size
        val a = Array(k) { DoubleArray(k + 1) }
        for (r in x.indices) for (i in 0 until k) { for (j in 0 until k) a[i][j] += x[r][i] * x[r][j]; a[i][k] += x[r][i] * y[r] }
        for (c in 0 until k) {
            val p = (c until k).maxByOrNull { abs(a[it][c]) }!!
            if (abs(a[p][c]) < 1e-9) return null
            val t = a[c]; a[c] = a[p]; a[p] = t
            for (r in 0 until k) if (r != c) { val f = a[r][c] / a[c][c]; for (j in c..k) a[r][j] -= f * a[c][j] }
        }
        return DoubleArray(k) { a[it][k] / a[it][it] }
    }

    fun ablation(traces: List<File>): JSONObject {
        // 1. Pente des chronos : prédire les 2 derniers tours propres de chaque relais (≥ 5 tours propres) depuis les autres.
        val e0 = mutableListOf<Double>(); val e1 = mutableListOf<Double>(); val e2 = mutableListOf<Double>()
        class LapRow(val race: String, val time: Double, val fuel: Double, val age: Double, val body: Double, val susp: Double, val stint: String)
        val rows = mutableListOf<LapRow>()
        for (f in traces) RaceLaps.load(f).forEachIndexed { ri, race ->
            var stint = 0
            var stintStart = 1
            for (l in race.laps) {
                if (l.stopped || l.refuelled) { stint++; stintStart = l.n + 1 }
                if (!l.clean) continue
                val key = "${f.name.take(8)}#${ri + 1}"
                // Âge dans le relais (remis à zéro à chaque arrêt), pas le numéro du tour.
                rows += LapRow(key, l.timeMs!!.toDouble(), l.fuelStart.toDouble(), (l.n - stintStart).toDouble(),
                    l.samples.map { it.p.bodyHeight.toDouble() }.average(),
                    l.samples.map { it.p.suspensionHeight.toList().average() }.average(), "$key/$stint")
            }
        }
        var stints = 0
        for ((_, st) in rows.groupBy { it.stint }) {
            if (st.size < 5) continue
            stints++
            val fit = st.dropLast(2); val test = st.takeLast(2)
            val m0 = fit.map { it.time }.average()
            val x = fit.map { it.age }; val y = fit.map { it.time }
            val mx = x.average(); val my = y.average()
            val b1 = x.indices.sumOf { (x[it] - mx) * (y[it] - my) } / x.indices.sumOf { (x[it] - mx) * (x[it] - mx) }
            val slopes = mutableListOf<Double>()
            for (i in x.indices) for (j in i + 1 until x.size) slopes += (y[j] - y[i]) / (x[j] - x[i])
            val b2 = slopes.sorted()[slopes.size / 2]
            val med = y.sorted()[y.size / 2]; val mxm = x.sorted()[x.size / 2]
            for (t in test) {
                e0 += abs(t.time - m0); e1 += abs(t.time - (my + b1 * (t.age - mx))); e2 += abs(t.time - (med + b2 * (t.age - mxm)))
            }
        }
        // 2. Hauteur de caisse et suspensions : chrono ~ carburant + tour (A) contre + caisse + suspensions (B), course de côté.
        val races = rows.map { it.race }.distinct()
        val eA = mutableListOf<Double>(); val eB = mutableListOf<Double>()
        for (r in races) {
            val train = rows.filter { it.race != r }; val test = rows.filter { it.race == r }
            if (train.size < 8 || test.isEmpty()) continue
            // Rythme, voiture et circuit propres à chaque course : TOUTES les variables sont recentrées par course (sinon la
            // caisse, qui change surtout d'une voiture à l'autre, extrapole entre voitures).
            fun centered(xs: List<LapRow>) = xs.groupBy { it.race }.flatMap { (_, g) ->
                val mt = g.map { it.time }.average(); val mf = g.map { it.fuel }.average(); val ma = g.map { it.age }.average()
                val mb = g.map { it.body }.average(); val ms = g.map { it.susp }.average()
                g.map { LapRow(it.race, it.time, it.fuel - mf, it.age - ma, it.body - mb, it.susp - ms, it.stint) to it.time - mt }
            }
            val tr = centered(train); val te = centered(test)
            val a = ols(tr.map { (l, _) -> doubleArrayOf(1.0, l.fuel, l.age) }, tr.map { it.second })
            val b = ols(tr.map { (l, _) -> doubleArrayOf(1.0, l.fuel, l.age, l.body, l.susp) }, tr.map { it.second })
            for ((l, y) in te) {
                if (a != null) eA += abs(y - (a[0] + a[1] * l.fuel + a[2] * l.age))
                if (b != null) eB += abs(y - (b[0] + b[1] * l.fuel + b[2] * l.age + b[3] * l.body + b[4] * l.susp))
            }
        }
        fun mae(e: List<Double>) = if (e.isEmpty()) JSONObject.NULL else r1(e.average())
        return JSONObject()
            .put("nature", "Ablation sur traces RÉELLES (chronos MESURÉS) ; prédictions évaluées hors calibration (CALCUL).")
            .put("pente_des_chronos", JSONObject().put("predictions", e0.size).put("relais", stints)
                .put("interpretable", stints >= MIN_STINTS)
                .put("lecture", if (stints >= MIN_STINTS) "comparaison appariée sur $stints relais" else "moins de $MIN_STINTS relais : aucune conclusion (un tour aberrant suffit à faire pencher une pente)")
                .put("M0_sans_pente_erreur_ms", mae(e0)).put("M1_pente_moindres_carres_erreur_ms", mae(e1)).put("M2_pente_robuste_erreur_ms", mae(e2)))
            .put("caisse_et_suspensions", JSONObject().put("predictions", eA.size)
                .put("A_carburant_tour_erreur_ms", mae(eA)).put("B_plus_caisse_suspensions_erreur_ms", mae(eB))
                .put("lecture", "B n'entre dans le moteur que s'il bat A sur des courses distinctes ET sans fuite causale : la caisse et les suspensions moyennes du tour sont une CONSÉQUENCE de sa vitesse (appui aérodynamique) et ne sont connues qu'à la fin du tour prédit"))
    }
}
