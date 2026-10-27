package com.gt7pitwall.core

import com.gt7pitwall.core.telemetry.PacketFormat
import com.gt7pitwall.core.telemetry.TelemetryParser
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Champs UDP jusqu'ici ignorés par Pitwall (revue PR #37, P1-E). Ces tests vérifient le **décodage** aux offsets publiés
 * (gt7-udp, PDTools, gt7-datalogger #150), pas leur signification physique, qui reste à valider sur PS5.
 */
class ExperimentalFieldsTest {
    private fun plain(size: Int, fill: ByteBuffer.() -> Unit = {}): ByteArray =
        TestPackets.plain(lap = 3, speedMps = 40f, packetId = 7, size = size).also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).fill() }

    @Test
    fun engineTorqueIsReadOnlyFromTildeAndC() {
        for (f in listOf(PacketFormat.TILDE, PacketFormat.C)) {
            val p = TelemetryParser.parseDecrypted(plain(f.size) { putFloat(0x154, 0.83f) })!!
            assertEquals(0.83f, p.engineTorqueNorm, f.label)
        }
        // Formats A et B : l'offset n'existe pas, jamais deviné.
        assertNull(TelemetryParser.parseDecrypted(plain(PacketFormat.A.size))!!.engineTorqueNorm)
        assertNull(TelemetryParser.parseDecrypted(plain(PacketFormat.B.size))!!.engineTorqueNorm)
    }

    @Test
    fun torqueKeepsItsSignAndRangeButNotItsNoise() {
        fun t(v: Float) = TelemetryParser.parseDecrypted(plain(PacketFormat.C.size) { putFloat(0x154, v) })!!.engineTorqueNorm
        assertEquals(-0.71f, t(-0.71f), "frein moteur : négatif conservé")
        assertEquals(9.9f, t(9.9f), "non borné à ±1 (Taycan dans gt7-datalogger #150)")
        assertEquals(0f, t(4e-6f), "bruit des replays ramené à 0")
        assertNull(t(Float.NaN), "valeur non finie : null, jamais propagée")
        assertNull(t(Float.POSITIVE_INFINITY))
    }

    @Test
    fun otherUnusedFieldsAreDecodedAtTheirPublishedOffsets() {
        val p = TelemetryParser.parseDecrypted(plain(PacketFormat.C.size) {
            putFloat(0x140, 120f); putFloat(0x144, 118f); putFloat(0x148, -30f); putFloat(0x14C, -31f)
            put(0x13F, 1)
            putFloat(0x94, 0.02f); putFloat(0x98, 0.999f); putFloat(0x9C, -0.01f); putFloat(0xA0, 0.12f)
            putFloat(0x12C, 3.5f)
        })!!
        assertEquals(listOf(120f, 118f, -30f, -31f), assertNotNull(p.wheelTorque).toList())
        assertEquals(1, p.energyRecoveryFlag)
        val rp = assertNotNull(p.roadPlane)
        assertEquals(0.999f, rp.ny)
        assertEquals(0.12f, rp.distance)
        assertEquals(3.5f, p.steeringRateRadS)
        // Format A : plan de la route présent (tous formats), rien des extensions.
        val a = TelemetryParser.parseDecrypted(plain(PacketFormat.A.size) { putFloat(0x98, 1f) })!!
        assertNotNull(a.roadPlane)
        assertNull(a.wheelTorque); assertNull(a.energyRecoveryFlag); assertNull(a.steeringRateRadS)
        // Plan nul (champ non rempli) : absent, pas une route plate inventée.
        assertNull(TelemetryParser.parseDecrypted(plain(PacketFormat.A.size))!!.roadPlane)
    }
}

class TorqueProbeTest {
    private fun packet(throttle: Int, torque: Float, gear: Int = 3, id: Int) = com.gt7pitwall.core.telemetry.TelemetryParser.parseDecrypted(
        TestPackets.plain(speedMps = 30f, throttle = throttle, packetId = id, size = com.gt7pitwall.core.telemetry.PacketFormat.C.size),
    )!!.copy(engineTorqueNorm = torque, currentGear = gear)

    @Test
    fun theProbeChecksTheCommunityClaimsWithoutDecidingAnything() {
        val probe = com.gt7pitwall.core.telemetry.TorqueProbe()
        // Couple proportionnel à l'accélérateur (cas « conforme ») ; frein moteur négatif ; point mort à 0.
        for (i in 0 until 1_000) {
            val th = (i * 7) % 256
            probe.onPacket(packet(th, th / 255f * 1.4f + if (th == 0) -0.3f else 0f, id = i + 1))
        }
        repeat(50) { probe.onPacket(packet(0, 0f, gear = 0, id = 2_000 + it)) }
        val r = probe.report()
        assertTrue((probe.throttleCorrelation() ?: 0.0) > 0.95, r.joinToString("\n"))
        assertTrue(r.any { it.startsWith("Point mort ou débrayé : 100 %") }, r.joinToString("\n"))
        assertTrue(r.any { it.startsWith("Frein moteur") && "100 % négatifs" in it }, r.joinToString("\n"))
        assertTrue(r.any { it.contains("dépasse ±1") }, r.joinToString("\n"))
    }

    @Test
    fun formatAHasNoTorque() {
        val probe = com.gt7pitwall.core.telemetry.TorqueProbe()
        probe.onPacket(com.gt7pitwall.core.telemetry.TelemetryParser.parseDecrypted(TestPackets.plain(speedMps = 30f, packetId = 1))!!)
        assertEquals("0x154 absent : format A ou B (il faut le format ~ ou C).", probe.report().single())
    }
}
