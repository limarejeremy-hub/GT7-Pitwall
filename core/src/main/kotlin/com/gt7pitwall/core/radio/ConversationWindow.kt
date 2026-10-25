package com.gt7pitwall.core.radio

/**
 * Conversation continue avec l'ingénieur. « Radio » une fois ouvre la conversation (un bip quand le micro est prêt) ;
 * après chaque réponse, l'écoute reprend sans mot de réveil ni bip. Elle se referme après [idleMs] sans phrase
 * intelligible du pilote — le temps où l'ingénieur parle ne compte pas — ou sur « terminé », « fin de communication » :
 * double bip, puis retour au mot de réveil.
 *
 * Les messages automatiques de course n'ouvrent jamais de conversation. Les phrases entendues sans mot de réveil
 * pendant la conversation ([addressedByWake] faux) ne peuvent modifier la course qu'après confirmation du pilote.
 */
class ConversationWindow(var idleMs: Long = DEFAULT_IDLE_MS) {
    enum class Effect {
        /** Bip de disponibilité : le micro écoute maintenant. */
        READY_BEEP,

        /** Double bip : conversation refermée. */
        CLOSED_BEEP,
    }

    var open: Boolean = false
        private set

    /** Fin de la fenêtre (horloge monotone, ms), quand personne ne parle. */
    private var deadline = 0L
    private var engineerSpeaking = false
    private var thinking = false

    /** Début de la suspension en cours (parole ou réflexion) : bornée à [MAX_HOLD_MS], un état coincé ne bloque rien. */
    private var holdSince: Long? = null

    /** Fragments inintelligibles de suite (bruit du jeu reconnu comme parole) : trop nombreux, la conversation se ferme. */
    private var noise = 0

    /** Nombre de phrases du pilote depuis l'ouverture : la première suit directement « Radio ». */
    var exchanges: Int = 0
        private set

    /** « Radio » seul : ouvre la conversation. Le bip n'est joué qu'une fois le micro prêt ([micReady]). */
    fun wake(nowMs: Long) {
        open = true
        exchanges = 0
        deadline = nowMs + idleMs
        hold(nowMs)
    }

    /**
     * Le micro écoute réellement : bip de disponibilité, une seule fois par conversation (après « Radio » seul, ou à la
     * reprise d'écoute qui suit une demande dite d'un trait avec « Radio »). Jamais à chaque phrase.
     */
    fun micReady(): Effect? = if (open && !beeped) Effect.READY_BEEP.also { beeped = true } else null

    private var beeped = false

    /**
     * Phrase du pilote entendue pendant la conversation. Renvoie [Effect.CLOSED_BEEP] si elle la referme. Une phrase
     * inintelligible ne prolonge pas la fenêtre.
     */
    fun utterance(text: String, nowMs: Long): Effect? {
        if (!open) return null
        if (isClosing(text)) return close()
        if (isIntelligible(text)) {
            exchanges++
            noise = 0
            deadline = nowMs + idleMs
        } else if (++noise >= MAX_NOISE) {
            // Écoute involontaire : le jeu ou la pièce font réagir la reconnaissance sans que le pilote parle.
            return close()
        }
        return null
    }

    /** La phrase vient-elle directement après « Radio » (première de la conversation) ? */
    val addressedByWake: Boolean get() = exchanges == 0

    /** L'ingénieur parle : la fenêtre est suspendue ; quand il a fini, elle repart pour [idleMs]. */
    fun engineerSpeaking(speaking: Boolean, nowMs: Long) {
        val ended = engineerSpeaking && !speaking
        if (!speaking) stuckSpeaking = false
        engineerSpeaking = speaking && !stuckSpeaking
        hold(nowMs)
        if (ended && open) deadline = nowMs + idleMs
    }

    /** Sources déclarées coincées (suspension au-delà de [MAX_HOLD_MS]) : ignorées jusqu'à leur retour à faux. */
    private var stuckSpeaking = false
    private var stuckThinking = false

    private fun hold(nowMs: Long) {
        holdSince = if (engineerSpeaking || thinking) holdSince ?: nowMs else null
    }

