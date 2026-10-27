package com.gt7pitwall.core.analysis

import org.json.JSONObject
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Virages numérotés d'un circuit (numérotation de la source communautaire jbhoorasingh/gt7-datalogger-track-data, CC0) :
 * apex en coordonnées X/Z de GT7. [matchesOfficial] faux : le nombre de virages diffère du catalogue officiel, la
 * numérotation est présentée « à vérifier ».
 */
class NumberedCorners(val course: Int, val name: String, val matchesOfficial: Boolean, val corners: List<Ref>) {
    data class Ref(val n: Int, val name: String, val x: Float, val z: Float)

    companion object {
        fun parseAll(json: String): Map<Int, NumberedCorners> {
            val o = JSONObject(json)
            require(o.optString("format") == "gt7pitwall-track-corners") { "format de virages inconnu" }
            val a = o.getJSONArray("tracks")
            return (0 until a.length()).associate { i ->
                val t = a.getJSONObject(i)
                val cs = t.getJSONArray("corners")
                val refs = (0 until cs.length()).map { j ->
                    val c = cs.getJSONObject(j)
                    Ref(c.getInt("n"), c.optString("name"), c.getDouble("x").toFloat(), c.getDouble("z").toFloat())
                }
                t.getInt("course") to NumberedCorners(t.getInt("course"), t.getString("name"), t.optBoolean("count_matches_official"), refs)
            }
        }
    }
}

/**
 * Libellé d'un virage détecté par Pitwall ([CornerMap], numéroté dans l'ordre depuis la ligne) : « T5 » quand son
 * point de vitesse minimale tombe à moins de [MATCH_M] d'un apex numéroté de la source (chaque apex ne sert qu'une
 * fois) ; sinon « V3 » (virage détecté, numéro estimé). Sens inverse ou circuit sans relevé : toujours estimé.
 */
object CornerLabels {
    const val MATCH_M = 45f

    data class Label(val corner: Int, val text: String, val community: Boolean, val name: String = "")

    fun of(map: CornerMap, numbered: NumberedCorners?, reverse: Boolean = false): Map<Int, Label> {
        val apexes = map.corners.associate { c ->
            val i = (c.apexM / CornerMap.STEP_M).roundToInt().coerceIn(0, map.xs.size - 1)
            c.number to (map.xs[i] to map.zs[i])
        }
        val out = HashMap<Int, Label>()
        if (numbered != null && !reverse) {
            // Appariement glouton par distance croissante : un virage ↔ un apex.
            val pairs = apexes.flatMap { (k, p) -> numbered.corners.map { r -> Triple(k, r, hypot(p.first - r.x, p.second - r.z)) } }
                .filter { it.third <= MATCH_M }.sortedBy { it.third }
            val usedRef = HashSet<Int>()
            for ((k, r, _) in pairs) {
                if (k in out || r.n in usedRef) continue
                usedRef += r.n
                out[k] = Label(k, "T${r.n}", community = true, name = r.name)
            }
        }
        for (c in map.corners) if (c.number !in out) out[c.number] = Label(c.number, "V${c.number}", community = false)
        return out
    }
}

/**
 * Bilan par virage, comparé à des passages valides du même virage (même zone de la carte, donc même position sur le
 * circuit) : temps perdu par rapport au meilleur passage, et cause probable quand la mesure la montre. Aucune cause sans
 * mesure ; confiance selon le nombre de passages et la régularité de la perte.
 */
object CornerReport {
    enum class Confidence(val label: String) { LOW("faible"), MEDIUM("moyenne"), HIGH("élevée") }

