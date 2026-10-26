package com.gt7pitwall.core.sport

import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.strategy.TyreCompound
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Flux RSS public de GTPlanet pour les courses quotidiennes de GT7
 * (https://www.gtplanet.net/tag/gran-turismo-7-daily-races/feed/). Chaque article hebdomadaire contient, pour chaque
 * course, une liste « Libellé : valeur » (Track, Car, Power/Weight/PP Limit, Tires, Settings, Start Type, Mandatory Pit
 * Stop, Fuel Multiplier, Tire Multiplier). Seuls ces libellés sont lus : la prose de l'article n'est jamais interprétée.
 * Un libellé absent ou illisible laisse le champ inconnu.
 */
object GtPlanetFeed {
    const val NAME = "GTPlanet"
    const val URL = "https://www.gtplanet.net/tag/gran-turismo-7-daily-races/feed/"

    /**
     * Secours, même site et même format (flux WordPress standard) : le flux général, où l'article de la semaine figure
     * dans les jours qui suivent sa publication. Lu seulement si le flux de l'étiquette échoue ou n'a pas la semaine.
     */
    const val FALLBACK_URL = "https://www.gtplanet.net/feed/"
    val PARIS: ZoneId = ZoneId.of("Europe/Paris")

    /** Articles hebdomadaires du flux, du plus récent au plus ancien. Un article sans fiche lisible est ignoré. */
    fun parse(xml: String, fetchedAtMs: Long): List<WeeklyPost> =
        Regex("(?s)<item>(.*?)</item>").findAll(xml).mapNotNull { item(it.groupValues[1], fetchedAtMs) }.toList()

    private fun item(x: String, fetchedAtMs: Long): WeeklyPost? {
        val title = tag(x, "title")?.let(::text) ?: return null
        val link = tag(x, "link")?.trim()
        val pub = tag(x, "pubDate")?.trim()?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME) }.getOrNull() }
            ?: return null
        val content = tag(x, "content:encoded") ?: return null
        val source = SourceRef(NAME, link, fetchedAtMs)
        val races = sections(content).mapNotNull { (series, body) -> race(series, body, source) }.associateBy { it.series }
        if (races.isEmpty()) return null
        return WeeklyPost(source, pub.withZoneSameInstant(PARIS).toLocalDate(), title, races)
    }

    /** Contenu d'une balise (CDATA retiré). */
    private fun tag(x: String, name: String): String? =
        Regex("(?s)<${Regex.escape(name)}[^>]*>(.*?)</${Regex.escape(name)}>").find(x)?.groupValues?.get(1)
            ?.replace(Regex("(?s)^\\s*<!\\[CDATA\\[(.*)]]>\\s*$"), "$1")

    /** Titres « … Race A » suivis de leur liste. */
    private fun sections(html: String): List<Pair<Series, String>> {
        val heads = Regex("(?is)<h[2-4][^>]*>(.*?)</h[2-4]>").findAll(html).toList()
        return heads.mapIndexedNotNull { i, h ->
            val letter = Regex("(?i)\\brace\\s+([ABC])\\b").find(text(h.groupValues[1]))?.groupValues?.get(1)?.uppercase()
                ?: return@mapIndexedNotNull null
            val series = when (letter) {
                "A" -> Series.DAILY_A
                "B" -> Series.DAILY_B
                else -> Series.DAILY_C
            }
            val end = heads.getOrNull(i + 1)?.range?.first ?: html.length
            series to html.substring(h.range.last + 1, end)
        }
    }

    private fun race(series: Series, body: String, source: SourceRef): DailyRaceInfo? {
        val fields = Regex("(?is)<li[^>]*>(.*?)</li>").findAll(body).mapNotNull { li ->
            val t = text(li.groupValues[1])
            val m = Regex("^([^:]{2,40}):\\s*(.*)$").find(t) ?: return@mapNotNull null
            m.groupValues[1].trim().lowercase(Locale.ROOT) to m.groupValues[2].trim()
        }.toList()
        if (fields.isEmpty()) return null
        fun field(vararg keys: String): String? = fields.firstOrNull { (k, _) -> keys.any { it == k } }?.second?.takeIf { it.isNotBlank() }

        val trackRaw = field("track", "circuit", "course")
        val laps = trackRaw?.let { Regex("(?i)\\((\\d{1,3})\\s*laps?\\)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: field("laps")?.let { Regex("^(\\d{1,3})\\b").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val duration = trackRaw?.let { Regex("(?i)\\((\\d{1,3})\\s*min(ute)?s?\\)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val trackName = trackRaw?.replace(Regex("\\s*\\([^)]*\\)\\s*$"), "")?.trim()?.takeIf { it.isNotEmpty() }

        val tyresRaw = field("tires", "tyres", "tire", "tyre", "tire compounds", "tyre compounds")
        val allowed = tyresRaw?.let(::compounds)?.takeIf { it.isNotEmpty() }
        val requiredRaw = field("required tires", "required tyres", "mandatory tires", "mandatory tyres", "compulsory tires", "compulsory tyres")
        val required = when {
            requiredRaw != null -> compounds(requiredRaw).takeIf { it.isNotEmpty() } ?: if (isNone(requiredRaw)) emptySet() else null
            tyresRaw != null && Regex("(?i)required|mandatory|compulsory|must").containsMatchIn(tyresRaw) -> allowed
            else -> null
        }

        return DailyRaceInfo(
            series = series,
            source = source,
            trackName = trackName,
            laps = laps,
            durationMin = duration,
            cars = field("car", "cars", "category"),
            bop = field("power/weight/pp limit", "bop", "power/weight limit"),
            allowedTyres = allowed,
            requiredTyres = required,
            settings = field("settings", "tuning"),
            startType = field("start type", "start"),
            mandatoryStops = field("mandatory pit stop", "mandatory pit stops", "pit stops")?.let(::stops),
            fuelMultiplier = field("fuel multiplier", "fuel consumption")?.let(::multiplier),
            tyreMultiplier = field("tire multiplier", "tyre multiplier", "tire wear", "tyre wear")?.let(::multiplier),
            lines = fields.map { (k, v) -> "$k: $v" },
        )
    }

    private fun isNone(v: String) = v.trim().let { it in setOf("–", "-", "—", "") || Regex("(?i)^(none|no|n/a|0)$").matches(it) }

    /** « – » ou « None » : aucun arrêt (dit par la source) ; un nombre : ce nombre ; autre chose : illisible (null). */
    private fun stops(v: String): Int? = when {
        isNone(v) -> 0
        else -> Regex("^(\\d{1,2})\\b").find(v.trim())?.groupValues?.get(1)?.toIntOrNull()
    }

    /** « 2x », « x2 », « 2 » → 2 ; valeurs non entières ou illisibles → null. */
    private fun multiplier(v: String): Int? =
        Regex("^(?:x\\s*)?(\\d{1,2})(?:\\s*x)?$", RegexOption.IGNORE_CASE).find(v.trim())?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it >= 1 }

    /** Gommes nommées en anglais (« Racing Hard, Racing Medium ») → codes GT7. */
    fun compounds(v: String): Set<TyreCompound> {
        val out = linkedSetOf<TyreCompound>()
        val t = v.lowercase(Locale.ROOT)
        Regex("(racing|sports?|comfort)\\s+(hard|medium|soft)s?").findAll(t).forEach { m ->
            val family = when {
                m.groupValues[1].startsWith("racing") -> "R"
                m.groupValues[1].startsWith("sport") -> "S"
                else -> "C"
            }
            out += TyreCompound.valueOf(family + m.groupValues[2].first().uppercaseChar())
        }
        if (Regex("\\bintermediates?\\b").containsMatchIn(t)) out += TyreCompound.IM
        if (Regex("\\bwet\\b").containsMatchIn(t)) out += TyreCompound.W
        if (Regex("\\bdirt\\b").containsMatchIn(t)) out += TyreCompound.D
        // Codes déjà écrits « RH / RM ».
        out += TyreCompound.parseCodes(v.replace(Regex("[^A-Za-z|,/ ]"), " ")).filter { it.name.length == 2 }
        return out
    }

    /** Texte d'un fragment HTML : balises retirées, entités décodées, espaces normalisés. */
    fun text(html: String): String = decode(html.replace(Regex("<[^>]+>"), " ")).replace(Regex("\\s+"), " ").trim()

    private fun decode(s: String): String {
        var t = s
        t = Regex("&#(\\d+);").replace(t) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
        t = Regex("&#x([0-9a-fA-F]+);").replace(t) { m -> m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value }
        return t.replace("&nbsp;", " ").replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    }
}
