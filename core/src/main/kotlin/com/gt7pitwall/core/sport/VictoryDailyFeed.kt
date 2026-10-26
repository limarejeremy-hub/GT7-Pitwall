package com.gt7pitwall.core.sport

import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.strategy.TyreCompound
import java.time.LocalDate
import org.json.JSONObject

/**
 * Personal, no-account, no-API-key reading of Victory's PUBLIC daily races page.
 * The public HTML includes schema.org ItemList/ SportsEvent JSON-LD for race identity,
 * track, car and date. Only the corresponding visible A/B/C cards provide laps/fuel/wear.
 *
 * Neither private endpoints nor application software are reverse engineered.
 * Missing tyre obligations/pit requirements stay UNKNOWN, not inferred from allowed tyres.
 */
object VictoryDailyFeed {
    const val NAME = "Victory"
    const val URL = "https://victorydash.io/daily-races"

    fun parse(html: String, fetchedAtMs: Long): List<WeeklyPost> {
        val ld = Regex("""(?is)<script\b[^>]*type=["']application/ld\+json["'][^>]*>(.*?)</script>""")
            .findAll(html).mapNotNull { m ->
                runCatching { JSONObject(m.groupValues[1].trim()) }.getOrNull()
            }.firstOrNull { it.optString("@type") == "ItemList" } ?: return emptyList()
        val entries = ld.optJSONArray("itemListElement") ?: return emptyList()
        if (entries.length() != 3) return emptyList()
        val byWeek = linkedMapOf<LocalDate, MutableMap<Series, DailyRaceInfo>>()
        val source = SourceRef(NAME, URL, fetchedAtMs)
        for (i in 0 until entries.length()) {
            val event = entries.optJSONObject(i)?.optJSONObject("item") ?: return emptyList()
            if (event.optString("@type") != "SportsEvent") return emptyList()
            val name = event.optString("name")
            val match = Regex("""(?i)^GT7 Daily Race ([ABC]) at (.+)$""").matchEntire(name) ?: return emptyList()
            val series = when (match.groupValues[1].uppercase()) {
                "A" -> Series.DAILY_A
                "B" -> Series.DAILY_B
                "C" -> Series.DAILY_C
                else -> return emptyList()
            }
            val track = match.groupValues[2].trim().takeIf { it.isNotEmpty() } ?: return emptyList()
            val date = event.optString("startDate").take(10)
            val monday = runCatching { LocalDate.parse(date) }.getOrNull()
                ?.takeIf { it.dayOfWeek.value == 1 } ?: return emptyList()
            val end = runCatching { LocalDate.parse(event.optString("endDate").take(10)) }.getOrNull()
            if (end != monday.plusDays(7)) return emptyList()
            val parts = event.optString("description").split('·').map { it.trim() }
            if (parts.size < 3) return emptyList()
            val car = parts[parts.size - 2].takeIf { it.isNotBlank() } ?: return emptyList()
            val tyres = GtPlanetFeed.compounds(parts.last()).takeIf { it.isNotEmpty() } ?: return emptyList()
            // Race ID is read only from the public JSON-LD. The card must confirm its series
            // and link URL; no fallback to a previous race's card.
            val eventUrl = event.optString("url")
            if (!eventUrl.startsWith("$URL/")) return emptyList()
            val id = eventUrl.removePrefix("$URL/")
            if (!Regex("""p_rt_[a-z0-9_]+""", RegexOption.IGNORE_CASE).matches(id)) return emptyList()
            val cards = Regex(
                """(?is)<a\b[^>]*href=["']/daily-races/@@{Regex.escape(id)}["'][^>]*>(.*?)</article>\s*</a>"""
            .replace("@@{Regex.escape(id)}", Regex.escape(id))
            )
            val card = cards.find(html)?.groupValues?.get(1) ?: return emptyList()
            val text = GtPlanetFeed.text(card.replace(Regex("""(?is)<script\b[^>]*>.*?</script>"""), " "))
            // A card must visibly name its own race, so repeated JSON scripts never count as
            // the rendered UI or as a valid schedule.
            if (!text.contains(Regex("""(?i)\bRace\s+""" + match.groupValues[1] + """\b""")))
                return emptyList()
            val laps = Regex("""(?i)\b(\d{1,3})\s+laps?\b""").find(text)
                ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..150 } ?: return emptyList()
            val fuel = Regex("""(?i)\bFuel\s*[×x]\s*(\d{1,2})\b""").find(text)
                ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..50 } ?: return emptyList()
            val wear = Regex("""(?i)\bWear\s*[×x]\s*(\d{1,2})\b""").find(text)
                ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..50 } ?: return emptyList()
            val info = DailyRaceInfo(
                series = series, source = source, trackName = track, laps = laps,
                cars = car, allowedTyres = tyres, fuelMultiplier = fuel, tyreMultiplier = wear,
                bop = if (text.contains(Regex("""(?i)\bBoP\b"""))) "BoP" else null,
                // A page listing allowed RM and RS does NOT prove both are compulsory.
                requiredTyres = null, mandatoryStops = null,
            )
            val week = byWeek.getOrPut(monday) { linkedMapOf() }
            if (week.put(series, info) != null) return emptyList()
        }
        return byWeek.mapNotNull { (week, races) ->
            if (races.size == 3) WeeklyPost(source, week, "Sport A/B/C — semaine du $week", races)
            else null
        }
    }
}
