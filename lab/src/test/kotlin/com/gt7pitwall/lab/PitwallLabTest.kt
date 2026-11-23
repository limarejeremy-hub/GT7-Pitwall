package com.gt7pitwall.lab

import com.gt7pitwall.core.telemetry.PacketFormat
import com.gt7pitwall.core.telemetry.TelemetryTrace
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pitwall Lab : la trace utilisée ici est un GABARIT de test (fabriqué ci-dessous), jamais une mesure. Elle reproduit le
 * scénario du 22/11 (ligne P12/16, P13/16 à +567 ms) pour vérifier le câblage du Lab, pas GT7.
 */
class PitwallLabTest {
    private fun plain(lap: Int, total: Int, pos: Int, cars: Int, id: Int): ByteArray {
        val b = ByteBuffer.allocate(PacketFormat.C.size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0x00, 0x47375330) // magie GT7 « 0S7G »
        b.putFloat(0x04, if (lap >= 1) id * 50f / 100f else 0f)
        b.putFloat(0x44, 80f)
        b.putFloat(0x48, 100f)
        b.putFloat(0x4C, if (lap >= 1) 50f else 0f)
        b.putInt(0x70, id)
        b.putShort(0x74, lap.toShort())
        b.putShort(0x76, total.toShort())
        b.putInt(0x78, -1)
        b.putInt(0x7C, -1)
        b.putShort(0x84, pos.toShort())
        b.putShort(0x86, cars.toShort())
        b.putShort(0x8E, 1)
        b.putInt(0x124, 2184)
        return b.array()
    }

    private fun synthetic(): File {
        val f = Files.createTempFile("gabarit-", ".gt7trace").toFile()
        DataOutputStream(GZIPOutputStream(f.outputStream())).use { out ->
            TelemetryTrace.writeHeader(out)
            val line = 60_000
            for (i in 0 until 10_000) {
                val t = i * 10
                val (lap, total, pos) = when {
                    t < 5_000 -> Triple(0, 3, 12); t < 25_000 -> Triple(1, 3, 12); t < 45_000 -> Triple(2, 3, 12)
                    t < line -> Triple(3, 3, 12); t < line + 567 -> Triple(4, 3, 12); else -> Triple(4, 3, 13)
                }
                TelemetryTrace.writeFrame(out, t, plain(lap, total, pos, 16, i + 1))
            }
        }
        return f
    }

    @Test
    fun inspectReportsOnlyMeasurements() {
        val r = PitwallLab.inspect(synthetic())
        assertEquals(10_000, r.getJSONObject("echantillons").getInt("valeur"))
        assertEquals("MESURE", r.getJSONObject("echantillons").getString("source"))
        assertEquals(0, r.getJSONObject("illisibles").getInt("valeur"))
        assertTrue(r.getJSONObject("transitions_x84_x86").getJSONArray("valeur").length() >= 2)
    }

    @Test
    fun replayArchivesLineAndRevisedResult() {
        val r = PitwallLab.replay(synthetic(), com.gt7pitwall.core.race.Series.DAILY_B, com.gt7pitwall.core.race.SessionKind.RACE)
        val s = r.getJSONArray("sessions").let { it.getJSONObject(it.length() - 1) }
        assertEquals("P12/16", s.getJSONObject("ligne").getString("valeur"))
        assertEquals("CALCUL", s.getJSONObject("resultat_archive").getString("source"))
        assertTrue(s.getJSONObject("revisions_apres_ligne").getJSONArray("valeur").getString(0).startsWith("P12/16 → P13/16"))
        assertEquals(1, r.getJSONObject("compteurs").getJSONObject("valeur").getInt("course_terminee"))
    }
}
