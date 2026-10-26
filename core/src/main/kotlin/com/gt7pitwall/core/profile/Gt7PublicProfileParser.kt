package com.gt7pitwall.core.profile

/**
 * A conservative reader of the PUBLIC text of GT GridStats /player/{PSN_ID}.
 *
 * The site sometimes sends default zero counters while race history is already
 * populated. Only accept season-wide race totals if both Daily Races and World
 * Series telemetry corroborate the totals. Missing != zero.
 *
 * This is neither the provider's documented bearer API nor an official Sony API.
 */
object Gt7PublicProfileParser {
    private val count = """([0-9][0-9,]*)"""

    fun parse(visibleText: String, expectedPsn: String): Gt7CareerStats {
        require(Gt7PlayerProfile.validPsnId(expectedPsn)) { "ID PSN invalide" }
        val text = visibleText.replace(Regex("""\s+"""), " ").trim()
        require(text.length in 35..150000) { "Page publique vide ou excessive" }
        val marker = text.indexOf("Driver Rating", ignoreCase = true)
        require(marker >= 0) { "Classement public introuvable" }

        // The driver's PSN must appear in the title/header, not a random
        // opponent listed in the race history.
        val header = text.substring(maxOf(0, marker - 400), marker)
        val idPattern = Regex(
            "(?i)(?<![A-Za-z0-9_-])" + Regex.escape(expectedPsn) + "(?![A-Za-z0-9_-])"
        )
        require(idPattern.containsMatchIn(header)) { "Profil différent de l'ID PSN demandé" }

        val ratingArea = text.substring(marker, minOf(text.length, marker + 140))
        val ratings = Regex(
            """(?i)Driver Rating\s+(?:(?:\d+(?:[.,]\d+)?)\s*%\s+)?(A\+|A|B|C|D|E|S)\s*/\s*(S|A|B|C|D|E)"""
        ).find(ratingArea) ?: throw IllegalArgumentException("DR/SR non affichés publiquement")
        val dr = ratings.groupValues[1].uppercase()
        val sr = ratings.groupValues[2].uppercase()

        // The DR-point value must follow the rating itself, not "pts to go".
        val drPoints = Regex("""(?i)^\s*([0-9][0-9,]*)\s+pts\b""")
            .find(ratingArea.substring(ratings.range.last + 1))
            ?.groupValues?.get(1)?.let(::toCount)

        val sportStart = text.indexOf("Sport Mode Telemetry", ignoreCase = true)
        val sportEnd = if (sportStart >= 0) {
            listOf("Collector Level", "Daily Race History", "Competition History")
                .map { text.indexOf(it, sportStart + 1, ignoreCase = true) }
                .filter { it >= 0 }.minOrNull() ?: text.length
        } else -1
        val sport = if (sportStart >= 0)
            text.substring(sportStart, sportEnd).take(1200) else ""

        val daily = raceSection(sport, "Daily Races")
        val world = raceSection(sport, "World Series")
        val topArea = if (sportStart >= 0) text.substring(marker, sportStart) else ""

        // Explicit cross-check prevents the common "Total Entries 0"
        // placeholder from replacing verified real-world progress.
        val headlineRaces = field(topArea, "Total Entries")
        val headlineWins = field(topArea, "Victories")
        val headlinePoles = field(topArea, "Poles")

        val corroborated = daily != null && world != null &&
            headlineRaces != null && headlineWins != null &&
            daily.entries.toLong() + world.entries == headlineRaces.toLong() &&
            daily.wins.toLong() + world.wins == headlineWins.toLong() &&
            daily.wins <= daily.entries && world.wins <= world.entries &&
            daily.top5 <= daily.entries && world.top5 <= world.entries &&
            headlineWins <= headlineRaces
        val raceTotal = if (corroborated) headlineRaces else null
        val wins = if (corroborated) headlineWins else null
        val poles = if (corroborated) headlinePoles?.takeIf { it <= headlineRaces!! } else null
        val top5 = if (corroborated) {
            (daily!!.top5.toLong() + world!!.top5).takeIf { it <= Int.MAX_VALUE }?.toInt()
        } else null

        // Independent, clearly labelled totals can be read even if the
        // race counters have not loaded. Only accept bounded, plausible data.
        val collectorLevel = field(text, "Collector Level")?.takeIf { it <= 1000 }
        val garageCars = Regex("""(?i)\bGarage\s+([0-9][0-9,]*)\s+CARS\b""")
            .find(text)?.groupValues?.get(1)?.let(::toCount)?.takeIf { it <= 100000 }
        val distanceKm = Regex("""(?i)\bDistance\s+([0-9][0-9,]*(?:\.[0-9]+)?)\s+KM\b""")
            .find(text)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it in 0.0..1.0e9 }

        val stats = Gt7CareerStats(
            dr = dr, sr = sr, drPoints = drPoints,
            races = raceTotal, wins = wins, poles = poles, top5 = top5,
            garageCars = garageCars, collectorLevel = collectorLevel,
            distanceKm = distanceKm
        )
        require(stats.validate() == null) { "Statistiques publiques incohérentes" }
        return stats
    }

    private data class RaceGroup(val entries: Int, val wins: Int, val top5: Int)

    private fun raceSection(sport: String, label: String): RaceGroup? {
        val regex = Regex(
            "(?i)\\b" + Regex.escape(label) +
                "\\s+" + count + "\\s+Entries\\s+Wins\\s+" + count +
                "\\s+Top\\s*5\\s+" + count
        )
        val match = regex.find(sport) ?: return null
        val data = (1..3).map { toCount(match.groupValues[it]) ?: return null }
        return RaceGroup(data[0], data[1], data[2])
    }

    private fun field(text: String, label: String): Int? =
        Regex("(?i)\\b" + Regex.escape(label) + "\\s+" + count)
            .find(text)?.groupValues?.get(1)?.let(::toCount)

    private fun toCount(raw: String): Int? =
        raw.replace(",", "").toIntOrNull()?.takeIf { it >= 0 }
}
