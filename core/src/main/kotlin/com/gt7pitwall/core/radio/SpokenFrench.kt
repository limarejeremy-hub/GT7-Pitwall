package com.gt7pitwall.core.radio

/**
 * Texte envoyé à la SYNTHÈSE VOCALE seulement (#73 P1-C : « onze » entendu « on », /s/ et /z/ tronqués). L'écran, le
 * journal et la mémoire gardent le texte d'origine ; la voix et son réglage ne changent pas.
 *
 * - « P11 » → « P onze » : la position n'est plus un sigle collé que le moteur de synthèse peut lire comme un mot.
 * - Petits nombres isolés (0 à 20) écrits en lettres (« 11 tours » → « onze tours », « 0 » → « zéro ») : pas de
 *   lecture de chiffres ni de liaison inventée. Les chronos, décimales et heures (« 1:59.523 », « 41,7 ») ne sont
 *   jamais touchés.
 * - Ponctuation finale garantie : le moteur termine la phrase (chute et relâchement de la dernière consonne) au lieu de
 *   la couper.
 *
 * Ce sont des précautions de TEXTE : elles ne prouvent pas une bonne prononciation. L'écoute sur le Fold et sur Discord
 * reste à faire avec le pilote.
 */
object SpokenFrench {
    private val WORDS = listOf(
        "zéro", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix",
        "onze", "douze", "treize", "quatorze", "quinze", "seize", "dix-sept", "dix-huit", "dix-neuf", "vingt",
    )

    private val POSITION = Regex("""\bP(\d{1,2})\b""")

    /** Entier isolé : ni collé à une lettre, ni partie d'un chrono, d'une décimale ou d'une heure. */
    private val SMALL = Regex("""(?<![\p{L}\d.,:/'])(\d{1,2})(?![\p{L}\d]|[.,:/]\d)""")

    fun words(n: Int): String? = WORDS.getOrNull(n)

    fun forAudio(text: String): String {
        var t = text.trim()
        if (t.isEmpty()) return t
        t = POSITION.replace(t) { m -> words(m.groupValues[1].toInt())?.let { "P $it" } ?: m.value }
        t = SMALL.replace(t) { m -> words(m.groupValues[1].toInt()) ?: m.value }
        if (t.last() !in ".!?…") t += "."
        return t
    }

    /**
     * Silence ajouté autour d'une phrase synthétisée pour Discord ([rate] Hz, mono 16 bits) : en tête, le temps que le
     * salon ouvre la voix (début de phrase tronqué) ; en queue, au-delà du délai interne de l'encodeur Opus et de la
     * dernière trame complétée (fin de « onze » coupée).
     */
    fun padded(pcm: ShortArray, rate: Int, leadMs: Int = DISCORD_LEAD_MS, tailMs: Int = DISCORD_TAIL_MS): ShortArray {
        if (pcm.isEmpty()) return pcm
        val lead = rate * leadMs / 1000
        val tail = rate * tailMs / 1000
        val out = ShortArray(lead + pcm.size + tail)
        pcm.copyInto(out, lead)
        return out
    }

    const val DISCORD_LEAD_MS = 60
    const val DISCORD_TAIL_MS = 160
}

/** Phrases d'écoute de #73 P1-C (Fold et Discord) : nombres et /s/ /z/ en début et en fin de phrase. */
object DictionSamples {
    val LINES = listOf(
        "P11.",
        "Position 11.",
        "Il reste 11 tours.",
        "11, 12, 13, 16, 0.",
        "Sans souci, les pneus tiennent seize tours.",
        "Zéro arrêt obligatoire, mais deux gommes imposées.",
    )
}
