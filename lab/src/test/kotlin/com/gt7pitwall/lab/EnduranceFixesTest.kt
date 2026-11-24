package com.gt7pitwall.lab

import com.gt7pitwall.core.telemetry.PacketFormat
import com.gt7pitwall.core.telemetry.TelemetryTrace
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Défauts relevés par la revue qualité V513 (PR #93), reproduits sur des traces FABRIQUÉES (circuit circulaire de
 * 1 500 m, voie des stands décalée de 15 m). Elles vérifient le code du Lab, jamais une conclusion sur GT7.
 */
class EnduranceFixesTest {
    private val dir = Files.createTempDirectory("v513-fix").toFile()

    @Test
    fun theLapBeforeAStopIsNotCleanBecauseItHoldsThePitEntry() {
        val f = Fixture.write(dir, "entree", total = 6, stops = listOf(Fixture.Stop(4, 0.02f, 20_000)))
        val laps = RaceLaps.load(f).single().laps
        assertTrue(laps.first { it.n == 4 }.stopped)
        assertFalse(laps.first { it.n == 3 }.clean, "le tour 3 contient l'entrée de voie")
        assertTrue(laps.first { it.n == 2 }.clean)
    }

    @Test
    fun aPitVisitWithTwoImmobilisationsIsCountedOnce() {
        val f = Fixture.write(dir, "double", total = 7, stops = listOf(Fixture.Stop(3, 0.98f, 10_000), Fixture.Stop(4, 0.01f, 10_000)))
        val stops = PitResearch.run(listOf(f)).getJSONArray("arrets")
        assertEquals(1, stops.length(), stops.toString())
        val s = stops.getJSONObject(0)
        assertEquals(2, s.getInt("immobilisations"))
        assertTrue(s.getDouble("immobile_s_MESURE") in 19.5..20.5, s.toString())
        // Un seul nouveau relais pneus.
        val rows = TyreResearch.run(listOf(f)).getJSONArray("tours")
        val lap5 = (0 until rows.length()).map { rows.getJSONObject(it) }.first { it.getInt("tour") == 5 }
        assertEquals(1, lap5.getInt("relais"))
    }

    @Test
    fun aWindowStartingBeforeTheLineIsReadAcrossIt() {
        val f = Fixture.write(dir, "propre", total = 6)
        val laps = RaceLaps.load(f).single().laps
        val a = laps.first { it.n == 3 }
        val b = laps.first { it.n == 4 }
        val t = PitResearch.windowTime(a, b, -0.1f, 0.1f)
        assertNotNull(t)
        assertEquals(6.0, t, 0.2) // 20 % d'un tour de 30 s
    }

    @Test
    fun theDetectorFiresOnAFiveLapStintWithALossAndSaysWhatItCouldNotTest() {
        val f = Fixture.write(dir, "perte", total = 8, speedOfLap = { if (it >= 5) 47f else 50f }, tempOfLap = { 70f + 2f * it })
        val det = TyreResearch.run(listOf(f)).getJSONObject("detecteur_degradation")
        val relais = det.getJSONArray("relais").getJSONObject(0)
        assertTrue(relais.getBoolean("testable"))
        assertEquals(5, relais.getInt("alerte_premier_tour"), relais.toString())
        assertEquals(1, det.getInt("relais_testables"))
    }

    @Test
    fun noFuelForecastIsComparedOnAnUnfinishedRace() {
        val unfinished = Fixture.write(dir, "inachevee", total = 8, lapsDriven = 6)
        assertEquals(0, FuelPerformance.run(listOf(unfinished)).getJSONObject("prevision_arrivee").getInt("previsions"))
        val finished = Fixture.write(dir, "complete", total = 8)
        val p = FuelPerformance.run(listOf(finished)).getJSONObject("prevision_arrivee")
        assertTrue(p.getInt("previsions") > 0)
        assertTrue(p.getDouble("erreur_absolue_moyenne") < 0.2, p.toString())
        // Une seule course : aucun intervalle calibré sur « d'autres courses » n'est inventé.
        assertTrue(p.isNull("taux_couverture_calibre_autres_courses"))
    }

