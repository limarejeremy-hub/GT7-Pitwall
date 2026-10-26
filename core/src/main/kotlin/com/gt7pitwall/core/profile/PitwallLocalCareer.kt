package com.gt7pitwall.core.profile

import com.gt7pitwall.core.race.SessionKind
import com.gt7pitwall.core.race.SessionMode
import com.gt7pitwall.core.race.SessionOutcome
import com.gt7pitwall.core.race.SessionRecord

/**
 * First-party GT7 career facts exclusively from sessions actually captured on
 * this Android device. Nothing here claims to be the player's GLOBAL PSN career.
 * Every value can be traced back to an archived SessionRecord.
 */
data class PitwallLocalCareer(
    val sessionsSaved: Int = 0,
    val completedSessions: Int = 0,
    val timedLaps: Int = 0,
    val recordedCars: Int = 0,
    val identifiedTracks: Int = 0,
    /** These counters count ONLY verified online Sport race archives. */
    val verifiedSportRaces: Int = 0,
    val sportWins: Int = 0,
    val sportPodiums: Int = 0,
    val sportTop5: Int = 0,
    val sportFinishPositionsMeasured: Int = 0,
    val sportStartingPositionsMeasured: Int = 0,
    val netPositionsGained: Int? = null,
    val bestTimedLapMs: Int? = null,
    val recentRaceDateMs: Long? = null,
    val sessionsWithoutLapTime: Int = 0,
) {
    companion object {
        val EMPTY = PitwallLocalCareer()

        fun fromSessions(input: Collection<SessionRecord>): PitwallLocalCareer {
            // Sessions get rewritten after every lap. An archive ID represents
            // one session, not another new race each time it is saved.
            val all = input.groupBy { it.id }
                .values.map { group -> group.maxByOrNull { it.updatedAtMs }!! }
                .filter { it.laps.isNotEmpty() }
            val verifiedSport = all.filter { s ->
                s.context.profile.id.mode == SessionMode.SPORT_OFFICIAL &&
                    s.context.profile.id.kind == SessionKind.RACE &&
                    s.outcome == SessionOutcome.FINISHED
            }
            val withFinish = verifiedSport.filter { (it.finishPosition ?: 0) > 0 }
            val validGridAndFinish = withFinish.filter { (it.gridPosition ?: 0) > 0 }
            val timed = all.flatMap { it.laps }.mapNotNull { it.timeMs?.takeIf { ms -> ms > 0 } }
            return PitwallLocalCareer(
                sessionsSaved = all.size,
                completedSessions = all.count { it.outcome == SessionOutcome.FINISHED },
                timedLaps = timed.size,
                recordedCars = all.mapNotNull { it.context.carCode?.takeIf { id -> id > 0 } }
                    .distinct().size,
                identifiedTracks = all.mapNotNull { it.context.rules.trackId?.takeIf { id -> id > 0 } }
                    .distinct().size,
                verifiedSportRaces = verifiedSport.size,
                sportWins = withFinish.count { it.finishPosition == 1 },
                sportPodiums = withFinish.count { it.finishPosition!! in 1..3 },
                sportTop5 = withFinish.count { it.finishPosition!! in 1..5 },
                sportFinishPositionsMeasured = withFinish.size,
                sportStartingPositionsMeasured = verifiedSport.count { (it.gridPosition ?: 0) > 0 },
                netPositionsGained = validGridAndFinish
                    .takeIf { it.isNotEmpty() }?.sumOf { it.gridPosition!! - it.finishPosition!! },
                bestTimedLapMs = timed.minOrNull(),
                recentRaceDateMs = all.maxOfOrNull { it.startedAtMs },
                sessionsWithoutLapTime = all.count { s -> s.laps.none { (it.timeMs ?: 0) > 0 } },
            )
        }
    }
}
