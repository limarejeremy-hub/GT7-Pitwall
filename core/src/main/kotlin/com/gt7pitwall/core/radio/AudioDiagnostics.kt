package com.gt7pitwall.core.radio

/**
 * Diagnostic de la voix envoyée au salon Discord (issue #48 : « un bruit quand il parle, comme une perte… un freeze »).
 * Mesure seulement ce que le téléphone peut voir : trames Opus produites, file d'envoi, appels de JDA toutes les 20 ms,
 * vidages de la file. Aucun son, aucune trame n'est gardé : des compteurs et des horodatages monotones.
 * Ce qui se passe après l'envoi (réseau, Discord, DAVE, PS5) n'est pas observable d'ici.
 */
object OpusToc {
    /** Durée d'un paquet Opus d'après son octet TOC (dixièmes de ms), null si illisible. */
    fun durationTenthMs(packet: ByteArray): Int? {
        if (packet.isEmpty()) return null
        val toc = packet[0].toInt() and 0xFF
        val config = toc shr 3
        val single = when {
            config < 12 -> intArrayOf(100, 200, 400, 600)[config and 3]
            config < 16 -> intArrayOf(100, 200)[config and 1]
            else -> intArrayOf(25, 50, 100, 200)[config and 3]
        }
        val count = when (toc and 3) {
            0 -> 1
            1, 2 -> 2
            else -> if (packet.size > 1) packet[1].toInt() and 0x3F else return null
        }
        return single * count
    }

    const val EXPECTED_TENTH_MS = 200
}

/** Ce que contient une phrase encodée : trames, durée, trames vides ou d'une durée autre que 20 ms. */
data class PhraseAudio(val frames: Int, val durationMs: Int, val empty: Int, val invalidDuration: Int) {
    companion object {
        fun of(frames: List<ByteArray>): PhraseAudio {
            var tenth = 0
            var empty = 0
            var invalid = 0
            for (f in frames) {
                if (f.isEmpty()) {
                    empty++
                    continue
                }
                val d = OpusToc.durationTenthMs(f)
                if (d == null || d != OpusToc.EXPECTED_TENTH_MS) invalid++
                tenth += d ?: 0
            }
            return PhraseAudio(frames.size, tenth / 10, empty, invalid)
        }
    }
}

/**
 * Surveille la file d'envoi. JDA demande une trame toutes les 20 ms : un appel en retard pendant une phrase, une file
 * vide alors qu'elle se disait prête, ou un vidage en pleine phrase sont autant de trous audibles possibles.
 * Thread-safe : la file est lue par le fil d'envoi de JDA, écrite par la synthèse.
 */
class AudioSendMonitor(private val clock: () -> Long) {
    data class Event(val code: String, val text: String)

    @Volatile
    var enabled = false

    private var phrase: String? = null
    private var phraseFrames = 0
    private var lastProvideMs: Long? = null
    private var pollGaps = 0
    private var maxGapMs = 0L
    private var underruns = 0
    private var provided = 0
    private val events = ArrayList<Event>()
    private val lastEventMs = HashMap<String, Long>()

    @Synchronized
    fun beginPhrase(label: String, frames: List<ByteArray>) {
        if (!enabled) return
        phrase = label.take(40)
        phraseFrames = frames.size
        pollGaps = 0
        maxGapMs = 0
        underruns = 0
        provided = 0
        lastProvideMs = null
        val a = PhraseAudio.of(frames)
        if (a.invalidDuration > 0 || a.empty > 0) {
            add("AUDIO_FRAME_DURATION_INVALID", "« $phrase » : ${a.invalidDuration} trame(s) d'une autre durée que 20 ms, ${a.empty} vide(s)")
        }
    }

    /** Appel de JDA : [hadFrame] faux alors que la file venait de répondre « prête » = trou. */
    @Synchronized
    fun onProvide(hadFrame: Boolean, expected: Boolean) {
        if (!enabled || phrase == null) return
        val now = clock()
        lastProvideMs?.let { last ->
            val gap = now - last
            if (gap > GAP_MS) {
                pollGaps++
                if (gap > maxGapMs) maxGapMs = gap
            }
        }
        lastProvideMs = now
        if (hadFrame) provided++ else if (expected) underruns++
    }

    /** File vidée ([dropped] trames non jouées) : alerte prioritaire, arrêt, salon perdu… */
    @Synchronized
    fun onClear(reason: String, dropped: Int, current: String?) {
        if (!enabled || dropped <= 0) return
        add("AUDIO_CLEAR_REASON", "$dropped trame(s) abandonnée(s) : $reason" + (current?.let { " pendant « ${it.take(40)} »" } ?: ""))
    }

    /** Fin d'une phrase : seules les anomalies sont gardées (résumé, jamais 50 événements par seconde). */
    @Synchronized
    fun endPhrase(cutShort: Boolean) {
        val p = phrase ?: return
        phrase = null
        if (!enabled) return
        if (pollGaps > 0) add("AUDIO_FRAME_POLL_GAP", "« $p » : $pollGaps appel(s) d'envoi en retard (> $GAP_MS ms), pire $maxGapMs ms")
        if (underruns > 0) add("AUDIO_QUEUE_UNDERRUN", "« $p » : $underruns trame(s) manquante(s) alors que la file se disait prête")
        if (cutShort) add("AUDIO_PREEMPTED", "« $p » : coupée après $provided trame(s) sur $phraseFrames")
        else if (provided in 1 until phraseFrames) add("AUDIO_PLAYBACK_DONE_QUEUE", "« $p » : $provided trame(s) envoyée(s) sur $phraseFrames")
    }

    /** Événements à consigner depuis le dernier appel. */
    @Synchronized
    fun drain(): List<Event> = events.toList().also { events.clear() }

    private fun add(code: String, text: String) {
        val now = clock()
        val last = lastEventMs[code]
        if (last != null && now - last < RATE_LIMIT_MS) return
        lastEventMs[code] = now
        events += Event(code, text)
        while (events.size > MAX_EVENTS) events.removeAt(0)
    }

    companion object {
        /** JDA appelle toutes les 20 ms ; au-delà de 60 ms, un trou est possible à l'oreille. */
        const val GAP_MS = 60L
        const val RATE_LIMIT_MS = 5_000L
        const val MAX_EVENTS = 50
    }
}
