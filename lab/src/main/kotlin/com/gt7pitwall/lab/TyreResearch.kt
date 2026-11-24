package com.gt7pitwall.lab

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/**
 * Pitwall Lab — `tyre-research <trace...>` : banc expérimental des pneus sur traces RÉELLES (aucune trace simulée).
 *
 * Piste A — octets non interprétés (0x93, 0xD4–0xF3, 0x120) : constants ? varient-ils au fil d'un relais ? aux stands ?
 * Piste B — rayon des pneus (0xB4–0xC0) : constant, thermique, ou évolution avec le relais ? retour après un arrêt ?
 *           Rayon effectif v/ω par roue : roue libre (= rayon) ou motrice (patinage).
 * Indicateurs par roue et par tour (MESURE + CALCUL) : température moyenne et maximale, patinage médian sous
 * accélération, blocages et patinages forts, chronos ; tendances par relais (Spearman, pente) ; qualité des mesures.
 * Modèles candidats du chrono par tour, évalués sur des courses DISTINCTES de leur calibration (laisser-une-course-de-côté).
 */
object TyreResearch {
    private val UNKNOWN = listOf(0xD4, 0xD8, 0xDC, 0xE0, 0xE4, 0xE8, 0xEC, 0xF0, 0x120)

    private data class LapRow(val race: Int, val lap: Int, val stint: Int, val stintLap: Int, val timeMs: Int?, val clean: Boolean,
                              val fuel: Float, val temps: List<Double>, val tempMax: List<Double>, val slip: List<Double?>,
                              val effRadius: List<Double?>, val radius: List<Double>, val locks: Int, val spins: Int, val samples: Int)

