package com.gt7pitwall.core

import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.radio.CommandSource
import com.gt7pitwall.core.session.PitAssessment
import com.gt7pitwall.core.strategy.RaceRules
import com.gt7pitwall.core.strategy.TyreCompound
import com.gt7pitwall.core.tyres.ThermalPhase
import com.gt7pitwall.core.tyres.ThermalSample
import com.gt7pitwall.core.tyres.TyreChangeHunter
import com.gt7pitwall.core.tyres.TyreChangeVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Course réelle du 25/10 (Porsche 963 '24, Le Mans, 7 tours, carburant ×4, pneus ×6) : arrêt au tour 4, plein et pneus
 * changés (confirmé « oui » par le pilote). Séries exportées par le journal de course, une valeur par seconde.
 */
class RealRace2510Test {
    private fun series(s: String) = s.split(',').map { it.toFloat() }

    private val avg = series("76.1,76.0,75.9,75.7,75.5,75.4,75.2,75.0,74.7,74.5,74.3,74.1,73.8,74.5,74.7,74.6,74.5,74.4,74.2,74.0,73.8,73.7,73.5,73.4,73.3,73.1,73.0,72.9,72.8,72.6,72.5,72.4,72.3,72.2,72.1,71.9,71.8,71.7,71.6,71.5,71.4,71.3,71.1,71.0,70.8,70.7,70.5,70.4,70.3,70.1,70.0,60.0,59.9,59.9,59.8,59.8,59.7,59.6,59.6,59.9,59.9,59.7,59.6,59.6,59.5,59.9,60.3,60.3,61.1,61.5,61.6,61.7,62.0,62.1,62.0,61.9")
    private val avd = series("74.8,74.6,74.5,74.3,74.2,74.0,73.9,73.7,73.5,73.2,73.0,72.8,72.6,73.6,74.2,74.2,74.1,73.9,73.8,73.6,73.4,73.3,73.2,73.0,72.9,72.8,72.6,72.5,72.4,72.3,72.2,72.0,71.9,71.8,71.7,71.6,71.5,71.4,71.2,71.1,71.0,70.9,70.8,70.6,70.5,70.3,70.2,70.0,69.9,69.8,69.7,60.0,59.9,59.9,59.8,59.8,59.7,59.6,59.6,59.9,59.9,59.7,59.6,59.6,59.5,59.7,59.8,59.8,60.6,61.4,61.9,61.9,62.1,62.0,61.9,61.8")
    private val arg = series("83.9,80.1,80.4,80.7,80.8,80.9,80.9,80.9,80.9,80.7,80.7,80.5,80.2,80.1,80.0,79.8,79.6,79.6,79.6,79.4,79.2,79.0,78.8,78.7,78.5,78.4,78.2,78.1,78.0,77.8,77.7,77.5,77.4,77.3,77.2,77.0,76.9,76.8,76.6,76.5,76.4,76.3,76.1,75.9,75.8,75.6,75.4,75.3,75.1,75.0,74.9,60.0,59.9,59.9,59.8,59.8,59.7,59.6,59.6,60.3,60.8,60.8,61.0,61.1,61.3,61.5,61.7,61.9,61.9,61.9,61.9,61.9,62.5,63.4,63.7,63.9")
    private val ard = series("85.4,82.1,82.4,82.6,82.6,82.8,82.9,82.9,82.8,82.7,82.5,82.3,82.0,81.9,81.8,81.6,81.5,81.5,81.4,81.2,81.0,80.8,80.6,80.5,80.3,80.2,80.0,79.9,79.7,79.6,79.4,79.3,79.2,79.0,78.9,78.8,78.6,78.5,78.4,78.2,78.1,78.0,77.8,77.7,77.5,77.3,77.1,77.0,76.8,76.7,76.6,60.0,59.9,59.9,59.8,59.8,59.7,59.6,59.6,60.2,60.8,60.8,61.0,61.1,61.3,61.3,61.4,61.5,61.6,61.7,61.9,61.9,62.1,62.7,63.0,63.1")
    private val kmh = series("88,111,132,154,171,191,202,218,233,241,250,228,220,141,64,51,55,72,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,59,59,41,11,0,0,0,0,0,0,0,0,0,0,0,0,9,39,99,132,145,166,183,197,193,198,213,137,102,100,97,101,126,153,171")
    private val fuel = series("23.5,23.4,23.3,23.3,23.2,23.1,23.0,22.9,22.7,22.6,22.5,22.5,22.5,22.5,22.5,22.5,22.5,22.5,22.4,22.4,22.4,22.4,22.4,22.4,22.4,22.4,22.4,22.4,22.4,22.4,22.4,22.4,22.4,22.4,22.4,22.4,22.4,22.4,22.4,22.4,22.4,22.4,22.4,22.4,22.4,22.4,22.4,22.4,22.4,22.4,22.4,22.4,24.1,34.1,44.1,54.1,64.1,65.4,65.4,65.4,65.3,65.2,65.1,65.0,64.9,64.9,64.8,64.7,64.7,64.7,64.7,64.7,64.7,64.6,64.5,64.4")

    /** Phases du journal : « A20 S41 E15 ». */
    private fun samples(): List<ThermalSample> = avg.indices.map { i ->
        val phase = when {
            i < 20 -> ThermalPhase.BEFORE
            i < 61 -> ThermalPhase.STOP
            else -> ThermalPhase.EXIT
        }
        ThermalSample(i + 1, phase, listOf(avg[i], avd[i], arg[i], ard[i]), kmh[i], fuel[i])
    }

    @Test
    fun theRealTyreChangeIsSuspectedFromTheUniformReset() {
        val c = TyreChangeHunter.analyze(4, PitAssessment.REFUEL, samples())
        // Avant : « non déterminé », le pneu avant droit n'ayant varié que de 10,0 °C (seuil 10 °C).
        assertEquals(TyreChangeVerdict.SUSPECTED, c.verdict, c.reason)
        assertTrue("même température (60 °C)" in c.reason, c.reason)
        val step = c.step!!
        assertEquals(52, step.atSecond, "le saut a lieu voiture immobile, avant le début du plein")
        assertEquals(listOf(-10.0f, -9.7f, -14.9f, -16.6f), step.deltas.map { Math.round(it * 10) / 10f })
    }

    @Test
    fun keptTyresThatOnlyCoolDownAreNotSuspected() {
        // Contre-exemple construit à partir de la même série : on retire la remise à 60 °C, les pneus refroidissent
        // simplement pendant l'arrêt (aucune donnée réelle de pneus conservés n'est encore disponible).
        val real = samples()
        val cooled = real.mapIndexed { i, s ->
            if (i < 51) s else s.copy(temps = real[50].temps.map { it - 0.15f * (i - 50) })
        }
        val c = TyreChangeHunter.analyze(4, PitAssessment.REFUEL, cooled)
        assertEquals(TyreChangeVerdict.UNDETERMINED, c.verdict, c.reason)
    }

    @Test
    fun theCompoundSaidAloneAnswersTheQuestion() {
        for (answer in listOf("moyens", "medium", "les moyens", "course mediums")) {
            val c = RaceController(storage = FakeStorage())
            c.setRules(RaceRules(totalLaps = 10, mandatoryStops = 1, allowedTyres = setOf(TyreCompound.RH, TyreCompound.RM, TyreCompound.RS), startTyre = TyreCompound.RS))
            c.confirmRules("2026-10-25")
            val s = RaceSim(c, totalLaps = 10, perLap = 9f, fuel = 100f)
            s.start()
            s.driveLaps(3)
            s.pitStop(fuel = 30f)
            s.say("oui")
            assertTrue("quelle gomme" in s.lastAnswer, s.lastAnswer)
            // Course réelle : réponse entendue pendant la conversation continue, sans « radio ».
            val out = c.utterance(answer, source = CommandSource.CONVERSATION)
            assertTrue(out.any { "course mediums montés" in it.text }, "$answer → $out")
            assertEquals(TyreCompound.RM, c.ledger.changes.last().compound, answer)
        }
    }
}
