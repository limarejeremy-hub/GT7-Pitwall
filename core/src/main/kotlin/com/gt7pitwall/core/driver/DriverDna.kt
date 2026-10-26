package com.gt7pitwall.core.driver

import com.gt7pitwall.core.analysis.Corner
import com.gt7pitwall.core.analysis.CornerMap
import com.gt7pitwall.core.analysis.CornerPass
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Résultat d'un conseil : perte avant, perte mesurée sur les passages suivants (null : pas encore vérifié). */
data class AdviceOutcome(val corner: Int, val issue: CornerIssue, val lossBeforeMs: Int, val lossAfterMs: Int?, val sessionId: String)

/** Statistiques d'une session, pour suivre l'évolution du pilotage d'une course à l'autre. */
data class SessionStyle(
    val sessionId: String,
    val cleanLaps: Int,
    /** Écart-type des tours propres (ms) : régularité. */
    val lapSpreadMs: Int?,
    val lockupsPerLap: Float?,
    val wheelspinsPerLap: Float?,
    /** Trois derniers tours propres contre trois premiers du relais (ms, positif = plus lent en fin de relais). */
    val stintFadeMs: Int?,
    val fuelPerLap: Float?,
)

/**
 * Profil de pilotage (Driver DNA) pour une voiture, un circuit et un type de session : carte des virages, derniers passages
 * mesurés dans chaque virage, style par session et suivi des conseils. Les situations incompatibles (autre voiture,
 * autre circuit ou configuration, qualification contre course) ont chacune leur profil : jamais mélangées.
 */
data class DriverProfile(
    val key: String,
    val map: CornerMap?,
    val passes: Map<Int, List<CornerPass>> = emptyMap(),
    val sessions: List<SessionStyle> = emptyList(),
    val advice: List<AdviceOutcome> = emptyList(),
) {
    val sessionCount: Int get() = sessions.size

    /** Ajoute des passages, en gardant les [MAX_PASSES] derniers et les meilleurs de chaque virage (la référence personnelle). */
    fun withPasses(add: List<CornerPass>): DriverProfile {
        val merged = passes.toMutableMap()
        for ((c, list) in add.groupBy { it.corner }) {
            val all = merged[c].orEmpty() + list
            val best = all.filter { it.offTrack != true }.sortedBy { it.timeMs }.take(CornerCoach.MIN_REFERENCE)
            val last = all.takeLast(MAX_PASSES)
            merged[c] = (best.filter { it !in last } + last)
        }
        return copy(passes = merged)
    }

    /**
     * Points forts et points faibles, sur des mesures répétées seulement : par virage, écart moyen à la référence personnelle
     * et irrégularité, avec au moins [MIN_PASSES_TRAIT] passages sur au moins deux sessions.
     */
    fun traits(): Traits {
        val rows = passes.mapNotNull { (c, list) ->
            val clean = list.filter { it.offTrack != true }
            if (clean.size < MIN_PASSES_TRAIT) return@mapNotNull null
            val ref = CornerCoach.reference(clean).map { it.timeMs }.sorted().let { it[it.size / 2] }
            val gaps = clean.map { (it.timeMs - ref).toDouble() }
            val mean = gaps.average()
            val sd = sqrt(gaps.sumOf { (it - mean) * (it - mean) } / (gaps.size - 1).coerceAtLeast(1))
            Triple(c, mean.roundToInt(), sd.roundToInt())
        }
        if (rows.size < 2 || sessions.size < 2) return Traits(emptyList(), emptyList(), rows.size)
        // Faible : écart moyen d'au moins 0,1 s à sa propre référence ; fort : proche de la référence et régulier.
        val weak = rows.filter { it.second >= 100 }.sortedByDescending { it.second }.take(2)
        val strong = rows.filter { it !in weak && it.second < 60 && it.third < 80 }.sortedBy { it.second + it.third }.take(2)
        return Traits(strong = strong.map { it.first }, weak = weak.map { it.first to it.second }, measuredCorners = rows.size)
    }

    data class Traits(val strong: List<Int>, val weak: List<Pair<Int, Int>>, val measuredCorners: Int) {
        val text: String?
            get() {
                if (strong.isEmpty() && weak.isEmpty()) return null
                val parts = mutableListOf<String>()
                if (strong.isNotEmpty()) parts += "points forts : virage${if (strong.size > 1) "s" else ""} ${strong.joinToString(" et ")} (réguliers, proches de ton meilleur)"
                if (weak.isNotEmpty()) parts += "à travailler : " + weak.joinToString(", ") { "virage ${it.first} (${CornerCoach.tenths(it.second)} d'écart moyen)" }
                return parts.joinToString(" ; ").replaceFirstChar { it.uppercase() } + "."
            }
    }

    /** Évolution du style d'une session à l'autre (régularité), seulement avec au moins deux sessions mesurées. */
    fun progress(): String? {
        val s = sessions.filter { it.lapSpreadMs != null && it.cleanLaps >= 4 }
        if (s.size < 2) return null
        val a = s[s.size - 2].lapSpreadMs!!
        val b = s.last().lapSpreadMs!!
        return when {
            b < a * 0.8 -> "Régularité en progrès : écart entre tes tours propres passé de ${CornerCoach.tenths(a)} à ${CornerCoach.tenths(b)}."
            b > a * 1.25 -> "Régularité en baisse : écart entre tes tours propres passé de ${CornerCoach.tenths(a)} à ${CornerCoach.tenths(b)}."
            else -> null
        }
    }

    /** Taux de conseils suivis d'une amélioration mesurée. */
    fun adviceRecord(): Pair<Int, Int> {
        val checked = advice.filter { it.lossAfterMs != null }
        return checked.count { it.lossAfterMs!! < it.lossBeforeMs - IMPROVED_MS } to checked.size
    }

    companion object {
        const val MAX_PASSES = 24
        const val MIN_PASSES_TRAIT = 6
        const val IMPROVED_MS = 50

        fun key(carCode: Int, track: String, kind: String): String = "$carCode|$track|$kind"
    }
}

