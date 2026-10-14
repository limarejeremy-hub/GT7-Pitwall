package com.gt7pitwall.core

import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.radio.DriverCommand
import com.gt7pitwall.core.radio.DriverCommandParser
import com.gt7pitwall.core.radio.DriverQuery
import com.gt7pitwall.core.strategy.RaceRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Essai du 14/10 (v37) : « doit changer mes pneus » donnait les températures ; pas d'alerte pneus avant l'essence. */
class TyreChangeAdviceTest {
    private val p = DriverCommandParser::parse

    @Test
    fun askingWhenToChangeTyresIsAQuestionNotATemperatureRequest() {
        val q = DriverCommand.Ask(DriverQuery.TYRE_CHANGE)
        assertEquals(q, p("doit changer mes pneus")) // transcription réelle
        assertEquals(q, p("quand est-ce que je dois changer les pneus"))
        assertEquals(q, p("il faut changer les gommes"))
        assertEquals(q, p("prochain changement de pneus"))
        // Les autres phrases sur les pneus gardent leur sens.
        assertEquals(DriverCommand.TyresChanged, p("j'ai changé les pneus"))
        assertEquals(DriverCommand.Ask(DriverQuery.TYRES), p("mes pneus"))
        assertEquals(DriverCommand.SetTyreStint(8), p("les pneus tiennent huit tours"))
    }

    @Test
    fun withoutTyreLifeTheEngineerExplainsAndAsks() {
        val c = RaceController()
        val s = RaceSim(c, totalLaps = 10, perLap = 2f)
        s.start()
        s.driveLaps(2)
        s.say("doit changer mes pneus")
        assertTrue("GT7 ne transmet pas l'usure" in s.lastAnswer && "les pneus tiennent" in s.lastAnswer, s.lastAnswer)
    }

    @Test
    fun aTyreStopBeforeTheFuelStopIsPlannedAndCalled() {
        val c = RaceController()
        // 15 tours, essence pour environ 10 tours, pneus pour 4 tours : les pneus imposent le premier arrêt.
        val s = RaceSim(c, totalLaps = 15, perLap = 6f, fuel = 60f)
        s.start()
        s.say("les pneus tiennent quatre tours")
        s.driveLaps(1)
        val first = c.plan!!.stops.first()
        assertTrue(first.changeTyres && first.endOfLap <= 4, c.plan!!.stops.toString())
        assertTrue(c.plan!!.stops.any { it.forFuel }, "un ravitaillement reste prévu")

        s.say("quand est-ce que je dois changer mes pneus")
        assertTrue("sur 4" in s.lastAnswer && "Changement prévu à la fin du tour ${first.endOfLap}" in s.lastAnswer, s.lastAnswer)

        while (s.lap < first.endOfLap) s.driveLaps(1)
        val box = s.texts.last { it.startsWith("Box, box") }
        assertTrue("Change les pneus" in box, box)
    }

    @Test
    fun withTyreWearOnTheEngineerAsksForTheTyreLifeOnce() {
        val c = RaceController()
        c.setRules(RaceRules(tyreMultiplier = 3))
        val s = RaceSim(c, totalLaps = 10, perLap = 2f)
        s.start()
        s.driveLaps(3)
        val asks = s.said.filter { "Combien de tours tiennent" in it.text }
        assertEquals(1, asks.size)
        assertTrue(asks[0].expectsReply)
        s.say("les pneus tiennent six tours")
        assertEquals(6, c.rules.tyreStintLaps)
    }
}
