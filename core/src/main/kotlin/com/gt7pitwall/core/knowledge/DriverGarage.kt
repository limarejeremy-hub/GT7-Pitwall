package com.gt7pitwall.core.knowledge

import com.gt7pitwall.core.race.EventProfile
import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.race.SessionOutcome
import com.gt7pitwall.core.race.SessionRecord
import com.gt7pitwall.core.sport.Edition
import com.gt7pitwall.core.sport.SportNames

/**
 * GT7 UDP already sends the current car code. GridStats isn't necessary to
 * resolve a driver-selected car in Pitwall. Archived records are evidence of a
 * PREVIOUS choice, never proof of the car currently in GT7's lobby.
 */
enum class CarEvidence(val label: String) {
    LIVE("Confirmée par la PS5"),
    LIVE_UNNAMED("Code GT7 reçu, catalogue à actualiser"),
    /** Code reçu plus tôt, PS5 muette depuis (menus, garage) : jamais présenté comme une confirmation en direct. */
    LAST_SEEN("Dernière voiture détectée (PS5 sans données en ce moment)"),
    THIS_WEEK("Dernière voiture utilisée cette semaine"),
    FIXED_EVENT("Voiture annoncée pour l'épreuve"),
    EARLIER("Déjà utilisée sur ce circuit"),
    UNKNOWN("Voiture à identifier"),
}

data class GarageCar(
    val code: Int?,
    val name: String?,
    val evidence: CarEvidence,
    val raceCount: Int = 0,
    val completedLaps: Int = 0,
    val bestCleanLapMs: Int? = null,
    val bestFinishPosition: Int? = null,
    val lastDrivenAtMs: Long? = null,
) {
    val liveConfirmed: Boolean get() = evidence == CarEvidence.LIVE || evidence == CarEvidence.LIVE_UNNAMED
}

/**
 * Pure, offline resolver. No PlayStation account credentials, third-party API
 * tokens or personal data leave the Fold. The archived data belongs to the driver.
 */
object DriverGarage {
    fun resolve(
        telemetryCarCode: Int?,
        telemetryConnected: Boolean,
        profile: EventProfile,
        trackId: Int?,
        edition: Edition?,
        sessions: Collection<SessionRecord>,
        db: Gt7Database,
    ): GarageCar {
        val matching = sessions.filter {
            val p = it.context.profile
            p.id.series == profile.id.series && p.id.kind == profile.id.kind && p.id.mode == profile.id.mode &&
                it.context.carCode?.let { code -> code > 0 && db.car(code) != null } == true &&
                (trackId == null || it.context.rules.trackId == trackId)
        }
        val currentEvent = matching.filter { s ->
            // Never mistake a session from a previous weekly rotation for this one.
            if (profile.id.series == Series.OTHER) false
            else edition != null && s.context.profile.eventDate == edition.validFrom.toString() &&
                (trackId != null || s.context.rules.trackId == edition.trackId)
        }
        val nowCode = telemetryCarCode?.takeIf { it > 0 && telemetryConnected }
        // Dernière voiture détectée par GT7 : plus récente que n'importe quelle archive (issue #40).
        val lastSeenCode = telemetryCarCode?.takeIf { it > 0 && !telemetryConnected }
        val thisWeekCode = if (lastSeenCode == null) currentEvent.maxByOrNull { it.startedAtMs }?.context?.carCode else null
        val fixed = if (nowCode == null && lastSeenCode == null && thisWeekCode == null) fixedOneMakeCar(edition, db) else null
        val earlierCode = if (nowCode == null && lastSeenCode == null && thisWeekCode == null && fixed == null &&
            trackId != null) matching.maxByOrNull { it.startedAtMs }?.context?.carCode else null

        val code = nowCode ?: lastSeenCode ?: thisWeekCode ?: fixed?.id ?: earlierCode
        val evidence = when {
            nowCode != null -> if (db.car(nowCode) == null) CarEvidence.LIVE_UNNAMED else CarEvidence.LIVE
            lastSeenCode != null -> CarEvidence.LAST_SEEN
            thisWeekCode != null -> CarEvidence.THIS_WEEK
            fixed != null -> CarEvidence.FIXED_EVENT
            earlierCode != null -> CarEvidence.EARLIER
            else -> CarEvidence.UNKNOWN
        }
        val compatible = if (code == null) emptyList() else matching.filter {
            it.context.carCode == code && (profile.id.series == Series.OTHER ||
                edition == null || it.context.profile.eventDate == edition.validFrom.toString())
        }
        val laps = compatible.flatMap { it.laps }
            .filter { it.timeMs != null && it.timeMs > 0 && !it.pitLane && !it.refuelled }
        return GarageCar(
            code = code,
            name = code?.let { db.car(it)?.fullName } ?: fixed?.fullName,
            evidence = evidence,
            raceCount = compatible.size,
            completedLaps = laps.size,
            bestCleanLapMs = laps.minOfOrNull { it.timeMs!! },
            bestFinishPosition = compatible.filter { it.outcome == SessionOutcome.FINISHED }
                .mapNotNull { it.finishPosition?.takeIf { pos -> pos > 0 } }.minOrNull(),
            lastDrivenAtMs = compatible.maxOfOrNull { it.startedAtMs },
        )
    }

    /**
     * Only a specific, uniquely matching car from an explicitly named one-make
     * event is safe. "Gr.3" / "Gr.4" or contradictory public sources are NOT cars.
     */
    private fun fixedOneMakeCar(edition: Edition?, db: Gt7Database): Car? {
        val raw = edition?.cars?.takeIf { it.known }?.value ?: return null
        if (!SportNames.meaningfulCar(raw)) return null
        val key = SportNames.carKey(raw)
        if (key.matches(Regex("gr[1-4]"))) return null
        return db.cars.values.filter { SportNames.carKey(it.fullName) == key }.singleOrNull()
    }
}
