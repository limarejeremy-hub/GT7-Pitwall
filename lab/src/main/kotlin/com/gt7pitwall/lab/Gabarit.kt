package com.gt7pitwall.lab

import com.gt7pitwall.core.telemetry.PacketFormat
import com.gt7pitwall.core.telemetry.TelemetryTrace
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream

/**
 * Trace GABARIT (fabriquée, jamais une mesure) pour tester le Lab dans une session sans traces privées. Le nom du
 * fichier contient toujours « GABARIT-NON-MESURE ». Scénario : course de 3 tours, ligne P12/16, P13/16 à +567 ms
 * (comme le 22/11), puis quelques anomalies volontaires repérables par `anomalies` : trou de flux de 2 s, 0x84 = -1
 * pendant 1 s en course, plein de carburant en roulant.
 */
object Gabarit {
    const val MARK = "GABARIT-NON-MESURE"

    private fun packet(lap: Int, total: Int, pos: Int, cars: Int, id: Int, fuel: Float, speed: Float, lastLapMs: Int): ByteArray {
        val b = ByteBuffer.allocate(PacketFormat.C.size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0x00, 0x47375330) // magie GT7
        b.putFloat(0x04, if (speed > 1f) id * speed / 100f else 0f)
        b.putFloat(0x44, fuel)
        b.putFloat(0x48, 100f)
        b.putFloat(0x4C, speed)
        b.putInt(0x70, id)
        b.putShort(0x74, lap.toShort())
        b.putShort(0x76, total.toShort())
        b.putInt(0x78, -1)
        b.putInt(0x7C, lastLapMs)
        b.putShort(0x84, pos.toShort())
        b.putShort(0x86, cars.toShort())
        b.putShort(0x8E, 1)
        b.putInt(0x124, 2184)
        return b.array()
    }

    /** Écrit la trace gabarit dans [dir] et renvoie le fichier. */
    fun write(dir: File, anomalies: Boolean = true): File {
        dir.mkdirs()
        val f = File(dir, "pitwall-trace-$MARK.gt7trace")
        DataOutputStream(GZIPOutputStream(f.outputStream())).use { out ->
            TelemetryTrace.writeHeader(out)
            val line = 60_000
            var id = 0
            var t = 0
            var lapStart = 0
            var lastLap = -1
            var curLap = 0
            while (t < line + 5_000) {
                id++
                val (lap, total, pos) = when {
                    t < 5_000 -> Triple(0, 3, 12); t < 25_000 -> Triple(1, 3, 12); t < 45_000 -> Triple(2, 3, 12)
                    t < line -> Triple(3, 3, 12); t < line + 567 -> Triple(4, 3, 12); else -> Triple(4, 3, 13)
                }
                if (lap != curLap) {
                    if (curLap >= 1) lastLap = t - lapStart
                    lapStart = t
                    curLap = lap
                }
                var p = pos
                var fuel = 80f - t / 10_000f
                if (anomalies) {
                    if (t in 30_000 until 31_000) p = -1 // 0x84 incohérent en course
                    if (t >= 40_000) fuel += 10f // plein en roulant
                }
                TelemetryTrace.writeFrame(out, t, packet(lap, total, p, 16, id, fuel, if (lap >= 1) 50f else 0f, lastLap))
                t += if (anomalies && t == 20_000) 2_000 else 10 // trou de flux de 2 s
            }
        }
        return f
    }
}
