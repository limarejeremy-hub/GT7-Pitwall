package com.gt7pitwall.core.radio

import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.engineer.Priority
import kotlin.math.sqrt

/**
 * Comment le micro peut écouter pendant que l'ingénieur parle. Le mode dépend du matériel, pas d'un réglage optimiste :
 * l'interruption n'est annoncée que là où elle peut fonctionner.
 */
enum class DuplexMode(val label: String) {
    /**
     * Écoute pendant la parole. Discord : la voix du pilote arrive dans un flux séparé de celle du bot, l'ingénieur ne
     * s'entend pas. Fold avec casque ou écouteurs : la voix ne sort pas du haut-parleur.
     */
    FULL("interruption pendant la parole"),

    /** Haut-parleur du Fold : le micro entendrait l'ingénieur. Écoute seulement dans les courtes pauses entre ses phrases. */
    GAPS("interruption entre les phrases"),

    /** Pas d'interruption possible (micro coupé pendant la parole, comme avant). */
    OFF("pas d'interruption"),
}

object DuplexPolicy {
    /**
     * [route] : où parle l'ingénieur ; [phoneSpeaker] : sur le Fold, la voix sort du haut-parleur intégré ;
     * [speakerDuplexOptIn] : le pilote a activé, à ses risques, l'écoute pendant la parole sur haut-parleur (annulation
     * d'écho du téléphone, [echoCanceller]) — expérimental.
     */
    fun mode(route: VoiceRoute, phoneSpeaker: Boolean, echoCanceller: Boolean = false, speakerDuplexOptIn: Boolean = false): DuplexMode = when {
        route == VoiceRoute.REMOTE -> DuplexMode.FULL
        !phoneSpeaker -> DuplexMode.FULL
        speakerDuplexOptIn && echoCanceller && route == VoiceRoute.PHONE -> DuplexMode.FULL
        else -> DuplexMode.GAPS
    }

    /** Une alerte critique ne se coupe pas : elle est courte et doit être entendue en entier. */
    fun interruptible(m: EngineerMessage?): Boolean = m == null || m.priority != Priority.CRITICAL
}

/**
 * Détection de voix simple et robuste : énergie au-dessus d'un plancher de bruit adaptatif (bruit du jeu, souffle),
 * pendant au moins [minSpeechMs]. Le plancher ne s'adapte que hors parole. Échantillons 16 bits à [sampleRate].
 */
class VoiceActivity(
    private val sampleRate: Int = 16_000,
    private val minRms: Double = 500.0,
    private val ratio: Double = 3.0,
    private val minSpeechMs: Long = 250,
    private val hangoverMs: Long = 300,
) {
    private var floor = 0.0
    private var speechSamples = 0L
    private var quietSamples = 0L

    var speaking = false
        private set

    /** Bloc reçu ; vrai quand une voix est établie (parole d'au moins [minSpeechMs]). */
    fun accept(samples: ShortArray, n: Int): Boolean {
        if (n <= 0) return speaking
        var sum = 0.0
        for (i in 0 until n) sum += samples[i].toDouble() * samples[i]
        val rms = sqrt(sum / n)
        val threshold = maxOf(minRms, floor * ratio)
        if (rms >= threshold) {
            speechSamples += n
            quietSamples = 0
            if (speechSamples * 1000 / sampleRate >= minSpeechMs) speaking = true
        } else {
            quietSamples += n
            floor = if (floor == 0.0) rms else floor * 0.95 + rms * 0.05
            if (quietSamples * 1000 / sampleRate >= hangoverMs) {
                speaking = false
                speechSamples = 0
            }
        }
        return speaking
    }

    fun reset() {
        speechSamples = 0
        quietSamples = 0
        speaking = false
    }
}

/**
 * Interruption de l'ingénieur par le pilote (barge-in). Il faut à la fois une voix établie dans le flux du pilote et des
 * mots reconnus qui ne sont pas ceux que l'ingénieur est en train de dire : un mot d'interruption (« attends », « stop »,
 * « non »…) ou une phrase d'au moins deux vrais mots. Un bruit du jeu sans mots, un mot isolé ou l'écho de la voix de
 * l'ingénieur ne coupent rien.
 *
 * [echoGuardMs] : sans flux séparé, la fin de la voix de l'ingénieur résonne encore un instant (Cornelius garde 600 ms
 * pour un satellite sans annulation d'écho).
 */
