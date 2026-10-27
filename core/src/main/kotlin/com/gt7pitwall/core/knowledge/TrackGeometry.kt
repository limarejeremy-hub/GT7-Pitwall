package com.gt7pitwall.core.knowledge

import org.json.JSONObject
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Signature géométrique d'une configuration de circuit : tracé de référence (un point tous les 20 m, dans le sens de
 * roulage), cadre englobant et identifiants gt7info des deux sens. Données : jbhoorasingh/gt7-datalogger-track-data
 * (CC0 ; captures dérivées de zetetos/gt-telemetry, MIT), rapprochées des identifiants gt7info par
 * `tools/track_signatures/build.py`.
 */
class TrackSignature(
    val name: String,
    /** Circuit gt7info dans le sens du tracé (null si ce sens n'a pas pu être rapproché). */
    val course: Int?,
    /** Circuit gt7info du sens inverse (même géométrie parcourue à l'envers), s'il existe. */
    val reverseCourse: Int?,
    val lengthM: Int,
    val minX: Float, val maxX: Float, val minZ: Float, val maxZ: Float,
    /** Tracé : x0, z0, x1, z1… (mètres, repère de la télémétrie GT7 : positions X et Z). */
    val path: FloatArray,
) {
    val points: Int get() = path.size / 2

    companion object {
        fun parseAll(json: String): List<TrackSignature> {
            val o = JSONObject(json)
            require(o.optString("format") == "gt7pitwall-track-signatures") { "format de signatures inconnu" }
            val a = o.getJSONArray("signatures")
            return (0 until a.length()).mapNotNull { i ->
                val r = a.getJSONObject(i)
                val box = r.getJSONArray("box")
                val p = r.getJSONArray("path")
                if (p.length() < 20 || p.length() % 2 != 0) return@mapNotNull null
                TrackSignature(
                    name = r.getString("name"),
                    course = r.optInt("course", 0).takeIf { !r.isNull("course") && it != 0 },
                    reverseCourse = r.optInt("reverse_course", 0).takeIf { !r.isNull("reverse_course") && it != 0 },
                    lengthM = r.getInt("length_m"),
                    minX = box.getDouble(0).toFloat(), maxX = box.getDouble(1).toFloat(),
                    minZ = box.getDouble(2).toFloat(), maxZ = box.getDouble(3).toFloat(),
                    path = FloatArray(p.length()) { p.getDouble(it).toFloat() },
                )
            }
        }
    }
}

/**
 * Reconnaissance du circuit par la géométrie, à partir des positions X/Z de la télémétrie, bien avant la fin du
 * premier tour (la longueur d'un tour complet n'est plus nécessaire).
 *
 * À chaque échantillon (tous les [STEP_M] mètres parcourus), chaque configuration candidate est jugée sur l'écart
 * entre la voiture et son tracé de référence :
 * - hors du cadre englobant (marge [BOX_MARGIN_M]) ou à plus de [HARD_DEV_M] du tracé : éliminée ;
 * - à plus de [SOFT_DEV_M] pendant [SOFT_STRIKES] échantillons de suite (sortie de piste brève tolérée) : éliminée ;
 * - le sens de progression le long du tracé distingue un circuit de son inverse (même géométrie) : sens inverse
 *   sans circuit inverse connu → éliminée.
 * Verdict seulement après [MIN_DISTANCE_M] mètres et [MIN_SAMPLES] échantillons :
 * - une seule configuration restante → reconnue ;
 * - plusieurs (configurations voisines, comme Le Mans avec ou sans chicanes, tant que la partie qui les distingue
 *   n'est pas parcourue) → ambiguë, jamais tranchée au hasard ;
 * - aucune → circuit absent des signatures : la reconnaissance par la longueur reste la seule.
 */
class TrackGeometryRecognizer(private val signatures: List<TrackSignature>) {
    sealed interface Verdict {
        data class Identified(val course: Int, val name: String, val reverse: Boolean, val distanceM: Float, val meanDevM: Float) : Verdict
        data class Ambiguous(val courses: List<Int>, val distanceM: Float) : Verdict
        data class Unknown(val distanceM: Float) : Verdict
        data object Pending : Verdict
    }

