package com.gt7pitwall.core

import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.telemetry.TelemetryPacket
import com.gt7pitwall.core.telemetry.TelemetryParser
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Circuit « stade » pour tester le coaching par virage : ligne droite de 600 m, épingle de 50 m de rayon (virage 1),
 * ligne droite retour, virage de 90 m de rayon (virage 2). Le pilote simulé freine, enroule et réaccélère ; chaque tour
 * peut modifier un virage (freinage plus tôt ou plus tard, vitesse minimale, réaccélération retardée, vitesse générale).
 */
class CornerSim(
    var controller: RaceController? = null,
    val totalLaps: Int = 0,
    val carCode: Int = 3607,
) {
    data class Style(
        /** Mètres : positif = freine plus tard que la normale, négatif = plus tôt. */
        val brakeShiftM: Float = 0f,
        /** m/s ajoutés à la vitesse dans le virage (négatif = plus lent). */
        val cornerSpeedDelta: Float = 0f,
        /** Mètres de plus avant de remettre les gaz après le virage. */
        val throttleDelayM: Float = 0f,
        /** Glissement ajouté aux roues arrière en réaccélération (0,2 = 1,2 au lieu de 1,0). */
        val exitSpin: Float = 0f,
        /** Accélération obtenue en sortie (1 = normale) : moins de motricité, moins d'accélération. */
        val exitAccel: Float = 1f,
    )

    private val straight = 600f
    private val r1 = 50f
    val length = 2 * straight + (2 * PI * r1).toFloat()
    private val c1Start = straight
    private val c1End = straight + (PI * r1).toFloat()
    private val c2Start = c1End + straight
    private val c2End = length

    private val vMax = 70f
    private val v1 = 22f
    private val v2 = 27f
    private val decel = 12f
    private val accel = 6f

    var lap = 0
        private set
    private var s = length - 30f // sur la grille, juste avant la ligne
    private var id = 0
    private var lapTicks = 0
    var lastLapMs = -1
        private set
    private var bestLapMs = -1
    var fuel = 100f
    val said = mutableListOf<EngineerMessage>()
    val texts: List<String> get() = said.map { it.text }
    val packets = mutableListOf<TelemetryPacket>()
    var keepPackets = false
    /** Paquets envoyés à un autre consommateur (le coach seul, sans contrôleur). */
    var sink: ((TelemetryPacket) -> Unit)? = null

    fun position(at: Float): Pair<Float, Float> {
        val m = ((at % length) + length) % length
        return when {
            m < c1Start -> m to 0f
            m < c1End -> {
                val a = (m - c1Start) / r1 - (PI / 2).toFloat()
                (straight + r1 * cos(a)) to (r1 + r1 * sin(a))
            }
            m < c2Start -> (straight - (m - c1End)) to (2 * r1)
            else -> {
                val a = (m - c2Start) / r1 + (PI / 2).toFloat()
                (r1 * cos(a)) to (r1 + r1 * sin(a))
            }
        }
    }

    /** Vitesse cible à la distance [m] avec le style donné par virage (index 0 : virage 1). */
    private fun speedAt(m: Float, styles: List<Style>): Float {
        fun corner(start: Float, end: Float, v: Float, st: Style): Float? {
            val vc = v + st.cornerSpeedDelta
            val brakeDist = (vMax * vMax - vc * vc) / (2 * decel)
            val brakeAt = start - brakeDist + st.brakeShiftM
            // Freinage : de vMax à vc entre brakeAt et l'entrée (décélération ajustée au décalage).
            if (m in brakeAt..start) {
                val f = (m - brakeAt) / (start - brakeAt)
                return sqrt(vMax * vMax + (vc * vc - vMax * vMax) * f)
            }
            if (m in start..end + st.throttleDelayM) return vc
            val after = m - end - st.throttleDelayM
            // Moindre motricité sur les 150 premiers mètres de la sortie seulement.
            val effective = minOf(after, 150f) * st.exitAccel + maxOf(after - 150f, 0f)
            if (after in 0f..2000f) return minOf(vMax, sqrt(vc * vc + 2 * accel * effective))
            return null
        }
        val a = corner(c1Start, c1End, v1, styles[0])
        val b = corner(c2Start, c2End, v2, styles[1])
        // Sortie du virage 2 : continue sur la ligne droite du tour suivant.
        val b2 = if (m < c1Start) corner(c2Start - length, c2End - length, v2, styles[1]) else null
        return listOfNotNull(a, b, b2, vMax).min()
    }

    private fun pedals(m: Float, v: Float, styles: List<Style>): Pair<Int, Int> {
        val vPrev = speedAt(m - 2f, styles)
        val inCorner = m in c1Start..c1End + styles[0].throttleDelayM || m >= c2Start || m <= styles[1].throttleDelayM
        return when {
            v < vPrev - 0.05f -> 0 to 200 // freinage
            inCorner -> 60 to 0 // filet de gaz dans le virage
            else -> 255 to 0
        }
    }

    private fun curvature(m: Float): Float = when {
        m in c1Start..c1End -> 1f / r1
        m >= c2Start -> 1f / r1
        else -> 0f
    }

    /** Glissement arrière : patinage en réaccélération, dans les 150 m qui suivent un virage au style concerné. */
    private fun rearSlip(m: Float, throttle: Int): Float {
        if (throttle < 255) return 1f
        val st = lastStyles
        val s1 = st.getOrNull(0) ?: return 1f
        val s2 = st.getOrNull(1) ?: return 1f
        return when {
            m in c1End..c1End + 150f -> 1f + s1.exitSpin
            m < 150f -> 1f + s2.exitSpin
            else -> 1f
        }
    }

    private fun send(v: Float, throttle: Int, brake: Int) {
        val (x, z) = position(s)
        val radius = 0.33f
        val rear = rearSlip(s % length, throttle)
        val plain = TestPackets.plain(
            lap = lap, totalLaps = totalLaps, fuel = fuel, capacity = 100f, speedMps = v,
            lastLapMs = lastLapMs, bestLapMs = bestLapMs, carCode = carCode, packetId = ++id,
            posX = x, posZ = z, brake = brake, throttle = throttle, yawRate = v * curvature(s % length),
            wheelRps = floatArrayOf(v / radius, v / radius, v * rear / radius, v * rear / radius), tyreRadius = radius,
        )
        val p = TelemetryParser.parseDecrypted(plain)!!
        if (keepPackets) packets += p
        sink?.invoke(p)
        controller?.let { said += it.onPacket(p) }
    }

    /** Un tour complet avec le style donné (virage 1, virage 2), départ lancé. */
    fun lap(styles: List<Style> = listOf(Style(), Style()), speedFactor: Float = 1f) {
        lastStyles = styles
        lastSpeed = speedFactor
        val target = lap + 1
        while (lap < target) {
            val m = s % length
            val v = speedAt(m, styles) * speedFactor
            val (t, b) = pedals(m, v, styles)
            s += v / 60f
            lapTicks++
            if (s >= length) {
                s -= length
                if (lap >= 1) {
                    // Quelques millisecondes d'écart : GT7 ne change le « dernier tour » que si le chrono change.
                    lastLapMs = lapTicks * 1000 / 60 + (lap * 7) % 11
                    if (bestLapMs < 0 || lastLapMs < bestLapMs) bestLapMs = lastLapMs
                }
                lap++
                lapTicks = 0
                fuel -= 3f
            }
            send(v, t, b)
        }
    }

    /** Départ : quelques secondes à l'arrêt, puis franchissement de la ligne. */
    fun start() {
        repeat(120) { send(0f, 0, 0) }
        while (lap == 0) {
            s += 20f / 60f
            if (s >= length) {
                s -= length
                lap++
                lapTicks = 0
            }
            send(20f, 255, 0)
        }
    }

    /** Laisse passer le chrono officiel (3 s de ligne droite). */
    fun settle() {
        repeat(400) {
            val m = s % length
            val v = speedAt(m, lastStyles) * lastSpeed
            val (t, b) = pedals(m, v, lastStyles)
            s += v / 60f
            lapTicks++
            send(v, t, b)
        }
    }

    private var lastStyles = listOf(Style(), Style())
    private var lastSpeed = 1f

    fun say(text: String) {
        controller?.let { said += it.utterance(text) }
    }
}