/** Tous les profils de pilotage, sérialisés en JSON pour le stockage du téléphone. */
class DriverBook(profiles: Collection<DriverProfile> = emptyList()) {
    private val byKey = LinkedHashMap<String, DriverProfile>().apply { profiles.forEach { put(it.key, it) } }
    val profiles: Collection<DriverProfile> get() = byKey.values

    operator fun get(key: String): DriverProfile? = byKey[key]
    fun put(p: DriverProfile) {
        byKey[p.key] = p
    }

    /** Variantes d'une même clé (configuration de circuit différente sous le même identifiant) : « clé#2 »… */
    fun variants(base: String): List<DriverProfile> = byKey.values.filter { it.key == base || it.key.startsWith("$base#") }

    fun toJson(): String = JSONObject().apply {
        put("schema", 1)
        put("profiles", JSONArray().apply { byKey.values.forEach { put(DnaJson.profile(it)) } })
    }.toString()

    companion object {
        fun parse(json: String?): DriverBook = runCatching {
            val o = JSONObject(json ?: return DriverBook())
            val a = o.getJSONArray("profiles")
            DriverBook((0 until a.length()).mapNotNull { runCatching { DnaJson.profile(a.getJSONObject(it)) }.getOrNull() })
        }.getOrDefault(DriverBook())
    }
}

internal object DnaJson {
    private fun r1(f: Float) = Math.round(f * 10.0) / 10.0

    fun profile(p: DriverProfile): JSONObject = JSONObject().apply {
        put("key", p.key)
        p.map?.let { m ->
            put("map", JSONObject().apply {
                put("x", JSONArray().apply { m.xs.forEach { put(r1(it)) } })
                put("z", JSONArray().apply { m.zs.forEach { put(r1(it)) } })
                put("corners", JSONArray().apply {
                    m.corners.forEach { c ->
                        put(JSONArray(listOf(c.number, r1(c.entryM), r1(c.apexM), r1(c.exitM), r1(c.zoneStartM), r1(c.zoneEndM))))
                    }
                })
            })
        }
        put("passes", JSONArray().apply { p.passes.values.flatten().forEach { put(pass(it)) } })
        put("sessions", JSONArray().apply {
            p.sessions.forEach { s ->
                put(JSONObject().apply {
                    put("id", s.sessionId); put("laps", s.cleanLaps)
                    s.lapSpreadMs?.let { put("spread", it) }
                    s.lockupsPerLap?.let { put("lock", r1(it)) }
                    s.wheelspinsPerLap?.let { put("spin", r1(it)) }
                    s.stintFadeMs?.let { put("fade", it) }
                    s.fuelPerLap?.let { put("fuel", Math.round(it * 1000.0) / 1000.0) }
                })
            }
        })
        put("advice", JSONArray().apply {
            p.advice.forEach { a ->
                put(JSONObject().apply {
                    put("c", a.corner); put("i", a.issue.name); put("before", a.lossBeforeMs); put("s", a.sessionId)
                    a.lossAfterMs?.let { put("after", it) }
                })
            }
        })
    }

