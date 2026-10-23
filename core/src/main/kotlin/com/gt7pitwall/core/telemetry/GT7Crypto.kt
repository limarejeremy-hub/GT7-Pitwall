package com.gt7pitwall.core.telemetry

/**
 * Formats de paquets du "Simulator Interface" de GT7 (référence : PDTools de Nenkai, licence MIT).
 * Le format est choisi par l'octet de heartbeat envoyé à la PS5.
 */
enum class PacketFormat(val heartbeat: Byte, val xorKey: Int, val size: Int, val label: String) {
    /** Format historique : 296 octets. */
    A('A'.code.toByte(), 0xDEADBEAF.toInt(), 0x128, "A"),

    /** GT7 ≥ 1.42 : + angle du volant, roulis/tangage/cavalement (sway, heave, surge). */
    B('B'.code.toByte(), 0xDEADBEEF.toInt(), 0x13C, "B"),

    /** GT7 ≥ 1.42 : format le plus complet (+ pédales filtrées, type de voiture, récupération d'énergie). */
    TILDE('~'.code.toByte(), 0x55FABB4F, 0x158, "~"),
    ;

    companion object {
        /** Le format se déduit de la taille du paquet reçu. */
        fun forLength(length: Int): PacketFormat? = entries.sortedByDescending { it.size }.firstOrNull { length >= it.size }
    }
}

/**
 * Chiffrement des paquets "Simulator Interface" de Gran Turismo 7.
 *
 *  - le téléphone envoie un octet de heartbeat ('A', 'B' ou '~') à la PS5 sur le port UDP 33739 ;
 *  - la PS5 répond ~60 fois par seconde vers le port 33740 de l'expéditeur ;
 *  - chaque paquet est chiffré en Salsa20 avec une clé fixe et un nonce dérivé de l'entier situé
 *    à l'offset 0x40, combiné à une clé XOR propre au format.
 */
object GT7Crypto {
    /** Les 32 premiers octets de "Simulator Interface Packet GT7 ver 0.0". */
    private val KEY: ByteArray = "Simulator Interface Packet GT7 ver 0.0".toByteArray(Charsets.US_ASCII).copyOf(32)

    const val HEARTBEAT_A: Byte = 'A'.code.toByte()
    const val XOR_KEY_A: Int = 0xDEADBEAF.toInt()
    const val PACKET_SIZE_A: Int = 0x128

    /** "G7S0" lu en little-endian. */
    const val MAGIC: Int = 0x47375330

    private const val IV_OFFSET = 0x40

    /**
     * Déchiffre un paquet reçu (format déduit de sa taille). Renvoie `null` si le paquet est trop
     * court ou si la signature déchiffrée ne correspond pas.
     */
    fun decrypt(data: ByteArray, length: Int = data.size): ByteArray? {
        val format = PacketFormat.forLength(length) ?: return null
        val plain = Salsa20(KEY, nonceFor(readIntLe(data, IV_OFFSET), format)).process(data, format.size)
        return if (readIntLe(plain, 0) == MAGIC) plain else null
    }

    /**
     * Chiffre un paquet en clair (tests et simulateur). Le format est déduit de la taille du paquet.
     * L'entier à l'offset 0x40 sert de nonce et reste lisible après chiffrement, comme dans GT7.
     */
    fun encrypt(plain: ByteArray, iv: Int): ByteArray {
        val format = requireNotNull(PacketFormat.forLength(plain.size))
        val enc = Salsa20(KEY, nonceFor(iv, format)).process(plain, format.size)
        writeIntLe(enc, IV_OFFSET, iv)
        return enc
    }

    private fun nonceFor(iv1: Int, format: PacketFormat): ByteArray {
        val iv2 = iv1 xor format.xorKey
        val nonce = ByteArray(8)
        writeIntLe(nonce, 0, iv2)
        writeIntLe(nonce, 4, iv1)
        return nonce
    }
}
