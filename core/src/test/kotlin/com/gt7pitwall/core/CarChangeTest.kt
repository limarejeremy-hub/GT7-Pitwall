package com.gt7pitwall.core

import com.gt7pitwall.core.knowledge.Car
import com.gt7pitwall.core.knowledge.CarEvidence
import com.gt7pitwall.core.knowledge.DriverGarage
import com.gt7pitwall.core.knowledge.Gt7Database
import com.gt7pitwall.core.race.EventProfile
import com.gt7pitwall.core.race.ProfileId
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.race.SessionKind
import com.gt7pitwall.core.telemetry.CarIdentity
import com.gt7pitwall.core.telemetry.GT7Crypto
import com.gt7pitwall.core.telemetry.TelemetryPacket
import com.gt7pitwall.core.telemetry.TelemetryParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #40 (essai PS5 du 28/10) : voiture changée dans GT7, Pitwall figé sur la précédente. Le code 0x124 des menus
 * (tour négatif) était ignoré alors que la connexion vivante faisait afficher l'ancienne voiture « confirmée ».
 */
class CarChangeTest {
    private val brz = 3474
    private val other = 2000
    private val db = Gt7Database(mapOf(brz to Car(brz, "BRZ GT300 '21", "Subaru"), other to Car(other, "M6 GT3 '16", "BMW")), emptyList())

    private var id = 0
    private var now = 1_000_000L

    private fun pkt(
        car: Int, lap: Int = 1, speedMps: Float = 30f, flags: Int = TelemetryPacket.FLAG_ON_TRACK,
        size: Int = GT7Crypto.PACKET_SIZE_A, posX: Float? = null,
    ): TelemetryPacket {
        id++
        val plain = TestPackets.plain(lap = lap, totalLaps = 5, speedMps = speedMps, carCode = car, packetId = id, flags = flags, size = size,
            posX = posX ?: if (speedMps > 1f) id * speedMps / 60f else 0f)
        return TelemetryParser.parseDecrypted(plain)!!
    }

    /** [n] paquets à 60 par seconde. */
    private fun feed(c: CarIdentity, n: Int, make: () -> TelemetryPacket): List<CarIdentity.Transition> =
        (1..n).mapNotNull { now += 16; c.onPacket(make(), now) }

    @Test fun brzThenAnotherCarThenBrzAgain() {
        val c = CarIdentity()
        assertEquals(1, feed(c, 40) { pkt(brz) }.size)
        assertEquals(brz, c.current)
        val t = feed(c, 40) { pkt(other, lap = -1, speedMps = 0f) }.single()
        assertEquals(brz, t.from)
        assertEquals(other, t.to)
        assertEquals("menu", t.state)
        feed(c, 40) { pkt(brz, lap = 0, speedMps = 0f) }
        assertEquals(brz, c.current)
        assertEquals(listOf(null, brz, other), c.transitions.map { it.from })
        assertEquals("avant le départ", c.transitions.last().state)
    }

    @Test fun sameCarZerosLoadingAndIsolatedValuesChangeNothing() {
        val c = CarIdentity()
        feed(c, 40) { pkt(brz) }
        feed(c, 40) { pkt(brz) }
        feed(c, 20) { pkt(0) }
        feed(c, 5) { pkt(other) }
        feed(c, 10) { pkt(brz) }
        feed(c, 60) { pkt(other, flags = TelemetryPacket.FLAG_LOADING) }
        assertEquals(brz, c.current)
        assertEquals(1, c.transitions.size)
    }

    @Test fun frozenSnapshotIsNeverLive() {
        val c = CarIdentity()
        feed(c, 40) { pkt(brz) }
        assertTrue(c.live(now))
        // Même instantané renvoyé en boucle (menus après l'arrivée) : la voiture n'est plus « en direct ».
        feed(c, 200) { pkt(brz, posX = 12f) }
        assertFalse(c.live(now))
        assertEquals(brz, c.current)
    }

    @Test fun silenceTurnsLiveIntoLastSeen() {
        val c = CarIdentity()
        feed(c, 40) { pkt(brz) }
        assertTrue(c.live(now))
        assertFalse(c.live(now + CarIdentity.LIVE_MS + 1))
        assertEquals(brz, c.current)
    }

    @Test fun formatsAAndCBothCarryTheCarCode() {
        val c = CarIdentity()
        feed(c, 40) { pkt(brz, size = GT7Crypto.PACKET_SIZE_A) }
        feed(c, 40) { pkt(other, size = com.gt7pitwall.core.telemetry.PacketFormat.C.size) }
        assertEquals(listOf("A", "C"), c.transitions.map { it.format.label })
    }

    @Test fun garageShowsLastSeenInsteadOfAConfirmationOrAnArchive() {
        val profile = EventProfile(id = ProfileId(Series.OTHER, SessionKind.RACE))
        val live = DriverGarage.resolve(brz, true, profile, null, null, emptyList(), db)
        assertEquals(CarEvidence.LIVE, live.evidence)
        val last = DriverGarage.resolve(brz, false, profile, null, null, emptyList(), db)
        assertEquals(CarEvidence.LAST_SEEN, last.evidence)
        assertEquals(brz, last.code)
        assertFalse(last.liveConfirmed)
        assertEquals(CarEvidence.UNKNOWN, DriverGarage.resolve(null, false, profile, null, null, emptyList(), db).evidence)
    }

    @Test fun carChangedInTheMenusIsShownAndTheOldPlanIsNotPresentedAsTheNewCars() {
        val race = RaceController(db, clock = { now })
        repeat(120) { now += 16; race.onPacket(pkt(brz, lap = 1)) }
        assertEquals("Subaru BRZ GT300 '21", race.carName)
        assertFalse(race.carChangePending)
        // Garage puis menus : GT7 envoie le nouveau code avec un tour négatif, ignoré par le suivi de session.
        repeat(60) { now += 16; race.onPacket(pkt(other, lap = -1, speedMps = 0f)) }
        assertEquals(brz, race.snapshot.packet?.carCode)
        assertEquals(other, race.displayCarCode)
        assertEquals("BMW M6 GT3 '16", race.carName)
        assertTrue(race.carChangePending)
        val brief = race.briefing()
        assertTrue(brief.any { "Nouvelle voiture détectée (BMW M6 GT3 '16)" in it }, brief.toString())
        assertFalse(brief.any { "course" in it && "comparable" in it }, brief.toString())
        assertTrue(race.victoryBriefing().any { it == "Voiture : BMW M6 GT3 '16." })
        // Retour en piste avec la nouvelle voiture : nouvelle session, plus rien en attente.
        repeat(10) { now += 16; race.onPacket(pkt(other, lap = 0, speedMps = 0f)) }
        assertEquals(other, race.snapshot.packet?.carCode)
        assertFalse(race.carChangePending)
    }

    @Test fun reconnectionWithTheSameCarIsNotAChange() {
        val race = RaceController(db, clock = { now })
        repeat(60) { now += 16; race.onPacket(pkt(brz)) }
        now += 30_000
        repeat(60) { now += 16; race.onPacket(pkt(brz)) }
        assertEquals(1, race.carIdentity.transitions.size)
        assertNull(race.carIdentity.transitions.single().from)
        assertFalse(race.carChangePending)
    }
}
