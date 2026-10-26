package com.gt7pitwall.core.engineer

import com.gt7pitwall.core.strategy.FuelAdvice
import com.gt7pitwall.core.strategy.FuelReport

/**
 * Ce que la radio dit du carburant en fin de tour (course du 26/10 : « carburant pour 30,9 tours, marge de 18,9 tours »
 * répété alors que l'arrivée était assurée). Le calcul continue à chaque tour ; la radio ne parle que lorsque la
 * situation change de niveau :
 * - une seule fois « Essence suffisante pour l'arrivée » (de nouveau seulement après une alerte levée, par exemple
 *   après un plein) ;
 * - marge à l'arrivée de [TIGHT_ENTER_LAPS] tours ou moins : alerte, une fois, réarmée quand la marge repasse au-dessus
 *   de [TIGHT_EXIT_LAPS] (hystérésis : pas de répétition autour du seuil) ;
 * - carburant insuffisant pour finir sans arrêt carburant prévu : alerte, une fois par entrée dans ce niveau.
 *
 * Les alertes de bas niveau absolu (moins de deux tours, moins d'un tour) restent celles de l'ingénieur, critiques et
 * prioritaires ; un arrêt carburant prévu par le plan est annoncé par l'appel au stand, pas ici.
 *
 * Vocabulaire : [fuelLapsRemaining] = tours couverts par l'essence restante ; [raceLapsRemaining] = tours de course
 * restants ; marge = différence des deux.
 */
class FuelRadio {
    enum class Level { UNKNOWN, SAFE, TIGHT, INSUFFICIENT }

    var level: Level = Level.UNKNOWN
        private set

    /** Le dernier niveau dit à la radio (null : rien dit depuis le début de la course). */
    var announced: Level? = null
        private set

    fun reset() {
        level = Level.UNKNOWN
        announced = null
    }

    /**
     * Phrase à dire en fin de tour, ou null. [fuelStopPlanned] : le plan prévoit encore un arrêt pour l'essence (l'appel
     * au stand s'en charge).
     */
    fun onLap(f: FuelReport?, fuelStopPlanned: Boolean): String? {
        val fuelLaps = f?.lapsOfFuel ?: return null
        val raceLaps = f.raceLapsRemaining ?: return null
        if (f.advice is FuelAdvice.RaceFinished || f.perLap == null) return null
        val margin = fuelLaps - raceLaps
        val next = when {
            margin < 0f -> Level.INSUFFICIENT
            margin <= TIGHT_ENTER_LAPS -> Level.TIGHT
            // Hystérésis : on ne quitte « juste » qu'avec une vraie marge.
            level == Level.TIGHT && margin <= TIGHT_EXIT_LAPS -> Level.TIGHT
            else -> Level.SAFE
        }
        level = next
        if (next == announced) return null
        val sentence = when (next) {
            Level.SAFE -> if (announced == null) "Essence suffisante pour l'arrivée." else "C'est bon de nouveau : essence suffisante pour l'arrivée."
            Level.TIGHT -> "Carburant juste : marge de ${laps(margin)} à l'arrivée. Vise ${perLap(f, raceLaps)} par tour."
            Level.INSUFFICIENT -> if (fuelStopPlanned) null else "Essence insuffisante pour finir : il manque ${laps(-margin)}."
            Level.UNKNOWN -> null
        }
        // Insuffisant avec un arrêt prévu : rien à dire ici, mais le niveau compte comme annoncé (pas de « suffisant »
        // prématuré ensuite, et le retour à « suffisant » après le plein sera dit une fois).
        announced = next
        return sentence
    }

    private fun laps(x: Float): String {
        val r = (x * 10).toInt() / 10f
        return if (r < 2f) "${String.format(java.util.Locale.FRANCE, "%.1f", r)} tour" else "${String.format(java.util.Locale.FRANCE, "%.1f", r)} tours"
    }

    private fun perLap(f: FuelReport, raceLaps: Float): String {
        val target = f.fuelLevel / raceLaps.coerceAtLeast(0.1f)
        val pct = if (f.fuelCapacity > 0f) target / f.fuelCapacity * 100f else target
        return "${String.format(java.util.Locale.FRANCE, "%.1f", pct)} % du réservoir"
    }

    companion object {
        /** Marge à l'arrivée (tours) à partir de laquelle le carburant devient « juste ». */
        const val TIGHT_ENTER_LAPS = 2f

        /** Marge à retrouver pour quitter « juste » (hystérésis, incertitude de la consommation moyenne). */
        const val TIGHT_EXIT_LAPS = 2.5f
    }
}
