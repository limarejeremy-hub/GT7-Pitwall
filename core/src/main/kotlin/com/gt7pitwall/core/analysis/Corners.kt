package com.gt7pitwall.core.analysis

import com.gt7pitwall.core.telemetry.TelemetryPacket
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Un instant du tour, compact : uniquement des valeurs transmises par GT7 (ou leur dérivée immédiate). Les champs absents
 * du format reçu restent null : aucune analyse ne s'appuie alors dessus.
 */
data class LapSample(
    val tick: Int,
    val x: Float,
    val z: Float,
    val speedMps: Float,
    /** Pédales 0..255. */
    val throttle: Int,
    val brake: Int,
    /** Lacet (rad/s). */
    val yawRate: Float,
    /** Angle moyen des roues avant (rad, format C), sinon null. */
    val wheelAngle: Float?,
    /** Glissement le plus bas à l'avant (blocage < 1) et le plus haut à l'arrière (patinage > 1), null sous 20 km/h. */
    val frontSlip: Float?,
    val rearSlip: Float?,
    val tcs: Boolean,
    /**
     * Deux roues ou plus sur une surface connue hors piste (herbe, terre, sable, neige ; format C). Faux : surfaces
     * connues, sur la piste. null : pas de surface (formats A, B, ~) ou codes inconnus sous deux roues ou plus.
     */
    val offTrack: Boolean?,
    /** Surface sous chaque roue (format C), conservée telle quelle : un vibreur sous une seule roue reste visible. */
    val surfaces: com.gt7pitwall.core.telemetry.TyreSurfaces? = null,
    /** Glissement de chaque roue (FL, FR, RL, RR), null sous 20 km/h. */
    val slips: com.gt7pitwall.core.telemetry.Wheels? = null,
    val gear: Int = 0,
    val rpm: Float = 0f,
    /** Accélération longitudinale obtenue (m/s²), d'après la vitesse des paquets successifs ; null au premier paquet. */
    val longAccel: Float? = null,
    /** Température des quatre pneus (°C). */
    val tyreTemps: com.gt7pitwall.core.telemetry.Wheels? = null,
    /** Contrôle de stabilité (ASM) actif : la voiture est corrigée, ce n'est pas une sortie de virage comparable. */
    val asm: Boolean = false,
    /** Couple moteur normalisé (0x154, formats ~ et C, **expérimental**), null ailleurs. */
    val engineTorque: Float? = null,
    /**
     * Frein réellement appliqué (0x13D, formats ~ et C, 0..255), d'après la communauté : après l'ABS, distinct de la
     * pédale ([brake], 0x92). Nul en replay selon les mesures publiées. Expérimental.
     */
    val brakeApplied: Int? = null,
)

/**
 * Enregistre le tour en cours, paquet par paquet, depuis le passage de la ligne. Mêmes garde-fous que [SectorTrace] : une
 * pause, un chargement, une téléportation ou une coupure rendent le tour inexploitable (jamais recollé).
 */
class LapRecorder {
    private val samples = ArrayList<LapSample>(8_000)
    private var last: TelemetryPacket? = null
    private var tick = 0
    var valid = false
        private set

    fun startLap(p: TelemetryPacket) {
        samples.clear()
        tick = 0
        distanceM = 0f
        last = p
        valid = !p.isPaused && !p.isLoading
        if (valid) samples += sample(p)
    }

    fun invalidate() {
        valid = false
    }

    fun add(p: TelemetryPacket) {
        val prev = last ?: return
        if (!valid) return
        if (p.isPaused || p.isLoading) {
            valid = false
            return
        }
        val dId = p.packetId - prev.packetId
        if (dId <= 0) return
        val step = hypot(p.positionX - prev.positionX, p.positionZ - prev.positionZ)
        if (dId > SectorTrace.MAX_PACKET_GAP || step > SectorTrace.MAX_STEP_PER_PACKET * dId) {
            valid = false
            return
        }
        tick += dId
        val accel = (p.speedMps - prev.speedMps) * 60f / dId
        last = p
        distanceM += step
        samples += sample(p, accel)
    }

    /** Distance parcourue depuis la ligne (m). */
    var distanceM = 0f
        private set

    /** Échantillons du tour en cours (prolongement du tour précédent au-delà de la ligne). */
    fun current(): List<LapSample> = if (valid) samples.toList() else emptyList()

