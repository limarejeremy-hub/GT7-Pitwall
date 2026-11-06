package com.gt7pitwall.core.ai.tools

/**
 * V492 : latence des réponses de Véga (bout en bout, côté téléphone), en mémoire seulement : aucune donnée envoyée ni
 * gardée après fermeture. p50 / p95 sur les [capacity] dernières mesures, par chemin (avec ou sans outils).
 */
class LatencyStats(private val capacity: Int = 50) {
    private val samples = ArrayDeque<Long>()

    @Synchronized
    fun record(ms: Long) {
        samples.addLast(ms.coerceAtLeast(0))
        while (samples.size > capacity) samples.removeFirst()
    }

    @get:Synchronized
    val count: Int get() = samples.size

    companion object {
        /** En dessous, un p95 n'est qu'une indication (revue #72 : un p95 de n=1 n'est pas un indicateur). */
        const val MIN_FOR_P95 = 10
    }

    /** Percentile au plus proche rang (0 < [p] ≤ 100) ; null sans mesure. */
    @Synchronized
    fun percentile(p: Int): Long? {
        if (samples.isEmpty()) return null
        val sorted = samples.sorted()
        val rank = Math.ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    /** « p50 3,1 s · p95 8,2 s (12 mesures) », ou null sans mesure. */
    fun summary(): String? {
        val n = count
        if (n == 0) return null
        fun s(ms: Long) = String.format(java.util.Locale.FRANCE, "%.1f s", ms / 1000.0)
        val note = if (n < MIN_FOR_P95) " : trop peu de mesures pour un p95 fiable (il en faut $MIN_FOR_P95)" else ""
        return "p50 ${s(percentile(50)!!)} · p95 ${s(percentile(95)!!)} ($n mesure${if (n > 1) "s" else ""}$note)"
    }
}