    private class Candidate(val sig: TrackSignature) {
        var alive = true
        var strikes = 0
        var lastIndex = -1
        var forward = 0
        var backward = 0
        var devSum = 0.0
        var samples = 0
    }

    private var candidates = signatures.map(::Candidate)
    private var lastX = Float.NaN
    private var lastZ = Float.NaN
    var distanceM = 0f
        private set
    private var samples = 0
    var verdict: Verdict = Verdict.Pending
        private set

    fun reset() {
        candidates = signatures.map(::Candidate)
        lastX = Float.NaN
        lastZ = Float.NaN
        distanceM = 0f
        samples = 0
        recent.clear()
        verdict = Verdict.Pending
    }

    /**
     * Position de la voiture (X, Z de la télémétrie). [moving] : la voiture roule en piste (ni pause, ni chargement,
     * ni stands) ; à l'arrêt, rien n'est échantillonné. Renvoie le verdict courant.
     */
    fun onPosition(x: Float, z: Float, moving: Boolean): Verdict {
        if (!moving || x.isNaN() || z.isNaN()) return verdict
        if (lastX.isNaN()) {
            lastX = x; lastZ = z
            sample(x, z)
            return verdict
        }
        val step = hypot(x - lastX, z - lastZ)
        // Saut de plusieurs centaines de mètres entre deux paquets : téléportation (retour au stand, nouvelle session).
        if (step > JUMP_M) {
            reset()
            lastX = x; lastZ = z
            return verdict
        }
        if (step < STEP_M) return verdict
        distanceM += step
        lastX = x; lastZ = z
        sample(x, z)
        return verdict
    }

    private fun sample(x: Float, z: Float) {
        samples++
        recent.addLast(x to z)
        if (recent.size > RECENT) recent.removeFirst()
        for (c in candidates) {
            if (!c.alive) continue
            val s = c.sig
            if (x < s.minX - BOX_MARGIN_M || x > s.maxX + BOX_MARGIN_M || z < s.minZ - BOX_MARGIN_M || z > s.maxZ + BOX_MARGIN_M) {
                c.alive = false
                continue
            }
            // Suivi local d'abord : sur un tracé qui se croise (Suzuka, Tokyo), le point le plus proche peut sauter sur
            // l'autre branche et fausser le sens de roulage.
            val local = if (c.lastIndex >= 0) nearest(s.path, x, z, c.lastIndex, LOCAL_WINDOW) else null
            val (index, dev) = local?.takeIf { it.second <= SOFT_DEV_M } ?: nearest(s.path, x, z)
            if (dev > HARD_DEV_M) {
                c.alive = false
                continue
            }
            c.strikes = if (dev > SOFT_DEV_M) c.strikes + 1 else 0
            if (c.strikes >= SOFT_STRIKES) {
                c.alive = false
                continue
            }
            c.devSum += dev
            c.samples++
            if (c.lastIndex >= 0 && index != c.lastIndex) {
                val n = s.points
                var d = index - c.lastIndex
                if (d > n / 2) d -= n
                if (d < -n / 2) d += n
                if (d > 0) c.forward += d else c.backward += -d
            }
            c.lastIndex = index
        }
        verdict = decide()
    }

    /** Derniers points échantillonnés (diagnostic seulement). */
    private val recent = ArrayDeque<Pair<Float, Float>>()

    /** Tracé le plus proche des derniers points, dans un repère donné (« normal », « x inversé »…). */
    data class Closest(val name: String, val meanDevM: Float, val frame: String)

