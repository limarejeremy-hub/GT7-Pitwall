package com.gt7pitwall.core

import com.gt7pitwall.core.engineer.FuelRadio
import com.gt7pitwall.core.strategy.FuelAdvice
import com.gt7pitwall.core.strategy.FuelReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Radio carburant : une annonce par changement de situation, jamais à chaque tour (course du 26/10). */
class FuelRadioTest {
    /** [fuel] % du réservoir, [perLap] % par tour, [raceLaps] tours restants. */
    private fun report(fuel: Float, perLap: Float, raceLaps: Float): FuelReport {
        val lapsOfFuel = fuel / perLap
        val margin = lapsOfFuel - raceLaps
        val advice = if (margin >= 0.2f) FuelAdvice.Comfortable(margin) else if (margin >= 0f) FuelAdvice.Tight(margin, fuel / raceLaps) else
            FuelAdvice.PitRequired(-margin * perLap, 1, 1, 0f, fuel / raceLaps)
        return FuelReport(fuel, 100f, perLap, 3, lapsOfFuel, raceLaps, raceLaps * perLap, advice)
    }

    @Test
    fun theSafeMessageIsSaidOnceThenSilence() {
        // Course du 26/10 : 93,4 % au tour 2, 3 % par tour, 12 tours restants (marge ~19 tours).
        val r = FuelRadio()
        assertEquals("Essence suffisante pour l'arrivée.", r.onLap(report(93.4f, 3.04f, 12f), false))
        var fuel = 93.4f
        for (left in 11 downTo 1) {
            fuel -= 3f
            assertNull(r.onLap(report(fuel, 3f, left.toFloat()), false), "tour à $left restants")
        }
    }

    @Test
    fun aTightMarginIsAnnouncedOnceWithHysteresis() {
        val r = FuelRadio()
        r.onLap(report(50f, 4f, 9f), false) // marge 3,5 : suffisant
        val tight = r.onLap(report(44f, 4.2f, 8.5f), false) // marge ~2,0
        assertNotNull(tight)
        assertTrue(tight.startsWith("Carburant juste"), tight)
        // Autour du seuil (2,2 puis 1,9 puis 2,3) : aucune répétition.
        assertNull(r.onLap(report(41f, 4f, 8.05f), false))
        assertNull(r.onLap(report(37f, 4f, 7.35f), false))
        assertNull(r.onLap(report(33f, 4f, 5.95f), false))
        // Marge vraiment retrouvée (> 2,5) : une phrase, une fois.
        assertTrue(r.onLap(report(30f, 3f, 6f), false)!!.contains("essence suffisante"))
        assertNull(r.onLap(report(27f, 3f, 5f), false))
    }

    @Test
    fun insufficientFuelIsSaidOnceUnlessAFuelStopIsPlannedAndRefuelReassuresOnce() {
        val r = FuelRadio()
        val short = r.onLap(report(20f, 4f, 8f), false)
        assertTrue(short!!.startsWith("Essence insuffisante pour finir"), short)
        assertNull(r.onLap(report(16f, 4f, 7f), false), "pas de répétition")
        // Après le plein : une seule phrase rassurante.
        assertTrue(r.onLap(report(60f, 4f, 6f), false)!!.contains("essence suffisante"))
        assertNull(r.onLap(report(56f, 4f, 5f), false))

        // Arrêt carburant prévu : l'appel au stand en parle, pas la fin de tour ; ni « suffisant » prématuré.
        val planned = FuelRadio()
        assertNull(planned.onLap(report(30f, 4f, 10f), fuelStopPlanned = true))
        assertNull(planned.onLap(report(26f, 4f, 9f), fuelStopPlanned = true))
        planned.reset()
        assertEquals("Essence suffisante pour l'arrivée.", planned.onLap(report(90f, 3f, 10f), false), "nouvelle course : réarmé")
    }
}
