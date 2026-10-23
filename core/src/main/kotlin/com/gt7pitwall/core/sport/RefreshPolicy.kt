package com.gt7pitwall.core.sport

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZonedDateTime

/**
 * Quand relire le programme Sport. Les courses changent le lundi (heure de Paris) ; la fiche publique arrive dans la
 * journée. On relit donc : sans cache ; quand aucune édition ne couvre aujourd'hui ; le lundi et le mardi tant que la
 * semaine en cours manque ; et au plus une fois par jour sinon (correction de la source). Jamais plus d'une fois toutes
 * les [MIN_INTERVAL_MS], pour ne pas solliciter la source inutilement.
 */
object RefreshPolicy {
    const val MIN_INTERVAL_MS = 30 * 60_000L
    const val DAILY_MS = 24 * 3_600_000L

    fun needsRefresh(now: ZonedDateTime, lastFetchMs: Long?, state: ScheduleState?): Boolean {
        val nowMs = now.toInstant().toEpochMilli()
        if (lastFetchMs == null || state == null) return true
        val since = nowMs - lastFetchMs
        if (since < MIN_INTERVAL_MS) return false
        val today = now.withZoneSameInstant(GtPlanetFeed.PARIS).toLocalDate()
        val weekStart = ScheduleBuilder.weekStart(today)
        val haveThisWeek = state.history.any { it.validFrom == weekStart }
        return when {
            state.editions.isEmpty() -> true
            !haveThisWeek && today.dayOfWeek in setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY) -> true
            !haveThisWeek -> true
            else -> since >= DAILY_MS
        }
    }

    /** Âge lisible de la dernière lecture (« il y a 3 h »). */
    fun age(nowMs: Long, lastFetchMs: Long?): String {
        if (lastFetchMs == null) return "jamais lu"
        val min = (nowMs - lastFetchMs) / 60_000
        return when {
            min < 1 -> "à l'instant"
            min < 60 -> "il y a $min min"
            min < 48 * 60 -> "il y a ${min / 60} h"
            else -> "il y a ${min / 1440} jours"
        }
    }

    fun fromMs(ms: Long): ZonedDateTime = Instant.ofEpochMilli(ms).atZone(GtPlanetFeed.PARIS)
}
