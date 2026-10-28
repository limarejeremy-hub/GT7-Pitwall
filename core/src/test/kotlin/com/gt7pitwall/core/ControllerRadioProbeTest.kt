package com.gt7pitwall.core

import com.gt7pitwall.core.telemetry.ControllerRadioProbe
import com.gt7pitwall.core.telemetry.ControllerRadioProbe.Bit
import com.gt7pitwall.core.telemetry.ControllerRadioProbe.Kind
import com.gt7pitwall.core.telemetry.GT7Crypto
import com.gt7pitwall.core.telemetry.PacketFormat
import com.gt7pitwall.core.telemetry.TelemetryPacket
import com.gt7pitwall.core.telemetry.TelemetryParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #38, phase 1 : sonde des bits de 0x8E (phares). Trames **synthétiques** : ces tests vérifient la mesure et la
 * prudence des conclusions, jamais qu'une touche de la DualSense est détectable — seul un essai PS5 le dira.
 */
class ControllerRadioProbeTest {
    private val base = TelemetryPacket.FLAG_ON_TRACK or TelemetryPacket.FLAG_IN_GEAR
    private val high = TelemetryPacket.FLAG_HIGH_BEAM
    private val lights = TelemetryPacket.FLAG_LIGHTS

    private class Feed(val probe: ControllerRadioProbe) {
        var t = 1_000L
        var id = 0
        fun send(flags: Int, frames: Int = 1, size: Int = PacketFormat.C.size, car: Int = 3607) = repeat(frames) {
            val p = TelemetryParser.parseDecrypted(TestPackets.plain(speedMps = 0f, packetId = ++id, flags = flags, carCode = car, size = size))!!
            probe.onPacket(p, t)
            t += 16 // ~60 paquets/s
        }
        fun pause(ms: Long) { t += ms }
    }

    private fun feed(): Feed = Feed(ControllerRadioProbe().also { it.setEnabled(true, 0) })

    private fun kinds(f: Feed, b: Bit = Bit.HIGH_BEAM) = f.probe.episodes().filter { it.bit == b }.map { it.kind }

    @Test
    fun pulsesOfOneToThreeFramesAndHoldsAreClassified() {
        val f = feed()
        f.send(base, 30)
        f.send(base or high, 1); f.send(base, 30)
        f.send(base or high, 3); f.send(base, 30)
        f.send(base or high, 20); f.send(base, 30) // 0,32 s
        f.send(base or high, 60); f.send(base, 30) // ~1 s
        f.send(base or high, 150); f.send(base, 30) // ~2,4 s
        assertEquals(listOf(Kind.PULSE, Kind.PULSE, Kind.SHORT, Kind.HOLD, Kind.LONG), kinds(f))
    }

    @Test
    fun lightsAlreadyOnAtStartAreAStateNotAPress() {
        val f = feed()
        f.send(base or high, 200)
        assertEquals(listOf(Kind.PERMANENT), kinds(f))
        assertTrue(f.probe.verdict(Bit.HIGH_BEAM).contains("aucune transition (indétectable)"), f.probe.verdict(Bit.HIGH_BEAM))
        f.send(base, 10)
        assertEquals(listOf(Kind.UNMEASURABLE), kinds(f), "début inconnu : la durée n'est pas mesurable")
    }

    @Test
    fun automaticHeadlightsAreTrackedSeparately() {
        // Feux automatiques (nuit) : seul le bit « feux » change ; les pleins phares ne bougent pas.
        val f = feed()
        f.send(base, 30); f.send(base or lights, 300); f.send(base, 30)
        assertEquals(emptyList(), kinds(f))
        assertEquals(listOf(Kind.LONG), kinds(f, Bit.LIGHTS))
        assertTrue(f.probe.verdict(Bit.HIGH_BEAM).contains("jamais changé"))
    }

    @Test
    fun aDoubleClickGivesTwoEpisodesNotOne() {
        val f = feed()
        f.send(base, 30); f.send(base or high, 2); f.send(base, 3); f.send(base or high, 2); f.send(base, 30)
        assertEquals(listOf(Kind.PULSE, Kind.PULSE), kinds(f))
    }

    @Test
    fun pauseLoadingAndStreamCutsMakeAnEpisodeUnmeasurable() {
        val f = feed()
        f.send(base, 10); f.send(base or high, 5)
        f.send(base or high or TelemetryPacket.FLAG_PAUSED, 30) // pause pendant l'appui (ou replay figé)
        f.send(base, 10)
        f.send(base or high, 5); f.pause(2_000); f.send(base or high, 5); f.send(base, 10) // coupure UDP puis reprise
        assertEquals(listOf(Kind.UNMEASURABLE, Kind.UNMEASURABLE), kinds(f))
        assertTrue(f.probe.summary().first().contains("1 coupure(s) du flux"), f.probe.summary().first())
    }

    @Test
    fun formatAIsSupported() {
        // Les drapeaux 0x8E existent dès le format A (296 octets), donc aussi en course Sport si seul A y est envoyé.
        val f = feed()
        f.send(base, 10, size = GT7Crypto.PACKET_SIZE_A); f.send(base or high, 2, size = GT7Crypto.PACKET_SIZE_A); f.send(base, 10, size = GT7Crypto.PACKET_SIZE_A)
        assertEquals(listOf(Kind.PULSE), kinds(f))
        assertTrue(f.probe.summary()[1].contains("A "), f.probe.summary()[1])
    }

    @Test
    fun noPressIsEverAttributedWithoutMatchingMarks() {
        val f = feed()
        f.send(base, 30)
        repeat(6) { f.send(base or high, 2); f.send(base, 60) }
        assertTrue(f.probe.verdict(Bit.HIGH_BEAM).contains("sans aucun repère du pilote — aucune attribution possible"))
        // Repères, mais une transition non corrélée en plus : pas de correspondance démontrée.
        val g = feed()
        g.send(base, 30)
        repeat(5) { g.probe.mark(g.t); g.pause(800); g.send(base or high, 2); g.send(base, 60) }
        g.send(base, 320); g.send(base or high, 2); g.send(base, 60) // allumage sans repère, 5 s plus tard
        assertTrue(g.probe.verdict(Bit.HIGH_BEAM).contains("pas de correspondance démontrée"), g.probe.verdict(Bit.HIGH_BEAM))
        // Cinq repères, cinq allumages, rien d'autre : concordance limitée à cet essai.
        val h = feed()
        h.send(base, 30)
        repeat(5) { h.probe.mark(h.t); h.pause(800); h.send(base or high, 2); h.send(base, 60) }
        assertTrue(h.probe.verdict(Bit.HIGH_BEAM).contains("concordance sur cet essai seulement"), h.probe.verdict(Bit.HIGH_BEAM))
    }

    @Test
    fun aDisabledProbeRecordsNothingAndAReconnectIsLogged() {
        val off = ControllerRadioProbe()
        val p = TelemetryParser.parseDecrypted(TestPackets.plain(packetId = 1, flags = base or high))!!
        off.onPacket(p, 1_000)
        off.mark(1_000)
        assertFalse(off.enabled)
        assertEquals(listOf("Sonde désactivée."), off.summary())
        val f = feed()
        f.send(base, 10, car = 3607); f.pause(3_000); f.send(base, 10, car = 3474)
        val log = f.probe.export()
        assertTrue(log.contains("coupure du flux") && log.contains("changement de voiture : 3607 → 3474"), log)
        assertFalse(log.contains("192.168"), "aucune adresse réseau")
    }
}
