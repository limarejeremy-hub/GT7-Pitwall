package com.gt7pitwall.core.radio

import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.engineer.Priority

/** Sortie vocale distante (bot Discord dans le salon vocal de la PS5) : peut décrocher à tout moment. */
interface RemoteVoice {
    /** Prête à diffuser : dans le salon vocal, connexion établie. */
    val live: Boolean

    /** [onFailed] est appelé si le message n'a pas pu être diffusé (connexion perdue, synthèse impossible). */
    fun speak(message: EngineerMessage, onFailed: (EngineerMessage) -> Unit)

    fun stop()
}

enum class VoiceRoute { PHONE, REMOTE, BOTH }

/**
 * Choisit où parle l'ingénieur : Discord quand le bot est dans le salon, sinon le haut-parleur du Fold. Jamais les deux,
 * sauf réglage explicite [mirrorOnPhone]. Un message que Discord n'a pas pu diffuser est rejoué sur le Fold s'il est
 * encore d'actualité : aucune alerte n'est perdue quand la connexion tombe.
 */
class RoutedVoiceOutput(
    private val phone: VoiceOutput,
    private val remote: RemoteVoice,
    private val clock: () -> Long = System::currentTimeMillis,
) : VoiceOutput {

    @Volatile
    var mirrorOnPhone: Boolean = false

    val route: VoiceRoute
        get() = when {
            !remote.live -> VoiceRoute.PHONE
            mirrorOnPhone -> VoiceRoute.BOTH
            else -> VoiceRoute.REMOTE
        }

    override fun speak(message: EngineerMessage) {
        when (route) {
            VoiceRoute.PHONE -> phone.speak(message)
            VoiceRoute.BOTH -> {
                remote.speak(message) { }
                phone.speak(message)
            }
            VoiceRoute.REMOTE -> remote.speak(message) { failed ->
                if (clock() - failed.timestampMs <= STALE_MS) phone.speak(failed)
            }
        }
    }

    override fun stop() {
        phone.stop()
        remote.stop()
    }

    companion object {
        /** Au-delà, un message non diffusé n'est plus rejoué (« essence pour 3 tours » dit avec 20 s de retard est faux). */
        const val STALE_MS = 10_000L
    }
}

/**
 * File de diffusion de l'ingénieur, comme sur le haut-parleur : les messages passent dans l'ordre, une alerte critique
 * vide la file et coupe la phrase en cours. [generation] change à chaque coupure : une phrase préparée avant est abandonnée.
 */
class SpeechQueue<T>(private val priorityOf: (T) -> Priority) {
    private val items = ArrayDeque<T>()

    @Volatile
    var generation: Long = 0
        private set

    /** Ajoute [item] ; renvoie les éléments écartés par une alerte critique (la phrase en cours doit être coupée). */
    @Synchronized
    fun offer(item: T): List<T>? {
        if (priorityOf(item) != Priority.CRITICAL) {
            items.addLast(item)
            return null
        }
        val dropped = items.toList()
        items.clear()
        generation++
        items.addLast(item)
        return dropped
    }

    @Synchronized
    fun poll(): T? = items.removeFirstOrNull()

    /** Vide la file (connexion perdue, sortie du salon) et renvoie ce qui n'a pas été diffusé. */
    @Synchronized
    fun drain(): List<T> {
        generation++
        return items.toList().also { items.clear() }
    }
}
