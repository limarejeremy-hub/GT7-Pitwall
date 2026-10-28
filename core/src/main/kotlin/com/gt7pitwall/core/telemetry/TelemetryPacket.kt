package com.gt7pitwall.core.telemetry

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Une valeur par roue : avant gauche, avant droite, arrière gauche, arrière droite. */
data class Wheels(val fl: Float, val fr: Float, val rl: Float, val rr: Float) {
    fun toList(): List<Float> = listOf(fl, fr, rl, rr)

    companion object {
        val ZERO = Wheels(0f, 0f, 0f, 0f)
        val NAMES = listOf("avant gauche", "avant droit", "arrière gauche", "arrière droit")
    }
}

/**
 * Surface sous un pneu (format C). Codes de GT7 d'après MacManley/gt7-udp (README, « Surface Type ») ; un code non
 * documenté reste [UNKNOWN] avec son caractère brut, jamais deviné.
 */
enum class Surface(val code: Char, val label: String) {
    TARMAC('T', "asphalte"),
    KERB('C', "vibreur"),
    DIRT('D', "terre"),
    GRASS('G', "herbe"),
    SAND('S', "sable"),
    SNOW('s', "neige"),
    UNKNOWN('?', "inconnue"),
    ;

    companion object {
        fun of(code: Char): Surface = entries.firstOrNull { it.code == code && it != UNKNOWN } ?: UNKNOWN
    }
}

/** Surface sous les quatre pneus, avec les codes bruts reçus (diagnostic). */
data class TyreSurfaces(val fl: Surface, val fr: Surface, val rl: Surface, val rr: Surface, val raw: String) {
    fun toList(): List<Surface> = listOf(fl, fr, rl, rr)
    fun count(s: Surface): Int = toList().count { it == s }

    /** Nombre de roues hors asphalte et hors vibreur (herbe, terre, sable, neige ou inconnu). */
    val wheelsOff: Int get() = toList().count { it != Surface.TARMAC && it != Surface.KERB }

    companion object {
        fun parse(bytes: ByteArray): TyreSurfaces {
            val c = CharArray(4) { (bytes[it].toInt() and 0xFF).toChar() }
            val raw = String(CharArray(4) { if (c[it] in ' '..'~') c[it] else '.' })
            return TyreSurfaces(Surface.of(c[0]), Surface.of(c[1]), Surface.of(c[2]), Surface.of(c[3]), raw)
        }
    }
}

