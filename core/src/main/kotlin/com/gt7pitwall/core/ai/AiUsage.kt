package com.gt7pitwall.core.ai

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Requêtes réellement envoyées à chaque modèle cloud, et pauses après un refus du service. Aucun quota n'est supposé :
 * on ne connaît que ce que Google répond (429, 503…) et, si le pilote l'a saisi, le plafond lu dans sa console.
 * Les compteurs journaliers suivent le jour du Pacifique, celui des quotas de l'API Gemini.
 */
class AiUsage(private val clock: () -> Long = System::currentTimeMillis) {

    data class Stats(
        val requestsToday: Int = 0,
        val successesToday: Int = 0,
        val failuresToday: Int = 0,
        val lastLatencyMs: Long? = null,
        val lastError: AiResult.Failure? = null,
        val lastErrorAtMs: Long? = null,
        /** Pas de nouvel appel avant cette heure (après un 429 ou un 503). */
        val pausedUntilMs: Long? = null,
        val day: String = "",
    )

    private val stats = mutableMapOf<AiBackend, Stats>()

    /** Plafonds journaliers saisis par le pilote (lus dans sa console Google) ; absent = aucun plafond local. */
    val dailyCaps = mutableMapOf<AiBackend, Int>()

    @Synchronized
    fun stats(b: AiBackend): Stats = current(b)

    /** Le modèle peut être interrogé maintenant : pas en pause, sous le plafond saisi par le pilote. */
    @Synchronized
    fun canSend(b: AiBackend): Boolean {
        val s = current(b)
        if (s.pausedUntilMs?.let { clock() < it } == true) return false
        val cap = dailyCaps[b]
        return cap == null || s.requestsToday < cap
    }

    /** Raison pour laquelle le modèle n'est pas interrogé, ou null. */
    @Synchronized
    fun blockedReason(b: AiBackend): String? {
        val s = current(b)
        s.pausedUntilMs?.takeIf { clock() < it }?.let { until ->
            val min = ((until - clock()) / 60_000L).coerceAtLeast(1)
            return "en pause $min min après « ${s.lastError?.kind?.label ?: "refus"} »"
        }
        val cap = dailyCaps[b] ?: return null
        return if (s.requestsToday >= cap) "plafond du jour atteint ($cap requêtes, saisi dans les réglages)" else null
    }

    /** Une requête part vraiment vers le service. */
    @Synchronized
    fun onSent(b: AiBackend) {
        val s = current(b)
        stats[b] = s.copy(requestsToday = s.requestsToday + 1)
    }

    @Synchronized
    fun onSuccess(b: AiBackend, latencyMs: Long) {
        val s = current(b)
        stats[b] = s.copy(successesToday = s.successesToday + 1, lastLatencyMs = latencyMs, pausedUntilMs = null)
        backoff.remove(b)
    }

    /** Refus du service : pause courte (doublée à chaque refus), ou jusqu'au jour suivant si le plafond est journalier. */
    @Synchronized
    fun onFailure(b: AiBackend, f: AiResult.Failure, latencyMs: Long?) {
        val s = current(b)
        val now = clock()
        val pause = when (f.kind) {
            FailureKind.QUOTA -> if (f.daily) nextPacificMidnight(now) else now + nextBackoff(b, QUOTA_PAUSE_MS)
            FailureKind.OVERLOADED -> now + nextBackoff(b, OVERLOAD_PAUSE_MS)
            FailureKind.MODEL_UNAVAILABLE, FailureKind.AUTH, FailureKind.NOT_CONFIGURED -> now + CONFIG_PAUSE_MS
            else -> null
        }
        stats[b] = s.copy(
            failuresToday = s.failuresToday + 1,
            lastLatencyMs = latencyMs ?: s.lastLatencyMs,
            lastError = f,
            lastErrorAtMs = now,
            pausedUntilMs = pause ?: s.pausedUntilMs,
        )
    }

    /** Le pilote a corrigé la configuration : on peut réessayer tout de suite. */
    @Synchronized
    fun clearPause(b: AiBackend) {
        stats[b] = current(b).copy(pausedUntilMs = null)
        backoff.remove(b)
    }

    private val backoff = mutableMapOf<AiBackend, Long>()

    private fun nextBackoff(b: AiBackend, base: Long): Long {
        val next = backoff[b]?.let { (it * 2).coerceAtMost(MAX_PAUSE_MS) } ?: base
        backoff[b] = next
        return next
    }

    private fun current(b: AiBackend): Stats {
        val today = pacificDay(clock())
        val s = stats[b]
        if (s == null || s.day != today) {
            // Nouveau jour : compteurs à zéro, la pause éventuelle reste (elle a sa propre échéance).
            val fresh = Stats(day = today, pausedUntilMs = s?.pausedUntilMs, lastError = s?.lastError, lastErrorAtMs = s?.lastErrorAtMs, lastLatencyMs = s?.lastLatencyMs)
            stats[b] = fresh
            return fresh
        }
        return s
    }

    /** Sauvegarde compacte (une ligne par modèle) pour survivre à la fermeture de l'application. */
    @Synchronized
    fun save(): String = stats.entries.joinToString("\n") { (b, s) ->
        listOf(b.name, s.day, s.requestsToday, s.successesToday, s.failuresToday, s.pausedUntilMs ?: 0, s.lastLatencyMs ?: -1).joinToString(";")
    }

    @Synchronized
    fun restore(saved: String?) {
        saved?.lines()?.forEach { line ->
            val p = line.split(';')
            if (p.size < 7) return@forEach
            val b = runCatching { AiBackend.valueOf(p[0]) }.getOrNull() ?: return@forEach
            stats[b] = Stats(
                day = p[1],
                requestsToday = p[2].toIntOrNull() ?: 0,
                successesToday = p[3].toIntOrNull() ?: 0,
                failuresToday = p[4].toIntOrNull() ?: 0,
                pausedUntilMs = p[5].toLongOrNull()?.takeIf { it > 0 },
                lastLatencyMs = p[6].toLongOrNull()?.takeIf { it >= 0 },
            )
        }
    }

    companion object {
        val PACIFIC: ZoneId = ZoneId.of("America/Los_Angeles")
        const val QUOTA_PAUSE_MS = 60_000L
        const val OVERLOAD_PAUSE_MS = 30_000L
        const val CONFIG_PAUSE_MS = 10 * 60_000L
        const val MAX_PAUSE_MS = 60 * 60_000L

        fun pacificDay(ms: Long): String = Instant.ofEpochMilli(ms).atZone(PACIFIC).toLocalDate().toString()

        fun nextPacificMidnight(ms: Long): Long {
            val z: ZonedDateTime = Instant.ofEpochMilli(ms).atZone(PACIFIC).toLocalDate().plusDays(1).atStartOfDay(PACIFIC)
            return z.toInstant().toEpochMilli()
        }
    }
}
