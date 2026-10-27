package com.gt7pitwall.core

import com.gt7pitwall.core.telemetry.BlackBoxProbe
import com.gt7pitwall.core.telemetry.TelemetryParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** La sonde mesure sans rien conserver : débit, pertes, temps de calcul, taille de trace compressée, batterie. */
class BlackBoxProbeTest {
    @Test
    fun theProbeMeasuresRateLossesSizeAndBattery() {
        var now = 1_000_000L
        val probe = BlackBoxProbe { now }
        probe.start()
        probe.battery(80, 31f)
        var id = 0
        // 20 minutes à 60 paquets/s ; un paquet sur 100 perdu.
        repeat(20 * 60 * 60) { k ->
            id++
            if (k % 100 == 99) id++
            val p = TelemetryParser.parseDecrypted(
                TestPackets.plain(lap = 1 + k / 5000, speedMps = 50f, packetId = id, posX = (k % 3000).toFloat(), posZ = 0f),
            )!!
            now += 16
            probe.onPacket(p, processingNs = 300_000L + (k % 7) * 10_000L)
        }
        now = 1_000_000L + 20 * 60_000L
        probe.battery(74, 36f)
        val r = probe.report()
        println(r.joinToString("\n"))
        assertTrue(r[0].startsWith("Durée mesurée : 20 min, 72000 paquets (60/s), 720 perdus"), r[0])
        assertTrue("au 95e centile" in r[1], r[1])
        // 36 octets × 20 Hz × 3 600 s ≈ 2,6 Mo brut par heure (la compression réelle se mesure sur la Fold).
        assertTrue(r[2].startsWith("Trace 20 Hz : 2,6 Mo brut"), r[2])
        assertEquals("Batterie : 80 % → 74 %, soit 18 % par heure (téléphone entier), 36 °C au maximum.", r[3])
    }
}