    @Test
    fun anAutonomyWithAnUnknownUpperBoundIsNotTurnedIntoThousandsOfLaps() {
        val f = Fixture.write(dir, "autonomie", total = 8, speedOfLap = { 50f - 0.05f * it * it })
        val det = TyreResearch.run(listOf(f)).getJSONObject("detecteur_degradation").getJSONArray("relais")
        for (i in 0 until det.length()) {
            val a = det.getJSONObject(i).getJSONObject("autonomie")
            if (a.getString("estimation") == "fourchette") {
                val r = a.getJSONArray("tours_avant_perte_2s")
                if (!r.isNull(1)) assertTrue(r.getDouble(1) < 1_000, a.toString())
            }
        }
    }
}

/** Traces fabriquées pour [EnduranceFixesTest] (jamais une mesure). */
internal object Fixture {
    const val L = 1_500f
    private const val DT = 50
    private const val LANE_SPEED = 20f

    class Stop(val lap: Int, val frac: Float, val ms: Int, val refuel: Float = 0f)

    fun write(
        dir: File, name: String, total: Int, lapsDriven: Int = total, stops: List<Stop> = emptyList(),
        speedOfLap: (Int) -> Float = { 50f }, tempOfLap: (Int) -> Float = { 80f },
    ): File {
        val f = File(dir, "pitwall-$name-${Gabarit.MARK}.gt7trace")
        val radius = L / (2 * PI).toFloat()
        DataOutputStream(GZIPOutputStream(f.outputStream())).use { out ->
            TelemetryTrace.writeHeader(out)
            var t = 0
            var id = 0
            var fuel = 60f
            fun emit(lap: Int, d: Float, v: Float, lane: Boolean, lastLapMs: Int) {
                val th = 2 * PI * ((d % L) / L)
                val r = radius + if (lane) 15f else 0f
                TelemetryTrace.writeFrame(out, t, packet(++id, lap, total, (r * cos(th)).toFloat(), (r * sin(th)).toFloat(), fuel, v, lastLapMs, tempOfLap(lap.coerceAtLeast(1))))
                t += DT
            }
            repeat(20) { emit(0, 0f, 0f, false, -1) }
            var d = 0f
            var lastLapMs = -1
            var lapStart = t
            var curLap = 1
            val pending = stops.map { (it.lap - 1) * L + it.frac * L to it }.toMutableList()
            var stillMs = 0
            var refuelRate = 0f
            while (true) {
                val lap = floor(d / L).toInt() + 1
                if (lap != curLap) { lastLapMs = t - lapStart; lapStart = t; curLap = lap }
                if (lap > lapsDriven) break
                val lane = stops.any { abs(d - ((it.lap - 1) * L + it.frac * L)) < 0.05f * L }
                if (stillMs <= 0) pending.firstOrNull { d >= it.first }?.let { pending.remove(it); stillMs = it.second.ms; refuelRate = it.second.refuel / (it.second.ms / DT) }
                val v = when { stillMs > 0 -> 0f; lane -> LANE_SPEED; else -> speedOfLap(lap) }
                emit(lap, d, v, lane, lastLapMs)
                if (stillMs > 0) { stillMs -= DT; fuel += refuelRate } else { d += v * DT / 1000f; fuel -= v * DT / 1000f * 3f / L }
            }
            if (lapsDriven == total) repeat(40) { emit(total + 1, d, 0f, false, lastLapMs) }
        }
        return f
    }

    private fun packet(id: Int, lap: Int, total: Int, x: Float, z: Float, fuel: Float, speed: Float, lastLapMs: Int, tyreC: Float): ByteArray {
        val b = ByteBuffer.allocate(PacketFormat.C.size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0x00, 0x47375330)
        b.putFloat(0x04, x)
        b.putFloat(0x0C, z)
        b.putFloat(0x44, fuel)
        b.putFloat(0x48, 100f)
        b.putFloat(0x4C, speed)
        for (o in listOf(0x60, 0x64, 0x68, 0x6C)) b.putFloat(o, tyreC)
        b.putInt(0x70, id)
        b.putShort(0x74, lap.toShort())
        b.putShort(0x76, total.toShort())
        b.putInt(0x78, -1)
        b.putInt(0x7C, lastLapMs)
        b.putShort(0x84, 5)
        b.putShort(0x86, 8)
        b.putShort(0x8E, 1)
        b.putInt(0x124, 2184)
        return b.array()
    }
}