    private fun pass(c: CornerPass): JSONObject = JSONObject().apply {
        put("c", c.corner); put("l", c.lap); put("t", c.timeMs)
        c.brakeStartM?.let { put("b", r1(it)) }
        c.brakePeak?.let { put("bp", Math.round(it * 100.0) / 100.0) }
        c.brakeRampMs?.let { put("br", it) }
        put("e", r1(c.entryKmh)); put("m", r1(c.minKmh)); put("ma", r1(c.minAtM))
        c.throttleOnM?.let { put("g", r1(it)) }
        put("x", r1(c.exitKmh))
        c.lockup?.let { put("lk", it) }
        c.wheelspin?.let { put("sp", it) }
        put("tcs", Math.round(c.tcsShare * 100.0) / 100.0)
        put("lat", r1(c.apexLateralM))
        c.rotationRatio?.let { put("rot", Math.round(it * 100.0) / 100.0) }
        c.offTrack?.let { put("off", it) }
    }

    fun profile(o: JSONObject): DriverProfile {
        val map = o.optJSONObject("map")?.let { m ->
            val x = m.getJSONArray("x"); val z = m.getJSONArray("z"); val cs = m.getJSONArray("corners")
            CornerMap(
                FloatArray(x.length()) { x.getDouble(it).toFloat() },
                FloatArray(z.length()) { z.getDouble(it).toFloat() },
                (0 until cs.length()).map { i ->
                    val c = cs.getJSONArray(i)
                    Corner(c.getInt(0), c.getDouble(1).toFloat(), c.getDouble(2).toFloat(), c.getDouble(3).toFloat(), c.getDouble(4).toFloat(), c.getDouble(5).toFloat())
                },
            )
        }
        fun f(j: JSONObject, k: String) = if (j.has(k)) j.getDouble(k).toFloat() else null
        fun i(j: JSONObject, k: String) = if (j.has(k)) j.getInt(k) else null
        fun b(j: JSONObject, k: String) = if (j.has(k)) j.getBoolean(k) else null
        val ps = o.optJSONArray("passes") ?: JSONArray()
        val passes = (0 until ps.length()).map { idx ->
            val j = ps.getJSONObject(idx)
            CornerPass(
                j.getInt("c"), j.getInt("l"), j.getInt("t"), f(j, "b"), f(j, "bp"), i(j, "br"), f(j, "e")!!, f(j, "m")!!, f(j, "ma")!!,
                f(j, "g"), f(j, "x")!!, b(j, "lk"), b(j, "sp"), f(j, "tcs") ?: 0f, f(j, "lat") ?: 0f, f(j, "rot"), b(j, "off"),
            )
        }.groupBy { it.corner }
        val ss = o.optJSONArray("sessions") ?: JSONArray()
        val sessions = (0 until ss.length()).map { idx ->
            val j = ss.getJSONObject(idx)
            SessionStyle(j.getString("id"), j.getInt("laps"), i(j, "spread"), f(j, "lock"), f(j, "spin"), i(j, "fade"), f(j, "fuel"))
        }
        val aa = o.optJSONArray("advice") ?: JSONArray()
        val advice = (0 until aa.length()).mapNotNull { idx ->
            val j = aa.getJSONObject(idx)
            val issue = runCatching { CornerIssue.valueOf(j.getString("i")) }.getOrNull() ?: return@mapNotNull null
            AdviceOutcome(j.getInt("c"), issue, j.getInt("before"), i(j, "after"), j.optString("s"))
        }
        return DriverProfile(o.getString("key"), map, passes, sessions, advice)
    }
}
