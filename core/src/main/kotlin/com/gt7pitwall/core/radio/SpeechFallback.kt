package com.gt7pitwall.core.radio

/** Fin d'une écoute de la reconnaissance Google. */
enum class GoogleOutcome(val label: String, val failure: Boolean) {
    RESULT("phrase reçue", false),

    /** Arrêtée par la radio (l'ingénieur reprend la parole, écoute coupée) : pas un échec de Google. */
    CANCELLED("annulée", false),

    NO_MATCH("rien compris", true),
    SPEECH_TIMEOUT("aucune parole entendue", true),

    /** La reconnaissance n'a jamais signalé être prête à écouter. */
    NOT_READY("jamais prête à écouter", true),

    /** Prête, mais pas de résultat dans le délai. */
    TIMEOUT("délai dépassé", true),

    ERROR("erreur", true),
}

/**
 * Fiabilité de la radio avant tout : quand la reconnaissance Google échoue, le pilote en est averti et la demande
 * suivante passe par Vosk (hors ligne, toujours disponible). Après [maxFailures] échecs d'affilée, Google est suspendu
 * jusqu'à ce que le réglage soit changé ou l'écoute relancée. Chaque écoute a son numéro : un résultat arrivé après
 * une annulation ou un redémarrage est ignoré.
 */
class SpeechFallbackPolicy(private val maxFailures: Int = 3) {
    private var lastSession = 0
    private var active: Int? = null

    var consecutiveFailures = 0
        private set

    /** La prochaine demande passe par Vosk (Google vient d'échouer). */
    var voskForNextRequest = false
        private set

    val suspended: Boolean get() = consecutiveFailures >= maxFailures

    /**
     * Google pour cette demande ? Seulement s'il est disponible, sans échec en attente de repli, et — avec le micro du
     * casque PS5 — si la reconnaissance accepte un flux audio fourni par l'application ([externalAudioSupported]).
     */
    @Synchronized
    fun useGoogle(available: Boolean, phoneMic: Boolean, externalAudioSupported: Boolean?): Boolean =
        available && !voskForNextRequest && !suspended && (phoneMic || externalAudioSupported != false)

    @Synchronized
    fun start(): Int {
        active = ++lastSession
        return lastSession
    }

    /** Écoute en cours annulée (redémarrage, l'ingénieur reprend la parole) : son résultat sera ignoré. */
    @Synchronized
    fun cancelActive() {
        active = null
    }

    data class Decision(val fallbackToVosk: Boolean, val notice: String?)

    /** Résultat de l'écoute [session] ; null si elle a été annulée ou remplacée entre-temps. */
    @Synchronized
    fun finish(session: Int, outcome: GoogleOutcome, errorCode: Int? = null): Decision? {
        if (active != session) return null
        active = null
        if (!outcome.failure) {
            if (outcome == GoogleOutcome.RESULT) consecutiveFailures = 0
            return Decision(fallbackToVosk = false, notice = null)
        }
        consecutiveFailures++
        voskForNextRequest = true
        val why = outcome.label + (errorCode?.let { ", code $it" } ?: "")
        val notice = if (suspended) {
            "Je n'ai pas reçu ta demande : la reconnaissance Google ne répond pas ($why). Je passe sur la reconnaissance " +
                "hors ligne pour la suite. Répète, je t'écoute."
        } else {
            "Je n'ai pas reçu ta demande (reconnaissance Google : $why). Répète, je t'écoute."
        }
        return Decision(fallbackToVosk = true, notice = notice)
    }

    /** Une demande a été traitée par Vosk : Google sera réessayé à la suivante (sauf s'il est suspendu). */
    @Synchronized
    fun voskHandledRequest() {
        voskForNextRequest = false
    }

    /** Réglage changé ou écoute relancée par le pilote : on repart de zéro. */
    @Synchronized
    fun reset() {
        consecutiveFailures = 0
        voskForNextRequest = false
        active = null
    }
}
