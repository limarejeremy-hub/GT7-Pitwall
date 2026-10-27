package com.gt7pitwall.core

import com.gt7pitwall.core.analysis.CornerPass
import com.gt7pitwall.core.tyres.TyreOrDriving
import com.gt7pitwall.core.tyres.TyreOrDriving.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Issue #30-D : baisse de rythme due aux pneus ou au pilotage, lue sur les mêmes virages, sans usure inventée. */
class TyreOrDrivingTest {
    private fun pass(corner: Int, lap: Int, timeMs: Int, minKmh: Float, lockup: Boolean = false) = CornerPass(
        corner = corner, lap = lap, timeMs = timeMs, brakeStartM = null, brakePeak = null, brakeRampMs = null,
        entryKmh = minKmh + 60f, minKmh = minKmh, minAtM = 0f, throttleOnM = null, exitKmh = minKmh + 30f,
        lockup = lockup, wheelspin = false, tcsShare = 0f, apexLateralM = 0f, rotationRatio = null, offTrack = false,
    )

    /** 8 virages, tours 2 à 7 ; [loss] donne la perte (ms) et la baisse de vitesse minimale d'un virage à un tour. */
    private fun stint(loss: (corner: Int, lap: Int) -> Pair<Int, Float>, lock: (Int, Int) -> Boolean = { _, _ -> false }) =
        (2..7).flatMap { lap -> (1..8).map { k -> val (ms, drop) = loss(k, lap); pass(k, lap, 3_000 + ms, 100f - drop, lock(k, lap)) } }

    @Test
    fun aLossSpreadOverMostCornersWithLowerMinimumSpeedsPointsToTyres() {
        // Dégradation progressive : chaque virage perd ~25 ms et 0,8 km/h par tour à partir du tour 4.
        val passes = stint({ _, lap -> val n = (lap - 3).coerceAtLeast(0); 25 * n to 0.8f * n }, { k, lap -> lap == 7 && k <= 2 })
        val r = TyreOrDriving.analyze(passes, stintStartLap = 1) { "T$it" }
        assertEquals(Verdict.TYRES, r.verdict, r.text)
        assertTrue(r.text.contains("8 virages sur 8"), r.text)
        assertTrue(r.text.contains("km/h plus basse"), r.text)
        assertTrue(r.text.contains("blocages ou patinages de plus"), r.text)
        assertEquals("moyenne", r.confidence)
        assertFalse(r.text.contains("%"), "jamais de pourcentage d'usure")
    }

    @Test
    fun aLossConcentratedOnOneCornerPointsToDriving() {
        // T5 manqué deux fois en fin de relais (0,6 s), le reste du tour inchangé.
        val passes = stint({ k, lap -> if (k == 5 && lap >= 6) 600 to 6f else 0 to 0f })
        val r = TyreOrDriving.analyze(passes, stintStartLap = 1) { "T$it" }
        assertEquals(Verdict.DRIVING, r.verdict, r.text)
        assertTrue(r.text.startsWith("Perte concentrée"), r.text)
        assertTrue(r.text.contains("T5 : 0,60 s"), r.text)
    }

    @Test
    fun aSteadyStintIsStableAndTooFewLapsIsUnknown() {
        val steady = stint({ k, lap -> ((k + lap) % 3) * 5 to 0f })
        assertEquals(Verdict.STABLE, TyreOrDriving.analyze(steady, 1).verdict)
        // Relais commencé au tour 5 : seuls les tours 6 et 7 comptent, c'est trop peu.
        val r = TyreOrDriving.analyze(steady, stintStartLap = 5)
        assertEquals(Verdict.UNKNOWN, r.verdict)
        assertEquals("Pas assez de passages comparables.", r.text)
    }

    @Test
    fun theFirstLapOfTheStintIsIgnored() {
        // Relais commencé au tour 2 : ce tour, pneus froids, est très lent ; il ne doit pas faire croire à une remontée.
        val passes = stint({ _, lap -> if (lap == 2) 400 to 5f else 0 to 0f })
        assertEquals(Verdict.STABLE, TyreOrDriving.analyze(passes, stintStartLap = 2).verdict)
    }
}
