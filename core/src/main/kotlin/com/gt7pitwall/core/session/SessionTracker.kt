package com.gt7pitwall.core.session

import com.gt7pitwall.core.analysis.SectorTrace
import com.gt7pitwall.core.analysis.TrackOutline
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
    /** Secteurs Pitwall en ms (voir [SectorTrace]), null si le tour n'a pas été suivi en entier. */
    val sectorsMs: List<Int>? = null,
    /** Vitesse maximale du tour (km/h), null si le tour n'a pas été suivi en entier. */
    val maxSpeedKmh: Float? = null,
    /** Part du tour avec antipatinage (TCS) actif, 0..1. */
    val tcsShare: Float? = null,
    /** Température de surface moyenne des pneus sur le tour (°C). */
    val avgTyreTemp: Float? = null,
    /** Distance du tour mesurée par les positions (m), pour reconnaître le circuit. */
    val distanceM: Float? = null,
    /** Blocages de roue au freinage. */
    val lockups: Int? = null,
    /** Patinages à l'accélération. */
    val wheelspins: Int? = null,
    /** Température moyenne de chaque pneu sur le tour (°C), null si le tour n'a pas été suivi en entier. */
    val wheelTemps: com.gt7pitwall.core.telemetry.Wheels? = null,
    /** Blocages et patinages à l'avant et à l'arrière. */
    val frontLockups: Int? = null,
    val rearLockups: Int? = null,
    val frontWheelspins: Int? = null,
    val rearWheelspins: Int? = null,
    /** Carburant à bord au passage de la ligne qui termine le tour (mesuré). */
    val fuelAfter: Float? = null,
    /**
     * Le tour a commencé dans la voie des stands (sortie après un arrêt à cheval sur la ligne) : sa consommation est
     * mesurée mais atypique, elle est exclue des moyennes.
     */
    val pitLane: Boolean = false,
    /** Format C : part du tour sur un vibreur et sorties de piste mesurées, null sans donnée de surface. */
    val kerbShare: Float? = null,
    val offTrackEvents: Int? = null,
)

sealed interface SessionEvent {
    /** Premier paquet reçu. */
    data object FirstData : SessionEvent

    /** Nouvelle session détectée (changement de voiture ou compteur de tours revenu en arrière). */
    data object SessionReset : SessionEvent

    data class LapCompleted(val lap: LapRecord, val previousBestMs: Int?) : SessionEvent

    /** Le niveau de carburant a augmenté pendant le tour en cours. Émis une fois par tour. */
    data object RefuelDetected : SessionEvent

    /** Émis juste après le [LapCompleted] du tour précédent (le chrono est annoncé d'abord). */
    data class FinalLapStarted(val lap: Int) : SessionEvent

    /** Dernier tour bouclé : GT7 passe alors le compteur à "total + 1". Émis après son [LapCompleted]. */
    data class RaceFinished(val totalLaps: Int) : SessionEvent

    /** Place sur la grille transmise par GT7 avant le départ (une fois par session). */
    data class GridPosition(val position: Int, val cars: Int) : SessionEvent

    /**
     * Fin d'un passage où la voiture a ravitaillé ou s'est immobilisée (un événement par passage).
     * Seul [PitAssessment.REFUEL] prouve un arrêt aux stands ; un arrêt sans essence doit être confirmé par le pilote.
     */
    data class PitVisitEnded(val visit: PitDetector.Visit) : SessionEvent
}

