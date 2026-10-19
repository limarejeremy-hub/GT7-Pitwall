package com.gt7pitwall.core.sport

import com.gt7pitwall.core.knowledge.Course
import com.gt7pitwall.core.knowledge.Gt7Database
import com.gt7pitwall.core.race.Series
import java.text.Normalizer
import java.time.LocalDate

/**
 * Programme Sport à partir des articles de toutes les sources : une édition par course et par semaine. Une valeur
 * donnée par une seule source est « probable » ; concordante entre deux sources, « confirmée » ; discordante,
 * « contradictoire » et non appliquée.
 */
object ScheduleBuilder {
    /** Une édition vaut une semaine (les courses quotidiennes changent chaque lundi). */
    const val EDITION_DAYS = 7L

    fun build(posts: List<WeeklyPost>, database: Gt7Database, today: LocalDate, lastFetchMs: Long?, lastError: String?): ScheduleState {
        val byWeek = posts.groupBy { weekStart(it.validFrom) }
        val editions = byWeek.flatMap { (start, weekPosts) ->
            Series.entries.filter { it != Series.OTHER }.mapNotNull { s ->
                val infos = weekPosts.mapNotNull { it.races[s] }
                if (infos.isEmpty()) null else merge(s, start, infos, database)
            }
        }.sortedByDescending { it.validFrom }
        val current = editions.filter { it.covers(today) }.associateBy { it.series }
        return ScheduleState(current, editions, lastFetchMs, lastError, stale = current.isEmpty())
    }

    /**
     * Début de l'édition : le lundi de la semaine de publication (un article publié lundi soir ou mardi décrit la
     * semaine commencée ce lundi).
     */
    fun weekStart(day: LocalDate): LocalDate = day.minusDays((day.dayOfWeek.value - 1).toLong())

    fun merge(series: Series, start: LocalDate, infos: List<DailyRaceInfo>, database: Gt7Database): Edition {
        fun <T> field(get: (DailyRaceInfo) -> T?, same: (T, T) -> Boolean = { a, b -> a == b }): Sourced<T> {
            val given = infos.mapNotNull { i -> get(i)?.let { it to i.source } }
            if (given.isEmpty()) return Sourced.unknown()
            val first = given.first().first
            if (given.any { !same(it.first, first) }) {
                return Sourced(null, FieldStatus.CONFLICT, given.map { it.second }, given.joinToString(" / ") { "${it.second.name} : ${it.first}" })
            }
            val distinctSources = given.map { it.second.name }.distinct().size
            return Sourced(first, if (distinctSources >= 2) FieldStatus.CONFIRMED else FieldStatus.PROBABLE, given.map { it.second })
        }
        val trackName = field({ it.trackName }) { a, b -> norm(a) == norm(b) }
        val matches = trackName.value?.let { matchTrack(it, database) } ?: emptyList()
        return Edition(
            series = series,
            validFrom = start,
            validTo = start.plusDays(EDITION_DAYS),
            trackName = trackName,
            trackId = matches.singleOrNull()?.id,
            trackCandidates = if (matches.size > 1) matches.map { it.id } else emptyList(),
            laps = field({ it.laps }),
            cars = field({ it.cars }),
            bop = field({ it.bop }),
            settings = field({ it.settings }),
            startType = field({ it.startType }),
            allowedTyres = field({ it.allowedTyres }),
            requiredTyres = field({ it.requiredTyres }),
            mandatoryStops = field({ it.mandatoryStops }),
            fuelMultiplier = field({ it.fuelMultiplier }),
            tyreMultiplier = field({ it.tyreMultiplier }),
            sources = infos.map { it.source }.distinctBy { it.name },
        )
    }

    /**
     * Circuit de la base GT7 désigné par un nom publié. Nom identique d'abord ; sinon un seul circuit dont le nom
     * contient tous les mots publiés (tracé principal, sens normal). Plusieurs possibilités : ambigu, rien n'est choisi.
     */
    fun matchTrack(name: String, database: Gt7Database): List<Course> {
        val n = norm(name)
        if (n.isEmpty()) return emptyList()
        val courses = database.courses.distinctBy { it.id }
        courses.filter { norm(it.name) == n && !it.reverse }.let { if (it.isNotEmpty()) return it.take(1) }
        val words = n.split(' ').filter { it.length > 1 }
        val contains = courses.filter { c -> !c.reverse && words.all { w -> w in norm(c.name).split(' ') } }
        if (contains.size <= 1) return contains
        // Plusieurs tracés : le principal (tracé 1) seulement s'il est unique.
        return contains.filter { it.layout == 1 }.takeIf { it.size == 1 } ?: contains
    }

    fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
}
