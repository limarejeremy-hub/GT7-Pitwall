package com.gt7pitwall.core

import com.gt7pitwall.core.session.LapDisplay
import kotlin.test.Test
import kotlin.test.assertEquals

class LapDisplayTest {
    @Test
    fun afterTheFinishTheScreenSaysFinishedNeverLapSixOfFive() {
        // Course du 27/10 : GT7 envoie le tour 6 sur 5 après l'arrivée.
        val end = LapDisplay.of(6, 5)
        assertEquals("Course terminée — 5/5", end.line)
        assertEquals("5/5", end.value)
        assertEquals(true, end.finished)
        // Arrivée détectée par Pitwall alors que GT7 affiche encore le dernier tour.
        assertEquals("Course terminée — 5/5", LapDisplay.of(5, 5, raceFinished = true).line)
        assertEquals("3 / 5", LapDisplay.of(3, 5).line)
        assertEquals("5 / 5", LapDisplay.of(5, 5).line)
        assertEquals("Avant départ", LapDisplay.of(0, 5).line)
        assertEquals("4", LapDisplay.of(4, 0).line)
    }
}
