package com.gt7pitwall.core.telemetry

/**
 * Chiffrement des paquets "Simulator Interface" de Gran Turismo 7.
 *
 * Protocole (documenté par la communauté, cf. README) :
 *  - le téléphone envoie un octet de "heartbeat" ('A') à la PS5 sur le port UDP 33739 ;
 *  - la PS5 répond ~60 fois par seconde vers le port 33740 de l'expéditeur ;
 *  - chaque paquet est chiffré en Salsa20 avec une clé fixe et un nonce dérivé
 *    de l'entier situé à l'offset 0x40 du paquet.
 *
 * Seul le format 'A' (paquet de 296 octets) est géré dans cette version.
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
     * Déchiffre un paquet reçu. Renvoie `null` si le paquet est trop court
     * ou si la signature déchiffrée ne correspond pas (paquet invalide ou autre format).
     */
    fun decrypt(data: ByteArray, length: Int = data.size): ByteArray? {
        if (length < PACKET_SIZE_A) return null
        val plain = cipherFor(data).process(data, PACKET_SIZE_A)
        return if (readIntLe(plain, 0) == MAGIC) plain else null
    }

    /**
     * Chiffre un paquet en clair (utile pour les tests et un simulateur).
     * L'entier à l'offset 0x40 du paquet en clair sert de nonce et reste lisible après chiffrement,
     * exactement comme dans les paquets envoyés par GT7.
     */
    fun encrypt(plain: ByteArray, iv: Int): ByteArray {
        require(plain.size >= PACKET_SIZE_A)
        val nonce = nonceFor(iv)
        val enc = Salsa20(KEY, nonce).process(plain, PACKET_SIZE_A)
        writeIntLe(enc, IV_OFFSET, iv)
        return enc
    }

    private fun cipherFor(data: ByteArray): Salsa20 = Salsa20(KEY, nonceFor(readIntLe(data, IV_OFFSET)))

    private fun nonceFor(iv1: Int): ByteArray {
        val iv2 = iv1 xor XOR_KEY_A
        val nonce = ByteArray(8)
        writeIntLe(nonce, 0, iv2)
        writeIntLe(nonce, 4, iv1)
        return nonce
    }
}
