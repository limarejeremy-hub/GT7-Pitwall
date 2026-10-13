package com.gt7pitwall.core.analysis

import com.gt7pitwall.core.session.LapRecord
import kotlin.math.sqrt

/**
 * Statistiques de chronos à partir des tours réellement bouclés.
 * Base de l'analyse de qualification : à enrichir (tours invalidés, comparaisons de runs…).
 */
data class LapStats(
    val lapCount: Int,
    val bestMs: Int?,
    val bestLapNumber: Int?,
    val lastMs: Int?,
    /** Moyenne des [LapAnalyzer.RECENT_WINDOW] derniers tours chronométrés. */
    val recentAverageMs: Int?,
    /** Écart-type des derniers tours (régularité), en ms. */
    val recentStdDevMs: Int?,
)

object LapAnalyzer {
    const val RECENT_WINDOW = 5

    fun analyze(laps: List<LapRecord>): LapStats {
        val timed = laps.filter { (it.timeMs ?: 0) > 0 }
        val best = timed.minByOrNull { it.timeMs!! }
        val recent = timed.takeLast(RECENT_WINDOW).map { it.timeMs!!.toDouble() }
        val avg = if (recent.size >= 2) recent.average() else null
        val std = if (avg != null) sqrt(recent.sumOf { (it - avg) * (it - avg) } / recent.size) else null
        return LapStats(
            lapCount = laps.size,
            bestMs = best?.timeMs,
            bestLapNumber = best?.lapNumber,
            lastMs = timed.lastOrNull()?.timeMs,
            recentAverageMs = avg?.toInt(),
            recentStdDevMs = std?.toInt(),
        )
    }
}