    /** Le tour terminé, s'il a été suivi en entier. */
    fun finish(): List<LapSample>? = if (valid && samples.size > MIN_SAMPLES) samples.toList() else null

    private fun sample(p: TelemetryPacket, longAccel: Float? = null): LapSample {
        val slip = p.wheelSlip
        val angle = listOfNotNull(p.frontWheelAngleLeftRad, p.frontWheelAngleRightRad).takeIf { it.isNotEmpty() }?.average()?.toFloat()
        val off = p.surfaces?.let { s ->
            when {
                s.wheelsOff >= 2 -> true
                s.unknownWheels + s.wheelsOff >= 2 -> null // surface incertaine : ni sortie ni piste confirmée
                else -> false
            }
        }
        return LapSample(
            tick, p.positionX, p.positionZ, p.speedMps, p.throttle, p.brake, p.angularVelocityY,
            angle, slip?.let { minOf(it.fl, it.fr) }, slip?.let { maxOf(it.rl, it.rr) }, p.isTcsActive,
            off, p.surfaces, slip, p.currentGear, p.engineRpm, longAccel?.takeIf { it.isFinite() }, p.tyreTemps, p.isAsmActive,
            p.engineTorqueNorm, p.brakeFiltered,
        )
    }

    companion object {
        const val MIN_SAMPLES = 600 // 10 s
    }
}

/**
 * Virage reconnu sur le tracé de référence : bornes en mètres depuis la ligne ([entryM], [exitM]), point de vitesse
 * minimale ([apexM] : corde **estimée**, pas une corde officielle), et zone d'analyse ([zoneStartM]..[zoneEndM]) qui
 * comprend le freinage avant et la réaccélération après.
 */
data class Corner(
    val number: Int,
    val entryM: Float,
    val apexM: Float,
    val exitM: Float,
    val zoneStartM: Float,
    val zoneEndM: Float,
)

/**
 * Carte des virages d'un circuit, construite à partir d'un tour propre : tracé tous les [STEP_M] mètres et virages
 * reconnus par la courbure (cap qui tourne) et la vitesse. Les virages pris à fond sans ralentir ne sont pas retenus :
 * il n'y a rien à y corriger.
 */
class CornerMap(val xs: FloatArray, val zs: FloatArray, val corners: List<Corner>) {
    val lengthM: Float get() = (xs.size - 1) * STEP_M