    data class Line(
        val corner: Int,
        val label: String,
        val passes: Int,
        val bestMs: Int,
        /** Perte moyenne par rapport au meilleur passage (ms). */
        val meanLossMs: Int,
        val entryKmh: Float,
        val minKmh: Float,
        val bestMinKmh: Float,
        val lockups: Int,
        val wheelspins: Int,
        val cause: String?,
        val confidence: Confidence,
    ) {
        val text: String get() = buildString {
            append("$label : ${passes} passage${if (passes > 1) "s" else ""}, ")
            append(if (meanLossMs >= 50) "${s(meanLossMs)} perdus en moyenne sur ton meilleur" else "régulier")
            append(" ; vitesse minimale ${minKmh.roundToInt()} km/h (meilleure ${bestMinKmh.roundToInt()})")
            if (lockups > 0) append(", $lockups blocage${if (lockups > 1) "s" else ""}")
            if (wheelspins > 0) append(", $wheelspins patinage${if (wheelspins > 1) "s" else ""}")
            cause?.let { append(" ; cause probable : $it (confiance ${confidence.label})") }
            append(".")
        }
    }

    const val MIN_PASSES = 2

    fun of(passes: List<CornerPass>, labels: Map<Int, CornerLabels.Label>): List<Line> =
        passes.groupBy { it.corner }.mapNotNull { (corner, ps) ->
            if (ps.size < MIN_PASSES) return@mapNotNull null
            val best = ps.minBy { it.timeMs }
            val others = ps - best
            val meanLoss = others.map { it.timeMs - best.timeMs }.average().roundToInt()
            val label = labels[corner]?.text ?: "V$corner"
            val cause = cause(best, others)
            // Confiance : nombre de passages comparables et perte qui se répète (pas un seul tour raté).
            val repeated = others.count { it.timeMs - best.timeMs >= 80 }
            val confidence = when {
                cause == null -> Confidence.LOW
                others.size >= 4 && repeated >= others.size * 2 / 3 -> Confidence.HIGH
                others.size >= 2 && repeated >= 2 -> Confidence.MEDIUM
                else -> Confidence.LOW
            }
            Line(
                corner, label, ps.size, best.timeMs, meanLoss,
                ps.map { it.entryKmh }.average().toFloat(), ps.map { it.minKmh }.average().toFloat(), best.minKmh,
                ps.count { it.lockup == true }, ps.count { it.wheelspin == true }, cause, confidence,
            )
        }.sortedByDescending { it.meanLossMs }

    /** Cause dominante de l'écart avec le meilleur passage, seulement si elle est mesurée. */
    private fun cause(best: CornerPass, others: List<CornerPass>): String? {
        if (others.isEmpty()) return null
        val reasons = mutableListOf<Pair<Float, String>>()
        val brakeDiffs = others.mapNotNull { o -> o.brakeStartM?.let { b -> best.brakeStartM?.let { b - it } } }
        if (brakeDiffs.size >= (others.size + 1) / 2) {
            val d = brakeDiffs.average().toFloat()
            // Distance depuis la ligne : freiner plus tôt = plus petite distance.
            if (d <= -8f) reasons += -d to "freinage ${(-d).roundToInt()} m plus tôt"
            if (d >= 8f) reasons += d to "freinage ${d.roundToInt()} m plus tard (entrée trop rapide)"
        }
        val minDiff = others.map { best.minKmh - it.minKmh }.average().toFloat()
        if (minDiff >= 3f) reasons += minDiff * 4f to "vitesse minimale ${minDiff.roundToInt()} km/h plus basse"
        val throttleDiffs = others.mapNotNull { o -> o.throttleOnM?.let { t -> best.throttleOnM?.let { t - it } } }
        if (throttleDiffs.size >= (others.size + 1) / 2) {
            val d = throttleDiffs.average().toFloat()
            if (d >= 8f) reasons += d to "réaccélération ${d.roundToInt()} m plus tard"
        }
        val lock = others.count { it.lockup == true }
        if (lock * 2 > others.size && best.lockup != true) reasons += 30f to "blocages au freinage"
        val spin = others.count { it.wheelspin == true }
        if (spin * 2 > others.size && best.wheelspin != true) reasons += 30f to "patinage à la réaccélération"
        return reasons.maxByOrNull { it.first }?.second
    }

    private fun s(ms: Int): String = String.format(java.util.Locale.FRANCE, "%.2f s", ms / 1000.0)
}
