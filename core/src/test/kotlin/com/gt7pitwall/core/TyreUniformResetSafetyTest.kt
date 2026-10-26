package com.gt7pitwall.core

import com.gt7pitwall.core.session.PitAssessment
import com.gt7pitwall.core.tyres.ThermalPhase
import com.gt7pitwall.core.tyres.ThermalSample
import com.gt7pitwall.core.tyres.TyreChangeHunter
import com.gt7pitwall.core.tyres.TyreChangeVerdict
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Les deux détecteurs thermiques doivent distinguer une vraie rupture
 * à l'arrêt d'un changement de phase, d'une coupure UDP ou du simple refroidissement.
 * La reconnaissance reste expérimentale : SUSPECTED n'est jamais CONFIRMED.
 */
class TyreUniformResetSafetyTest {
    private val warm = listOf(74f, 71f, 78f, 81f)
    private val fresh = listOf(60f, 60f, 60f, 60f)

    @Test
    fun uniformResetInsideAContinuousStationaryStopIsSuspectedOnly() {
        val samples = (0 until 18).map { i ->
            ThermalSample(i, if (i < 4) ThermalPhase.BEFORE else ThermalPhase.STOP,
                if (i < 9) warm else fresh, 0f, 50f)
        }
        val result = TyreChangeHunter.analyze(4, PitAssessment.REFUEL, samples)
        assertEquals(TyreChangeVerdict.SUSPECTED, result.verdict)
    }

    @Test
    fun aSuddenResetAtPitEntryIsNotEvidenceOfNewTyres() {
        val samples = (0 until 14).map { i ->
            ThermalSample(i, if (i < 6) ThermalPhase.BEFORE else ThermalPhase.STOP,
                if (i < 6) warm else fresh, if (i < 6) 80f else 0f, 50f)
        }
        val result = TyreChangeHunter.analyze(4, PitAssessment.REFUEL, samples)
        assertEquals(TyreChangeVerdict.UNDETERMINED, result.verdict)
    }

    @Test
    fun aMissingSecondDuringTheTemperatureStepIsNotEvidence() {
        val samples = (0 until 18).map { i ->
            ThermalSample(if (i < 10) i else i + 1,
                if (i < 4) ThermalPhase.BEFORE else ThermalPhase.STOP,
                if (i < 10) warm else fresh, 0f, 50f)
        }
        val result = TyreChangeHunter.analyze(4, PitAssessment.REFUEL, samples)
        assertEquals(TyreChangeVerdict.UNDETERMINED, result.verdict)
    }

    @Test
    fun ordinaryCoolingDoesNotBecomeAChange() {
        val samples = (0 until 18).map { i ->
            ThermalSample(i, if (i < 4) ThermalPhase.BEFORE else ThermalPhase.STOP,
                warm.map { it - i * 0.3f }, 0f, 50f)
        }
        val result = TyreChangeHunter.analyze(4, PitAssessment.REFUEL, samples)
        assertEquals(TyreChangeVerdict.UNDETERMINED, result.verdict)
    }
}
