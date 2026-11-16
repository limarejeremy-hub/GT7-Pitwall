package com.gt7pitwall.core.display

import com.gt7pitwall.core.session.LapRecord
import com.gt7pitwall.core.session.LiveLap
import com.gt7pitwall.core.telemetry.TelemetryPacket
import kotlin.math.roundToInt

/**
 * Tableau de bord « GT3 » (V507) : ce qu'affiche l'écran de course à ~30 images/s, calculé ici en Kotlin pur (testé).
 *
 * Tout vient du paquet GT7 ou d'une mesure Pitwall, et chaque valeur absente reste null :
 * - LED de rupteur : seuils transmis par GT7 pour la voiture (début du témoin [TelemetryPacket.rpmAlertMin], rupteur
 *   [TelemetryPacket.rpmAlertMax]) ; sans seuils valides, pas de LED (jamais de seuil inventé) ;
 * - rapport engagé et rapport conseillé par GT7 ;
 * - chrono du tour en cours : GT7 s'il l'envoie (format récent), sinon mesuré par Pitwall (paquets à 60/s) ;
 * - delta et secteurs EN DIRECT : mesurés par Pitwall contre son meilleur tour complet de la session (secteurs =
 *   tiers de la distance, pas les secteurs officiels ; delta = estimation, la trajectoire varie un peu).
 */
data class GtDash(
    val live: Boolean,
    val paused: Boolean,
    val gear: String?,
    val suggestedGear: Int?,
    val speedKmh: Int?,
    val rpm: Int?,
    val leds: Leds,
    /** Pédales 0..1. */
    val throttle: Float?,
    val brake: Float?,
    val tcs: Boolean,
    val asm: Boolean,
    val lapTimeMs: Int?,
    val lapTimeSource: TimeSource,
    val deltaMs: Int?,
    /** Secteur en cours (1..3), null sans tour de référence. */
    val sector: Int?,
    /** Trois cases de secteur du tour en cours. */
    val sectors: List<SectorCell>,
    val lastLapMs: Int?,
    val bestLapMs: Int?,
    val waterC: Int?,
    val oilC: Int?,
    val turboBar: Float?,
) {
    enum class TimeSource(val label: String) { GT7("GT7"), PITWALL("mesuré Pitwall"), NONE("") }

    /**
     * Rampe de [total] LED : [lit] allumées (vert, puis jaune, puis rouge) ; [shift] : passer le rapport (toutes en
     * bleu, clignotantes). [available] faux : GT7 n'a pas donné de seuils pour cette voiture.
     */
    data class Leds(val total: Int, val lit: Int, val shift: Boolean, val available: Boolean) {
        /** Couleur de la LED [i] (0 = gauche) quand elle est allumée. */
        fun zone(i: Int): Zone = when {
            shift -> Zone.SHIFT
            i < total / 3 -> Zone.GREEN
            i < total * 2 / 3 -> Zone.YELLOW
            else -> Zone.RED
        }
    }

    enum class Zone { GREEN, YELLOW, RED, SHIFT }

    enum class SectorState { BEST, IMPROVED, SLOWER, CURRENT, PENDING }

    data class SectorCell(val index: Int, val ms: Int?, val state: SectorState)

    companion object {
        const val LED_COUNT = 15

        /** R pour la marche arrière (0 dans GT7), sinon le numéro ; null sans paquet. */
        fun gearLabel(gear: Int?): String? = when {
            gear == null -> null
            gear == 0 -> "R"
            gear in 1..14 -> gear.toString()
            else -> "N"
        }

        fun leds(p: TelemetryPacket?, count: Int = LED_COUNT): Leds {
            val lo = p?.rpmAlertMin ?: 0
            val hi = p?.rpmAlertMax ?: 0
            if (p == null || lo <= 0 || hi <= lo) return Leds(count, 0, shift = false, available = false)
            val rpm = p.engineRpm
            val shift = rpm >= hi || p.isRevLimiterAlert
            // Allumage progressif entre le début du témoin GT7 et le rupteur ; une LED de plus par tranche égale.
            val lit = if (shift) count else (((rpm - lo) / (hi - lo)) * count).toInt().coerceIn(0, count)
            return Leds(count, lit, shift, available = true)
        }

        /**
         * Secteurs du tour en cours : violet = meilleur de la session sur ce secteur, vert = meilleur que le tour de
         * référence, jaune = plus lent ; secteur en cours ; secteurs à venir.
         */
        fun sectors(live: LiveLap?, laps: List<LapRecord>, referenceLap: Int?): List<SectorCell> {
            val n = 3
            val bests = (0 until n).map { i -> laps.mapNotNull { it.sectorsMs?.getOrNull(i)?.takeIf { v -> v > 0 } }.minOrNull() }
            val ref = laps.firstOrNull { it.lapNumber == referenceLap }?.sectorsMs
            return (0 until n).map { i ->
                val ms = live?.splitsMs?.getOrNull(i)
                val state = when {
                    ms != null && bests[i] != null && ms <= bests[i]!! -> SectorState.BEST
                    ms != null && ref?.getOrNull(i) != null && ms < ref[i] -> SectorState.IMPROVED
                    ms != null && (bests[i] != null || ref != null) -> SectorState.SLOWER
                    ms != null -> SectorState.BEST
                    live?.sector == i + 1 -> SectorState.CURRENT
                    else -> SectorState.PENDING
                }
                SectorCell(i + 1, ms, state)
            }
        }

        fun of(p: TelemetryPacket?, live: Boolean, liveLap: LiveLap?, laps: List<LapRecord>): GtDash {
            val gt7Time = p?.currentLapTimeMs?.takeIf { it > 0 }
            val lapTime = gt7Time ?: liveLap?.elapsedMs
            return GtDash(
                live = live,
                paused = p?.isPaused == true,
                gear = gearLabel(p?.currentGear),
                suggestedGear = p?.suggestedGearOrNull?.takeIf { it != p.currentGear },
                speedKmh = p?.speedKmh?.roundToInt(),
                rpm = p?.engineRpm?.roundToInt()?.takeIf { it >= 0 },
                leds = leds(p),
                throttle = p?.let { (it.throttle / 255f).coerceIn(0f, 1f) },
                brake = p?.let { (it.brake / 255f).coerceIn(0f, 1f) },
                tcs = p?.isTcsActive == true,
                asm = p?.isAsmActive == true,
                lapTimeMs = lapTime,
                lapTimeSource = when {
                    gt7Time != null -> TimeSource.GT7
                    lapTime != null -> TimeSource.PITWALL
                    else -> TimeSource.NONE
                },
                deltaMs = liveLap?.deltaMs,
                sector = liveLap?.sector,
                sectors = sectors(liveLap, laps, liveLap?.referenceLap),
                lastLapMs = p?.lastLapMs?.takeIf { it > 0 },
                bestLapMs = p?.bestLapMs?.takeIf { it > 0 },
                waterC = p?.waterTemperature?.takeIf { it > 0f }?.roundToInt(),
                oilC = p?.oilTemperature?.takeIf { it > 0f }?.roundToInt(),
                turboBar = p?.turboBar,
            )
        }
    }
}
