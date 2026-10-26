package com.gt7pitwall.core.profile

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale

/** GT7 CAREER stats, distinct from per-session UDP data and the Pitwall archive. */
enum class ProfileEvidence(val label: String) {
    USER_ENTERED("Déclaré par le pilote"),
    USER_IMPORT("Import fourni par le pilote"),
}

data class Gt7CareerStats(
    val dr: String? = null,
    val sr: String? = null,
    val drPoints: Int? = null,
    val races: Int? = null,
    val wins: Int? = null,
    val poles: Int? = null,
    val fastestLaps: Int? = null,
    val cleanRaces: Int? = null,
    val top5: Int? = null,
    val garageCars: Int? = null,
    val collectorLevel: Int? = null,
    val distanceKm: Double? = null,
) {
    val victoryRate: Double? get() =
        if (races != null && races > 0 && wins != null && wins <= races) wins.toDouble() / races else null
    val top5Rate: Double? get() =
        if (races != null && races > 0 && top5 != null && top5 <= races) top5.toDouble() / races else null
    val cleanRate: Double? get() =
        if (races != null && races > 0 && cleanRaces != null && cleanRaces <= races)
            cleanRaces.toDouble() / races else null

    fun validate(): String? {
        for ((label, n) in mapOf("points DR" to drPoints,"courses" to races,"victoires" to wins,
            "poles" to poles,"meilleurs tours" to fastestLaps,"courses propres" to cleanRaces,
            "top 5" to top5,"garage" to garageCars,"collectionneur" to collectorLevel)) {
            if (n != null && n < 0) return "$label : valeur négative"
        }
        if (races != null) {
            if (wins != null && wins > races) return "Victoires supérieures aux courses"
            if (poles != null && poles > races) return "Poles supérieures aux courses"
            if (cleanRaces != null && cleanRaces > races) return "Courses propres supérieures aux courses"
            if (top5 != null && top5 > races) return "Top 5 supérieurs aux courses"
        }
        if (distanceKm != null && (!distanceKm.isFinite() || distanceKm < 0)) return "Distance invalide"
        if (dr != null && dr.uppercase() !in setOf("E","D","C","B","A","A+","S")) return "Classement DR inconnu"
        if (sr != null && sr.uppercase() !in setOf("E","D","C","B","A","S")) return "Classement SR inconnu"
        return null
    }
}

data class Gt7CareerSample(
    val stats: Gt7CareerStats,
    val capturedAtMs: Long,
    val evidence: ProfileEvidence,
)

data class Gt7PlayerProfile(
    val psnId: String = "",
    val nickname: String = "",
    val officialProfileUrl: String = "",
    val samples: List<Gt7CareerSample> = emptyList(),
) {
    val latest: Gt7CareerSample? get() = samples.maxByOrNull { it.capturedAtMs }
    val previous: Gt7CareerSample? get() = samples.sortedByDescending { it.capturedAtMs }.getOrNull(1)
    val drPointsChange: Int? get() =
        latest?.stats?.drPoints?.let { current ->
            previous?.stats?.drPoints?.let { current - it }
        }
    val racesChange: Int? get() =
        latest?.stats?.races?.let { current -> previous?.stats?.races?.let { current - it } }

    /** No silent cross-account history mixing. A PSN ID change resets stats. */
    fun changeIdentity(next: String): Gt7PlayerProfile {
        val clean = next.trim()
        require(validPsnId(clean)) { "ID PSN invalide (3 à 16 caractères)" }
        return if (psnId.equals(clean, ignoreCase = true)) copy(psnId = clean)
            else Gt7PlayerProfile(psnId = clean)
    }

    fun add(stats: Gt7CareerStats, evidence: ProfileEvidence, now: Long): Gt7PlayerProfile {
        require(validPsnId(psnId)) { "Configure ton ID PSN avant les statistiques" }
        require(stats.validate() == null) { stats.validate() ?: "Données invalides" }
        // Avoid duplicated snapshots on a repeated save, retain a bounded local history.
        if (latest?.stats == stats) return this
        return copy(samples = (samples + Gt7CareerSample(stats, now, evidence))
            .sortedByDescending { it.capturedAtMs }.take(200))
    }

    companion object {
        fun validPsnId(value: String) = Regex("^[A-Za-z][A-Za-z0-9_-]{2,15}$").matches(value)
        fun validOfficialUrl(raw: String): Boolean {
            val url = runCatching { URI(raw.trim()) }.getOrNull() ?: return false
            val host = url.host?.lowercase(Locale.ROOT)
            return url.scheme == "https" &&
                (host == "gran-turismo.com" || host == "www.gran-turismo.com") &&
                url.userInfo == null && url.port == -1 &&
                Regex("^/[a-z]{2}(?:-[a-z]{2})?/gt7/user/mymenu/(?:[0-9a-fA-F-]{36}/profile/?)?$").matches(url.path ?: "")
        }
    }
}

