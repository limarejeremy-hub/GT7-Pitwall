package com.gt7pitwall.core.radio

/**
 * Choix de la voix française installée (essai PS5 : voix jugée robotique). Android prenait la voix par défaut de la
 * langue, pas forcément la meilleure installée. On prend la voix française locale de meilleure qualité annoncée par le
 * moteur, puis la plus réactive. Jamais une voix réseau : elle ajoute du délai et se tait sans connexion.
 */
object VoiceChoice {
    /** Voix telle que le moteur de synthèse la décrit ([quality] et [latency] : échelles d'Android, 100 à 500). */
    data class Candidate(
        val name: String,
        val language: String,
        val quality: Int,
        val latency: Int,
        val network: Boolean,
        val installed: Boolean,
    )

    fun pick(voices: List<Candidate>): Candidate? = voices
        .filter { it.language.lowercase().startsWith("fr") && it.installed && !it.network }
        .sortedWith(
            compareByDescending<Candidate> { it.language.equals("fr-FR", ignoreCase = true) }
                .thenByDescending { it.quality }
                .thenBy { it.latency },
        )
        .firstOrNull()
}