/**
 * Contenu d'un paquet de télémétrie GT7 (formats 'A', 'B', '~' et 'C').
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
    /**
     * Niveau de carburant, unité interne de GT7 (capacité : 100 pour la plupart des voitures, 5 en kart, 0 en électrique,
     * quelle que soit la taille réelle du réservoir) : rien ne prouve que ce soient des litres (voir FuelUnits).
     */
    val fuelLevel: Float,
    val fuelCapacity: Float,
    /** Vitesse en mètres par seconde. */
    val speedMps: Float,
    /** Pression turbo brute : 1,0 = 0 bar ; 2,0 = +1 bar. */
    val turboBoost: Float,
    val oilPressure: Float,
    /** GT7 envoie toujours 85 : valeur non mesurée, à ne pas afficher. */
    val waterTemperature: Float,
    /** GT7 envoie toujours 110 : valeur non mesurée, à ne pas afficher. */
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
    /**
     * Documenté comme la place sur la grille (-1 après le départ). Essais PS5 (course hors ligne, Course Sport A) : suit
     * aussi la position en course, mais compte les voitures pendant le matchmaking. Lu uniquement via PositionTracker.
     */
    val preRacePosition: Int,
    /** Participants (baisse si une voiture se déconnecte) ; mêmes réserves que [preRacePosition]. */
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
    val format: PacketFormat = PacketFormat.A,
    /** Rotation de la voiture (quaternion). */
    val rotationX: Float = 0f,
    val rotationY: Float = 0f,
    val rotationZ: Float = 0f,
    val rotationW: Float = 1f,
    /** Vitesse de rotation de la voiture autour de chaque axe (rad/s). */
    val angularVelocityX: Float = 0f,
    val angularVelocityY: Float = 0f,
    val angularVelocityZ: Float = 0f,
    /** Vitesse de rotation des roues (rad/s). */
    val wheelSpeed: Wheels = Wheels.ZERO,
    /** Rayon des pneus (m). */
    val tyreRadius: Wheels = Wheels.ZERO,
    val suspensionHeight: Wheels = Wheels.ZERO,
    val clutchEngagement: Float = 0f,
    val rpmClutchToGearbox: Float = 0f,
    /** Rapports de boîte (7 premiers). */
    val gearRatios: List<Float> = emptyList(),
    /** Angle du volant en radians (formats B et ~ uniquement). */
    val steeringRad: Float? = null,
    val sway: Float? = null,
    val heave: Float? = null,
    val surge: Float? = null,
    /** Pédales "filtrées" 0..255 (format ~ uniquement). */
    val throttleFiltered: Int? = null,
    val brakeFiltered: Int? = null,
    /** Type de voiture (format ~ ; 4 = électrique d'après PDTools). */
    val carType: Int? = null,
    /** Récupération d'énergie (format ~ ; voitures électriques/hybrides). */
    val energyRecovery: Float? = null,
    /** Surface sous chaque pneu (format C uniquement). */
    val surfaces: TyreSurfaces? = null,
    /**
     * Chrono du tour en cours en millisecondes (format C uniquement, 0x15C). Valeur brute de GT7 : son comportement
     * (départ, stand, pause) reste à valider sur PS5 avant tout usage à la radio.
     */
    val currentLapTimeMs: Int? = null,
    /** Angle des roues avant gauche et droite, en radians (format C uniquement). */
    val frontWheelAngleLeftRad: Float? = null,
    val frontWheelAngleRightRad: Float? = null,
    /** Empattement en mètres (format C uniquement). */
    val wheelBaseM: Float? = null,
    /** Catégorie de la voiture, texte brut de GT7 (« GR3 », « GRX »… ; format C uniquement). */
    val carCategory: String? = null,
) {
    val tyreTemps: Wheels get() = Wheels(tyreTempFrontLeft, tyreTempFrontRight, tyreTempRearLeft, tyreTempRearRight)

    /** Pression turbo en bar (relative), null si la voiture n'a pas de turbo. */
    val turboBar: Float? get() = if (hasTurbo) turboBoost - 1f else null

    /** Rapport conseillé par l'assistance, null s'il n'y en a pas (GT7 envoie 15). */
    val suggestedGearOrNull: Int? get() = suggestedGear.takeIf { it in 1..14 }

    val isHandbrake: Boolean get() = flags and FLAG_HANDBRAKE != 0
    val areLightsOn: Boolean get() = flags and FLAG_LIGHTS != 0
    /**
     * Bits « pleins phares » et « feux de croisement » de 0x8E (gt7-udp, PDTools). Ce sont des **états** transmis par GT7,
     * pas des événements de bouton : leur lien avec une touche de la DualSense reste à démontrer (issue #38).
     */
    val isHighBeam: Boolean get() = flags and FLAG_HIGH_BEAM != 0
    val isLowBeam: Boolean get() = flags and FLAG_LOW_BEAM != 0
    val isElectric: Boolean get() = fuelCapacity <= 0f || carType == 4

    /**
     * Glissement de chaque roue : vitesse de la roue (rotation × rayon) / vitesse de la voiture.
     * 1,0 = adhérence ; < 1 = roue qui bloque au freinage ; > 1 = patinage à l'accélération.
     * null sous 20 km/h (rapport non significatif).
     */
    val wheelSlip: Wheels?
        get() {
            if (speedMps < 5.5f) return null
            fun slip(rps: Float, r: Float) = if (r > 0f) kotlin.math.abs(rps) * r / speedMps else 1f
            return Wheels(
                slip(wheelSpeed.fl, tyreRadius.fl),
                slip(wheelSpeed.fr, tyreRadius.fr),
                slip(wheelSpeed.rl, tyreRadius.rl),
                slip(wheelSpeed.rr, tyreRadius.rr),
            )
        }
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

/** Décodage d'un paquet GT7 déchiffré (offsets du format 'A', puis extensions B, ~ et C ; little-endian). */
object TelemetryParser {

    private fun wheels(b: ByteBuffer, offset: Int) =
        Wheels(b.getFloat(offset), b.getFloat(offset + 4), b.getFloat(offset + 8), b.getFloat(offset + 12))

    /** Déchiffre puis décode. `null` si le paquet n'est pas un paquet GT7 valide. */
    fun parseEncrypted(data: ByteArray, length: Int = data.size): TelemetryPacket? =
        GT7Crypto.decrypt(data, length)?.let(::parseDecrypted)

    fun parseDecrypted(plain: ByteArray): TelemetryPacket? {
        if (plain.size < GT7Crypto.PACKET_SIZE_A) return null
        val b = ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getInt(0x00) != GT7Crypto.MAGIC) return null
        val gear = b.get(0x90).toInt() and 0xFF
        val format = PacketFormat.forLength(plain.size) ?: PacketFormat.A
        val ext = plain.size >= PacketFormat.B.size
        val full = plain.size >= PacketFormat.TILDE.size
        val c = plain.size >= PacketFormat.C.size
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
            format = format,
            rotationX = b.getFloat(0x1C),
            rotationY = b.getFloat(0x20),
            rotationZ = b.getFloat(0x24),
            rotationW = b.getFloat(0x28),
            angularVelocityX = b.getFloat(0x2C),
            angularVelocityY = b.getFloat(0x30),
            angularVelocityZ = b.getFloat(0x34),
            wheelSpeed = wheels(b, 0xA4),
            tyreRadius = wheels(b, 0xB4),
            suspensionHeight = wheels(b, 0xC4),
            clutchEngagement = b.getFloat(0xF8),
            rpmClutchToGearbox = b.getFloat(0xFC),
            gearRatios = List(7) { b.getFloat(0x104 + 4 * it) },
            steeringRad = if (ext) b.getFloat(0x128) else null,
            sway = if (ext) b.getFloat(0x130) else null,
            heave = if (ext) b.getFloat(0x134) else null,
            surge = if (ext) b.getFloat(0x138) else null,
            throttleFiltered = if (full) b.get(0x13C).toInt() and 0xFF else null,
            brakeFiltered = if (full) b.get(0x13D).toInt() and 0xFF else null,
            carType = if (full) b.get(0x13E).toInt() and 0xFF else null,
            energyRecovery = if (full) b.getFloat(0x150) else null,
            surfaces = if (c) TyreSurfaces.parse(plain.copyOfRange(0x158, 0x15C)) else null,
            currentLapTimeMs = if (c) b.getInt(0x15C) else null,
            frontWheelAngleLeftRad = if (c) b.getFloat(0x160) else null,
            frontWheelAngleRightRad = if (c) b.getFloat(0x164) else null,
            wheelBaseM = if (c) b.getFloat(0x168) else null,
            carCategory = if (c) category(plain, 0x16C) else null,
        )
    }

    /** Texte ASCII terminé par un octet nul ; null s'il est vide ou contient un caractère non imprimable. */
    private fun category(plain: ByteArray, offset: Int): String? {
        val bytes = plain.copyOfRange(offset, offset + 4).takeWhile { it != 0.toByte() }
        if (bytes.isEmpty() || bytes.any { it < 0x20 || it > 0x7E }) return null
        return String(bytes.toByteArray(), Charsets.US_ASCII).trim().ifEmpty { null }
    }
}