/** No API calls. Accepts only explicitly supplied personal JSON payloads. */
object Gt7CareerImport {
    fun fromRacerJson(raw: String, expectedPsn: String): Gt7CareerStats {
        require(Gt7PlayerProfile.validPsnId(expectedPsn)) { "Configure ton ID PSN" }
        require(raw.length <= 120_000) { "Import trop volumineux" }
        // Never process or inadvertently retain a PlayStation session secret.
        require(!Regex("""(?i)"(?:npsso|access_token|refresh_token|password|cookie|authorization)"\s*:""")
            .containsMatchIn(raw)) { "Import contenant un secret de connexion : refusé" }
        val obj = JSONObject(raw)
        val driver = when {
            obj.has("drivers") -> {
                val drivers = obj.optJSONArray("drivers") ?: throw IllegalArgumentException("Liste pilotes invalide")
                val matching = (0 until drivers.length()).mapNotNull { drivers.optJSONObject(it) }
                    .filter { it.optString("PSN_ID").equals(expectedPsn, ignoreCase = true) }
                require(matching.size == 1) { "Aucun pilote unique pour cet ID PSN" }
                matching.single()
            }
            obj.optString("PSN_ID").equals(expectedPsn, ignoreCase = true) -> obj
            else -> throw IllegalArgumentException("Le profil importé ne correspond pas à ton ID PSN")
        }
        val s = driver.optJSONObject("stats") ?: JSONObject()
        fun num(o: JSONObject, key: String): Int? = if (o.has(key) && !o.isNull(key))
            o.optInt(key, -1).takeIf { it >= 0 } else null
        val stats = Gt7CareerStats(
            dr = driver.optString("DR").takeIf { it.isNotBlank() },
            sr = driver.optString("SR").takeIf { it.isNotBlank() },
            drPoints = num(s,"dr_points"),
            races = num(s,"total_races"),
            wins = num(s,"victories"),
            poles = num(s,"poles"),
            fastestLaps = num(s,"fastest_laps"),
            cleanRaces = num(s,"clean_races"),
            top5 = num(s,"top5"),
            garageCars = num(s,"garage_count"),
            collectorLevel = num(s,"collector_level"),
            distanceKm = s.optDouble("distance_km",Double.NaN).takeIf { it.isFinite() && it >= 0 },
        )
        require(stats.validate() == null) { stats.validate() ?: "Données invalides" }
        require(stats.races != null || stats.drPoints != null || stats.dr != null) {
            "Aucune statistique GT7 reconnue"
        }
        return stats
    }
}

/** Local-only persistence format. No NPSSO, cookies, OAuth tokens or passwords. */
object Gt7CareerJson {
    fun encode(profile: Gt7PlayerProfile): String = JSONObject().apply {
        put("version",1)
        put("psnId",profile.psnId)
        put("nickname",profile.nickname)
        put("officialProfileUrl",profile.officialProfileUrl)
        put("samples",JSONArray().apply {
            profile.samples.take(200).forEach { sample ->
                put(JSONObject().apply {
                    put("capturedAtMs",sample.capturedAtMs)
                    put("evidence",sample.evidence.name)
                    put("stats",JSONObject().apply {
                        val s = sample.stats
                        put("dr",s.dr); put("sr",s.sr);put("drPoints",s.drPoints)
                        put("races",s.races);put("wins",s.wins);put("poles",s.poles)
                        put("fastestLaps",s.fastestLaps);put("cleanRaces",s.cleanRaces)
                        put("top5",s.top5);put("garageCars",s.garageCars)
                        put("collectorLevel",s.collectorLevel);put("distanceKm",s.distanceKm)
                    })
                })
            }
        })
    }.toString()

    fun decode(raw: String): Gt7PlayerProfile {
        val o = JSONObject(raw)
        require(o.optInt("version") == 1) { "Version du profil non reconnue" }
        val id = o.optString("psnId")
        require(id.isBlank() || Gt7PlayerProfile.validPsnId(id)) { "ID PSN invalide" }
        val url = o.optString("officialProfileUrl")
        val cleanUrl = url.takeIf { it.isBlank() || Gt7PlayerProfile.validOfficialUrl(it) } ?: ""
        val arr = o.optJSONArray("samples") ?: JSONArray()
        val samples = (0 until minOf(arr.length(),200)).mapNotNull { i ->
            runCatching {
                val item=arr.getJSONObject(i);val s=item.getJSONObject("stats")
                fun n(key:String)=if(s.isNull(key)) null else s.optInt(key,-1).takeIf { it>=0 }
                fun str(key:String)=s.optString(key).takeIf { it.isNotBlank() && it!="null" }
                Gt7CareerSample(Gt7CareerStats(str("dr"),str("sr"),n("drPoints"),n("races"),
                    n("wins"),n("poles"),n("fastestLaps"),n("cleanRaces"),n("top5"),
                    n("garageCars"),n("collectorLevel"),
                    if(s.isNull("distanceKm"))null else s.optDouble("distanceKm",Double.NaN)
                        .takeIf { it.isFinite() && it>=0 }),
                    item.getLong("capturedAtMs"),
                    ProfileEvidence.valueOf(item.optString("evidence")))
            }.getOrNull()?.takeIf { it.stats.validate()==null }
        }.sortedByDescending { it.capturedAtMs }
        return Gt7PlayerProfile(id,o.optString("nickname").take(80),cleanUrl,samples)
    }
}