    companion object {
        const val STEP_M = 5f

        /** Rayon de courbure maximal d'un virage (m). */
        const val MAX_RADIUS_M = 220f
        const val MIN_CORNER_M = 15f
        const val MERGE_GAP_M = 30f
        const val MIN_SPEED_DROP_KMH = 8f
        const val APPROACH_M = 220f
        const val EXIT_M = 160f

        /** Tracé régulier (un point tous les [STEP_M] m) à partir des échantillons d'un tour. */
        private fun resample(samples: List<LapSample>): Triple<FloatArray, FloatArray, FloatArray>? {
            val xs = ArrayList<Float>()
            val zs = ArrayList<Float>()
            val vs = ArrayList<Float>()
            xs += samples[0].x; zs += samples[0].z; vs += samples[0].speedMps
            var acc = 0f
            for (i in 1 until samples.size) {
                val a = samples[i - 1]
                val b = samples[i]
                val d = hypot(b.x - a.x, b.z - a.z)
                if (d <= 0f) continue
                var from = 0f
                while (acc + (d - from) >= STEP_M) {
                    val need = STEP_M - acc
                    from += need
                    val f = from / d
                    xs += a.x + (b.x - a.x) * f
                    zs += a.z + (b.z - a.z) * f
                    vs += a.speedMps + (b.speedMps - a.speedMps) * f
                    acc = 0f
                }
                acc += d - from
            }
            if (xs.size < 60) return null
            return Triple(xs.toFloatArray(), zs.toFloatArray(), vs.toFloatArray())
        }

        /** Construit la carte ; null si le tour est trop court ou si aucun virage n'est reconnaissable. */
        fun build(samples: List<LapSample>): CornerMap? {
            val (xs, zs, vs) = resample(samples) ?: return null
            val n = xs.size
            // Cap entre points successifs, déroulé (pas de saut de 2π).
            val heading = DoubleArray(n - 1) { atan2((zs[it + 1] - zs[it]).toDouble(), (xs[it + 1] - xs[it]).toDouble()) }
            for (i in 1 until heading.size) {
                while (heading[i] - heading[i - 1] > PI) heading[i] -= 2 * PI
                while (heading[i] - heading[i - 1] < -PI) heading[i] += 2 * PI
            }
            // Courbure lissée sur ±10 m.
            val k = 2
            val curv = DoubleArray(heading.size) { i ->
                val a = (i - k).coerceAtLeast(0)
                val b = (i + k).coerceAtMost(heading.size - 1)
                if (b == a) 0.0 else (heading[b] - heading[a]) / ((b - a) * STEP_M)
            }
            val kMin = 1.0 / MAX_RADIUS_M
            // Plages de forte courbure (même sens), fusionnées si elles sont proches.
            val runs = mutableListOf<IntRange>()
            var start = -1
            for (i in curv.indices) {
                val on = abs(curv[i]) >= kMin
                if (on && start < 0) start = i
                if ((!on || i == curv.lastIndex) && start >= 0) {
                    val end = if (on) i else i - 1
                    if ((end - start + 1) * STEP_M >= MIN_CORNER_M) {
                        val prev = runs.lastOrNull()
                        val sameSign = prev != null && curv[prev.first] * curv[start] > 0
                        if (prev != null && sameSign && (start - prev.last) * STEP_M <= MERGE_GAP_M) {
                            runs[runs.lastIndex] = prev.first..end
                        } else {
                            runs += start..end
                        }
                    }
                    start = -1
                }
            }
            // Un virage où la voiture ralentit nettement ; un virage pris à fond n'a rien à corriger.
            val kept = runs.filter { r ->
                val approach = (r.first - (APPROACH_M / STEP_M).toInt()).coerceAtLeast(0)
                val vmax = (approach..r.last).maxOf { vs[it] }
                val vmin = (r.first..r.last).minOf { vs[it] }
                (vmax - vmin) * 3.6f >= MIN_SPEED_DROP_KMH
            }
            if (kept.isEmpty()) return null
            val corners = kept.mapIndexed { idx, r ->
                val apex = (r.first..r.last).minBy { vs[it] }
                val prevExit = if (idx > 0) kept[idx - 1].last * STEP_M else 0f
                // Le dernier virage se termine sur la ligne droite du tour suivant : sa zone continue au-delà de la ligne.
                val nextEntry = if (idx < kept.lastIndex) kept[idx + 1].first * STEP_M else (n - 1) * STEP_M + kept[0].first * STEP_M
                val entry = r.first * STEP_M
                val exit = (r.last + 1) * STEP_M
                Corner(
                    number = idx + 1,
                    entryM = entry,
                    apexM = apex * STEP_M,
                    exitM = exit,
                    zoneStartM = maxOf(prevExit, entry - APPROACH_M, 0f),
                    zoneEndM = minOf(nextEntry, exit + EXIT_M),
                )
            }
            return CornerMap(xs, zs, corners)
        }
    }
}

/** Un échantillon placé sur le tracé de référence : distance depuis la ligne et écart latéral (m). */
data class Placed(val s: LapSample, val m: Float, val lateral: Float)

/**
 * Place chaque échantillon d'un tour sur le tracé de référence (point le plus proche, en avançant). Un échantillon à plus
 * de [MAX_OFFSET_M] du tracé (autre circuit, sortie large, téléportation) n'est pas placé : la zone concernée devient
 * incomplète, jamais devinée.
 */
object TrackProjection {
    const val MAX_OFFSET_M = 25f

    fun project(map: CornerMap, samples: List<LapSample>): List<Placed?> {
        val n = map.xs.size
        var idx = 0
        return samples.map { s ->
            var best = -1
            var bestD = Float.MAX_VALUE
            val from = (idx - 4).coerceAtLeast(0)
            val to = (idx + 40).coerceAtMost(n - 2)
            for (i in from..to) {
                val d = hypot(s.x - map.xs[i], s.z - map.zs[i])
                if (d < bestD) {
                    bestD = d
                    best = i
                }
            }
            if (best < 0 || bestD > MAX_OFFSET_M) return@map null
            idx = best
            // Projection sur le segment [best, best+1] : position le long du tracé et écart latéral signé.
            val ax = map.xs[best]; val az = map.zs[best]
            val bx = map.xs[best + 1]; val bz = map.zs[best + 1]
            val tx = bx - ax; val tz = bz - az
            val len2 = tx * tx + tz * tz
            val f = if (len2 > 0f) (((s.x - ax) * tx + (s.z - az) * tz) / len2).coerceIn(-0.5f, 1.5f) else 0f
            val lat = if (len2 > 0f) ((s.z - az) * tx - (s.x - ax) * tz) / kotlin.math.sqrt(len2) else 0f
            Placed(s, (best + f) * CornerMap.STEP_M, lat)
        }
    }

