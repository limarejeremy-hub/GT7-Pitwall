package com.gt7pitwall.core.engineer

/**
 * Niveaux de radio :
 *  - CRITICAL : rentrer aux stands, risque de panne, obligation réglementaire (toujours diffusé) ;
 *  - HIGH : changement important de stratégie, adhérence, pneus ;
 *  - NORMAL / INFO : chronos, statistiques, commentaires.
 */
enum class RadioLevel {
    /** Tout. */
    FULL,

    /** Critique + utile. */
    ESSENTIAL,

    /** Critique seulement (« silence »). */
    CRITICAL_ONLY,
}

/** Tri par priorité et suppression des répétitions. Les réponses aux questions du pilote passent toujours. */
class RadioFilter(
    private val clock: () -> Long = System::currentTimeMillis,
    private val repeatWindowMs: Long = 60_000,
) {
    var level: RadioLevel = RadioLevel.FULL
    private val lastSaid = HashMap<String, Long>()

    fun reset() = lastSaid.clear()

    fun apply(messages: List<EngineerMessage>): List<EngineerMessage> {
        val now = clock()
        return messages
            .filter { allowed(level, it) }
            .filter { m ->
                if (m.category == MessageCategory.ANSWER || m.category == MessageCategory.DRIVER) return@filter true
                val key = m.dedupKey ?: "${m.category}:${m.text}"
                val last = lastSaid[key]
                if (last != null && now - last < repeatWindowMs) return@filter false
                lastSaid[key] = now
                true
            }
            .let(::criticalFirst)
    }

    companion object {
        /** Les messages critiques passent devant ; les autres gardent l'ordre naturel (chrono puis arrivée…). */
        fun criticalFirst(messages: List<EngineerMessage>): List<EngineerMessage> =
            messages.sortedByDescending { if (it.priority == Priority.CRITICAL) 1 else 0 }

        fun allowed(level: RadioLevel, m: EngineerMessage): Boolean = when {
            m.category == MessageCategory.ANSWER || m.category == MessageCategory.DRIVER -> true
            m.priority == Priority.CRITICAL -> true
            m.priority == Priority.HIGH -> level != RadioLevel.CRITICAL_ONLY
            else -> level == RadioLevel.FULL
        }
    }
}
