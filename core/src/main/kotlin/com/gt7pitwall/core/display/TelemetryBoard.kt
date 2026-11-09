package com.gt7pitwall.core.display

import com.gt7pitwall.core.session.LapRecord
import com.gt7pitwall.core.strategy.TyreChange
import com.gt7pitwall.core.strategy.TyreCompound
import kotlin.math.roundToInt

/**
 * Écran 2 « Télémétrie » (maquette validée par le pilote le 09/11) : ce que l'écran affiche, calculé ici en Kotlin pur
 * (testé sur PC) à partir des données que Pitwall a déjà : tours mesurés et secteurs Pitwall, paquet GT7 (chrono du tour,
 * températures des 4 pneus, carburant), registre des arrêts et des gommes, règlement.
 *
 * Rien n'est inventé : une valeur absente reste null et l'écran affiche « — ». L'usure des pneus n'est PAS transmise par
 * GT7 : l'écran montre la température mesurée et une tendance de chrono sur le relais, présentée comme une estimation.
 */
data class TelemetryBoard(
    val lap: Int?,
    val totalLaps: Int?,
    val position: Int?,
    val cars: Int?,
    /** Chrono du tour en cours (GT7, champ récent ; null si la version du jeu ne l'envoie pas). */
    val currentLapMs: Int?,
    val lastLapMs: Int?,
    val bestLapMs: Int?,
    val bestLapNumber: Int?,
    /** Derniers tours complets (au plus [MAX_ROWS]), du plus ancien au plus récent. */
    val laps: List<LapRow>,
    val tyres: List<TyreCell>,
    val compound: TyreCompound?,
    /** Gomme déclarée par le pilote ou issue du règlement ; faux : supposée (non confirmée). */
    val compoundCertain: Boolean,
    val tyreAgeLaps: Int?,
    /** Tendance des chronos sur le relais en cours (s/tour, positive = plus lent) ; null sans assez de tours propres. */
    val paceTrendSPerLap: Double?,
    val fuelPct: Float?,
    val fuelPerLapPct: Float?,
    val fuelLapsLeft: Float?,
    /** Une case par tour de la course : gomme, arrêt, tour en cours. Vide si le nombre de tours est inconnu. */
    val stints: List<StintCell>,
    val requiredTyres: Set<TyreCompound>,
) {
    enum class SectorColor { BEST, IMPROVED, SLOWER, NONE }

    data class SectorCell(val ms: Int?, val color: SectorColor)

    data class LapRow(val lap: Int, val sectors: List<SectorCell>, val totalMs: Int?, val bestLap: Boolean)

    /** Repères GÉNÉRAUX de température de surface (°C), pas une fenêtre propre à chaque gomme. */
    enum class TempBand { COLD, IDEAL, WARM, HOT, UNKNOWN }

    data class TyreCell(val label: String, val tempC: Float?, val band: TempBand)

    data class StintCell(val lap: Int, val compound: TyreCompound?, val pit: Boolean, val current: Boolean, val future: Boolean)

    companion object {
        const val MAX_ROWS = 5
        const val COLD_BELOW = 60f
        const val WARM_FROM = 95f
        const val HOT_FROM = 105f
        /** Tours propres minimum dans le relais pour donner une tendance. */
        const val MIN_TREND_LAPS = 3

        fun band(t: Float?): TempBand = when {
            t == null || t <= 0f -> TempBand.UNKNOWN
            t < COLD_BELOW -> TempBand.COLD
            t < WARM_FROM -> TempBand.IDEAL
            t < HOT_FROM -> TempBand.WARM
            else -> TempBand.HOT
        }

        /**
         * Couleur de chaque secteur : meilleur de la session (BEST, violet), mieux que le même secteur au tour précédent
         * (IMPROVED, vert), sinon plus lent (SLOWER, jaune) ; NONE sans mesure ou sans tour précédent comparable.
         */
        fun lapRows(laps: List<LapRecord>, bestLapNumber: Int?): List<LapRow> {
            val complete = laps.filter { it.sectorsMs?.isNotEmpty() == true || it.timeMs != null }
            val n = complete.maxOfOrNull { it.sectorsMs?.size ?: 0 } ?: 0
            val bestBySector = (0 until n).map { i -> complete.mapNotNull { it.sectorsMs?.getOrNull(i)?.takeIf { v -> v > 0 } }.minOrNull() }
            val rows = complete.mapIndexed { idx, lap ->
                val prev = complete.getOrNull(idx - 1)
                val cells = (0 until n).map { i ->
                    val v = lap.sectorsMs?.getOrNull(i)?.takeIf { it > 0 }
                    val p = prev?.sectorsMs?.getOrNull(i)?.takeIf { it > 0 }
                    val color = when {
                        v == null -> SectorColor.NONE
                        v == bestBySector[i] -> SectorColor.BEST
                        p == null -> SectorColor.NONE
                        v < p -> SectorColor.IMPROVED
                        else -> SectorColor.SLOWER
                    }
                    SectorCell(v, color)
                }
                LapRow(lap.lapNumber, cells, lap.timeMs?.takeIf { it > 0 }, lap.lapNumber == bestLapNumber)
            }
            return rows.takeLast(MAX_ROWS)
        }

        /** Pente (s/tour) des chronos des tours propres du relais, par moindres carrés ; null si moins de [MIN_TREND_LAPS]. */
        fun paceTrend(laps: List<LapRecord>, stintStartLap: Int, excluded: Set<Int>): Double? {
            val pts = laps.filter { it.lapNumber > stintStartLap && it.lapNumber !in excluded && (it.timeMs ?: 0) > 0 }
                .map { it.lapNumber.toDouble() to it.timeMs!! / 1000.0 }
            if (pts.size < MIN_TREND_LAPS) return null
            val mx = pts.sumOf { it.first } / pts.size
            val my = pts.sumOf { it.second } / pts.size
            val den = pts.sumOf { (it.first - mx) * (it.first - mx) }
            if (den == 0.0) return null
            return pts.sumOf { (it.first - mx) * (it.second - my) } / den
        }

        /** Gomme de chaque tour d'après le départ et les changements enregistrés (le changement vaut à partir du tour suivant). */
        fun stints(totalLaps: Int?, currentLap: Int?, start: TyreCompound?, changes: List<TyreChange>, stopLaps: Set<Int>): List<StintCell> {
            val total = totalLaps?.takeIf { it in 1..200 } ?: return emptyList()
            return (1..total).map { lap ->
                val compound = changes.filter { it.lap < lap }.maxByOrNull { it.lap }?.compound ?: if (changes.none { it.lap < lap }) start else null
                StintCell(lap, compound, pit = lap in stopLaps, current = lap == currentLap, future = currentLap != null && lap > currentLap)
            }
        }

        fun of(c: com.gt7pitwall.core.race.RaceController): TelemetryBoard {
            val snap = c.snapshot
            val p = snap.packet
            val laps = snap.laps
            val bestLapNumber = laps.filter { (it.timeMs ?: 0) > 0 }.minByOrNull { it.timeMs!! }?.lapNumber
            val currentLap = p?.currentLap?.takeIf { it > 0 }
            val stopLaps = c.ledger.stopList.map { it.lap }.toSet()
            val stint = c.ledger.tyreStint(currentLap ?: 1, c.rules.startTyre)
            val excluded = stopLaps.flatMap { listOf(it, it + 1) }.toSet() + setOf(1)
            val fuelPerLap = laps.takeLast(5).mapNotNull { l -> l.fuelUsed?.takeIf { !l.refuelled && it > 0f } }
                .takeIf { it.isNotEmpty() }?.average()?.toFloat()
            val capacity = p?.fuelCapacity?.takeIf { it > 0f }
            val fuelPct = p?.fuelFraction?.let { it * 100f }
            val perLapPct = if (fuelPerLap != null && capacity != null) fuelPerLap / capacity * 100f else null
            val wheels = p?.tyreTemps
            val tyres = listOf(
                "AVG" to wheels?.fl, "AVD" to wheels?.fr, "ARG" to wheels?.rl, "ARD" to wheels?.rr,
            ).map { (l, t) -> TyreCell(l, t?.takeIf { it > 0f }, band(t)) }
            return TelemetryBoard(
                lap = currentLap,
                totalLaps = c.rules.totalLaps ?: p?.totalLaps?.takeIf { it > 0 },
                position = c.engineer.currentPosition,
                cars = c.engineer.carsInRace,
                currentLapMs = p?.currentLapTimeMs?.takeIf { it > 0 },
                lastLapMs = p?.lastLapMs?.takeIf { it > 0 } ?: laps.lastOrNull()?.timeMs,
                bestLapMs = p?.bestLapMs?.takeIf { it > 0 } ?: laps.mapNotNull { it.timeMs?.takeIf { t -> t > 0 } }.minOrNull(),
                bestLapNumber = bestLapNumber,
                laps = lapRows(laps, bestLapNumber),
                tyres = tyres,
                compound = stint.compound,
                compoundCertain = stint.certain,
                tyreAgeLaps = currentLap?.let { stint.age },
                paceTrendSPerLap = paceTrend(laps, stint.startLap, excluded),
                fuelPct = fuelPct,
                fuelPerLapPct = perLapPct,
                fuelLapsLeft = if (fuelPct != null && perLapPct != null && perLapPct > 0f) fuelPct / perLapPct else null,
                stints = stints(c.rules.totalLaps ?: p?.totalLaps, currentLap, c.rules.startTyre, c.ledger.changes, stopLaps),
                requiredTyres = c.rules.requiredTyres,
            )
        }

        /** « 1:59.523 », « 41.471 ». */
        fun lapTime(ms: Int?): String {
            if (ms == null || ms <= 0) return "—"
            val m = ms / 60_000
            val s = (ms % 60_000) / 1000
            val milli = ms % 1000
            return if (m > 0) "%d:%02d.%03d".format(m, s, milli) else "%d.%03d".format(s, milli)
        }

        fun pct(v: Float?): String = v?.let { "${(it * 10).roundToInt() / 10.0}".replace('.', ',') } ?: "—"
    }
}