    fun run(traces: List<File>): JSONObject {
        val out = JSONArray()
        val allRows = mutableListOf<Pair<String, LapRow>>()
        val unknownReport = JSONObject()
        for (f in traces) {
            val races = RaceLaps.load(f)
            // Piste A : sur TOUS les paquets de la trace (y compris menus et stands).
            val distinct = UNKNOWN.associateWith { mutableSetOf<Float>() }.toMutableMap()
            val b93 = mutableSetOf<Int>()
            com.gt7pitwall.core.telemetry.TelemetryTrace.stream(f.inputStream()) { fr ->
                val bb = java.nio.ByteBuffer.wrap(fr.plain).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                if (fr.plain.size >= 0x128) {
                    for (o in UNKNOWN) distinct.getValue(o).let { if (it.size < 50) it += bb.getFloat(o) }
                    b93 += fr.plain[0x93].toInt() and 0xFF
                }
                true
            }
            unknownReport.put(f.name, JSONObject().also { o ->
                distinct.forEach { (k, v) -> o.put("0x%X".format(k), JSONObject().put("valeurs_distinctes", v.size).put("exemples", JSONArray(v.take(4).map { it.toDouble() }))) }
                o.put("0x93", JSONObject().put("valeurs_distinctes", b93.size).put("exemples", JSONArray(b93.take(4))))
            })
            races.forEachIndexed { ri, race ->
                var stint = 0
                var stintLap = 0
                for (lap in race.laps) {
                    stintLap++
                    val drive = lap.samples.filter { it.p.speedKmh > 50f && it.p.brake == 0 && !it.p.isPaused }
                    val accel = drive.filter { it.p.throttle >= 200 && abs(it.p.angularVelocityY) < 0.05f }
                    val free = drive.filter { abs(it.p.angularVelocityY) < 0.05f }
                    fun wheel(i: Int, xs: List<RaceLaps.Sample>, f: (RaceLaps.Sample) -> Double?) = xs.mapNotNull(f)
                    val eff = (0 until 4).map { i -> RaceLaps.median(wheel(i, free) { s -> s.p.wheelSpeed.toList()[i].let { w -> if (abs(w) > 1f) (s.p.speedMps / abs(w)).toDouble() else null } }).takeIf { free.size > 30 } }
                    val slip = (0 until 4).map { i -> RaceLaps.median(wheel(i, accel) { s -> s.p.wheelSlip?.toList()?.get(i)?.toDouble() }).takeIf { accel.size > 30 } }
                    var locks = 0
                    var spins = 0
                    lap.samples.forEach { s -> s.p.wheelSlip?.toList()?.let { sl -> if (sl.any { it < -0.2f } && s.p.brake > 50) locks++; if (sl.any { it > 0.2f } && s.p.throttle > 150) spins++ } }
                    val row = LapRow(
                        ri + 1, lap.n, stint, stintLap, lap.timeMs, lap.clean, lap.fuelStart,
                        (0 until 4).map { i -> lap.samples.map { it.p.tyreTemps.toList()[i].toDouble() }.average() },
                        (0 until 4).map { i -> lap.samples.maxOf { it.p.tyreTemps.toList()[i].toDouble() } },
                        slip, eff, (0 until 4).map { i -> lap.samples.map { it.p.tyreRadius.toList()[i].toDouble() }.distinct().let { d -> d.average() } },
                        locks, spins, lap.samples.size,
                    )
                    allRows += f.name to row
                    // Nouveau relais pneus/carburant après un arrêt (ravitaillement ou immobilisation au stand).
                    if (lap.refuelled || lap.stopped) { stint++; stintLap = 0 }
                }
            }
        }
        // Piste B : rayon statique constant ? varie-t-il dans une course, ou après un arrêt ?
        val radiusByRace = allRows.groupBy { it.first to it.second.race }.map { (k, rows) ->
            val per = (0 until 4).map { i -> rows.map { it.second.radius[i] }.distinct() }
            val effByStint = rows.groupBy { it.second.stint }.mapValues { (_, rs) -> (0 until 4).map { i -> RaceLaps.median(rs.filter { it.second.clean }.mapNotNull { it.second.effRadius[i] }) } }
            JSONObject().put("trace", k.first).put("course", k.second)
                .put("rayon_0xB4_valeurs_distinctes_par_roue", JSONArray(per.map { JSONArray(it) }))
                .put("rayon_constant", per.all { it.size == 1 })
                .put("rayon_effectif_median_par_relais_CALCUL", JSONObject(effByStint.mapKeys { "relais_${it.key}" }.mapValues { JSONArray(it.value) }))
                .put("roues_libres", JSONArray((0 until 4).map { i -> rows.mapNotNull { it.second.effRadius[i] }.let { e -> e.isNotEmpty() && e.all { x -> abs(x - rows.first().second.radius[i]) / rows.first().second.radius[i] < 0.001 } } }))
        }
        // Tendances par relais (tours propres seulement).
        val trends = allRows.groupBy { Triple(it.first, it.second.race, it.second.stint) }.mapNotNull { (k, rows) ->
            val clean = rows.map { it.second }.filter { it.clean }
            if (clean.size < 4) return@mapNotNull null
            val x = clean.map { it.stintLap.toDouble() }
            JSONObject().put("trace", k.first).put("course", k.second).put("relais", k.third).put("tours_propres", clean.size)
                .put("chrono_pente_ms_par_tour", RaceLaps.slope(x, clean.map { it.timeMs!!.toDouble() })?.let(::r1))
                .put("chrono_spearman", RaceLaps.spearman(x, clean.map { it.timeMs!!.toDouble() })?.let(::r2))
                .put("temperature_pente_par_roue", JSONArray((0 until 4).map { i -> RaceLaps.slope(x, clean.map { it.temps[i] })?.let(::r2) }))
                .put("patinage_spearman_par_roue", JSONArray((0 until 4).map { i -> clean.filter { it.slip[i] != null }.let { c -> RaceLaps.spearman(c.map { it.stintLap.toDouble() }, c.map { it.slip[i]!! })?.let(::r2) } }))
                .put("carburant_debut_fin", JSONArray(listOf(r1(clean.first().fuel.toDouble()), r1(clean.last().fuel.toDouble()))))
        }
        // Détecteur de dégradation (candidat) et autonomie du relais, sur chaque relais réel.
        val detector = allRows.groupBy { Triple(it.first, it.second.race, it.second.stint) }.mapNotNull { (k, rows) ->
            val clean = rows.map { it.second }.filter { it.clean }.sortedBy { it.lap }
            if (clean.size < 4) return@mapNotNull null
            JSONObject().put("trace", k.first).put("course", k.second).put("relais", k.third).put("tours_propres", clean.size)
                .put("alerte_premier_tour", degradationAlert(clean) ?: JSONObject.NULL)
                .put("autonomie", autonomy(clean))
        }
        val laps = JSONArray(allRows.map { (t, r) ->
            JSONObject().put("trace", t).put("course", r.race).put("tour", r.lap).put("relais", r.stint).put("tour_relais", r.stintLap)
                .put("chrono_ms", r.timeMs ?: JSONObject.NULL).put("propre", r.clean).put("carburant", r1(r.fuel.toDouble()))
                .put("temp_moy", JSONArray(r.temps.map(::r1))).put("temp_max", JSONArray(r.tempMax.map(::r1)))
                .put("patinage_median", JSONArray(r.slip.map { it?.let(::r4) ?: JSONObject.NULL }))
                .put("rayon_effectif", JSONArray(r.effRadius.map { it?.let(::r5) ?: JSONObject.NULL }))
                .put("blocages", r.locks).put("patinages_forts", r.spins)
                .put("qualite", quality(r))
        })
        return JSONObject()
            .put("nature", "Banc pneus sur traces RÉELLES : MESURE (paquets) et CALCUL (Lab). Aucune usure n'est mesurée par GT7 dans ces paquets.")
            .put("piste_A_octets_inconnus", unknownReport)
            .put("piste_B_rayon", JSONArray(radiusByRace))
            .put("tendances_par_relais", JSONArray(trends))
            .put("modeles_chrono", models(allRows.map { it.second to it.first }))
            .put("detecteur_degradation", JSONObject()
                .put("regle", "alerte si deux tours propres consécutifs dépassent de plus de ${DEGRADATION_MS / 1000.0} s la médiane des 3 premiers tours propres du relais ET que la température moyenne des pneus a monté (CALCUL)")
                .put("relais", JSONArray(detector))
                .put("alertes", detector.count { it.opt("alerte_premier_tour") != JSONObject.NULL }))
            .put("tours", laps)
    }

