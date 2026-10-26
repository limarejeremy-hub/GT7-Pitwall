package com.gt7pitwall.core.sport

import com.gt7pitwall.core.race.Series
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Indicateur discret de fraîcheur des données (programme Sport, base voitures/circuits). Il dit ce qui est su, sans
 * rien présenter comme à jour quand ce ne l'est pas : une fiche manquante reste « en attente » ou « introuvable ».
 */
data class DataFreshness(val level: Level, val text: String, val detail: String) {
    enum class Level { OK, WAITING, PROBLEM }

    companion object {
        /** Le lundi, la fiche publique arrive dans la journée ; au-delà de mardi, son absence est un problème. */
        fun of(
            schedule: ScheduleState?,
            today: LocalDate,
            nowMs: Long,
            dbLastMs: Long?,
            dbError: String?,
        ): DataFreshness {
            val details = mutableListOf<String>()
            val series = listOf(Series.DAILY_A, Series.DAILY_B, Series.DAILY_C)
            val week = ScheduleBuilder.weekStart(today)
            val have = schedule?.editions?.filterKeys { it in series }.orEmpty().filterValues { it.validFrom == week }
            val early = today.dayOfWeek in setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY)
            val sportLevel = when {
                have.size == series.size -> Level.OK
                early -> Level.WAITING
                else -> Level.PROBLEM
            }
            val sportText = when {
                have.size == series.size -> "Sport ${week.dayOfMonth}/${week.monthValue}"
                have.isNotEmpty() -> "Sport ${have.size}/3"
                early -> "Sport en attente"
                else -> "Sport introuvable"
            }
            details += when {
                have.size == series.size -> "Courses A, B, C de la semaine du ${week.dayOfMonth}/${week.monthValue} lues"
                have.isNotEmpty() -> "Semaine en cours : ${have.keys.joinToString(", ") { it.label }} seulement"
                early -> "Fiche de la semaine pas encore publiée ou pas encore lue"
                else -> "Aucune fiche publique lue pour cette semaine"
            } + (schedule?.lastFetchMs?.let { " ; source vérifiée ${RefreshPolicy.age(nowMs, it)}" } ?: " ; source jamais lue")
            schedule?.lastError?.let { details += "Dernière lecture : $it" }
            val dbLevel = when {
                dbLastMs == null -> Level.WAITING
                dbError != null -> Level.WAITING
                nowMs - dbLastMs > DB_STALE_MS -> Level.WAITING
                else -> Level.OK
            }
            val dbText = if (dbLastMs == null) "Base livrée" else "Base ${short(nowMs - dbLastMs)}"
            details += "Voitures et circuits : " + (dbLastMs?.let { "vérifiés ${RefreshPolicy.age(nowMs, it)}" } ?: "version livrée avec l'application") +
                (dbError?.let { " ; dernière mise à jour impossible ($it)" } ?: "")
            val level = listOf(sportLevel, dbLevel).maxBy { it.ordinal }
            return DataFreshness(level, "$sportText · $dbText", details.joinToString("\n"))
        }

        /** Au-delà de 3 jours sans vérification réussie, la base est signalée (réseau absent, source muette). */
        const val DB_STALE_MS = 3 * 24 * 3_600_000L

        private fun short(ms: Long): String {
            val h = ms / 3_600_000
            return when {
                h < 1 -> "à jour"
                h < 48 -> "${h} h"
                else -> "${h / 24} j"
            }
        }
    }
}
