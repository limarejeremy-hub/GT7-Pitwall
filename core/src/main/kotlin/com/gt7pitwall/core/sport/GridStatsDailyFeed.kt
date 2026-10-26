package com.gt7pitwall.core.sport

import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.strategy.TyreCompound
import org.json.JSONObject
import java.time.LocalDate

/**
 * Deuxième source indépendante du programme Sport hebdomadaire.
 *
 * Contrat public GET https://gt-gridstats.com/api/dailies/this-week (domaine officiel avec tiret, vu sur la page « Public API »
 * du site le 26/10 ; « gtgridstats.com » sans tiret est un autre domaine : la clé du pilote n'y est jamais envoyée).
 * Authorization: Bearer <clé fournie par le pilote>.
 *
 * Seul le règlement explicite est lu, jamais les statistiques des voitures « méta » comme règlement.
 * La date vient de l'événement, PAS de la date de téléchargement : aucun glissement de semaine.
 */
object GridStatsDailyFeed {
    const val NAME = "GT GridStats"
    /** Seul domaine auquel la clé personnelle du pilote peut être envoyée. */
    const val HOST = "gt-gridstats.com"
    const val URL = "https://$HOST/api/dailies/this-week"

    fun parse(json: String, fetchedAtMs: Long): List<WeeklyPost> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        if (root.optString("status") != "success") return emptyList()
        val data = root.optJSONArray("data") ?: return emptyList()
        val byWeek = linkedMapOf<LocalDate, MutableMap<Series, DailyRaceInfo>>()
        for (i in 0 until data.length()) {
            val race = data.optJSONObject(i) ?: continue
            val series = when (race.optString("race_type")) {
                "A" -> Series.DAILY_A
                "B" -> Series.DAILY_B
                "C" -> Series.DAILY_C
                else -> continue
            }
            val date = race.optString("begin_date").take(10)
            val validFrom = runCatching { LocalDate.parse(date) }.getOrNull() ?: continue
            if (validFrom.dayOfWeek.value != 1) continue // sécurité contre une mauvaise édition
            val track = race.optString("track_name").trim().takeIf { it.isNotBlank() } ?: continue
            val laps = race.optInt("laps", 0).takeIf { it in 1..150 } ?: continue
            val raw = race.optJSONObject("raw_payload")
            val regulation = raw?.optJSONObject("regulation")
            val rules = raw?.optJSONObject("race")
            val tyreFamily = regulation?.optString("tire")?.takeIf { it.isNotBlank() }
            fun tyres(field: String): Set<TyreCompound>? {
                val array = regulation?.optJSONArray(field) ?: return null
                val compounds = linkedSetOf<TyreCompound>()
                for (j in 0 until array.length()) {
                    val value = array.optString(j).trim()
                    if (value.isBlank()) continue
                    val code = if (value.matches(Regex("(?i)^(RH|RM|RS|SH|SM|SS|CH|CM|CS|IM|W|D)$"))) {
                        value.uppercase()
                    } else {
                        if (tyreFamily.isNullOrBlank()) return null
                        "$tyreFamily $value"
                    }
                    val parsed = GtPlanetFeed.compounds(code)
                    if (parsed.size != 1) return null // jamais de règlement partiel présenté comme complet
                    compounds += parsed
                }
                return compounds
            }
            val allowed = tyres("compounds")
            val required = tyres("required_compounds")
            val carClass = race.optString("car_class").trim().takeIf { it.isNotBlank() }
            val namedCar = race.optJSONObject("meta_car")?.optString("name")?.trim()?.takeIf { it.isNotBlank() }
            val cars = if (carClass.equals("Road", ignoreCase = true) && race.optJSONArray("other_cars")?.length() == 0) namedCar ?: carClass else carClass
            val mandatoryStops = rules?.optInt("pit_constraint", -1)?.takeIf { it in 0..10 }
            val fuel = rules?.optInt("consume_fuel", 0)?.takeIf { it in 1..50 }
            val wear = rules?.optInt("consume_tire", 0)?.takeIf { it in 1..50 }
            val start = when (rules?.optString("start_type")) {
                "StartGrid" -> "Grid"
                "StartFormationRolling" -> "Rolling"
                else -> null
            }
            val source = SourceRef(NAME, URL, fetchedAtMs)
            val entry = DailyRaceInfo(
                series = series, source = source, trackName = track, laps = laps,
                cars = cars, startType = start,
                allowedTyres = allowed, requiredTyres = required,
                mandatoryStops = mandatoryStops, fuelMultiplier = fuel, tyreMultiplier = wear,
            )
            // Plusieurs entrées incompatibles pour une même course/semaine : ne pas choisir silencieusement.
            val week = byWeek.getOrPut(validFrom) { linkedMapOf() }
            val old = week[series]
            if (old == null) week[series] = entry
            else if (old != entry) week.remove(series)
        }
        return byWeek.map { (date, races) ->
            WeeklyPost(SourceRef(NAME, URL, fetchedAtMs), date, "Sport A/B/C — semaine du $date", races)
        }.filter { it.races.isNotEmpty() }
    }
}
