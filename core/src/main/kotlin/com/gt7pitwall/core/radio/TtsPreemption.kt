package com.gt7pitwall.core.radio

import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.engineer.Priority

/**
 * V511 (revue PR #86) — préemption de la synthèse Android du Fold (`TextToSpeech`), en Kotlin pur pour être testée.
 *
 * Défaut corrigé : `TtsVoiceOutput` décidait du `QUEUE_FLUSH` d'après le message EN COURS (`current`, posé seulement au
 * rappel `onStart`). Deux alertes critiques arrivant immédiatement : la première est dans la file mais pas encore
 * commencée, la seconde voit `current == null`, vide la file et efface la première.
 *
 * Ici la décision suit TOUTE la file (énoncés placés, commencés ou non, jusqu'à leur fin) :
 * - alerte critique sans autre alerte critique en file → [Action.FLUSH] : elle coupe et remplace les annonces non
 *   critiques (préemption inchangée) ;
 * - alerte critique alors qu'une autre est déjà en file → [Action.ADD] : elle passe juste après, sans rien effacer ;
 * - annonce non critique pendant qu'une alerte critique est en file → [Action.DEFER] : retenue ici, puis rendue par
 *   [finished] quand la dernière alerte critique est terminée (jamais intercalée entre deux alertes) ;
 * - sinon → [Action.ADD].
 */
class TtsPreemption {
    enum class Action { FLUSH, ADD, DEFER }

    private val queued = LinkedHashMap<String, Priority>()
    private val deferred = ArrayDeque<EngineerMessage>()

    private fun criticalQueued() = queued.values.any { it == Priority.CRITICAL }

    @Synchronized
    fun decide(message: EngineerMessage): Action = when {
        message.priority == Priority.CRITICAL -> if (criticalQueued()) Action.ADD else Action.FLUSH
        criticalQueued() -> Action.DEFER.also { deferred.addLast(message) }
        else -> Action.ADD
    }

    /** Énoncé placé dans la file Android (phrase, silence entre phrases, silence de fin). */
    @Synchronized
    fun queued(utteranceId: String, priority: Priority) {
        queued[utteranceId] = priority
    }

    /** `QUEUE_FLUSH` vient d'être demandé : tout ce qui était en file (forcément non critique) est effacé. */
    @Synchronized
    fun flushed() {
        queued.clear()
    }

    /**
     * Fin d'un énoncé (fin, erreur, arrêt). Renvoie les annonces retenues à dire maintenant, quand plus aucune alerte
     * critique n'est en file.
     */
    @Synchronized
    fun finished(utteranceId: String): List<EngineerMessage> {
        queued.remove(utteranceId)
        if (criticalQueued() || deferred.isEmpty()) return emptyList()
        return deferred.toList().also { deferred.clear() }
    }

    /** `tts.stop()` : file vide, annonces retenues abandonnées. */
    @Synchronized
    fun stopped() {
        queued.clear()
        deferred.clear()
    }

    val deferredCount: Int
        @Synchronized get() = deferred.size
}
