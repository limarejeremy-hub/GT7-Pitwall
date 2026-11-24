package com.gt7pitwall.lab

import com.gt7pitwall.core.telemetry.TelemetryPacket
import com.gt7pitwall.core.telemetry.TelemetryParser
import com.gt7pitwall.core.telemetry.TelemetryTrace
import java.io.File

/**
 * Pitwall Lab — découpage d'une trace brute en courses et en tours, avec la distance parcourue dans le tour (intégrée
 * depuis la vitesse MESURÉE et l'horodatage de la trace). Base commune de `tyre-research`, `fuel-performance` et
 * `pit-research`. Aucun moteur : uniquement les paquets.
 */
object RaceLaps {
    class Sample(val tMs: Long, val p: TelemetryPacket, val dist: Float)

    class Lap(val n: Int, val samples: List<Sample>) {
        /** Chrono MESURÉ (0x7C du premier paquet du tour suivant), null s'il n'est pas connu. */
        var timeMs: Int? = null
        val fuelStart: Float get() = samples.first().p.fuelLevel
        val fuelEnd: Float get() = samples.last().p.fuelLevel
        /** Carburant ajouté dans le tour (somme des hausses, MESURE) : à 20 Hz un plein monte d'environ 0,05 par échantillon. */
        val fuelAdded: Float get() = samples.zipWithNext().sumOf { (a, b) -> (b.p.fuelLevel - a.p.fuelLevel).coerceAtLeast(0f).toDouble() }.toFloat()
        val refuelled: Boolean get() = fuelAdded > 0.5f
        /**
         * Immobile en course pendant au moins 3 s, hors pause (MESURE). Ce n'est PAS forcément un passage au stand : un
         * tête-à-queue ou une pénalité donnent le même signal ; seul un plein ([refuelled]) le prouve.
         */
        val stopped: Boolean get() {
            var since: Long? = null
            for (s in samples) {
                if (s.p.speedKmh < 1f && !s.p.isPaused) { if (since == null) since = s.tMs; if (s.tMs - since >= 3_000) return true } else since = null
            }
            return false
        }
        val length: Float get() = samples.last().dist
        /** Tour qui précède un arrêt : il contient l'entrée de voie (observé le 06/11 : 6 à 6,5 s dans la voie). */
        var beforeStop: Boolean = false
        val clean: Boolean get() = n >= 2 && !refuelled && !stopped && !beforeStop && (timeMs ?: 0) > 0
    }

    class Race(val totalLaps: Int, val carCode: Int, val laps: MutableList<Lap> = mutableListOf()) {
        val format: String get() = laps.firstOrNull()?.samples?.firstOrNull()?.p?.format?.label ?: "?"
        /** Tours trop courts (≤ 20 échantillons) écartés au découpage : la numérotation des tours a alors des trous. */
        var droppedLaps: Int = 0
        /** Course complète : tous les tours de 1 à [totalLaps] sont présents, sans trou. */
        val complete: Boolean get() = laps.map { it.n } == (1..totalLaps).toList()
    }

    fun load(f: File): List<Race> {
        require(f.isFile) { "trace introuvable : ${f.name}" }
        val races = mutableListOf<Race>()
        var race: Race? = null
        var cur = mutableListOf<Sample>()
        var curLap = -1
        var prevT = 0L
        var dist = 0f
        fun close(next: TelemetryPacket?) {
            val r = race ?: return
            if (curLap >= 1 && cur.size > 20) {
                val lap = Lap(curLap, cur)
                lap.timeMs = next?.lastLapMs?.takeIf { it > 0 && next.currentLap == curLap + 1 }
                r.laps += lap
            } else if (curLap >= 1) r.droppedLaps++
            cur = mutableListOf()
            dist = 0f
        }
        TelemetryTrace.stream(f.inputStream()) { frame ->
            val p = TelemetryParser.parseDecrypted(frame.plain) ?: return@stream true
            val t = frame.atMs.toLong()
            val inRace = p.totalLaps > 0 && p.currentLap in 1..p.totalLaps && !p.isLoading
            if (!inRace) {
                // Chargement ou pause pendant la course (observé au stand le 06/11) : le tour continue, rien n'est coupé.
                if (p.totalLaps > 0 && p.currentLap == curLap && curLap >= 1) { prevT = t; return@stream true }
                if (curLap >= 1) close(p.takeIf { it.totalLaps > 0 && it.currentLap == curLap + 1 })
                if (p.totalLaps > 0 && p.currentLap > p.totalLaps) race = null
                curLap = -1
                prevT = t
                return@stream true
            }
            if (p.currentLap == 1 && curLap != 1 && (race == null || curLap > 1 || curLap == -1 && race?.laps?.isNotEmpty() == true)) {
                race = Race(p.totalLaps, p.carCode).also { races += it }
            }
            if (race == null) race = Race(p.totalLaps, p.carCode).also { races += it }
            if (p.currentLap != curLap) {
                if (curLap >= 1) close(p)
                curLap = p.currentLap
            } else if (!p.isPaused) {
                // Trou de flux (jusqu'à 1 s, 3 fois le 19/11) : vitesse supposée constante ; au-delà, la distance n'est pas devinée.
                val dt = ((t - prevT).coerceIn(0, 1_000)) / 1000f
                dist += p.speedMps * dt
            }
            cur += Sample(t, p, dist)
            prevT = t
            true
        }
        close(null)
        for (r in races) r.laps.zipWithNext().forEach { (a, b) -> if (b.n == a.n + 1 && b.stopped) a.beforeStop = true }
        return races.filter { it.laps.size >= 3 }
    }

    fun median(xs: List<Double>): Double? = xs.sorted().let { if (it.isEmpty()) null else if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }

    /** Corrélation de rang de Spearman, null si moins de 4 points. */
    fun spearman(x: List<Double>, y: List<Double>): Double? {
        if (x.size < 4 || x.size != y.size) return null
        fun ranks(v: List<Double>): List<Double> {
            val idx = v.indices.sortedBy { v[it] }
            val r = DoubleArray(v.size)
            var i = 0
            while (i < idx.size) {
                var j = i
                while (j + 1 < idx.size && v[idx[j + 1]] == v[idx[i]]) j++
                val avg = (i + j) / 2.0 + 1
                for (k in i..j) r[idx[k]] = avg
                i = j + 1
            }
            return r.toList()
        }
        val rx = ranks(x)
        val ry = ranks(y)
        val mx = rx.average()
        val my = ry.average()
        val num = rx.indices.sumOf { (rx[it] - mx) * (ry[it] - my) }
        val den = kotlin.math.sqrt(rx.sumOf { (it - mx) * (it - mx) } * ry.sumOf { (it - my) * (it - my) })
        return if (den == 0.0) null else num / den
    }

    /** Pente des moindres carrés y = a + b·x ; null si moins de 3 points. */
    fun slope(x: List<Double>, y: List<Double>): Double? {
        if (x.size < 3) return null
        val mx = x.average()
        val my = y.average()
        val den = x.sumOf { (it - mx) * (it - mx) }
        return if (den == 0.0) null else x.indices.sumOf { (x[it] - mx) * (y[it] - my) } / den
    }
}