/** Photographie immuable de la session, partagée avec l'interface et l'ingénieur. */
data class SessionSnapshot(
    val packet: TelemetryPacket?,
    val laps: List<LapRecord>,
    /** Carburant au passage de la ligne au début du tour en cours (null si non observé). */
    val fuelAtLapStart: Float?,
    val refuelledThisLap: Boolean,
    /** Tracé du meilleur tour suivi en entier (carte du circuit). */
    val trackOutline: TrackOutline? = null,
    /** Temps de course écoulé depuis le départ (ms, d'après les paquets à 60/s), null avant le départ. */
    val raceElapsedMs: Long? = null,
) {
    val bestRecordedLapMs: Int? get() = laps.mapNotNull { it.timeMs }.filter { it > 0 }.minOrNull()

    /** Consommations mesurées sur des tours complets sans ravitaillement. */
    val fuelSamples: List<Float> get() = laps.filter { !it.pitLane }.mapNotNull { it.fuelUsed }.filter { it > ZERO_FUEL }

    /** Tours complets mesurés où le niveau n'a pas baissé (épreuve sans consommation). */
    val zeroFuelLaps: Int get() = laps.count { it.fuelUsed != null && it.fuelUsed <= ZERO_FUEL }

    companion object {
        val EMPTY = SessionSnapshot(null, emptyList(), null, false)
        const val ZERO_FUEL = 0.01f
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
    private var lapStartedInPit = false
    private var pending: PendingLap? = null
    private var gridAnnounced = false
    private var trace = SectorTrace()
    private var outline: TrackOutline? = null
    private var outlineLapMs: Int? = null
    private var raceTicks: Long? = null
    private val pitDetector = PitDetector(refuelThreshold)
    private val fuelRise = FuelRise()

    /** Un passage aux stands (ravitaillement ou immobilisation) est en cours. */
    val pitVisitOpen: Boolean get() = pitDetector.inVisit

    private data class PendingLap(
        val lapNumber: Int,
        val lastLapMsBefore: Int,
        val fuelUsed: Float?,
        val refuelled: Boolean,
        val finalLapStarts: Int?,
        val finishesRace: Int?,
        val trace: SectorTrace,
        val fuelAfter: Float? = null,
        val pitLane: Boolean = false,
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
            // Hausse cumulée : un ravitaillement lent peut monter de moins de [refuelThreshold] par paquet. Seule une hausse
            // en cours compte : après le plein, le niveau reste stable au stand, et le cumul ne doit pas marquer le tour
            // suivant comme ravitaillé (course réelle du 21/10 : plein au tour 6, tour 7 compté « ravitaillé » à tort).
            val delta = p.fuelLevel - before.fuelLevel
            if (fuelRise.update(delta) > refuelThreshold && delta > FuelRise.NOISE) {
                if (!refuelledThisLap) events += SessionEvent.RefuelDetected
                refuelledThisLap = true
            }

            // Chrono de course : démarre au passage du tour 0 au tour 1 (ou au premier tour observé).
            val dId = p.packetId - before.packetId
            raceTicks?.let { if (dId in 1..30 && !p.isPaused) raceTicks = it + dId }
            if (raceTicks == null && p.currentLap >= 1 && p.currentLap > before.currentLap) raceTicks = 0

            // Passages aux stands : un événement par passage, jamais sur un simple arrêt à 0 km/h.
            val visit = pitDetector.update(p, before, raceStarted = (raceTicks ?: 0) > RACE_START_GRACE_TICKS, outline = outline)
            visit?.let { events += SessionEvent.PitVisitEnded(it) }

            if (p.currentLap > before.currentLap) {
                val completed = before.currentLap
                if (completed >= 1) {
                    val start = fuelAtLapStart
                    val used = if (start != null && !refuelledThisLap) start - p.fuelLevel else null
                    pending?.let { finishPending(it, null, events) }
                    // Le tracé du tour terminé est conservé jusqu'à l'arrivée du chrono officiel.
                    trace.add(p)
                    pending = PendingLap(
                        trace = trace,
                        lapNumber = completed,
                        lastLapMsBefore = before.lastLapMs,
                        fuelUsed = used?.takeIf { it >= 0f },
                        refuelled = refuelledThisLap,
                        fuelAfter = p.fuelLevel,
                        pitLane = lapStartedInPit,
                        finalLapStarts = p.currentLap.takeIf { p.totalLaps > 0 && it == p.totalLaps },
                        finishesRace = p.totalLaps.takeIf { it > 0 && completed == it },
                    )
                } else if (p.totalLaps > 0 && p.currentLap == p.totalLaps) {
                    events += SessionEvent.FinalLapStarted(p.currentLap)
                }
                fuelAtLapStart = p.fuelLevel
                refuelledThisLap = false
                lapStartedInPit = pitDetector.inVisit
                trace = SectorTrace().also { it.startLap(p) }
            }
        }

        // Seulement avant le départ : si 0x84 évolue en course (non vérifié), rien n'est annoncé (voir PositionProbe).
        if (!gridAnnounced && p.currentLap == 0 && p.preRacePosition > 0 && p.preRaceCarCount > 0) {
            gridAnnounced = true
            events += SessionEvent.GridPosition(p.preRacePosition, p.preRaceCarCount)
        }

        pending?.let { pl ->
            if (p.lastLapMs > 0 && p.lastLapMs != pl.lastLapMsBefore) {
                finishPending(pl, p.lastLapMs, events)
            } else if (++pl.waited > lapTimeWaitPackets) {
                finishPending(pl, null, events)
            }
        }

        if (before != null && p.currentLap == before.currentLap) trace.add(p)
        previous = p
        snapshot = SessionSnapshot(
            p,
            laps.toList(),
            fuelAtLapStart,
            refuelledThisLap,
            outline,
            raceTicks?.let { it * 1000L / 60L },
        )
        return events
    }

    private fun finishPending(pl: PendingLap, timeMs: Int?, events: MutableList<SessionEvent>) {
        val previousBest = laps.mapNotNull { it.timeMs }.filter { it > 0 }.minOrNull()
        val sectors = timeMs?.let { pl.trace.finish(it) }
        val complete = pl.trace.isComplete
        val record = LapRecord(
            pl.lapNumber,
            timeMs,
            pl.fuelUsed,
            pl.refuelled,
            sectors,
            maxSpeedKmh = if (complete) pl.trace.maxSpeedKmh else null,
            tcsShare = if (complete) pl.trace.tcsShare else null,
            avgTyreTemp = if (complete) pl.trace.avgTyreTemp else null,
            lockups = if (complete) pl.trace.lockups else null,
            wheelspins = if (complete) pl.trace.wheelspins else null,
            wheelTemps = if (complete) pl.trace.avgWheelTemps else null,
            frontLockups = if (complete) pl.trace.lockupsByAxle[0] else null,
            rearLockups = if (complete) pl.trace.lockupsByAxle[1] else null,
            frontWheelspins = if (complete) pl.trace.wheelspinsByAxle[0] else null,
            rearWheelspins = if (complete) pl.trace.wheelspinsByAxle[1] else null,
            distanceM = if (complete) pl.trace.lapDistance else null,
            fuelAfter = pl.fuelAfter,
            pitLane = pl.pitLane,
            kerbShare = if (complete) pl.trace.kerbShare else null,
            offTrackEvents = if (complete) pl.trace.offTracks else null,
        )
        // La carte suit le meilleur tour complet (ou le premier disponible).
        if (complete && timeMs != null && (outlineLapMs == null || timeMs < outlineLapMs!!)) {
            pl.trace.outline(pl.lapNumber)?.let {
                outline = it
                outlineLapMs = timeMs
            }
        }
        laps += record
        pending = null
        events += SessionEvent.LapCompleted(record, previousBest)
        pl.finalLapStarts?.let { events += SessionEvent.FinalLapStarted(it) }
        pl.finishesRace?.let { events += SessionEvent.RaceFinished(it) }
    }

    private fun reset() {
        laps.clear()
        fuelAtLapStart = null
        refuelledThisLap = false
        lapStartedInPit = false
        pending = null
        gridAnnounced = false
        trace = SectorTrace()
        outline = null
        outlineLapMs = null
        raceTicks = null
        pitDetector.reset()
        fuelRise.reset()
    }

    private companion object {
        /** Pas de passage aux stands dans les 10 premières secondes (départ arrêté, grille). */
        const val RACE_START_GRACE_TICKS = 600L
    }
}
