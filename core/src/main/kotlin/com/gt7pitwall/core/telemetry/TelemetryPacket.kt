package com.gt7pitwall.core.telemetry

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Contenu d'un paquet de télémétrie GT7 (format 'A').
 * Chaque champ correspond strictement à une valeur transmise par le jeu : rien n'est inventé.
 *
 * Remarque : GT7 n'envoie AUCUNE donnée sur les adversaires (positions, écarts),
 * ni la distance parcourue dans le tour, ni les temps de secteurs.
 */
data class TelemetryPacket(
    val packetId: Int,
    val positionX: Float,
    val positionY: Float,
    val positionZ: Float,
    val velocityX: Float,
    val velocityY: Float,
    val velocityZ: Float,
    val bodyHeight: Float,
    val engineRpm: Float,
    /** Niveau de carburant, dans l'unité GT7 (la capacité vaut souvent 100). */
    val fuelLevel: Float,
    val fuelCapacity: Float,
    /** Vitesse en mètres par seconde. */
    val speedMps: Float,
    val turboBoost: Float,
    val oilPressure: Float,
    val waterTemperature: Float,
    val oilTemperature: Float,
    val tyreTempFrontLeft: Float,
    val tyreTempFrontRight: Float,
    val tyreTempRearLeft: Float,
    val tyreTempRearRight: Float,
    /** Tour en cours (0 avant le départ). */
    val currentLap: Int,
    /** Nombre total de tours (0 si course au temps ou essai libre). */
    val totalLaps: Int,
    /** Meilleur tour en millisecondes, -1 si aucun. */
    val bestLapMs: Int,
    /** Dernier tour en millisecondes, -1 si aucun. */
    val lastLapMs: Int,
    val timeOfDayMs: Int,
    /** Position sur la grille avant le départ, -1 une fois la course lancée. */
    val preRacePosition: Int,
    /** Nombre de voitures avant le départ, -1 une fois la course lancée. */
    val preRaceCarCount: Int,
    val rpmAlertMin: Int,
    val rpmAlertMax: Int,
    val calculatedMaxSpeed: Int,
    val flags: Int,
    val currentGear: Int,
    val suggestedGear: Int,
    /** Accélérateur 0..255. */
    val throttle: Int,
    /** Frein 0..255. */
    val brake: Int,
    val clutchPedal: Float,
    val transmissionTopSpeed: Float,
    val carCode: Int,
) {
    val speedKmh: Float get() = speedMps * 3.6f
    val isOnTrack: Boolean get() = flags and FLAG_ON_TRACK != 0
    val isPaused: Boolean get() = flags and FLAG_PAUSED != 0
    val isLoading: Boolean get() = flags and FLAG_LOADING != 0
    val isInGear: Boolean get() = flags and FLAG_IN_GEAR != 0
    val hasTurbo: Boolean get() = flags and FLAG_HAS_TURBO != 0
    val isRevLimiterAlert: Boolean get() = flags and FLAG_REV_LIMIT != 0
    val isTcsActive: Boolean get() = flags and FLAG_TCS != 0
    val isAsmActive: Boolean get() = flags and FLAG_ASM != 0

    /** Fraction de carburant restante (0..1), `null` si la capacité n'est pas fournie (ex. véhicule électrique). */
    val fuelFraction: Float?
        get() = if (fuelCapacity > 0f) (fuelLevel / fuelCapacity).coerceIn(0f, 1f) else null

    companion object {
        const val FLAG_ON_TRACK = 1 shl 0
        const val FLAG_PAUSED = 1 shl 1
        const val FLAG_LOADING = 1 shl 2
        const val FLAG_IN_GEAR = 1 shl 3
        const val FLAG_HAS_TURBO = 1 shl 4
        const val FLAG_REV_LIMIT = 1 shl 5
        const val FLAG_HANDBRAKE = 1 shl 6
        const val FLAG_LIGHTS = 1 shl 7
        const val FLAG_HIGH_BEAM = 1 shl 8
        const val FLAG_LOW_BEAM = 1 shl 9
        const val FLAG_ASM = 1 shl 10
        const val FLAG_TCS = 1 shl 11
    }
}

/** Décodage d'un paquet GT7 déchiffré (offsets du format 'A', little-endian). */
object TelemetryParser {

    /** Déchiffre puis décode. `null` si le paquet n'est pas un paquet GT7 valide. */
    fun parseEncrypted(data: ByteArray, length: Int = data.size): TelemetryPacket? =
        GT7Crypto.decrypt(data, length)?.let(::parseDecrypted)

    fun parseDecrypted(plain: ByteArray): TelemetryPacket? {
        if (plain.size < GT7Crypto.PACKET_SIZE_A) return null
        val b = ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getInt(0x00) != GT7Crypto.MAGIC) return null
        val gear = b.get(0x90).toInt() and 0xFF
        return TelemetryPacket(
            packetId = b.getInt(0x70),
            positionX = b.getFloat(0x04),
            positionY = b.getFloat(0x08),
            positionZ = b.getFloat(0x0C),
            velocityX = b.getFloat(0x10),
            velocityY = b.getFloat(0x14),
            velocityZ = b.getFloat(0x18),
            bodyHeight = b.getFloat(0x38),
            engineRpm = b.getFloat(0x3C),
            fuelLevel = b.getFloat(0x44),
            fuelCapacity = b.getFloat(0x48),
            speedMps = b.getFloat(0x4C),
            turboBoost = b.getFloat(0x50),
            oilPressure = b.getFloat(0x54),
            waterTemperature = b.getFloat(0x58),
            oilTemperature = b.getFloat(0x5C),
            tyreTempFrontLeft = b.getFloat(0x60),
            tyreTempFrontRight = b.getFloat(0x64),
            tyreTempRearLeft = b.getFloat(0x68),
            tyreTempRearRight = b.getFloat(0x6C),
            currentLap = b.getShort(0x74).toInt(),
            totalLaps = b.getShort(0x76).toInt(),
            bestLapMs = b.getInt(0x78),
            lastLapMs = b.getInt(0x7C),
            timeOfDayMs = b.getInt(0x80),
            preRacePosition = b.getShort(0x84).toInt(),
            preRaceCarCount = b.getShort(0x86).toInt(),
            rpmAlertMin = b.getShort(0x88).toInt() and 0xFFFF,
            rpmAlertMax = b.getShort(0x8A).toInt() and 0xFFFF,
            calculatedMaxSpeed = b.getShort(0x8C).toInt() and 0xFFFF,
            flags = b.getShort(0x8E).toInt() and 0xFFFF,
            currentGear = gear and 0x0F,
            suggestedGear = gear ushr 4,
            throttle = b.get(0x91).toInt() and 0xFF,
            brake = b.get(0x92).toInt() and 0xFF,
            clutchPedal = b.getFloat(0xF4),
            transmissionTopSpeed = b.getFloat(0x100),
            carCode = b.getInt(0x124),
        )
    }
}