    /**
     * Une réponse est en préparation (moteur ou IA) : la fenêtre ne se referme pas pendant ce temps ; quand elle est prête,
     * l'ingénieur parle, puis le délai repart.
     */
    fun thinking(busy: Boolean, nowMs: Long) {
        // Appelé à chaque tick : seule la fin réelle d'une réflexion relance le délai. Essai PS5 : chaque appel « pas de
        // réflexion » repoussait l'échéance de [idleMs], la conversation ne se refermait jamais et le double bip manquait.
        val ended = thinking && !busy
        if (!busy) stuckThinking = false
        thinking = busy && !stuckThinking
        hold(nowMs)
        if (ended && open) deadline = maxOf(deadline, nowMs + idleMs)
    }

    /**
     * À appeler régulièrement : referme la conversation à l'expiration, avec le double bip. Essai PS5 : le bip ne venait
     * jamais, la fenêtre restant repoussée par le bruit du jeu entendu comme une phrase en cours, ou suspendue par un
     * état de parole resté coincé. Désormais la fermeture est garantie :
     * - la parole de l'ingénieur et la réflexion suspendent le délai, au plus [MAX_HOLD_MS] ;
     * - le pilote en train de parler ([driverTalking]) le prolonge, au plus [MAX_TALK_GRACE_MS] après l'échéance.
     */
    fun tick(nowMs: Long, driverTalking: Boolean = false): Effect? {
        if (!open) return null
        val since = holdSince
        if (since != null && nowMs - since < MAX_HOLD_MS) return null
        if (since != null) {
            // Suspension trop longue : l'état est coincé, il ne compte plus ; le pilote n'a rien dit depuis longtemps.
            stuckSpeaking = engineerSpeaking
            stuckThinking = thinking
            engineerSpeaking = false
            thinking = false
            holdSince = null
        }
        if (nowMs < deadline) return null
        if (driverTalking && nowMs < deadline + MAX_TALK_GRACE_MS) return null
        return close()
    }

    fun remainingMs(nowMs: Long): Long = if (open) (deadline - nowMs).coerceAtLeast(0) else 0

    /** Fermeture immédiate (écoute arrêtée, micro perdu) : pas de bip. */
    fun reset() {
        open = false
        exchanges = 0
        beeped = false
        noise = 0
        thinking = false
        holdSince = null
        stuckSpeaking = false
        stuckThinking = false
    }

    private fun close(): Effect {
        reset()
        return Effect.CLOSED_BEEP
    }

    companion object {
        /**
         * Silence avant fermeture, une fois que personne ne parle ni ne réfléchit. 5 s coupaient le pilote qui cherchait
         * ses mots en pleine course ; au-delà de 10 s, le bruit du jeu a le temps de déclencher une commande.
         */
        const val DEFAULT_IDLE_MS = 8_000L

        /** Parole de l'ingénieur ou réflexion plus longue que ça : état coincé, ignoré pour la fermeture. */
        const val MAX_HOLD_MS = 45_000L

        /** Une phrase du pilote dure rarement plus : au-delà, c'est le bruit du jeu, la conversation se ferme. */
        const val MAX_TALK_GRACE_MS = 6_000L

        /** Fragments inintelligibles de suite avant de refermer : écoute involontaire. */
        const val MAX_NOISE = 3

        private val CLOSING = Regex(
            "^(merci )?(c'est )?(termine|terminee|fin de (la )?communication|fin de transmission|over|terminer|stop radio|ca sera tout|c'est tout)( merci)?$",
        )

        /** « Terminé », « fin de communication », « merci, terminé ». */
        fun isClosing(text: String): Boolean {
            val t = DriverCommandParser.normalize(text).replace("[unk]", " ").replace(Regex("[^a-z' ]"), " ").replace(Regex("\\s+"), " ").trim()
            return CLOSING.matches(t)
        }

        private val FILLERS = setOf("euh", "heu", "hein", "bah", "ben", "hum", "hmm", "mmh", "pff", "bon")

        /** Au moins un vrai mot (trois lettres ou plus), hors mots inconnus de la reconnaissance et hésitations. */
        fun isIntelligible(text: String): Boolean {
            val t = DriverCommandParser.normalize(text).replace("[unk]", " ")
            return t.split(Regex("[^a-z'0-9]+")).any { it.length >= 3 && it !in FILLERS }
        }
    }
}
