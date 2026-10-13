package com.gt7pitwall.core

import com.gt7pitwall.core.TestPackets.hex
import com.gt7pitwall.core.telemetry.GT7Crypto
import com.gt7pitwall.core.telemetry.Salsa20
import com.gt7pitwall.core.telemetry.TelemetryParser
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CryptoAndParserTest {

    /** Vecteur produit par pycryptodome (implémentation Salsa20 de référence indépendante). */
    @Test
    fun salsa20MatchesReferenceImplementation() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = byteArrayOf(7, 6, 5, 4, 3, 2, 1, 0)
        val plain = ByteArray(150) { ((it * 13) and 0xFF).toByte() }
        val expected = hex(
            "8a36a73591d0fbf7e9c6d6c67a9e3dd9b667b61282322465b83b3c4d62df9643fa1e985a1aabbf2f7ea90413086fb7491ef3a3aa260a113e5e15484d62def08ae0f126175a09544e4ccf7ea3e9b2fc97c946ecc791a1d23939ee849c58bf1fb2c0a375968b5c6f48a3dff4da31495f91f68d82b29c54f202012176aed550c59a1fe56bf332f8e8977ce288e128dbb57c35a03448bb30",
        )
        assertContentEquals(expected, Salsa20(key, nonce).process(plain))
    }

    /** Paquet chiffré par pycryptodome selon le schéma GT7 (clé fixe, nonce dérivé de l'offset 0x40). */
    @Test
    fun decodesReferenceEncryptedPacket() {
        val enc = hex(REFERENCE_PACKET)
        val p = assertNotNull(TelemetryParser.parseEncrypted(enc))
        assertEquals(6500f, p.engineRpm)
        assertEquals(42.5f, p.fuelLevel)
        assertEquals(100f, p.fuelCapacity)
        assertEquals(180f, p.speedKmh, 0.001f)
        assertEquals(12345, p.packetId)
        assertEquals(3, p.currentLap)
        assertEquals(10, p.totalLaps)
        assertEquals(92456, p.bestLapMs)
        assertEquals(93001, p.lastLapMs)
        assertTrue(p.isOnTrack)
        assertTrue(p.isInGear)
        assertEquals(4, p.currentGear)
        assertEquals(5, p.suggestedGear)
        assertEquals(255, p.throttle)
        assertEquals(12, p.brake)
        assertEquals(3456, p.carCode)
        assertEquals(0.425f, p.fuelFraction!!, 0.0001f)
    }

    @Test
    fun encryptThenDecryptRoundTrip() {
        val plain = TestPackets.plain(lap = 7, fuel = 33.3f, lastLapMs = 61_234)
        val enc = GT7Crypto.encrypt(plain, iv = 0x0BADF00D)
        val p = assertNotNull(TelemetryParser.parseEncrypted(enc))
        assertEquals(7, p.currentLap)
        assertEquals(33.3f, p.fuelLevel)
        assertEquals(61_234, p.lastLapMs)
    }

    @Test
    fun rejectsCorruptedOrShortPackets() {
        val enc = hex(REFERENCE_PACKET)
        enc[0] = (enc[0].toInt() xor 0x55).toByte()
        assertNull(TelemetryParser.parseEncrypted(enc))
        assertNull(TelemetryParser.parseEncrypted(ByteArray(100)))
        assertNull(TelemetryParser.parseEncrypted(ByteArray(0x128)))
    }

    companion object {
        const val REFERENCE_PACKET =
            "9b30230172dc4f146908cc48817aee44f66e46233794ba33fc398a3a0eb88770fd20b8533f74b8d48f7964545ff90d4f343232c0fff4f52cb116bb58fc7bd59ccdab341276015480506967d0c23085d562ea6ac90a7587b6bb0f4c9229b753b00a05378e6e8a66498f3673a8a1150ba1128744008f92112c8a9c5517a6f374ce025d4b8364fee37af7831070e478fda0c8be95fa7d948a3dd94ac88c27473bd0eba523aa5a3bf15f873310e32526920bc63dc43052040a174637b4904ac52a4337fce27ceacb7322d7d6fbff0530e77c36c1f20f8a3fc3840883dd680aacb8bcb073fe339a29e7fc163f9e717ff3f63f0baca17274f4e6e2a40a38fa7899d256d8ee5a207bab98c4feacd4f08ba96c18c6050bed339cca4833df909c6926a6a6953000e8ca270304"
    }
}
