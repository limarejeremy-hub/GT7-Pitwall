package com.gt7pitwall.core.strategy

import com.gt7pitwall.core.session.SessionSnapshot
import kotlin.math.ceil
import kotlin.math.floor

/** Conseil carburant calculé uniquement à partir des niveaux mesurés dans la télémétrie. */
sealed interface FuelAdvice {
    /** Pas encore de tour complet mesuré. */
    data object Measuring : FuelAdvice

    /** Le niveau n'a pas baissé sur les tours complets mesurés : épreuve sans consommation. */
    data object NoConsumption : FuelAdvice

    /** GT7 ne fournit pas de capacité de réservoir (ex. véhicule électrique). */
    data object NotAvailable : FuelAdvice

    /** Course au temps / essai libre : la distance restante est inconnue, on donne seulement l'autonomie. */
    data class RangeOnly(val lapsOfFuel: Float) : FuelAdvice

    data class Comfortable(val marginLaps: Float) : FuelAdvice

    /** On finit, mais avec moins d'un demi-tour de marge : il faut gérer. */
    data class Tight(val marginLaps: Float, val targetPerLap: Float) : FuelAdvice

    data class PitRequired(
        /** Quantité à ajouter (unité GT7), marge d'un demi-tour incluse. */
        val fuelToAdd: Float,
        /** Dernier tour à la fin duquel il faut rentrer au stand. */
        val latestPitLap: Int,
        val stopsNeeded: Int,
        /** Économie nécessaire par tour pour éviter l'arrêt (0.08 = 8 %). */
        val savingNeeded: Float,
        val targetPerLap: Float,
    ) : FuelAdvice

    data object RaceFinished : FuelAdvice
}

data class FuelReport(
    val fuelLevel: Float,
    val fuelCapacity: Float,
    /** Consommation moyenne par tour (3 derniers tours valides). */
    val perLap: Float?,
    val samples: Int,
    /** Nombre de tours couverts par le carburant restant. */
    val lapsOfFuel: Float?,
    /** Tours restants dans la course, tour en cours inclus (fraction estimée par la consommation). */
    val raceLapsRemaining: Float?,
    val fuelToFinish: Float?,
    val advice: FuelAdvice,
)

object FuelStrategy {
    const val SAMPLE_WINDOW = 3
    const val TIGHT_MARGIN_LAPS = 0.5f
    const val SAFETY_LAPS = 0.5f

    /** [totalLapsOverride] : nombre de tours du règlement ou estimé (course au temps) quand GT7 n'en envoie pas. */
    fun compute(s: SessionSnapshot, totalLapsOverride: Int? = null): FuelReport? {
        val p = s.packet ?: return null
        val level = p.fuelLevel
        val capacity = p.fuelCapacity
        if (capacity <= 0f) {
            return FuelReport(level, capacity, null, 0, null, null, null, FuelAdvice.NotAvailable)
        }

        val samples = s.fuelSamples.takeLast(SAMPLE_WINDOW)
        val perLap = if (samples.isEmpty()) null else samples.average().toFloat()
        val lapsOfFuel = perLap?.let { level / it }

        // Part du tour en cours restant à parcourir, estimée par le carburant déjà consommé dans ce tour.
        val start = s.fuelAtLapStart
        val currentLapRemaining = if (perLap != null && start != null && !s.refuelledThisLap) {
            1f - ((start - level) / perLap).coerceIn(0f, 1f)
        } else {
            1f // inconnu : hypothèse prudente d'un tour complet
        }

        val totalLaps = if (p.totalLaps > 0) p.totalLaps else totalLapsOverride ?: 0
        val raceLapsRemaining: Float? = when {
            totalLaps <= 0 -> null
            p.currentLap > totalLaps -> 0f
            p.currentLap <= 0 -> totalLaps.toFloat()
            else -> (totalLaps - p.currentLap) + currentLapRemaining
        }

        val fuelToFinish = if (perLap != null && raceLapsRemaining != null) raceLapsRemaining * perLap else null

        val advice: FuelAdvice = when {
            raceLapsRemaining == 0f -> FuelAdvice.RaceFinished
            perLap == null && s.zeroFuelLaps > 0 -> FuelAdvice.NoConsumption
            perLap == null || lapsOfFuel == null -> FuelAdvice.Measuring
            raceLapsRemaining == null || fuelToFinish == null -> FuelAdvice.RangeOnly(lapsOfFuel)
            else -> {
                val marginLaps = (level - fuelToFinish) / perLap
                val targetPerLap = level / raceLapsRemaining
                when {
                    marginLaps >= TIGHT_MARGIN_LAPS -> FuelAdvice.Comfortable(marginLaps)
                    marginLaps >= 0f -> FuelAdvice.Tight(marginLaps, targetPerLap)
                    else -> {
                        val toAdd = (fuelToFinish - level) + SAFETY_LAPS * perLap
                        val lapsAfterCurrent = floor(lapsOfFuel - currentLapRemaining).toInt().coerceAtLeast(0)
                        val latest = if (lapsOfFuel < currentLapRemaining) p.currentLap else p.currentLap + lapsAfterCurrent
                        FuelAdvice.PitRequired(
                            fuelToAdd = toAdd,
                            latestPitLap = latest.coerceAtMost(totalLaps),
                            stopsNeeded = ceil(toAdd / capacity).toInt().coerceAtLeast(1),
                            savingNeeded = 1f - targetPerLap / perLap,
                            targetPerLap = targetPerLap,
                        )
                    }
                }
            }
        }

        return FuelReport(level, capacity, perLap, samples.size, lapsOfFuel, raceLapsRemaining, fuelToFinish, advice)
    }
}
