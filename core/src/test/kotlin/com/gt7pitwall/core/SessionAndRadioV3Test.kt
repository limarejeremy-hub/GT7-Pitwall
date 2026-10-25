package com.gt7pitwall.core

import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.engineer.Priority
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.radio.RadioPacer
import com.gt7pitwall.core.session.SessionEvent
import com.gt7pitwall.core.session.SessionTracker
import com.gt7pitwall.core.telemetry.TelemetryParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Race Engineer V3, phase 1 : cycle de session fiable et radio qui parle au bon moment. */
class SessionAndRadioV3Test {

    // ------------------------------------------------------------------ Sessions

    @Test
    fun aReplayOfAnArchivedRaceIsNeitherArchivedNorAnnounced() {
        val storage = FakeStorage()
        val c = RaceController(storage = storage)
        val race = RaceSim(c, totalLaps = 4, perLap = 5f)
        race.start()
        race.driveLaps(4)
        race.driveLaps(1)
        assertEquals(1, storage.sessions.size)
        // Rediffusion : mêmes chronos à la milliseconde, même voiture.
        val replay = RaceSim(c, totalLaps = 4, perLap = 5f).also { it.salt = race.salt }
        replay.start()
        replay.driveLaps(3)
        val after = replay.said.size
        replay.driveLaps(2)
        assertTrue(c.replay)
        assertEquals(1, storage.sessions.size, storage.sessions.map { it.id }.toString())
        assertTrue(replay.said.drop(after).isEmpty(), replay.texts.drop(after).toString())
        assertTrue(c.journal.recent().any { "Rediffusion" in it.text })

        // Une vraie nouvelle course avec la même voiture reste une course.
        val next = RaceSim(c, totalLaps = 4, perLap = 5f)
        next.start()
        next.driveLaps(3)
        assertFalse(c.replay)
        assertEquals(2, storage.sessions.size)
    }

    @Test
    fun aTelemetryBlackoutNeverProducesAFalseConsumption() {
        val c = RaceController()
        val s = RaceSim(c, totalLaps = 15, perLap = 5f)
        s.start()
        s.driveLaps(3)
        s.blackout(80f) // plus de deux tours sans paquet
        s.driveLaps(2)
        val laps = c.snapshot.laps
        // Le tour qui couvre la coupure n'a pas de consommation : jamais 10 L « en un tour ».
        assertTrue(laps.mapNotNull { it.fuelUsed }.all { it in 4.5f..5.5f }, laps.map { it.lapNumber to it.fuelUsed }.toString())
        assertEquals(5f, c.fuel!!.perLap!!, 0.2f)
    }

    @Test
    fun reconnectingMidSessionStartsCleanWithoutOldData() {
        val storage = FakeStorage()
        val s = RaceSim(RaceController(storage = storage), totalLaps = 15, perLap = 5f)
        s.start()
        s.driveLaps(3)
        s.driveTo(2f)
        // L'application redémarre en plein tour : nouveau contrôleur, mêmes archives.
        val c2 = RaceController(storage = storage)
        s.controller = c2
        s.driveLaps(3)
        val first = c2.snapshot.laps.first()
        assertNull(first.fuelUsed, "tour pris en cours de route : consommation inconnue")
        assertTrue(c2.ledger.stopList.isEmpty())
        assertEquals(5f, c2.fuel!!.perLap!!, 0.2f)
    }

    @Test
    fun aDifferentRaceFormatIsANewSession() {
        val t = SessionTracker()
        fun p(lap: Int, total: Int, id: Int) = TelemetryParser.parseDecrypted(TestPackets.plain(lap = lap, totalLaps = total, packetId = id))!!
        t.update(p(3, 10, 1))
        assertTrue(t.update(p(3, 10, 2)).none { it is SessionEvent.SessionReset })
        assertTrue(t.update(p(4, 15, 3)).any { it is SessionEvent.SessionReset }, "10 tours puis 15 : autre épreuve")
    }

