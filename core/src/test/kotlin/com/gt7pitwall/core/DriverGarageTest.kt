package com.gt7pitwall.core

import com.gt7pitwall.core.knowledge.Car
import com.gt7pitwall.core.knowledge.CarEvidence
import com.gt7pitwall.core.knowledge.DriverGarage
import com.gt7pitwall.core.knowledge.Gt7Database
import com.gt7pitwall.core.race.EventProfile
import com.gt7pitwall.core.race.LapEntry
import com.gt7pitwall.core.race.ProfileId
import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.race.SessionContext
import com.gt7pitwall.core.race.SessionKind
import com.gt7pitwall.core.race.SessionOutcome
import com.gt7pitwall.core.race.SessionRecord
import com.gt7pitwall.core.sport.Edition
import com.gt7pitwall.core.sport.FieldStatus
import com.gt7pitwall.core.sport.Sourced
import com.gt7pitwall.core.strategy.RaceRules
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Regressions: never confuse a previous car, a meta car and today's actual car. */
class DriverGarageTest {
    private val date = LocalDate.of(2026,10,26)
    private val profile = EventProfile(id = ProfileId(Series.DAILY_C, SessionKind.RACE),
        eventDate = date.toString())
    private val db = Gt7Database(mapOf(
        100 to Car(100,"BRZ GT300 '21","Subaru"),
        200 to Car(200,"M6 GT3 Sprint Model '16","BMW")
    ), emptyList())
    private fun sourcedCar(text: String) = Edition(
        series = Series.DAILY_C, validFrom = date, validTo = date.plusDays(7),
        trackName = Sourced("SUGO International Racing Course",FieldStatus.CONFIRMED),
        trackId = 33, laps = Sourced(14,FieldStatus.CONFIRMED),
        cars = Sourced(text,FieldStatus.CONFIRMED),
        bop = Sourced.unknown(), settings = Sourced.unknown(),
        startType = Sourced.unknown(), allowedTyres = Sourced.unknown(),
        requiredTyres = Sourced.unknown(), mandatoryStops = Sourced.unknown(),
        fuelMultiplier = Sourced.unknown(), tyreMultiplier = Sourced.unknown(),
        sources = emptyList(),
    )
    private fun race(
        id:String, code:Int, day:String = "2026-10-26", track:Int=33,
        time:Long=1_000L, position:Int=6
    ):SessionRecord = SessionRecord(
        id=id, context=SessionContext(profile.copy(eventDate=day),
            RaceRules(trackId=track),code,null),
        startedAtMs=time,updatedAtMs=time,
        outcome=SessionOutcome.FINISHED,
        laps=listOf(
            LapEntry(1,88_000,2.0f,false),
            LapEntry(2,80_500,1.9f,false),
            LapEntry(3,79_900,1.8f,false,pitLane=true),
        ),finishPosition=position,
    )

    @Test fun liveUdpAlwaysOverridesArchivedCar() {
        val a=DriverGarage.resolve(200,true,profile,33,sourcedCar("Gr.3"),
            listOf(race("a",100)),db)
        assertEquals(CarEvidence.LIVE,a.evidence)
        assertEquals(200,a.code)
        assertEquals("BMW M6 GT3 Sprint Model '16",a.name)
        assertEquals(0,a.raceCount)
    }

    @Test fun recentSameWeekRecordIsClearlyNotLive() {
        val a=DriverGarage.resolve(null,false,profile,33,sourcedCar("Gr.3"),
            listOf(race("old",200,day="2026-10-19",time=9_999),
                   race("new",100,time=5000,position=4)),db)
        assertEquals(CarEvidence.THIS_WEEK,a.evidence)
        assertEquals(100,a.code)
        assertEquals(1,a.raceCount)
        assertEquals(2,a.completedLaps)
        assertEquals(80_500,a.bestCleanLapMs)
        assertEquals(4,a.bestFinishPosition)
    }

    @Test fun unknownCompoundClassNeverBecomesTheDriversCar() {
        val a=DriverGarage.resolve(null,false,profile,33,sourcedCar("Gr.3"),emptyList(),db)
        assertEquals(CarEvidence.UNKNOWN,a.evidence)
        assertNull(a.code)
    }

    @Test fun uniqueOneMakeCanBeShownAsEventCarButNeverAsConfirmedLive() {
        val a=DriverGarage.resolve(null,false,profile,33,
            sourcedCar("Subaru BRZ GT300 '21"),emptyList(),db)
        assertEquals(CarEvidence.FIXED_EVENT,a.evidence)
        assertEquals(100,a.code)
        assertEquals(false,a.liveConfirmed)
    }

    @Test fun previousWeekMemoryRequiresTheSameKnownTrackAndMode() {
        val a=DriverGarage.resolve(null,false,profile,33,sourcedCar("Gr.3"),
            listOf(race("before",100,day="2026-10-19")),db)
        assertEquals(CarEvidence.EARLIER,a.evidence)
        assertEquals(100,a.code)
        assertEquals(0,a.raceCount) // weekly race stats may not be mixed across editions
        val b=DriverGarage.resolve(null,false,profile,null,sourcedCar("Gr.3"),
            listOf(race("before",100,day="2026-10-19")),db)
        assertEquals(CarEvidence.UNKNOWN,b.evidence)
    }

    @Test fun missingCatalogCodeIsNotRelabelledAsAnotherCar() {
        val a=DriverGarage.resolve(999,true,profile,33,sourcedCar("Gr.3"),
            listOf(race("old",100)),db)
        assertEquals(CarEvidence.LIVE_UNNAMED,a.evidence)
        assertEquals(999,a.code)
        assertNull(a.name)
    }
}