    /** Écart médian au tracé (m) : un tour d'un autre circuit ou d'une autre configuration ne s'y superpose pas. */
    fun medianOffset(map: CornerMap, samples: List<LapSample>): Float? {
        val placed = project(map, samples)
        val missing = placed.count { it == null }
        if (missing > placed.size / 10) return null
        val offs = placed.filterNotNull().map { abs(it.lateral) }.sorted()
        return offs.getOrNull(offs.size / 2)
    }
}

/**
 * Passage dans un virage, mesuré. Distances en mètres depuis la ligne sur le tracé de référence ; null quand la mesure
 * n'existe pas (pas de freinage, champ absent du format reçu).
 */
data class CornerPass(
    val corner: Int,
    val lap: Int,
    /** Temps passé dans la zone (freinage, virage, réaccélération), d'après l'horloge des paquets. */
    val timeMs: Int,
    val brakeStartM: Float?,
    /** Pression maximale du frein (0..1) et montée en pression (ms de 15 % à 80 % du maximum). */
    val brakePeak: Float?,
    val brakeRampMs: Int?,
    val entryKmh: Float,
    val minKmh: Float,
    val minAtM: Float,
    val throttleOnM: Float?,
    val exitKmh: Float,
    val lockup: Boolean?,
    val wheelspin: Boolean?,
    val tcsShare: Float,
    /** Écart latéral au tracé de référence au point de vitesse minimale (m, signé). */
    val apexLateralM: Float,
    /**
     * Rotation réelle / rotation attendue d'après l'angle des roues (format C) au milieu du virage : < 1 sous-virage
     * probable, > 1 survirage probable. null sans angle des roues.
     */
    val rotationRatio: Float?,
    val offTrack: Boolean?,
    /** Part de la zone avec au moins une roue sur un vibreur (format C) ; null sans surface. */
    val kerbShare: Float? = null,
    /** Part de la zone où la surface d'au moins une roue est inconnue ou absente. */
    val surfaceUnknownShare: Float = 1f,
)

object CornerMeasure {
    const val BRAKE_ON = 38 // 15 %
    const val THROTTLE_ON = 128 // 50 %
    const val MAX_GAP_M = 20f
    const val LOCK_SLIP = 0.85f
    const val SPIN_SLIP = 1.15f
    /** Au-delà de 10 % de la zone sans surface lisible, l'absence de sortie n'est pas prouvée. */
    const val MAX_UNKNOWN_SHARE = 0.1f

    /**
     * Mesures de chaque virage d'un tour complet ; un virage dont la zone n'est pas couverte en entier (trajectoire
     * incomplète, échantillons hors du tracé) est absent du résultat.
     */
    fun passes(map: CornerMap, lap: Int, samples: List<LapSample>, wheelBaseM: Float? = null, continuation: List<LapSample> = emptyList()): List<CornerPass> {
        // Début du tour suivant : placé après la fin du tracé, chronologie prolongée.
        val lastTick = samples.lastOrNull()?.tick ?: 0
        val next = TrackProjection.project(map, continuation.map { it.copy(tick = it.tick + lastTick + 1) })
            .map { it?.copy(m = it.m + map.lengthM) }
        val placed = TrackProjection.project(map, samples) + next
        return map.corners.mapNotNull { c -> pass(c, lap, placed, wheelBaseM) }
    }