    const val DEGRADATION_MS = 1_000.0

    /** Premier tour où la perte devient notable, ou null (candidat expérimental, aucune usure mesurée). */
    private fun degradationAlert(clean: List<LapRow>): Int? {
        val base = RaceLaps.median(clean.take(3).map { it.timeMs!!.toDouble() }) ?: return null
        val baseTemp = clean.take(3).map { it.temps.average() }.average()
        for (i in 4 until clean.size) {
            val a = clean[i - 1]
            val b = clean[i]
            if (a.timeMs!! - base > DEGRADATION_MS && b.timeMs!! - base > DEGRADATION_MS && b.temps.average() > baseTemp) return a.lap
        }
        return null
    }

    /**
     * Fourchette d'autonomie du relais (tours avant une perte de 2 s par tour) : seulement si la tendance est nette
     * (au moins 5 tours propres, Spearman ≥ 0,6, pente > 100 ms par tour) ; sinon « inconnue ».
     */
    private fun autonomy(clean: List<LapRow>): JSONObject {
        val x = clean.map { it.stintLap.toDouble() }
        val y = clean.map { it.timeMs!!.toDouble() }
        val rho = RaceLaps.spearman(x, y)
        val b = RaceLaps.slope(x, y)
        if (clean.size < 5 || rho == null || b == null || rho < 0.6 || b < 100.0) {
            return JSONObject().put("estimation", "inconnue").put("raison", "tendance insuffisante (tours=${clean.size}, spearman=${rho?.let(::r2)}, pente=${b?.let(::r1)} ms/tour)")
        }
        val mx = x.average()
        val my = y.average()
        val resid = x.indices.map { y[it] - (my + b * (x[it] - mx)) }
        val se = kotlin.math.sqrt(resid.sumOf { it * it } / (x.size - 2) / x.sumOf { (it - mx) * (it - mx) })
        val lo = (b - 2 * se).coerceAtLeast(1.0)
        val hi = b + 2 * se
        return JSONObject().put("estimation", "fourchette")
            .put("tours_avant_perte_2s", JSONArray(listOf(r1(2000.0 / hi), r1(2000.0 / lo))))
            .put("pente_ms_par_tour", r1(b)).put("incertitude_ms", r1(2 * se))
    }

