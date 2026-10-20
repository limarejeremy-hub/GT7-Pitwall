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

    /**
     * GT7 ≥ 1.68 environ : 368 octets, ~ + surface sous chaque pneu, chrono du tour en cours (ms), angle des roues avant,
     * empattement, catégorie. Même constante XOR que B (MacManley/gt7-udp, GT7UDPParser.cpp) : la taille les distingue.
     */
    C('C'.code.toByte(), 0xDEADBEEF.toInt(), 0x170, "C"),
    ;

    companion object {
        /** Le format se déduit de la taille du paquet reçu. */
        fun forLength(length: Int): PacketFormat? = entries.sortedByDescending { it.size }.firstOrNull { length >= it.size }
    }
}

/**
 * Chiffrement des paquets "Simulator Interface" de Gran Turismo 7.
 *
 *  - le téléphone envoie un octet de heartbeat ('A', 'B', '~' ou 'C') à la PS5 sur le port UDP 33739 ;
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

    /** Au-delà, un datagramme n'est pas un paquet GT7 plausible. */
    const val MAX_LENGTH = 0x400

    /**
     * Déchiffre un paquet reçu (format déduit de sa taille, clé du plus grand format connu qu'il contient). Renvoie `null` si le paquet est trop
     * court ou si la signature déchiffrée ne correspond pas.
     */
    fun decrypt(data: ByteArray, length: Int = data.size): ByteArray? {
        val format = PacketFormat.forLength(length) ?: return null
        // Un paquet plus long qu'un format connu est déchiffré en entier : ses octets en plus restent visibles (Gap Hunter).
        val plain = Salsa20(KEY, nonceFor(readIntLe(data, IV_OFFSET), format)).process(data, minOf(length, MAX_LENGTH))
        return if (readIntLe(plain, 0) == MAGIC) plain else null
    }

    /**
     * Diagnostic des formats inconnus : essaie chaque constante XOR connue sur toute la longueur reçue. Renvoie les
     * formats dont la clé donne la bonne signature (plusieurs si les constantes coïncident, comme B et C), sinon vide.
     */
    fun keysThatDecrypt(data: ByteArray, length: Int = data.size): List<PacketFormat> {
        if (length < IV_OFFSET + 4) return emptyList()
        val iv = readIntLe(data, IV_OFFSET)
        return PacketFormat.entries.filter { f ->
            val plain = Salsa20(KEY, nonceFor(iv, f)).process(data, minOf(length, 8))
            readIntLe(plain, 0) == MAGIC
        }
    }

    /**
     * Chiffre un paquet en clair (tests et simulateur). Le format est déduit de la taille du paquet.
     * L'entier à l'offset 0x40 sert de nonce et reste lisible après chiffrement, comme dans GT7.
     */
    fun encrypt(plain: ByteArray, iv: Int): ByteArray {
        val format = requireNotNull(PacketFormat.forLength(plain.size))
        val enc = Salsa20(KEY, nonceFor(iv, format)).process(plain, plain.size)
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
