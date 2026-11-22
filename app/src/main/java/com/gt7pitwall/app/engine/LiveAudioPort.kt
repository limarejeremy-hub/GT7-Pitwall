package com.gt7pitwall.app.engine

/**
 * V510 : sortie et entrée audio du prototype B (GPT-Live simulé) dans le même salon Discord que la voix A.
 * PCM16 mono 24 kHz dans les deux sens (format de la WebSocket GPT-Live).
 */
interface LiveAudioPort {
    /** Le bot est dans le salon vocal. */
    val available: Boolean

    /** Joue un morceau d'audio dans le casque, à la suite des précédents. */
    fun play(pcm24k: ShortArray)

    /** Coupure : vide tout l'audio en attente. */
    fun flush()

    /** Fin de session : libère l'encodeur. */
    fun end()

    /** Synthèse locale du serveur SIMULÉ (la voix A) en 24 kHz ; jamais utilisée par un vrai GPT-Live. */
    suspend fun synthesize(text: String): ShortArray?

    /** Micro du casque PS5 en 24 kHz : lit au plus [max] échantillons, bloque au plus ~0,25 s ; null si indisponible. */
    fun openMic(): MicReader?

    interface MicReader {
        fun read(max: Int): ShortArray?
        fun close()
    }
}
