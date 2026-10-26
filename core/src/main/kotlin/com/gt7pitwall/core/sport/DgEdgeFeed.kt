package com.gt7pitwall.core.sport

import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.strategy.TyreCompound
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Second independent, no-key public source for a driver's personal Sport schedule.
 *
 * Reads only the publicly rendered race summaries on DG EDGE (not its account, leaderboard,
 * hidden API, or premium area). One index + up to three race pages per daily refresh.
 * Do not publish these pages or any third-party dataset to GitHub.
 *
 * Each page includes a human-readable SEO race description; absent details remain unknown.
 * If the site changes its markup, fail closed instead of applying the wrong regulations.
 */
object DgEdgeFeed {
    const val NAME = "DG EDGE"
    const val INDEX = "https://www.dg-edge.com/events/dailies"
    private const val HOST = "https://www.dg-edge.com"
    private const val MAX_WEEK = 53

    /** Race links from the public index; only the requested ISO week can be selected. */
    fun links(indexHtml: String, weekStart: LocalDate): Map<Series, String> {
        val iso = WeekFields.ISO
        val year = weekStart.get(iso.weekBasedYear())
        val week = weekStart.get(iso.weekOfWeekBasedYear())
        val normalized = indexHtml.replace("\\/", "/").replace("&amp;", "&")
        // Never follow arbitrary links from a third-party page.
        val pattern = Regex(
            """(?i)(/events/dailies/week-(\d{1,2})-(\d{4})-race-([abc])-[a-z0-9-]+/\d+)(?:[/?#"'<\s]|$)"""
        )
        val grouped = pattern.findAll(normalized).mapNotNull { m ->
            if (m.groupValues[2].toIntOrNull() != week || m.groupValues[3].toIntOrNull() != year) return@mapNotNull null
            val series = series(m.groupValues[4].uppercase()) ?: return@mapNotNull null
            series to (HOST + m.groupValues[1])
        }.groupBy({ it.first }, { it.second })
        // A collision is ambiguous, never pick one entry at random.
        return grouped.mapNotNull { (series, urls) ->
            urls.distinct().singleOrNull()?.let { series to it }
        }.toMap()
    }

