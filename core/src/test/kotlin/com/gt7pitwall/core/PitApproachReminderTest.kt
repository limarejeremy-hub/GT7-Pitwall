package com.gt7pitwall.core

import com.gt7pitwall.core.strategy.PitApproachReminder
import com.gt7pitwall.core.strategy.PlannedStop
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PitApproachReminderTest {
    private val stop = PlannedStop(endOfLap = 8, changeTyres = true)

    @Test fun oneReminderOnlyInLastDistanceSectorOfScheduledPitLap() {
        val m = PitApproachReminder()
        assertNull(m.onProgress(7, 14, 14, 0.80f, stop, false))
        assertNull(m.onProgress(8, 14, 14, 0.65f, stop, false))
        assertNotNull(m.onProgress(8, 14, 14, 0.73f, stop, false))
        assertNull(m.onProgress(8, 14, 14, 0.77f, stop, false))
        m.reset()
        assertNotNull(m.onProgress(8, 14, 14, 0.72f, stop, false))
    }

    @Test fun noFalseCallsForWrongSeriesOrUnknownDistanceOrPitVisit() {
        val m = PitApproachReminder()
        assertNull(m.onProgress(8, 14, 5, 0.75f, stop, false))
        assertNull(m.onProgress(8, 14, 14, null, stop, false))
        assertNull(m.onProgress(8, 14, 14, 0.75f, stop, true))
        assertNull(m.onProgress(8, 14, 14, 0.99f, stop, false))
        assertNull(m.onProgress(8, 14, 14, 0.75f, null, false))
        assertNull(m.onProgress(8, 14, 14, 0.75f, stop.copy(endOfLap = 9), false))
    }

    @Test fun noCallAfterFinalLapNorOutsideRace() {
        val m = PitApproachReminder()
        assertNull(m.onProgress(14, 14, 14, 0.75f, stop.copy(endOfLap = 14), false))
        assertNull(m.onProgress(1, 14, 14, 0.75f, stop.copy(endOfLap = 1), false))
        assertNull(m.onProgress(8, 0, null, 0.75f, stop, false))
    }
}