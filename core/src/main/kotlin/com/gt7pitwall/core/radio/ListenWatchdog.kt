package com.gt7pitwall.core.radio

/**
 * Surveillance de l'écoute « Radio » (issue #32, essai APK #426 : « Radio » muet pendant 14 tours alors que la voix de
 * l'ingénieur et la télémétrie continuaient). L'écoute peut rester bloquée sans aucun signe à l'écran :
 * - arrêtée en erreur (micro indisponible pendant une bascule Discord / Fold) : un redémarrage demandé ensuite était
 *   ignoré, faute d'écoute « en cours » ;
 * - en pause indéfiniment, parce que la voix de l'ingénieur semble toujours parler (fin de lecture jamais signalée) ;
 * - micro prêté à la reconnaissance Google, jamais rendu ;
 * - boucle audio qui ne reçoit plus aucun bloc ;
 * - micro du casque PS5 disponible mais écoute restée sur le Fold (bascule manquée), ou l'inverse.
 *
 * Ce moniteur ne touche jamais au son : il lit l'état à chaque appel et dit quoi faire, avec la raison (journal et
 * Atelier). Les reprises sont espacées ([RETRY_MS]) : pas de boucle qui épuise la batterie ni de coupure en pleine phrase.
 */
class ListenWatchdog(
    private val maxSpeakingMs: Long = MAX_SPEAKING_MS,
    private val maxReleasedMs: Long = MAX_RELEASED_MS,
    private val noAudioMs: Long = NO_AUDIO_MS,
    private val switchMs: Long = SWITCH_MS,
    private val mutedMs: Long = MUTED_MS,
) {
    data class Input(
        val now: Long,
        /** Le pilote a activé l'écoute (réglage) : elle doit tourner. */
        val wanted: Boolean,
        /** Boucle audio en cours. */
        val running: Boolean,
        val error: Boolean,
        /** Micro en pause pendant que l'ingénieur parle. */
        val paused: Boolean,
        /** La voix de l'ingénieur signale qu'elle parle (Fold ou Discord). */
        val speaking: Boolean,
        /** Micro prêté à Google pour une phrase. */
        val micReleased: Boolean,
        val googleListening: Boolean,
        /** Dernier bloc audio lu par la boucle (0 : aucun). */
        val lastBlockMs: Long,
        /** Dernier bloc audio non nul (un micro coupé par Android rend des zéros). */
        val lastSoundMs: Long,
        /** Le micro du casque PS5 (Discord) est disponible et choisi dans les réglages. */
        val remoteAvailable: Boolean,
        /** L'écoute lit le micro du casque PS5. */
        val usingRemote: Boolean,
        /** Début de la boucle audio en cours (0 : aucune). */
        val startedMs: Long,
    )

    sealed interface Action {
        val reason: String

        /** Arrêt puis relance complète de l'écoute (micro, reconnaissances). */
        data class Restart(override val reason: String) : Action

        /** Fin de la pause : la voix de l'ingénieur ne signale plus sa fin, l'écoute reprend quand même. */
        data class Unpause(override val reason: String) : Action

        /** Micro repris à Google, qui n'écoute plus. */
        data class ReclaimMic(override val reason: String) : Action

        /** À signaler seulement (le relancer n'y changerait rien) : micro du Fold muet. */
        data class Report(override val reason: String) : Action
    }

    private var speakingSince = 0L
    private var releasedSince = 0L
    private var mismatchSince = 0L
    private var lastRestartMs = Long.MIN_VALUE / 2
    private var attempts = 0
    private var mutedReported = false

    /** Reprises faites depuis le lancement (Atelier). */
    var restarts = 0
        private set

    fun check(i: Input): Action? {
        speakingSince = if (i.paused && i.speaking) (speakingSince.takeIf { it > 0 } ?: i.now) else 0L
        releasedSince = if (i.micReleased && !i.googleListening) (releasedSince.takeIf { it > 0 } ?: i.now) else 0L
        val mismatch = i.running && !i.error && i.remoteAvailable != i.usingRemote
        mismatchSince = if (mismatch) (mismatchSince.takeIf { it > 0 } ?: i.now) else 0L
        // Écoute stable depuis une minute : les reprises suivantes repartent du délai le plus court.
        if (i.running && !i.error && i.startedMs > 0 && i.now - i.startedMs > STABLE_MS) attempts = 0
        if (!i.wanted) return null

        if (!i.running || i.error) {
            return restart(i.now, if (i.error) "écoute en erreur" else "écoute arrêtée alors qu'elle est activée")
        }
        if (speakingSince > 0 && i.now - speakingSince >= maxSpeakingMs) {
            speakingSince = 0L
            return Action.Unpause("la voix de l'ingénieur n'a pas signalé sa fin depuis ${(maxSpeakingMs / 1000)} s")
        }
        if (releasedSince > 0 && i.now - releasedSince >= maxReleasedMs) {
            releasedSince = 0L
            return Action.ReclaimMic("micro prêté à Google, qui n'écoute plus")
        }
        if (mismatchSince > 0 && i.now - mismatchSince >= switchMs) {
            return restart(
                i.now,
                if (i.remoteAvailable) "casque PS5 disponible, écoute restée sur le Fold" else "casque PS5 parti, écoute restée sur Discord",
            )
        }
        val listening = !i.paused && !i.micReleased
        val since = maxOf(i.lastBlockMs, i.startedMs)
        if (listening && since > 0 && i.now - since >= noAudioMs) {
            return restart(i.now, "aucun bloc audio depuis ${(i.now - since) / 1000} s")
        }
        if (listening && !i.usingRemote && i.lastBlockMs > 0) {
            val silentFor = i.now - maxOf(i.lastSoundMs, i.startedMs)
            if (silentFor >= mutedMs && !mutedReported) {
                mutedReported = true
                return Action.Report("micro du Fold : que des zéros depuis ${silentFor / 1000} s (micro coupé par Android ?)")
            }
            if (silentFor < mutedMs) mutedReported = false
        }
        return null
    }

    private fun restart(now: Long, reason: String): Action? {
        val wait = RETRY_MS[attempts.coerceAtMost(RETRY_MS.lastIndex)]
        if (now - lastRestartMs < wait) return null
        lastRestartMs = now
        attempts++
        restarts++
        return Action.Restart(reason)
    }

    companion object {
        /** Aucune phrase de l'ingénieur ne dure une minute : au-delà, la fin de lecture a été perdue. */
        const val MAX_SPEAKING_MS = 60_000L
        /** Google rend toujours la main en moins de 15 s ; 3 s après qu'il n'écoute plus, le micro est repris. */
        const val MAX_RELEASED_MS = 3_000L
        /** Un micro rend un bloc toutes les 0,25 s (le casque PS5 rend du silence entre deux phrases). */
        const val NO_AUDIO_MS = 5_000L
        const val SWITCH_MS = 3_000L
        const val MUTED_MS = 20_000L
        const val STABLE_MS = 60_000L
        /** Délais entre deux reprises successives : pas de boucle de redémarrage. */
        val RETRY_MS = longArrayOf(0L, 5_000L, 15_000L, 30_000L, 60_000L)
    }
}
