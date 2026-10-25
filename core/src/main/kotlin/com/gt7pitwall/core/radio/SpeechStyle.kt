package com.gt7pitwall.core.radio

import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.engineer.Priority

/**
 * Rendu oral d'un message avec la synthèse Android : variations modérées de débit et de hauteur selon ce que dit
 * l'ingénieur. Une alerte est plus directe, une réaction sportive un peu plus vive, une information secondaire plus posée.
 * Aucun effet ne retarde un message : seuls le débit et la hauteur changent.
 */
object SpeechStyle {
    data class Prosody(val rate: Float, val pitch: Float)

    val NEUTRAL = Prosody(1.0f, 1.0f)

    private val CELEBRATION = Regex("^(bien joué|ah ça|magnifique|superbe|énorme)", RegexOption.IGNORE_CASE)

    fun of(m: EngineerMessage): Prosody = when {
        m.priority == Priority.CRITICAL || m.category == MessageCategory.ALERT -> Prosody(1.12f, 1.0f)
        CELEBRATION.containsMatchIn(m.text.trim()) -> Prosody(1.06f, 1.06f)
        m.category == MessageCategory.ANSWER && m.text.trimEnd().endsWith("?") -> Prosody(1.0f, 1.03f)
        m.priority == Priority.INFO -> Prosody(0.97f, 0.99f)
        else -> NEUTRAL
    }

    /**
     * Phrases d'un message, pour une diffusion avec de courtes pauses d'écoute entre elles (haut-parleur du Fold). Les
     * nombres décimaux (« 1,5 ») et les abréviations courantes ne coupent pas la phrase.
     */
    fun sentences(text: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        val t = text.trim()
        var i = 0
        while (i < t.length) {
            val c = t[i]
            sb.append(c)
            val end = (c == '.' || c == '!' || c == '?') && (i + 1 >= t.length || t[i + 1] == ' ')
            if (end && sb.trim().length >= MIN_SENTENCE) {
                out += sb.toString().trim()
                sb.clear()
            }
            i++
        }
        if (sb.isNotBlank()) {
            if (out.isNotEmpty() && sb.trim().length < MIN_SENTENCE) out[out.lastIndex] = out.last() + " " + sb.toString().trim() else out += sb.toString().trim()
        }
        return out.ifEmpty { listOf(t) }
    }

    /** Pause entre deux phrases en mode d'interruption entre les phrases (ms). */
    const val GAP_MS = 450L

    /** Une phrase plus courte n'est pas séparée (« OK. », « P5. ») : elle reste avec la suivante. */
    private const val MIN_SENTENCE = 12
}
