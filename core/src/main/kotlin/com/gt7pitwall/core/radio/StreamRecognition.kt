package com.gt7pitwall.core.radio

/** Les deux reconnaissances Google d'Android. */
enum class GoogleEngine(val label: String) {
    ON_DEVICE("Google sur l'appareil"),
    STANDARD("service Google"),
}

/** Ce que la reconnaissance a fait du flux du casque PS5, d'après le tube qui le lui transmet. */
enum class StreamRead(val label: String) {
    READ("flux lu"),
    NOT_READ("flux NON lu (la reconnaissance écoutait autre chose, sans doute le micro du Fold)"),
    UNKNOWN("lecture du flux non mesurable"),
}

/**
 * Mesures du flux remis à Google pour une phrase (aucun son : des compteurs). [writtenBytes] sont entrés dans le tube ;
 * [unreadBytes] y étaient encore à la fin : la différence a forcément été lue par la reconnaissance. Être « prête » ou
 * détecter du son ne prouve rien (elle peut écouter le micro du téléphone) : seule la lecture du tube le prouve.
 */
data class StreamStats(
    val offeredBytes: Long,
    val writtenBytes: Long,
    /** Octets restés dans le tube à la fin (null : mesure impossible). */
    val unreadBytes: Long?,
    /** Délai entre l'ouverture du flux et le premier bloc écrit. */
    val firstByteMs: Long?,
    /** Durée d'audio envoyée. */
    val audioMs: Long,
    /** Pourquoi Pitwall a fermé le flux (fin de phrase, aucune voix...) ; null : encore ouvert. */
    val endLabel: String?,
    /** Une voix a été détectée par Pitwall dans le flux. */
    val voiceHeard: Boolean,
) {
    val consumedBytes: Long? get() = unreadBytes?.let { (writtenBytes - it).coerceAtLeast(0) }

    val read: StreamRead
        get() {
            val consumed = consumedBytes ?: return StreamRead.UNKNOWN
            return when {
                consumed >= MIN_PROOF_BYTES -> StreamRead.READ
                writtenBytes >= MIN_PROOF_BYTES -> StreamRead.NOT_READ
                else -> StreamRead.UNKNOWN
            }
        }

    fun summary(): String = buildString {
        append("${kb(offeredBytes)} envoyés (${"%.1f".format(audioMs / 1000.0)} s, 16 kHz mono 16 bits)")
        if (writtenBytes != offeredBytes) append(", ${kb(writtenBytes)} entrés dans le tube")
        consumedBytes?.let { append(", ${kb(it)} lus par la reconnaissance") }
        firstByteMs?.let { append(", 1er bloc à $it ms") }
        endLabel?.let { append(", fermé : $it") }
    }

    companion object {
        /** Une demi-seconde d'audio : moins, on ne conclut pas. */
        const val MIN_PROOF_BYTES = 16_000L

        private fun kb(bytes: Long) = if (bytes < 1024) "$bytes o" else "${bytes / 1024} Ko"
    }
}

/** Ce qu'un essai de Google sur le flux du casque dit de ce moteur. */
enum class StreamVerdict {
    /** Phrase reconnue en lisant le flux. */
    WORKS,

    /** Preuve que le flux n'a pas été lu : le moteur ignore l'audio fourni. */
    IGNORES_STREAM,

    /** Flux lu, voix présente, mais rien reconnu. */
    READS_BUT_EMPTY,

    /** Le moteur refuse le flux (erreur audio ou client, jamais prêt, pas de réponse). */
    REFUSED,

    /** Rien à conclure (annulé, pas de voix, panne réseau passagère...). */
    INCONCLUSIVE,
}

object StreamAttempt {
    /** Erreurs passagères (réseau, occupé) : elles ne disent rien de la prise en charge du flux. */
    private val TRANSIENT_ERRORS = setOf(1, 2, 4, 8, 10)

    fun assess(outcome: GoogleOutcome, errorCode: Int?, stats: StreamStats): Pair<StreamVerdict, String> {
        val read = stats.read
        return when (outcome) {
            GoogleOutcome.RESULT -> if (read == StreamRead.NOT_READ) {
                StreamVerdict.IGNORES_STREAM to "phrase reçue, mais le flux n'a pas été lu : elle venait du micro du Fold"
            } else {
                StreamVerdict.WORKS to "phrase reconnue (${read.label})"
            }
            GoogleOutcome.CANCELLED -> StreamVerdict.INCONCLUSIVE to "écoute annulée"
            GoogleOutcome.NO_MATCH, GoogleOutcome.SPEECH_TIMEOUT -> when {
                read == StreamRead.NOT_READ -> StreamVerdict.IGNORES_STREAM to "rien compris, et ${read.label}"
                !stats.voiceHeard -> StreamVerdict.INCONCLUSIVE to "aucune voix dans le flux"
                else -> StreamVerdict.READS_BUT_EMPTY to "voix présente dans le flux, ${read.label}, mais rien compris"
            }
            GoogleOutcome.NOT_READY -> StreamVerdict.REFUSED to "jamais prête avec le flux"
            GoogleOutcome.TIMEOUT -> if (read == StreamRead.NOT_READ) {
                StreamVerdict.IGNORES_STREAM to "pas de réponse, et ${read.label}"
            } else {
                StreamVerdict.REFUSED to "pas de réponse après la fin du flux (${read.label})"
            }
            GoogleOutcome.ERROR -> if (errorCode in TRANSIENT_ERRORS) {
                StreamVerdict.INCONCLUSIVE to "erreur passagère (code $errorCode)"
            } else {
                StreamVerdict.REFUSED to "erreur avec le flux" + (errorCode?.let { " (code $it)" } ?: "")
            }
        }
    }
}

