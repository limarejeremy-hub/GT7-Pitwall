package com.gt7pitwall.core

import com.gt7pitwall.core.engineer.RaceEngineer
import com.gt7pitwall.core.knowledge.CarTrackMemory
import com.gt7pitwall.core.knowledge.Gt7Database
import com.gt7pitwall.core.radio.DriverCommand
import com.gt7pitwall.core.radio.DriverCommandParser
import com.gt7pitwall.core.radio.DriverQuery
import com.gt7pitwall.core.session.LapRecord
import com.gt7pitwall.core.session.SessionEvent
import com.gt7pitwall.core.session.SessionTracker
import com.gt7pitwall.core.strategy.FuelStrategy
import com.gt7pitwall.core.strategy.PlannedStop
import com.gt7pitwall.core.strategy.RacePlanner
import com.gt7pitwall.core.strategy.RaceRules
import com.gt7pitwall.core.telemetry.TelemetryParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StrategyTest {

    private fun plan(
        rules: RaceRules,
        gameLaps: Int = 0,
        lap: Int = 1,
        fuel: Float = 100f,
        perLap: Float? = 14.5f,
        avg: Int? = 110_000,
        elapsed: Long? = null,
        stopsDone: Int = 0,
    ) = RacePlanner.plan(rules, gameLaps, lap, fuel, 100f, perLap, avg, elapsed, stopsDone, 25f)

    @Test
    fun realRaceOneStop() {
        // Course réelle du 13/10 : 10 tours, 14,5 L/tour, réservoir de 100 L.
        val p = assertNotNull(plan(RaceRules(), gameLaps = 10))
        assertEquals(10, p.totalLaps)
        assertEquals(listOf(PlannedStop(5, 49, false)), p.stops)
    }

    @Test
    fun mandatoryStopWithoutFuelNeed() {
        val p = assertNotNull(plan(RaceRules(mandatoryStops = 1, requiredTyres = "RM"), gameLaps = 5, perLap = 5f))
        assertEquals(listOf(PlannedStop(3, 0, true)), p.stops)
        assertEquals("RM", p.requiredTyres)
        // Arrêt déjà fait : plus rien à prévoir.
        assertTrue(assertNotNull(plan(RaceRules(mandatoryStops = 1), gameLaps = 5, perLap = 5f, stopsDone = 1)).stops.isEmpty())
    }

    @Test
    fun timedRaceEstimatesLaps() {
        // 30 minutes à 2 min/tour : 15 tours dans le temps + le tour en cours à l'expiration.
        val before = assertNotNull(plan(RaceRules(durationMin = 30), lap = 0, perLap = 5f, avg = 120_000))
        assertEquals(16, before.totalLaps)
        assertTrue(before.estimatedLaps)
        // Après 10 minutes, au début du tour 6.
        val during = assertNotNull(plan(RaceRules(durationMin = 30), lap = 6, perLap = 5f, avg = 120_000, elapsed = 600_000))
        assertEquals(16, during.totalLaps)
        // Sans rythme connu, impossible d'estimer : pas de plan inventé.
        assertNull(plan(RaceRules(durationMin = 30), avg = null))
    }

    @Test
    fun unknownConsumptionKeepsLitresUnknown() {
        val p = assertNotNull(plan(RaceRules(totalLaps = 12, mandatoryStops = 2), perLap = null))
        assertEquals(listOf(4, 8), p.stops.map { it.endOfLap })
        assertTrue(p.stops.all { it.liters == null })
        assertNull(plan(RaceRules(totalLaps = 12), perLap = null))
    }

    @Test
    fun parsesRulesFromRadio() {
        assertEquals(DriverCommand.SetDuration(30), DriverCommandParser.parse("course de 30 minutes"))
        assertEquals(DriverCommand.SetDuration(30), DriverCommandParser.parse("trente minutes"))
        assertEquals(DriverCommand.SetDuration(45), DriverCommandParser.parse("course de quarante cinq minutes"))
        assertEquals(DriverCommand.SetDuration(60), DriverCommandParser.parse("course d'une heure"))
        assertEquals(DriverCommand.SetLaps(12), DriverCommandParser.parse("course de douze tours"))
        assertEquals(DriverCommand.SetMandatoryStops(1), DriverCommandParser.parse("arrêt obligatoire"))
        assertEquals(DriverCommand.SetMandatoryStops(2), DriverCommandParser.parse("deux arrêts obligatoires"))
        assertEquals(DriverCommand.SetMandatoryStops(0), DriverCommandParser.parse("pas d'arrêt obligatoire"))
        assertEquals(DriverCommand.SetFuelMultiplier(2), DriverCommandParser.parse("consommation fois deux"))
        assertEquals(DriverCommand.SetTyreMultiplier(3), DriverCommandParser.parse("usure fois trois"))
        assertEquals(DriverCommand.Ask(DriverQuery.PLAN), DriverCommandParser.parse("quelle stratégie"))
        // Les anciennes phrases restent comprises.
        assertEquals(DriverCommand.Ask(DriverQuery.LAST_LAP), DriverCommandParser.parse("mon dernier tour"))
        assertEquals(DriverCommand.ReportPosition(8), DriverCommandParser.parse("huitième"))
    }

    @Test
    fun engineerAnnouncesPlanOnceAndCallsTheBoxOnThePlannedLap() {
        val e = RaceEngineer()
        val p1 = assertNotNull(plan(RaceRules(), gameLaps = 10))
        val first = assertNotNull(e.onPlan(p1)).text
        assertTrue(first.startsWith("Course de 10 tours. Stratégie : 1 arrêt. fin du tour 5, 49 litres."), first)
        assertNull(e.onPlan(p1.copy(stops = listOf(PlannedStop(5, 51, false))))) // seuls les litres changent
        assertTrue(assertNotNull(e.onPlan(p1.copy(stops = listOf(PlannedStop(6, 40, false))))).text.startsWith("Nouveau plan."))
        e.onPlan(p1)

        val t = SessionTracker()
        val said = mutableListOf<String>()
        fun go(lap: Int, fuel: Float, last: Int) {
            val pk = TestPackets.packet(lap = lap, totalLaps = 10, fuel = fuel, lastLapMs = last)
            said += e.onUpdate(t.update(pk), t.snapshot, FuelStrategy.compute(t.snapshot)).map { it.text }
        }
        var fuel = 100f
        for (lap in 1..5) {
            go(lap, fuel, 110_000 + lap)
            fuel -= 14.5f
        }
        assertTrue(said.any { it.startsWith("Tour 3 :") && "Stand au prochain tour" in it }, said.toString())
        val box = said.first { it.startsWith("Tour 4 :") }
        assertTrue("Box, box ! Rentre au stand à la fin de ce tour, ajoute environ 49 litres." in box, box)
    }

    @Test
    fun stationaryCarCountsAsPitStop() {
        val t = SessionTracker()
        var id = 0
        fun send(lap: Int, speed: Float) {
            val pk = TelemetryParser.parseDecrypted(TestPackets.plain(lap = lap, packetId = ++id, speedMps = speed))!!
            t.update(pk)
        }
        send(0, 0f)
        repeat(700) { send(1, 30f) } // plus de 10 s de course
        val events = mutableListOf<SessionEvent>()
        repeat(200) {
            val pk = TelemetryParser.parseDecrypted(TestPackets.plain(lap = 1, packetId = ++id, speedMps = 0f))!!
            events += t.update(pk)
        }
        assertEquals(listOf(SessionEvent.PitStopDetected(1)), events.filterIsInstance<SessionEvent.PitStopDetected>())
        assertEquals(1, t.snapshot.stops)
        assertTrue(t.snapshot.raceElapsedMs!! > 10_000)
    }

    @Test
    fun communityDatabaseAndMemory() {
        val dir = File("../app/src/main/assets/gt7db")
        val db = Gt7Database.parse(
            File(dir, "cars.csv").readText(),
            File(dir, "maker.csv").readText(),
            File(dir, "course.csv").readText(),
        )
        assertEquals("963 '24", db.car(3607)?.name) // voiture de la course du 13/10
        assertTrue(db.cars.size > 500)
        val spa = db.coursesMatching(7070f).map { it.name }
        assertTrue("Circuit de Spa-Francorchamps" in spa, spa.toString())
        assertEquals(25f, db.course(462)?.pitLaneDeltaS)

        val laps = listOf(
            LapRecord(1, 111_000, 29f, false),
            LapRecord(2, 110_000, 29f, false),
            LapRecord(3, 140_000, null, true),
        )
        val rec = CarTrackMemory.update(null, 3607, 462, laps, fuelMultiplier = 2)
        assertEquals(110_000, rec.bestLapMs)
        assertEquals(14.5f, rec.fuelPerLapX1!!, 0.001f) // 29 L à ×2 = 14,5 L à ×1
        assertEquals(110_500, rec.typicalLapMs) // le tour au stand est exclu
    }
}
