package com.gt7pitwall.core

import com.gt7pitwall.core.telemetry.ControllerRadioProbe
import com.gt7pitwall.core.telemetry.ControllerRadioProbe.Bit
import com.gt7pitwall.core.telemetry.ControllerRadioProbe.Kind
import com.gt7pitwall.core.telemetry.ControllerRadioProbe.Mode
import com.gt7pitwall.core.telemetry.ControllerRadioProbe.SeriesKind
import com.gt7pitwall.core.telemetry.GT7Crypto
import com.gt7pitwall.core.telemetry.PacketFormat
import com.gt7pitwall.core.telemetry.TelemetryPacket
import com.gt7pitwall.core.telemetry.TelemetryParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #38, phase 1 (et revue de la PR #39) : sonde des bits de 0x8E. Trames **synthétiques** : ces tests vérifient la
 * mesure et la prudence des conclusions, jamais qu'une touche de la DualSense est détectable — seul un essai PS5 le dira.
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
        /** Repère, 0,8 s pour poser le Fold, puis un appui de [frames] paquets, puis 1 s au repos. */
        fun press(flags: Int, frames: Int, base: Int) {
            probe.mark(t); send(base, 50); send(flags, frames); send(base, 60)
        }
    }

    private fun feed(): Feed = Feed(ControllerRadioProbe().also { it.setEnabled(true, 0) })
    private fun kinds(f: Feed, b: Bit = Bit.HIGH_BEAM) = f.probe.episodes().filter { it.bit == b }.map { it.kind }
    private fun verdict(f: Feed) = f.probe.verdict(Bit.HIGH_BEAM)

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
        assertTrue(verdict(f).contains("aucune transition (indétectable)"), verdict(f))
        f.send(base, 10)
        assertEquals(listOf(Kind.UNMEASURABLE), kinds(f), "début inconnu : la durée n'est pas mesurable")
    }

    @Test
    fun automaticHeadlightsAreTrackedSeparately() {
        val f = feed()
        f.send(base, 30); f.send(base or lights, 300); f.send(base, 30)
        assertEquals(emptyList(), kinds(f))
        assertEquals(listOf(Kind.LONG), kinds(f, Bit.LIGHTS))
        assertTrue(verdict(f).contains("jamais changé"))
    }

    @Test
    fun aDoubleClickGivesTwoEpisodesNotOne() {
        val f = feed()
        f.send(base, 30); f.send(base or high, 2); f.send(base, 3); f.send(base or high, 2); f.send(base, 30)
        assertEquals(listOf(Kind.PULSE, Kind.PULSE), kinds(f))
    }

    @Test
    fun formatAIsSupported() {
        val f = feed()
        f.send(base, 10, size = GT7Crypto.PACKET_SIZE_A); f.send(base or high, 2, size = GT7Crypto.PACKET_SIZE_A); f.send(base, 10, size = GT7Crypto.PACKET_SIZE_A)
        assertEquals(listOf(Kind.PULSE), kinds(f))
        assertTrue(f.probe.summary()[1].contains("A "), f.probe.summary()[1])
    }

    @Test
    fun fiveCleanMarkedPressesConcordOnlyForThisTrial() {
        val f = feed()
        f.probe.declareMode(Mode.OFFLINE, f.t)
        f.send(base, 100)
        f.probe.startSeries(SeriesKind.CONTROL, f.t); f.send(base, 600) // témoin : 10 s sans appui
        f.probe.startSeries(SeriesKind.SHORT_PRESSES, f.t); f.send(base, 100)
        repeat(5) { f.press(base or high, 2, base) }
        f.probe.startSeries(SeriesKind.HOLDS, f.t); f.send(base, 100)
        repeat(5) { f.press(base or high, 60, base) }
        val r = f.probe.seriesReports()
        assertEquals("témoin propre : aucune montée sans appui", r[0].verdict)
        assertTrue(r[1].concordant && r[2].concordant, r.joinToString("\n") { it.verdict })
        assertEquals(5, r[2].releases)
        assertTrue(verdict(f).contains("concordance sur cet essai seulement") && verdict(f).contains("hors ligne"), verdict(f))
        assertFalse(f.probe.summary().joinToString().contains("touche détectée"))
    }

    @Test
    fun clusteredMarksNeverMatchASingleRise() {
        // Revue PR #39, point 1 : cinq repères groupés puis une seule montée ne font jamais une concordance.
        val f = feed()
        f.send(base, 30)
        f.probe.startSeries(SeriesKind.SHORT_PRESSES, f.t); f.send(base, 100)
        repeat(5) { f.probe.mark(f.t); f.send(base, 3) }
        f.send(base, 30); f.send(base or high, 2); f.send(base, 60)
        val r = f.probe.seriesReports().single()
        assertEquals(1, r.paired)
        assertFalse(r.concordant)
        assertTrue(r.verdict.startsWith("non concluant : 1/5 repères appariés"), r.verdict)
    }

    @Test
    fun aMarkAfterTheRiseIsNeverPaired() {
        val f = feed()
        f.send(base, 30)
        f.probe.startSeries(SeriesKind.SHORT_PRESSES, f.t); f.send(base, 100)
        repeat(5) { f.send(base or high, 2); f.probe.mark(f.t); f.send(base, 200) } // repère posé après l'allumage, appuis espacés de 3,2 s
        assertEquals(0, f.probe.seriesReports().single().paired)
    }

    @Test
    fun disturbedSignalsGiveInconclusiveNeverConcordance() {
        fun trial(disturb: Feed.() -> Unit): ControllerRadioProbe.SeriesReport {
            val f = feed()
            f.send(base, 30)
            f.probe.startSeries(SeriesKind.HOLDS, f.t); f.send(base, 100)
            repeat(4) { f.press(base or high, 60, base) }
            f.probe.mark(f.t); f.send(base, 50); f.send(base or high, 20); f.disturb(); f.send(base, 60)
            return f.probe.seriesReports().single()
        }
        // Coupure UDP au milieu d'un appui marqué.
        val cut = trial { pause(2_000); send(base or high, 20) }
        assertFalse(cut.concordant); assertEquals(1, cut.ambiguousMarks)
        // Pause pendant l'appui.
        assertFalse(trial { send(base or high or TelemetryPacket.FLAG_PAUSED, 20) }.concordant)
        // Changement de voiture pendant le maintien.
        assertFalse(trial { send(base or high, 20, car = 3474) }.concordant)
        // Mode déclaré qui change pendant le maintien.
        assertFalse(trial { probe.declareMode(Mode.SPORT_QUALIFYING, t); send(base or high, 20) }.concordant)
        // Bit coincé à 1 (jamais relâché) : épisode ouvert, non mesurable.
        val f = feed()
        f.send(base, 30)
        f.probe.startSeries(SeriesKind.SHORT_PRESSES, f.t); f.send(base, 100)
        repeat(4) { f.press(base or high, 2, base) }
        f.probe.mark(f.t); f.send(base, 50); f.send(base or high, 300)
        val stuck = f.probe.seriesReports().single()
        assertFalse(stuck.concordant)
        assertTrue(stuck.verdict.startsWith("non concluant"), stuck.verdict)
    }

    @Test
    fun aRiseRightAfterAContextChangeIsNotMeasurable() {
        val f = feed()
        f.send(base, 30)
        f.probe.startSeries(SeriesKind.SHORT_PRESSES, f.t)
        f.probe.mark(f.t); f.send(base, 10); f.send(base or high, 2); f.send(base, 30) // 0,16 s après le début de série
        assertEquals(listOf(Kind.UNMEASURABLE), kinds(f))
    }

    @Test
    fun aDirtyControlMakesTheWholeTrialInconclusive() {
        val f = feed()
        f.send(base, 30)
        f.probe.startSeries(SeriesKind.CONTROL, f.t); f.send(base, 100); f.send(base or high, 2); f.send(base, 300) // montée sans appui
        f.probe.startSeries(SeriesKind.SHORT_PRESSES, f.t); f.send(base, 100)
        repeat(5) { f.press(base or high, 2, base) }
        assertTrue(f.probe.seriesReports()[0].verdict.startsWith("témoin : 1 montée(s) sans appui"))
        assertTrue(verdict(f).contains("non concluant — le témoin"), verdict(f))
    }

    @Test
    fun aDisabledProbeRecordsNothingAndTheExportIsTraceable() {
        val off = ControllerRadioProbe()
        off.onPacket(TelemetryParser.parseDecrypted(TestPackets.plain(packetId = 1, flags = base or high))!!, 1_000)
        off.mark(1_000)
        assertFalse(off.enabled)
        assertEquals(listOf("Sonde désactivée."), off.summary())
        val f = feed()
        f.probe.profile("Course Sport C", f.t)
        f.send(base, 10, car = 3607); f.pause(3_000); f.send(base, 10, car = 3474)
        val log = f.probe.export()
        assertTrue(log.contains("coupure du flux") && log.contains("changement de voiture : 3607 → 3474"), log)
        assertTrue(log.contains("pas une détection du mode") && log.contains("pas une latence de bout en bout"), log)
        assertFalse(log.contains("192.168"), "aucune adresse réseau")
    }
}