    // ------------------------------------------------------------------ Radio

    private val info = EngineerMessage("Températures stabilisées.", Priority.INFO, MessageCategory.LAP)
    private val coach = EngineerMessage("Virage 3 : remets les gaz plus tôt.", Priority.NORMAL, MessageCategory.RADIO, validUntilLap = 5)

    @Test
    fun aMessageDuringHeavyBrakingWaitsForAStraightEvenBeyondSixSeconds() {
        val pacer = RadioPacer()
        assertEquals(emptyList(), pacer.pace(listOf(info), busy = true, nowMs = 0))
        // Occupé 9 s d'affilée (enchaînement de virages) : rien n'est dit.
        for (t in 1_000L..9_000L step 1_000) assertEquals(emptyList(), pacer.pace(emptyList(), busy = true, nowMs = t))
        // Un simple relâché entre deux virages ne suffit pas.
        assertEquals(emptyList(), pacer.pace(emptyList(), busy = false, nowMs = 9_500))
        assertEquals(emptyList(), pacer.pace(emptyList(), busy = true, nowMs = 10_000))
        assertEquals(emptyList(), pacer.pace(emptyList(), busy = false, nowMs = 10_200))
        assertEquals(listOf(info), pacer.pace(emptyList(), busy = false, nowMs = 11_500), "ligne droite")
        // Une alerte n'attend jamais.
        val alert = EngineerMessage("Carburant critique.", Priority.CRITICAL, MessageCategory.ALERT)
        assertEquals(listOf(alert), pacer.pace(listOf(alert), busy = true, nowMs = 12_000))
    }

    @Test
    fun theBusyDetectorSeesBrakingRotationAndInstability() {
        val pacer = RadioPacer()
        fun p(brake: Int = 0, yaw: Float = 0f, speed: Float = 60f, id: Int) =
            TelemetryParser.parseDecrypted(TestPackets.plain(speedMps = speed, packetId = id, brake = brake, yawRate = yaw))!!
        assertFalse(pacer.busy(p(id = 1)))
        assertTrue(pacer.busy(p(brake = 200, id = 2)), "gros freinage")
        assertTrue(pacer.busy(p(yaw = 0.8f, id = 3)), "forte rotation")
        assertFalse(pacer.busy(p(id = 4)))
        assertTrue(pacer.busy(p(speed = 50f, id = 5)), "décélération de 10 m/s en un paquet")
        assertFalse(pacer.busy(p(speed = 50f, id = 6)))
    }

    @Test
    fun contradictoryAndStaleAnnouncementsAreDropped() {
        val pacer = RadioPacer()
        val stopAt7 = EngineerMessage("Arrêt fin du tour 7.", Priority.NORMAL, MessageCategory.FUEL, topic = "plan")
        val noStop = EngineerMessage("Plus d'arrêt nécessaire.", Priority.NORMAL, MessageCategory.FUEL, topic = "plan")
        // Deux avis dans la même salve : seul le dernier compte.
        pacer.pace(listOf(stopAt7, noStop), busy = true, nowMs = 0)
        assertEquals(listOf(noStop), pacer.pending)
        // Un avis plus récent remplace celui qui attendait.
        pacer.pace(listOf(stopAt7), busy = true, nowMs = 1_000)
        assertEquals(listOf(stopAt7), pacer.pending)
        // Consigne valable jusqu'au tour 5 : annulée au tour 6.
        pacer.pace(listOf(coach), busy = true, nowMs = 2_000, lap = 5)
        pacer.pace(emptyList(), busy = true, nowMs = 3_000, lap = 6)
        assertEquals(listOf(stopAt7), pacer.pending)
        // Trop ancienne : annulée plutôt que dite hors de propos.
        pacer.pace(emptyList(), busy = true, nowMs = 60_000, lap = 6)
        assertTrue(pacer.pending.isEmpty())
    }
}