class InterruptDetector(private val echoGuardMs: Long = 0L, private val voiceWindowMs: Long = 1_500L) {
    private var lastVoiceMs: Long? = null
    private var guardUntil = 0L
    private var engineerWords: Set<String> = emptySet()

    /** L'ingénieur commence ou finit une phrase ([text] : ce qu'il dit, pour reconnaître son écho). */
    fun engineer(speaking: Boolean, text: String?, nowMs: Long) {
        engineerWords = if (speaking) words(text.orEmpty()).toSet() else engineerWords
        if (!speaking) guardUntil = nowMs + echoGuardMs
    }

    /** La détection de voix du flux du pilote vient de s'établir. */
    fun voice(nowMs: Long) {
        if (nowMs < guardUntil) return
        lastVoiceMs = nowMs
    }

    /** Mots reconnus dans le flux du pilote pendant que l'ingénieur parle : vrai s'ils l'interrompent. */
    fun words(text: String, nowMs: Long): Boolean {
        val voice = lastVoiceMs ?: return false
        if (nowMs - voice > voiceWindowMs || nowMs < guardUntil) return false
        val heard = words(text)
        if (heard.isEmpty()) return false
        // Écho : tout ce qui est entendu fait partie de la phrase de l'ingénieur.
        if (heard.all { it in engineerWords }) return false
        val t = " " + heard.joinToString(" ") + " "
        val keyword = KEYWORDS.any { Regex(it).containsMatchIn(t) }
        val sentence = heard.count { it.length >= 3 && it !in FILLERS } >= 2
        if (!keyword && !sentence) return false
        lastVoiceMs = null
        return true
    }

    fun reset() {
        lastVoiceMs = null
        guardUntil = 0L
        engineerWords = emptySet()
    }

    companion object {
        val KEYWORDS = listOf(
            " attends? ", " stop ", " stoppe ", " arrete ", " non ", " pardon ", " une seconde ", " tais toi ", " chut ",
            " minute ", " radio ", " ecoute ", " coupe ",
        )
        private val FILLERS = setOf("euh", "heu", "hum", "hmm", "mmh", "bah", "ben", "hein")

        fun words(text: String): List<String> =
            DriverCommandParser.normalize(text).replace("[unk]", " ").split(Regex("[^a-z'0-9]+")).filter { it.isNotBlank() }
    }
}

/**
 * Mesure de la latence d'un tour de parole, étape par étape (horloge monotone, ms) : détection de la parole, fin de
 * phrase, transcription, compréhension, calcul du moteur, génération de l'IA, synthèse, premier son réellement émis.
 */
class LatencyTrace(val turn: Long) {
    enum class Step(val label: String) {
        SPEECH_START("début de la parole"),
        SPEECH_END("fin de phrase"),
        TRANSCRIBED("transcription"),
        UNDERSTOOD("compréhension"),
        ENGINE("calcul du moteur"),
        AI("réponse de l'IA"),
        SYNTHESIS("synthèse vocale"),
        AUDIBLE("premier son audible"),
    }

    private val marks = LinkedHashMap<Step, Long>()

    fun mark(step: Step, atMs: Long) {
        marks.putIfAbsent(step, atMs)
    }

    fun at(step: Step): Long? = marks[step]

    /** Durée de chaque étape depuis la précédente mesurée, et le total de la fin de phrase au premier son. */
    fun report(): List<Pair<Step, Long>> {
        val ordered = Step.entries.mapNotNull { s -> marks[s]?.let { s to it } }
        return ordered.zipWithNext { (_, a), (s, b) -> s to (b - a).coerceAtLeast(0) }
    }

    val totalMs: Long?
        get() = marks[Step.AUDIBLE]?.let { end -> (marks[Step.SPEECH_END] ?: marks[Step.TRANSCRIBED])?.let { end - it } }

    /** Étape la plus lente (pour le diagnostic). */
    val slowest: Pair<Step, Long>? get() = report().maxByOrNull { it.second }

    fun text(): String = report().joinToString(", ") { "${it.first.label} ${it.second} ms" } +
        (totalMs?.let { " ; total fin de phrase → premier son : $it ms" } ?: "")
}
