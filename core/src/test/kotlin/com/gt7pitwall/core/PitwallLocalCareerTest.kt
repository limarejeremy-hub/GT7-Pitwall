package com.gt7pitwall.core

import com.gt7pitwall.core.profile.PitwallLocalCareer
import com.gt7pitwall.core.race.EventProfile
import com.gt7pitwall.core.race.LapEntry
import com.gt7pitwall.core.race.ProfileId
import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.race.SessionContext
import com.gt7pitwall.core.race.SessionKind
import com.gt7pitwall.core.race.SessionMode
import com.gt7pitwall.core.race.SessionOutcome
import com.gt7pitwall.core.race.SessionRecord
import com.gt7pitwall.core.strategy.RaceRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PitwallLocalCareerTest {
    private fun session(
        id: String,
        mode: SessionMode = SessionMode.SPORT_OFFICIAL,
        kind: SessionKind = SessionKind.RACE,
        outcome: SessionOutcome = SessionOutcome.FINISHED,
        start: Int? = 7,
        end: Int? = 3,
        laps: List<LapEntry> = listOf(LapEntry(1,95_000,1.8f,false),
            LapEntry(2,94_000,1.7f,false)),
        car: Int=500,
        track: Int?=41,
        updated: Long=1234,
    ): SessionRecord {
        val profile=EventProfile(id=ProfileId(Series.DAILY_B,kind,mode))
        return SessionRecord(id=id,context=SessionContext(profile,RaceRules(trackId=track),car,null),
            startedAtMs=1000,updatedAtMs=updated,outcome=outcome,laps=laps,
            gridPosition=start,finishPosition=end)
    }

    @Test fun firstPartyRecordsNeverClaimUnmeasuredGlobalPlayStationRaces() {
        val a = PitwallLocalCareer.fromSessions(listOf(session("one")))
        assertEquals(1,a.sessionsSaved)
        assertEquals(1,a.completedSessions)
        assertEquals(2,a.timedLaps)
        assertEquals(1,a.verifiedSportRaces)
        assertEquals(1,a.sportPodiums)
        assertEquals(1,a.sportTop5)
        assertEquals(1,a.sportFinishPositionsMeasured)
        assertEquals(4,a.netPositionsGained)
        assertEquals(94_000,a.bestTimedLapMs)
    }

    @Test fun aPartialRaceOrUnverifiedModeNeverCreatesAnOfficialVictory() {
        val a=PitwallLocalCareer.fromSessions(listOf(
            session("a",mode=SessionMode.UNVERIFIED,end=1),
            session("b",mode=SessionMode.SPORT_PRACTICE,end=1),
            session("c",outcome=SessionOutcome.INTERRUPTED,end=1),
            session("d",kind=SessionKind.QUALIFYING,mode=SessionMode.TIME_TRIAL,end=1)
        ))
        assertEquals(4,a.sessionsSaved)
        assertEquals(3,a.completedSessions)
        assertEquals(0,a.verifiedSportRaces)
        assertEquals(0,a.sportWins)
        assertNull(a.netPositionsGained)
    }

    @Test fun missingFinishingPositionIsUnknownNotAZeroVictory() {
        val a=PitwallLocalCareer.fromSessions(listOf(session("one",end=null,start=null)))
        assertEquals(1,a.verifiedSportRaces)
        assertEquals(0,a.sportFinishPositionsMeasured)
        assertNull(a.netPositionsGained)
    }

    @Test fun sameSessionPersistedAtMultipleLapsCountsOnce() {
        val old=session("one",outcome=SessionOutcome.IN_PROGRESS,updated=100)
        val final=session("one",outcome=SessionOutcome.FINISHED,end=1,updated=200)
        val a=PitwallLocalCareer.fromSessions(listOf(old,final))
        assertEquals(1,a.sessionsSaved)
        assertEquals(1,a.verifiedSportRaces)
        assertEquals(1,a.sportWins)
        assertEquals(2,a.timedLaps)
    }

    @Test fun nonTimedArchiveIsCountedButNotAsTimedEvidence() {
        val a=PitwallLocalCareer.fromSessions(listOf(
            session("one",laps=listOf(LapEntry(1,null,null,false)),start=1,end=1)
        ))
        assertEquals(1,a.sessionsSaved)
        assertEquals(0,a.timedLaps)
        assertEquals(1,a.sessionsWithoutLapTime)
        assertEquals(1,a.sportWins)
    }

    @Test fun sameCarAndCircuitCountOnlyOnce() {
        val a=PitwallLocalCareer.fromSessions(listOf(
            session("x",car=500,track=41),
            session("y",car=500,track=41),
            session("z",car=600,track=null)
        ))
        assertEquals(3,a.sessionsSaved)
        assertEquals(2,a.recordedCars)
        assertEquals(1,a.identifiedTracks)
    }
}