    fun pass(c: Corner, lap: Int, placed: List<Placed?>, wheelBaseM: Float? = null): CornerPass? {
        // Échantillons de la zone, dans l'ordre ; la zone doit être couverte du début à la fin, sans trou.
        val first = placed.indexOfFirst { it != null && it.m >= c.zoneStartM }
        if (first < 0) return null
        val zone = ArrayList<Placed>()
        var i = first
        while (i < placed.size) {
            val p = placed[i] ?: return null
            if (p.m > c.zoneEndM) break
            if (zone.isNotEmpty() && p.m - zone.last().m > MAX_GAP_M) return null
            if (zone.isNotEmpty() && p.m < zone.last().m - MAX_GAP_M) return null
            zone += p
            i++
        }
        if (zone.size < 10) return null
        if (zone.first().m > c.zoneStartM + MAX_GAP_M || zone.last().m < c.zoneEndM - MAX_GAP_M) return null
        val timeMs = ((zone.last().s.tick - zone.first().s.tick) * 1000L / 60L).toInt()

        val core = zone.filter { it.m in c.entryM..c.exitM }
        if (core.isEmpty()) return null
        val minP = core.minBy { it.s.speedMps }
        val beforeApex = zone.filter { it.m <= minP.m }
        val braking = beforeApex.indexOfFirst { it.s.brake >= BRAKE_ON }
        val brakeStart = beforeApex.getOrNull(braking)
        val brakePeak = if (braking >= 0) beforeApex.drop(braking).maxOf { it.s.brake } / 255f else null
        val ramp = if (braking >= 0 && brakePeak != null) {
            val target = brakePeak * 255f * 0.8f
            beforeApex.drop(braking).firstOrNull { it.s.brake >= target }?.let { ((it.s.tick - brakeStart!!.s.tick) * 1000L / 60L).toInt() }
        } else {
            null
        }
        val afterApex = zone.filter { it.m >= minP.m }
        // Réaccélération : accélérateur à plus de 50 % pendant au moins 1/6 s.
        var throttleOn: Float? = null
        for (j in afterApex.indices) {
            val window = afterApex.subList(j, minOf(afterApex.size, j + 10))
            if (window.size == 10 && window.all { it.s.throttle >= THROTTLE_ON }) {
                throttleOn = afterApex[j].m
                break
            }
        }
        val entry = zone.minBy { abs(it.m - c.entryM) }
        val exit = zone.last()
        val slips = zone.filter { it.s.frontSlip != null }
        val lock = if (slips.isEmpty()) null else consecutive(zone) { it.s.brake >= BRAKE_ON && (it.s.frontSlip ?: 1f) < LOCK_SLIP } >= 6
        val spin = if (slips.isEmpty()) null else consecutive(afterApex) { it.s.throttle >= THROTTLE_ON && (it.s.rearSlip ?: 1f) > SPIN_SLIP } >= 6
        // Rotation : lacet mesuré contre lacet attendu (vitesse × tan(angle des roues) / empattement), au cœur du virage.
        val rotation = wheelBaseM?.takeIf { it > 1f }?.let { wb ->
            val r = core.mapNotNull { p ->
                val a = p.s.wheelAngle ?: return@mapNotNull null
                if (abs(a) < 0.03f || p.s.speedMps < 8f) return@mapNotNull null
                val expected = p.s.speedMps * kotlin.math.tan(a) / wb
                (p.s.yawRate / expected).takeIf { it.isFinite() }
            }
            if (r.size >= 10) r.sorted()[r.size / 2] else null
        }
        // Sortie confirmée seulement sur des surfaces connues ; zone trop souvent sans surface lisible : inconnu, jamais « propre ».
        val unknownShare = zone.count { it.s.surfaces == null || it.s.surfaces.unknownWheels > 0 }.toFloat() / zone.size
        val off = when {
            zone.none { it.s.surfaces != null } -> null
            consecutive(zone) { it.s.offTrack == true } >= 15 -> true
            unknownShare > MAX_UNKNOWN_SHARE -> null
            else -> false
        }
        val kerb = if (zone.none { it.s.surfaces != null }) null else zone.count { (it.s.surfaces?.kerbWheels ?: 0) > 0 }.toFloat() / zone.size
        return CornerPass(
            corner = c.number,
            lap = lap,
            timeMs = timeMs,
            brakeStartM = brakeStart?.m,
            brakePeak = brakePeak,
            brakeRampMs = ramp,
            entryKmh = entry.s.speedMps * 3.6f,
            minKmh = minP.s.speedMps * 3.6f,
            minAtM = minP.m,
            throttleOnM = throttleOn,
            exitKmh = exit.s.speedMps * 3.6f,
            lockup = lock,
            wheelspin = spin,
            tcsShare = zone.count { it.s.tcs }.toFloat() / zone.size,
            apexLateralM = minP.lateral,
            rotationRatio = rotation,
            offTrack = off,
            kerbShare = kerb,
            surfaceUnknownShare = unknownShare,
        )
    }

    private fun consecutive(list: List<Placed>, cond: (Placed) -> Boolean): Int {
        var best = 0
        var run = 0
        for (p in list) {
            run = if (cond(p)) run + 1 else 0
            if (run > best) best = run
        }
        return best
    }

    fun meters(m: Float): Int = m.roundToInt()
}