    /** Strict race detail parser: URL and the described race must agree on A/B/C and ISO week. */
    fun parseDetail(html: String, url: String, fetchedAtMs: Long): WeeklyPost? {
        val link = Regex("""^https://www\.dg-edge\.com/events/dailies/week-(\d{1,2})-(\d{4})-race-([abc])-[a-z0-9-]+/\d+/?$""", RegexOption.IGNORE_CASE)
            .matchEntire(url) ?: return null
        val weekNumber = link.groupValues[1].toIntOrNull()?.takeIf { it in 1..MAX_WEEK } ?: return null
        val year = link.groupValues[2].toIntOrNull()?.takeIf { it in 2022..2100 } ?: return null
        val race = series(link.groupValues[3].uppercase()) ?: return null
        val weekStart = runCatching {
            LocalDate.of(year, 1, 4).with(WeekFields.ISO.weekOfWeekBasedYear(), weekNumber.toLong())
                .with(java.time.temporal.ChronoField.DAY_OF_WEEK, DayOfWeek.MONDAY.value.toLong())
        }.getOrNull() ?: return null
        if (weekStart.get(WeekFields.ISO.weekBasedYear()) != year ||
            weekStart.get(WeekFields.ISO.weekOfWeekBasedYear()) != weekNumber) return null

        // SEO summary is rendered publicly, without scripts or authenticated endpoints.
        val meta = Regex("""(?is)<meta\b[^>]*>""").findAll(html).firstNotNullOfOrNull { tag ->
            if (!Regex("""(?i)\bname\s*=\s*["']description["']""").containsMatchIn(tag.value)) return@firstNotNullOfOrNull null
            Regex("""(?is)\bcontent\s*=\s*(["'])(.*?)\1""").find(tag.value)?.groupValues?.get(2)
        }
        val body = html.replace(Regex("""(?is)<script\b[^>]*>.*?</script>"""), " ")
            .replace(Regex("""(?is)<style\b[^>]*>.*?</style>"""), " ")
        val visible = GtPlanetFeed.text(body)
        val description = GtPlanetFeed.text(meta.orEmpty()).takeIf { it.contains("GT7 Daily Race", ignoreCase = true) }
            ?: visible
        val summary = Regex(
            """(?i)GT7 Daily Race ([ABC]) for Week (\d{1,2}) (\d{4}) runs (.{1,140}?) over (\d{1,3}) laps at (.{1,140}?) on (.{1,140}?) tyres\."""
        ).find(description) ?: return null
        if (summary.groupValues[1].uppercase() != link.groupValues[3].uppercase() ||
            summary.groupValues[2].toIntOrNull() != weekNumber ||
            summary.groupValues[3].toIntOrNull() != year) return null

        val laps = summary.groupValues[5].toIntOrNull()?.takeIf { it in 1..150 } ?: return null
        // SEO pages may say "runs the race over 5 laps". That is not a vehicle.
        // Ignore generic placeholders instead of marking real one-make cars contradictory.
        val cars = summary.groupValues[4].trim().takeIf(SportNames::meaningfulCar)
        val track = summary.groupValues[6].trim().takeIf { it.isNotBlank() } ?: return null
        val tyres = GtPlanetFeed.compounds(summary.groupValues[7]).takeIf { it.isNotEmpty() } ?: return null

        // The public card describes "14 x2 x4": laps, fuel, tyre wear. Limit the search to the
        // top part of the visible race detail, not the leaderboard statistics later on the page.
        val header = visible.substring(0, minOf(visible.length, 1700))
        val multipliers = Regex("""(?i)\b(\d{1,3})\s*[x×]\s*(\d{1,2})\s*[x×]\s*(\d{1,2})\b""")
            .findAll(header).firstOrNull { it.groupValues[1].toIntOrNull() == laps }
        val fuel = multipliers?.groupValues?.get(2)?.toIntOrNull()?.takeIf { it in 1..50 }
        val wear = multipliers?.groupValues?.get(3)?.toIntOrNull()?.takeIf { it in 1..50 }

        val settings = description.substringAfter("Race settings:", "")
            .substringBefore("Live leaderboard", "").trim().trimEnd('.', ' ')
        // Some pages truncate the SEO description but include "Required: RM, RS" in
        // the visible race header. Read both, limited to this race's settings section.
        val requirementPattern = Regex("""(?i)\bRequired:\s*((?:R[HMS]|S[HMS]|C[HMS]|IM|W|D)(?:\s*[,/]\s*(?:R[HMS]|S[HMS]|C[HMS]|IM|W|D))*)""")
        val detailHeader = visible.substringBefore("Event stats").take(2500)
        val compulsory = requirementPattern.find(description)?.groupValues?.get(1)
            ?: requirementPattern.find(detailHeader)?.groupValues?.get(1)
        val required = compulsory?.let { TyreCompound.parseCodes(it).toSet().takeIf { c -> c.isNotEmpty() } }
        val stops = Regex("""(?i)(\d{1,2})\s+(?:mandatory\s+)?(?:pit\s+)?stops?\b""")
            .find(settings)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..10 }
        val source = SourceRef(NAME, url, fetchedAtMs)
        val info = DailyRaceInfo(
            series = race, source = source, trackName = track, laps = laps, cars = cars,
            bop = if (settings.contains(Regex("""(?i)\bBoP\b"""))) "BoP" else null,
            settings = settings.takeIf { it.isNotEmpty() },
            startType = when {
                settings.contains("grid start", ignoreCase = true) -> "Grid"
                settings.contains("rolling start", ignoreCase = true) -> "Rolling"
                else -> null
            },
            allowedTyres = tyres, requiredTyres = required,
            mandatoryStops = stops, fuelMultiplier = fuel, tyreMultiplier = wear,
        )
        return WeeklyPost(source, weekStart, "Sport A/B/C — semaine du $weekStart", mapOf(race to info))
    }

    /** Reassemble the three detail pages only when all three refer to the same expected week. */
    fun parseWeek(pages: Map<String, String>, expectedWeek: LocalDate, fetchedAtMs: Long): WeeklyPost? {
        val items = pages.mapNotNull { (url, html) -> parseDetail(html, url, fetchedAtMs) }
            .filter { it.validFrom == expectedWeek }
        val races = items.flatMap { it.races.values }
        if (races.map { it.series }.toSet().size != 3 || races.size != 3) return null
        if (races.any { it.fuelMultiplier == null || it.tyreMultiplier == null }) return null
        val source = SourceRef(NAME, INDEX, fetchedAtMs)
        return WeeklyPost(source, expectedWeek, "Sport A/B/C — semaine du $expectedWeek", races.associateBy { it.series })
    }

    private fun series(letter: String): Series? = when (letter.uppercase(Locale.ROOT)) {
        "A" -> Series.DAILY_A
        "B" -> Series.DAILY_B
        "C" -> Series.DAILY_C
        else -> null
    }
}
