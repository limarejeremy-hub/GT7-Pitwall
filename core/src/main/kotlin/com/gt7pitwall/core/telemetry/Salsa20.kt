package com.gt7pitwall.core.telemetry

/**
 * Implémentation minimale de Salsa20/20 (clé 256 bits, nonce 64 bits, compteur démarrant à 0).
 * GT7 chiffre ses paquets de télémétrie avec cet algorithme. Aucune dépendance externe.
 */
internal class Salsa20(key: ByteArray, nonce: ByteArray) {

    private val state = IntArray(16)

    init {
        require(key.size == 32) { "Salsa20 : clé de 32 octets attendue" }
        require(nonce.size == 8) { "Salsa20 : nonce de 8 octets attendu" }
        // Constantes "expand 32-byte k"
        state[0] = 0x61707865
        state[5] = 0x3320646e
        state[10] = 0x79622d32
        state[15] = 0x6b206574
        for (i in 0 until 4) {
            state[1 + i] = readIntLe(key, 4 * i)
            state[11 + i] = readIntLe(key, 16 + 4 * i)
        }
        state[6] = readIntLe(nonce, 0)
        state[7] = readIntLe(nonce, 4)
        state[8] = 0
        state[9] = 0
    }

    /** Chiffre ou déchiffre (opération identique) les [length] premiers octets de [input]. */
    fun process(input: ByteArray, length: Int = input.size): ByteArray {
        val out = ByteArray(length)
        val block = ByteArray(64)
        var offset = 0
        while (offset < length) {
            nextBlock(block)
            val n = minOf(64, length - offset)
            for (i in 0 until n) {
                out[offset + i] = (input[offset + i].toInt() xor block[i].toInt()).toByte()
            }
            offset += n
        }
        return out
    }

    private fun nextBlock(out: ByteArray) {
        val x = state.copyOf()
        repeat(10) {
            // Tour sur les colonnes
            x[4] = x[4] xor rotl(x[0] + x[12], 7)
            x[8] = x[8] xor rotl(x[4] + x[0], 9)
            x[12] = x[12] xor rotl(x[8] + x[4], 13)
            x[0] = x[0] xor rotl(x[12] + x[8], 18)
            x[9] = x[9] xor rotl(x[5] + x[1], 7)
            x[13] = x[13] xor rotl(x[9] + x[5], 9)
            x[1] = x[1] xor rotl(x[13] + x[9], 13)
            x[5] = x[5] xor rotl(x[1] + x[13], 18)
            x[14] = x[14] xor rotl(x[10] + x[6], 7)
            x[2] = x[2] xor rotl(x[14] + x[10], 9)
            x[6] = x[6] xor rotl(x[2] + x[14], 13)
            x[10] = x[10] xor rotl(x[6] + x[2], 18)
            x[3] = x[3] xor rotl(x[15] + x[11], 7)
            x[7] = x[7] xor rotl(x[3] + x[15], 9)
            x[11] = x[11] xor rotl(x[7] + x[3], 13)
            x[15] = x[15] xor rotl(x[11] + x[7], 18)
            // Tour sur les lignes
            x[1] = x[1] xor rotl(x[0] + x[3], 7)
            x[2] = x[2] xor rotl(x[1] + x[0], 9)
            x[3] = x[3] xor rotl(x[2] + x[1], 13)
            x[0] = x[0] xor rotl(x[3] + x[2], 18)
            x[6] = x[6] xor rotl(x[5] + x[4], 7)
            x[7] = x[7] xor rotl(x[6] + x[5], 9)
            x[4] = x[4] xor rotl(x[7] + x[6], 13)
            x[5] = x[5] xor rotl(x[4] + x[7], 18)
            x[11] = x[11] xor rotl(x[10] + x[9], 7)
            x[8] = x[8] xor rotl(x[11] + x[10], 9)
            x[9] = x[9] xor rotl(x[8] + x[11], 13)
            x[10] = x[10] xor rotl(x[9] + x[8], 18)
            x[12] = x[12] xor rotl(x[15] + x[14], 7)
            x[13] = x[13] xor rotl(x[12] + x[15], 9)
            x[14] = x[14] xor rotl(x[13] + x[12], 13)
            x[15] = x[15] xor rotl(x[14] + x[13], 18)
        }
        for (i in 0 until 16) {
            writeIntLe(out, 4 * i, x[i] + state[i])
        }
        state[8]++
        if (state[8] == 0) state[9]++
    }

    private fun rotl(v: Int, c: Int): Int = (v shl c) or (v ushr (32 - c))
}

internal fun readIntLe(b: ByteArray, o: Int): Int =
    (b[o].toInt() and 0xFF) or
        ((b[o + 1].toInt() and 0xFF) shl 8) or
        ((b[o + 2].toInt() and 0xFF) shl 16) or
        ((b[o + 3].toInt() and 0xFF) shl 24)

internal fun writeIntLe(b: ByteArray, o: Int, v: Int) {
    b[o] = v.toByte()
    b[o + 1] = (v ushr 8).toByte()
    b[o + 2] = (v ushr 16).toByte()
    b[o + 3] = (v ushr 24).toByte()
}