/**
 * Quel moteur Google essayer sur le flux du casque PS5, appris des essais réels. Une preuve que le flux n'est pas lu
 * écarte le moteur aussitôt ; « lu mais rien compris » ou un refus, après [maxStrikes] fois. Quand les deux moteurs sont
 * écartés, Google est coupé pour le casque : Vosk seul, sans nouvel échec. Le micro du Fold n'est pas concerné.
 */
class StreamEnginePolicy(private val maxStrikes: Int = 2) {
    private val disabled = linkedMapOf<GoogleEngine, String>()
    private val strikes = mutableMapOf<GoogleEngine, Int>()
    private val works = mutableSetOf<GoogleEngine>()

    /** Moteur à essayer, ou null : Google coupé pour le casque. */
    @Synchronized
    fun next(onDeviceAvailable: Boolean): GoogleEngine? =
        GoogleEngine.entries.firstOrNull { (it != GoogleEngine.ON_DEVICE || onDeviceAvailable) && it !in disabled }

    @Synchronized
    fun googleOff(onDeviceAvailable: Boolean): Boolean = next(onDeviceAvailable) == null

    @Synchronized
    fun disabledReasons(): Map<GoogleEngine, String> = LinkedHashMap(disabled)

    @Synchronized
    fun works(engine: GoogleEngine): Boolean = engine in works

    /**
     * Résultat d'un essai ; renvoie la bascule à afficher (moteur écarté, Google coupé pour le casque), ou null.
     */
    @Synchronized
    fun record(engine: GoogleEngine, verdict: StreamVerdict, reason: String, onDeviceAvailable: Boolean): String? {
        when (verdict) {
            StreamVerdict.WORKS -> {
                works += engine
                strikes.remove(engine)
                return null
            }
            StreamVerdict.INCONCLUSIVE -> return null
            StreamVerdict.IGNORES_STREAM -> Unit
            StreamVerdict.READS_BUT_EMPTY, StreamVerdict.REFUSED -> {
                val n = (strikes[engine] ?: 0) + 1
                strikes[engine] = n
                if (n < maxStrikes) return null
            }
        }
        if (engine in disabled) return null
        disabled[engine] = reason
        works -= engine
        val off = "${engine.label} écarté pour le casque PS5 : $reason."
        return when (val next = next(onDeviceAvailable)) {
            null -> "$off Google est coupé pour le casque : Vosk seul (le micro du Fold garde Google)."
            else -> "$off Prochain essai : ${next.label}."
        }
    }

    /** Écarte un moteur (préférence mémorisée, ou français non installé). */
    @Synchronized
    fun disable(engine: GoogleEngine, reason: String) {
        disabled[engine] = reason
        works -= engine
    }

    /** « Réessayer Google sur le casque » : tout est oublié. */
    @Synchronized
    fun reset() {
        disabled.clear()
        strikes.clear()
        works.clear()
    }
}

/** Quelle phrase donner à l'ingénieur quand Google et Vosk ont écouté la même demande (casque PS5). */
sealed interface RequestPick {
    data class Google(val text: String) : RequestPick

    /** [command] : reconnaissance radio de Vosk ; [free] : sa transcription libre, s'il y en a une. */
    data class Vosk(val command: String, val free: String?) : RequestPick

    /** Personne n'a rien compris : le pilote doit répéter. */
    data object Repeat : RequestPick
}

object RequestArbiter {
    /**
     * Google d'abord (meilleur pour les phrases libres) ; sinon la même phrase entendue par Vosk, sans le mot de réveil.
     * Du bruit seul (« [unk] ») n'est pas une demande.
     */
    fun pick(googleText: String?, voskCommand: String, voskFree: String, wakeWord: String): RequestPick {
        val google = googleText?.let { stripWake(it, wakeWord) }?.takeIf { it.isNotEmpty() }
        if (google != null) return RequestPick.Google(google)
        val command = stripWake(voskCommand, wakeWord)
        if (command.replace("[unk]", " ").trim().length < 2) return RequestPick.Repeat
        val free = stripWake(voskFree, wakeWord).takeIf { it.length >= 2 }
        return RequestPick.Vosk(command, free)
    }

    fun stripWake(text: String, wakeWord: String): String {
        val norm = DriverCommandParser.normalize(text)
        val wake = DriverCommandParser.normalize(wakeWord)
        if (wake.isEmpty()) return norm.trim()
        return norm.replace(Regex("^\\s*${Regex.escape(wake)}\\s*,?\\s*"), "").trim()
    }
}
