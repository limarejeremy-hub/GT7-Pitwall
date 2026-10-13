package com.gt7pitwall.core

import com.gt7pitwall.core.telemetry.GT7Crypto
import com.gt7pitwall.core.telemetry.TelemetryPacket
import com.gt7pitwall.core.telemetry.TelemetryParser
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Construit des paquets GT7 en clair pour les tests (mêmes offsets que le jeu). */
object TestPackets {
    fun plain(
        lap: Int = 1,
        totalLaps: Int = 10,
        fuel: Float = 100f,
        capacity: Float = 100f,
        speedMps: Float = 0f,
        lastLapMs: Int = -1,
        bestLapMs: Int = -1,
        carCode: Int = 1000,
        packetId: Int = 1,
        flags: Int = TelemetryPacket.FLAG_ON_TRACK,
        posX: Float = 0f,
        posZ: Float = 0f,
    ): ByteArray {
        val b = ByteBuffer.allocate(GT7Crypto.PACKET_SIZE_A).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0x00, GT7Crypto.MAGIC)
        b.putFloat(0x04, posX)
        b.putFloat(0x0C, posZ)
        b.putFloat(0x44, fuel)
        b.putFloat(0x48, capacity)
        b.putFloat(0x4C, speedMps)
        b.putInt(0x70, packetId)
        b.putShort(0x74, lap.toShort())
        b.putShort(0x76, totalLaps.toShort())
        b.putInt(0x78, bestLapMs)
        b.putInt(0x7C, lastLapMs)
        b.putShort(0x8E, flags.toShort())
        b.putInt(0x124, carCode)
        return b.array()
    }

    fun packet(
        lap: Int = 1,
        totalLaps: Int = 10,
        fuel: Float = 100f,
        capacity: Float = 100f,
        lastLapMs: Int = -1,
        bestLapMs: Int = -1,
        carCode: Int = 1000,
    ): TelemetryPacket = TelemetryParser.parseDecrypted(
        plain(lap, totalLaps, fuel, capacity, lastLapMs = lastLapMs, bestLapMs = bestLapMs, carCode = carCode),
    )!!

    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
