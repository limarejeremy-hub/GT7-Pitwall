package com.gt7pitwall.core.strategy

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/** Règlement de l'épreuve, saisi par le pilote (GT7 ne le transmet pas). */
data class RaceRules(
    /** Nombre de tours (si GT7 ne l'envoie pas déjà). */
    val totalLaps: Int? = null,
    /** Course au temps, en minutes. */
    val durationMin: Int? = null,
    /** Multiplicateur de consommation de l'épreuve (×1, ×2…). */
    val fuelMultiplier: Int = 1,
    /** Multiplicateur d'usure des pneus de l'épreuve. */
    val tyreMultiplier: Int = 1,
    val mandatoryStops: Int = 0,
    /** Gommes imposées, en clair (ex. « RM + RH »). */
    val requiredTyres: String = "",
    /** Circuit choisi ou reconnu (identifiant de la base GT7). */
    val trackId: Int? = null,
) {
    val isEmpty: Boolean
        get() = totalLaps == null && durationMin == null && fuelMultiplier == 1 && tyreMultiplier == 1 &&
            mandatoryStops == 0 && requiredTyres.isBlank()
}

data class PlannedStop(
    /** Rentrer au stand à la fin de ce tour. */
    val endOfLap: Int,
    /** Litres à ajouter (null si la consommation n'est pas encore connue). */
    val liters: Int?,
    /** Arrêt imposé par le règlement, sans besoin d'essence. */
    val mandatoryOnly: Boolean,
)

data class RacePlan(
    val totalLaps: Int,
    /** Nombre de tours estimé (course au temps) plutôt que connu. */
    val estimatedLaps: Boolean,
    val stops: List<PlannedStop>,
    val perLap: Float?,
    /** Temps perdu par passage dans la voie des stands (base GT7), hors ravitaillement. */
    val pitLaneDeltaS: Float?,
    val requiredTyres: String,
) {
    fun nextStop(currentLap: Int): PlannedStop? = stops.firstOrNull { it.endOfLap >= currentLap }
}

/**
 * Plan de course à partir du règlement et de mesures réelles (consommation, chronos).
 * Nombre d'arrêts minimal pour le carburant et le règlement, relais équilibrés,
 * premier relais limité au carburant à bord.
 */
object RacePlanner {
    /** Marge de sécurité ajoutée à chaque plein, en tours. */
    const val SAFETY_LAPS = 0.3f

    fun plan(
        rules: RaceRules,
        gameTotalLaps: Int,
        currentLap: Int,
        fuelNow: Float,
        tank: Float,
        perLap: Float?,
        avgLapMs: Int?,
        raceElapsedMs: Long?,
        stopsDone: Int,
        pitLaneDeltaS: Float?,
    ): RacePlan? {
        var estimated = false
        val total: Int = when {
            gameTotalLaps > 0 -> gameTotalLaps
            rules.totalLaps != null && rules.totalLaps > 0 -> rules.totalLaps
            rules.durationMin != null && rules.durationMin > 0 && avgLapMs != null && avgLapMs > 0 -> {
                estimated = true
                val durationMs = rules.durationMin * 60_000L
                val elapsed = raceElapsedMs ?: 0L
                // À la fin du temps, on termine le tour en cours : un tour de plus que le temps écoulé.
                if (currentLap >= 1) {
                    (currentLap - 1) + ceil((durationMs - elapsed).coerceAtLeast(0).toDouble() / avgLapMs).toInt() + 1
                } else {
                    floor(durationMs.toDouble() / avgLapMs).toInt() + 1
                }
            }
            else -> return null
        }
        val lapIndex = max(currentLap, 1)
        val remaining = total - lapIndex + 1
        if (remaining <= 0) return RacePlan(total, estimated, emptyList(), perLap, pitLaneDeltaS, rules.requiredTyres)

        val mandatory = max(0, rules.mandatoryStops - stopsDone)
        // Sans consommation mesurée ni arrêt imposé, on ne sait rien : pas de plan plutôt qu'un faux « pas d'arrêt ».
        if ((perLap == null || perLap <= 0f) && mandatory == 0) return null
        val fuelStops = if (perLap == null || perLap <= 0f || tank <= 0f) {
            0
        } else {
            val need = (remaining + SAFETY_LAPS) * perLap
            if (need <= fuelNow) 0 else ceil((need - fuelNow) / tank).toInt()
        }
        val k = max(fuelStops, mandatory)
        if (k == 0) return RacePlan(total, estimated, emptyList(), perLap, pitLaneDeltaS, rules.requiredTyres)

        // Relais équilibrés ; le premier ne peut pas dépasser le carburant à bord.
        val stints = IntArray(k + 1) { i -> remaining / (k + 1) + if (i < remaining % (k + 1)) 1 else 0 }
        if (perLap != null && perLap > 0f) {
            val lapsOfFuel = floor(fuelNow / perLap - SAFETY_LAPS).toInt().coerceAtLeast(1)
            if (stints[0] > lapsOfFuel) {
                stints[0] = lapsOfFuel
                val rest = remaining - lapsOfFuel
                for (i in 1..k) stints[i] = rest / k + if (i - 1 < rest % k) 1 else 0
            }
        }

        val stops = mutableListOf<PlannedStop>()
        var lap = lapIndex - 1
        var fuel = fuelNow
        for (i in 0 until k) {
            lap += stints[i]
            val liters: Int? = perLap?.takeIf { it > 0f }?.let { pl ->
                fuel -= stints[i] * pl
                val needNext = (stints[i + 1] + SAFETY_LAPS) * pl
                val add = (needNext - fuel).coerceIn(0f, (tank - fuel).coerceAtLeast(0f))
                fuel += add
                add.roundToInt()
            }
            stops += PlannedStop(endOfLap = lap, liters = liters, mandatoryOnly = liters != null && liters < 1)
        }
        return RacePlan(total, estimated, stops, perLap, pitLaneDeltaS, rules.requiredTyres)
    }
}