    /** Qualité d'un tour pour l'analyse pneus (0–1) : propre, assez d'échantillons, mesures de patinage présentes. */
    private fun quality(r: LapRow): Double {
        var q = 1.0
        if (!r.clean) q -= 0.6
        if (r.samples < 1_000) q -= 0.2
        if (r.slip.count { it != null } < 2) q -= 0.2
        return r2(q.coerceAtLeast(0.0))
    }

    /**
     * Modèles candidats du chrono d'un tour propre (hors tour 1 et tours d'arrêt), évalués en laissant de côté une course
     * entière (calibration sur les AUTRES courses de la même voiture) :
     * M0 — moyenne des tours propres de la course jusque-là (aucune dégradation) ;
     * M1 — M0 + pente de dégradation par tour de relais apprise sur les autres courses.
     * Erreur absolue moyenne (ms) sur les tours à partir du 4e tour propre de la course évaluée.
     */
    private fun models(rows: List<Pair<LapRow, String>>): JSONObject {
        val byCar = rows.filter { it.first.clean }.groupBy { it.second }
        val res = JSONArray()
        val races = rows.filter { it.first.clean }.groupBy { it.second to it.first.race }
        for ((key, test) in races) {
            val others = races.filterKeys { it != key && it.first == key.first || (it != key && sameCarTrace(it, key)) }.values.flatten()
            val slopeLearned = others.groupBy { it.first.race to it.first.stint }.values.mapNotNull { st ->
                RaceLaps.slope(st.map { it.first.stintLap.toDouble() }, st.map { it.first.timeMs!!.toDouble() })
            }.let { s -> if (s.isEmpty()) null else s.average() }
            val t = test.map { it.first }.sortedBy { it.lap }
            var e0 = 0.0
            var e1 = 0.0
            var n = 0
            for (i in 3 until t.size) {
                val past = t.take(i)
                val base = past.map { it.timeMs!!.toDouble() }.average()
                val baseLap = past.map { it.stintLap.toDouble() }.average()
                val actual = t[i].timeMs!!.toDouble()
                e0 += abs(actual - base)
                val m1 = base + (slopeLearned ?: 0.0) * (t[i].stintLap - baseLap)
                e1 += abs(actual - m1)
                n++
            }
            if (n == 0) continue
            res.put(JSONObject().put("trace", key.first).put("course", key.second).put("tours_evalues", n)
                .put("calibration", if (slopeLearned == null) "aucune autre course comparable : M1 = M0" else "autres courses de la même trace")
                .put("pente_apprise_ms_par_tour", slopeLearned?.let(::r1) ?: JSONObject.NULL)
                .put("erreur_M0_ms", r1(e0 / n)).put("erreur_M1_ms", r1(e1 / n)))
        }
        return JSONObject().put("evaluation", "laisser-une-course-de-côté ; tours propres à partir du 4e").put("resultats", res)
    }

    private fun sameCarTrace(a: Pair<String, Int>, b: Pair<String, Int>) = a.first == b.first

    private fun r1(x: Double) = Math.round(x * 10) / 10.0
    private fun r2(x: Double) = Math.round(x * 100) / 100.0
    private fun r4(x: Double) = Math.round(x * 10_000) / 10_000.0
    private fun r5(x: Double) = Math.round(x * 100_000) / 100_000.0
}