    /**
     * Diagnostic pour un circuit resté « inconnu » : tracé le plus proche des [RECENT] derniers points, dans le repère
     * normal et dans les repères en miroir. Si le bon circuit n'apparaît proche qu'avec X ou Z inversé, le repère des
     * signatures diffère de celui de la PS5 ; s'il n'apparaît nulle part, le circuit manque aux signatures.
     */
    fun closest(): List<Closest> {
        if (recent.size < 5) return emptyList()
        val frames = listOf<Pair<String, (Pair<Float, Float>) -> Pair<Float, Float>>>(
            "normal" to { it }, "x inversé" to { (x, z) -> -x to z }, "z inversé" to { (x, z) -> x to -z }, "x et z inversés" to { (x, z) -> -x to -z },
        )
        return frames.mapNotNull { (label, f) ->
            val pts = recent.map(f)
            signatures.map { s -> s to pts.sumOf { (x, z) -> nearest(s.path, x, z).second.toDouble() } / pts.size }
                .minByOrNull { it.second }?.let { Closest(it.first.name, it.second.toFloat(), label) }
        }
    }

    /** Échantillons pris depuis le début de la session. */
    val sampleCount: Int get() = samples

    private fun decide(): Verdict {
        if (distanceM < MIN_DISTANCE_M || samples < MIN_SAMPLES) return Verdict.Pending
        data class Option(val course: Int, val c: Candidate, val reverse: Boolean)
        val options = candidates.filter { it.alive }.mapNotNull { c ->
            // Le sens se juge sur la progression le long du tracé : il faut une nette majorité.
            val total = c.forward + c.backward
            if (total == 0) return@mapNotNull null
            val reverse = c.backward > c.forward
            val share = maxOf(c.forward, c.backward).toFloat() / total
            if (share < DIRECTION_SHARE) return@mapNotNull null
            val course = (if (reverse) c.sig.reverseCourse else c.sig.course) ?: return@mapNotNull null
            Option(course, c, reverse)
        }.distinctBy { it.course }
        return when (options.size) {
            0 -> Verdict.Unknown(distanceM)
            1 -> options[0].let { Verdict.Identified(it.course, it.c.sig.name, it.reverse, distanceM, (it.c.devSum / it.c.samples).toFloat()) }
            else -> Verdict.Ambiguous(options.map { it.course }.sorted(), distanceM)
        }
    }

    companion object {
        const val STEP_M = 10f
        const val RECENT = 60
        const val JUMP_M = 300f
        const val BOX_MARGIN_M = 40f
        /** Largeur de piste et dégagements : au-delà, la voiture n'est pas sur ce tracé. */
        const val SOFT_DEV_M = 25f
        const val HARD_DEV_M = 80f
        const val SOFT_STRIKES = 4
        const val MIN_DISTANCE_M = 700f
        const val MIN_SAMPLES = 40
        const val DIRECTION_SHARE = 0.8f

        /** Points du tracé examinés de part et d'autre du dernier point suivi (±200 m). */
        const val LOCAL_WINDOW = 10

        /**
         * Index du point du tracé le plus proche et distance au segment le plus proche (mètres) ; sur tout le tracé, ou
         * seulement à ±[window] points de [center].
         */
        fun nearest(path: FloatArray, x: Float, z: Float, center: Int = -1, window: Int = -1): Pair<Int, Float> {
            val n = path.size / 2
            var best = Float.MAX_VALUE
            var bestIndex = 0
            val range = if (center < 0 || window < 0 || 2 * window + 1 >= n) (0 until n) else (center - window..center + window)
            for (k in range) {
                val i = ((k % n) + n) % n
                val j = (i + 1) % n
                val ax = path[2 * i]; val az = path[2 * i + 1]
                val bx = path[2 * j]; val bz = path[2 * j + 1]
                val dx = bx - ax; val dz = bz - az
                val len2 = dx * dx + dz * dz
                val t = if (len2 <= 0f) 0f else (((x - ax) * dx + (z - az) * dz) / len2).coerceIn(0f, 1f)
                val px = ax + t * dx; val pz = az + t * dz
                val d2 = (x - px) * (x - px) + (z - pz) * (z - pz)
                if (d2 < best) {
                    best = d2
                    bestIndex = if (t < 0.5f) i else j
                }
            }
            return bestIndex to sqrt(best)
        }
    }
}
