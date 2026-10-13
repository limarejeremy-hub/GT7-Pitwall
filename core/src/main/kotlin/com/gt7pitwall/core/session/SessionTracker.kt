package com.gt7pitwall.core.session

import com.gt7pitwall.core.telemetry.TelemetryPacket

/** Un tour bouclé, tel qu'observé dans la télémétrie. */
data class LapRecord(
    val lapNumber: Int,
    /** Temps officiel transmis par GT7 ("dernier tour"), null s'il n'a pas pu être lu. */
    val timeMs: Int?,
    /**
     * Carburant consommé pendant le tour, null si le tour n'a pas été observé en entier
     * (application lancée en cours de tour) ou s'il y a eu un ravitaillement.
     */
    val fuelUsed: Float?,
    val refuelled: Boolean,
)

sealed interface SessionEvent {
    /** Premier paquet reçu. */
    data object FirstData : SessionEvent

    /** Nouvelle session détectée (changement de voiture ou compteur de tours revenu en arrière). */
    data object SessionReset : SessionEvent

    data class LapCompleted(val lap: LapRecord, val previousBestMs: Int?) : SessionEvent

    /** Le niveau de carburant a augmenté pendant le tour en cours. Émis une fois par tour. */
    data object RefuelDetected : SessionEvent

    data class FinalLapStarted(val lap: Int) : SessionEvent
}

/** Photographie immuable de la session, partagée avec l'interface et l'ingénieur. */
data class SessionSnapshot(
    val packet: TelemetryPacket?,
    val laps: List<LapRecord>,
    /** Carburant au passage de la ligne au début du tour en cours (null si non observé). */
    val fuelAtLapStart: Float?,
    val refuelledThisLap: Boolean,
) {
    val bestRecordedLapMs: Int? get() = laps.mapNotNull { it.timeMs }.filter { it > 0 }.minOrNull()

    /** Consommations mesurées sur des tours complets sans ravitaillement. */
    val fuelSamples: List<Float> get() = laps.mapNotNull { it.fuelUsed }.filter { it > 0f }

    companion object {
        val EMPTY = SessionSnapshot(null, emptyList(), null, false)
    }
}

/**
 * Suit les tours et la consommation à partir des paquets successifs.
 * Logique pure : aucune dépendance Android ou réseau.
 */
class SessionTracker(
    /** Nombre de paquets (~60/s) à attendre la mise à jour du "dernier tour" après le passage de ligne. */
    private val lapTimeWaitPackets: Int = 180,
    /** Hausse minimale du niveau pour considérer un ravitaillement. */
    private val refuelThreshold: Float = 0.05f,
) {
    private var previous: TelemetryPacket? = null
    private val laps = mutableListOf<LapRecord>()
    private var fuelAtLapStart: Float? = null
    private var refuelledThisLap = false
    private var pending: PendingLap? = null

    private data class PendingLap(
        val lapNumber: Int,
        val lastLapMsBefore: Int,
        val fuelUsed: Float?,
        val refuelled: Boolean,
        var waited: Int = 0,
    )

    var snapshot: SessionSnapshot = SessionSnapshot.EMPTY
        private set

    fun update(p: TelemetryPacket): List<SessionEvent> {
        val events = mutableListOf<SessionEvent>()
        val prev = previous
        var resetNow = false

        if (prev == null) {
            events += SessionEvent.FirstData
        } else if (p.carCode != prev.carCode || p.currentLap < prev.currentLap) {
            reset()
            resetNow = true
            events += SessionEvent.SessionReset
        }

        // Après une remise à zéro, le paquet précédent appartient à l'ancienne session : on ne compare pas.
        val before = if (resetNow) null else prev
        if (before != null) {
            if (p.fuelLevel - before.fuelLevel > refuelThreshold) {
                if (!refuelledThisLap) events += SessionEvent.RefuelDetected
                refuelledThisLap = true
            }

            if (p.currentLap > before.currentLap) {
                val completed = before.currentLap
                if (completed >= 1) {
                    val start = fuelAtLapStart
                    val used = if (start != null && !refuelledThisLap) start - p.fuelLevel else null
                    pending?.let { finishPending(it, null, events) }
                    pending = PendingLap(
                        lapNumber = completed,
                        lastLapMsBefore = before.lastLapMs,
                        fuelUsed = used?.takeIf { it >= 0f },
                        refuelled = refuelledThisLap,
                    )
                }
                fuelAtLapStart = p.fuelLevel
                refuelledThisLap = false
                if (p.totalLaps > 0 && p.currentLap == p.totalLaps) {
                    events += SessionEvent.FinalLapStarted(p.currentLap)
                }
            }
        }

        pending?.let { pl ->
            if (p.lastLapMs > 0 && p.lastLapMs != pl.lastLapMsBefore) {
                finishPending(pl, p.lastLapMs, events)
            } else if (++pl.waited > lapTimeWaitPackets) {
                finishPending(pl, null, events)
            }
        }

        previous = p
        snapshot = SessionSnapshot(p, laps.toList(), fuelAtLapStart, refuelledThisLap)
        return events
    }

    private fun finishPending(pl: PendingLap, timeMs: Int?, events: MutableList<SessionEvent>) {
        val previousBest = laps.mapNotNull { it.timeMs }.filter { it > 0 }.minOrNull()
        val record = LapRecord(pl.lapNumber, timeMs, pl.fuelUsed, pl.refuelled)
        laps += record
        pending = null
        events += SessionEvent.LapCompleted(record, previousBest)
    }

    private fun reset() {
        laps.clear()
        fuelAtLapStart = null
        refuelledThisLap = false
        pending = null
    }
}
