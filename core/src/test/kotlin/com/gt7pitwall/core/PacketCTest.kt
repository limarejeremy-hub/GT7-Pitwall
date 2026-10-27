package com.gt7pitwall.core

import com.gt7pitwall.core.TestPackets.hex
import com.gt7pitwall.core.telemetry.GT7Crypto
import com.gt7pitwall.core.telemetry.PacketFormat
import com.gt7pitwall.core.telemetry.Salsa20
import com.gt7pitwall.core.telemetry.Surface
import com.gt7pitwall.core.telemetry.TelemetryParser
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Format C (368 octets). Paquets SYNTHÉTIQUES construits d'après MacManley/gt7-udp (GT7UDPParser.h/.cpp) : ces tests
 * vérifient le décodage et le chiffrement, pas le comportement réel de GT7, qui reste à valider sur PS5.
 */
class PacketCTest {

    private fun cPlain(): ByteArray {
        val plain = TestPackets.plain(lap = 3, fuel = 55f, packetId = 42, speedMps = 40f, size = PacketFormat.C.size)
        ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN).apply {
            putFloat(0x128, 0.1f) // volant (B)
            put(0x13C, 200.toByte()) // accélérateur filtré (~)
            putFloat(0x150, 3f) // récupération d'énergie (~)
            put(0x158, 'T'.code.toByte()); put(0x159, 'C'.code.toByte())
            put(0x15A, 'G'.code.toByte()); put(0x15B, 'X'.code.toByte())
            putInt(0x15C, 83_456) // chrono du tour en cours
            putFloat(0x160, 0.05f); putFloat(0x164, 0.04f)
            putFloat(0x168, 2.65f)
            put(0x16C, 'G'.code.toByte()); put(0x16D, 'R'.code.toByte()); put(0x16E, '3'.code.toByte()); put(0x16F, 0)
        }
        return plain
    }

    @Test
    fun formatCIsRequestedWithLetterCAndRecognisedBySize() {
        assertEquals('C'.code.toByte(), PacketFormat.C.heartbeat)
        assertEquals(368, PacketFormat.C.size)
        assertEquals(0xDEADBEEF.toInt(), PacketFormat.C.xorKey)
        assertEquals(PacketFormat.C, PacketFormat.forLength(368))
        assertEquals(PacketFormat.TILDE, PacketFormat.forLength(344))
        assertEquals(PacketFormat.B, PacketFormat.forLength(316))
        assertEquals(PacketFormat.A, PacketFormat.forLength(296))
    }

    @Test
    fun encryptionFollowsThePublishedNonceRule() {
        // Règle publiée (gt7-udp README) : nonce = (iv1 XOR 0xDEADBEEF) puis iv1, en little-endian.
        val plain = cPlain()
        val iv1 = 0x12345678
        val nonce = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(iv1 xor 0xDEADBEEF.toInt()).putInt(iv1).array()
        val key = "Simulator Interface Packet GT7 ver 0.0".toByteArray(Charsets.US_ASCII).copyOf(32)
        val expected = Salsa20(key, nonce).process(plain, plain.size)
        ByteBuffer.wrap(expected).order(ByteOrder.LITTLE_ENDIAN).putInt(0x40, iv1)
        assertContentEquals(expected, GT7Crypto.encrypt(plain, iv1))
    }

    @Test
    fun decodesTheFieldsAddedByFormatC() {
        val p = assertNotNull(TelemetryParser.parseEncrypted(GT7Crypto.encrypt(cPlain(), iv = 991)))
        assertEquals(PacketFormat.C, p.format)
        // Les champs des formats précédents sont toujours là.
        assertEquals(55f, p.fuelLevel)
        assertEquals(0.1f, p.steeringRad)
        assertEquals(200, p.throttleFiltered)
        assertEquals(3f, p.energyRecovery)
        // Nouveaux champs.
        assertEquals(83_456, p.currentLapTimeMs)
        val s = assertNotNull(p.surfaces)
        assertEquals(listOf(Surface.TARMAC, Surface.KERB, Surface.GRASS, Surface.UNKNOWN), s.toList())
        assertEquals("TCGX", s.raw)
        assertEquals(1, s.count(Surface.KERB))
        // Issue #36-D3 : un code inconnu n'est ni asphalte ni sortie de piste ; seule l'herbe compte comme hors piste.
        assertEquals(1, s.wheelsOff)
        assertEquals(1, s.unknownWheels)
        assertEquals(false, s.allTarmac)
        assertEquals(0.05f, p.frontWheelAngleLeftRad)
        assertEquals(0.04f, p.frontWheelAngleRightRad)
        assertEquals(2.65f, p.wheelBaseM)
        assertEquals("GR3", p.carCategory)
    }

    @Test
    fun olderFormatsDoNotInventFormatCFields() {
        val tilde = TestPackets.plain(size = PacketFormat.TILDE.size)
        val p = assertNotNull(TelemetryParser.parseEncrypted(GT7Crypto.encrypt(tilde, iv = 5)))
        assertEquals(PacketFormat.TILDE, p.format)
        assertNull(p.surfaces)
        assertNull(p.currentLapTimeMs)
        assertNull(p.wheelBaseM)
        assertNull(p.carCategory)
        val a = assertNotNull(TelemetryParser.parseEncrypted(hex(CryptoAndParserTest.REFERENCE_PACKET)))
        assertEquals(PacketFormat.A, a.format)
        assertNull(a.currentLapTimeMs)
    }

    @Test
    fun unreadableCategoryStaysUnknown() {
        val plain = cPlain()
        plain[0x16C] = 0x01
        val p = assertNotNull(TelemetryParser.parseEncrypted(GT7Crypto.encrypt(plain, iv = 3)))
        assertNull(p.carCategory)
    }

    @Test
    fun longerPacketsKeepTheirExtraBytesForTheDiagnostic() {
        // Format hypothétique plus long, même clé que C : rien n'est jeté, Gap Hunter voit les octets en plus.
        val plain = cPlain().copyOf(400)
        plain[396] = 0x7F
        val enc = GT7Crypto.encrypt(plain, iv = 77)
        val dec = assertNotNull(GT7Crypto.decrypt(enc, enc.size))
        assertEquals(400, dec.size)
        assertEquals(0x7F.toByte(), dec[396])
        assertEquals(PacketFormat.C, TelemetryParser.parseDecrypted(dec)!!.format)
    }

    @Test
    fun identifiesWhichKnownKeysDecryptAPacket() {
        val c = GT7Crypto.encrypt(cPlain(), iv = 10)
        val keys = GT7Crypto.keysThatDecrypt(c)
        assertTrue(PacketFormat.C in keys && PacketFormat.B in keys) // même constante XOR
        assertTrue(PacketFormat.A !in keys)
        assertEquals(emptyList(), GT7Crypto.keysThatDecrypt(ByteArray(368) { 7 }))
    }

    /**
     * Paquet A RÉEL capturé par un autre projet (vwhitteron/gt-telemetry, licence MIT,
     * internal/utils/salsa20_test.go, validSalsa20Content) : référence indépendante de notre code.
     */
    @Test
    fun decodesAnIndependentRealCapture() {
        val enc = hex(VWHITTERON_PACKET_A)
        assertEquals(listOf(PacketFormat.A), GT7Crypto.keysThatDecrypt(enc)) // les clés B, C et ~ échouent
        val p = assertNotNull(TelemetryParser.parseEncrypted(enc))
        assertEquals(PacketFormat.A, p.format)
        assertEquals(175_771, p.packetId)
        assertEquals(55.62648f, p.speedMps, 0.0001f)
        assertEquals(7973.833f, p.engineRpm, 0.01f)
        assertEquals(4, p.currentLap)
        assertEquals(139_965, p.bestLapMs)
        assertEquals(139_965, p.lastLapMs)
        assertEquals(-1, p.preRacePosition)
        assertEquals(3311, p.carCode)
        assertEquals(4, p.currentGear)
        assertEquals(255, p.throttle)
        // À noter : voiture lancée à 200 km/h, mais drapeau « en piste » (bit 0) absent (flags 0x0008).
        assertEquals(0x0008, p.flags)
        assertNull(p.currentLapTimeMs)
    }

    companion object {
        const val VWHITTERON_PACKET_A = "f6374a12433223cf4e531feb646dffaec8f0c8b96edcaf5291b440f2ab4801f1fc5a342c25757409f497e138754658495ec3bcdd6bc548cdc31a8fbc41631f1bfb599f0c0b8dd658700655ddc6eca74cc991ba7a1c14ab1a399075adce555a9d2030412e8ae30433a120bc776134eeb3188adc80394bb8be75aaef6f67039501745f851335a2bc9ce1c655b50709236727f56fd8894dff6610655c1b1232cd5cad28efad037303de47d720f2411ae20d54d64204fdaf198123215b6089623ec23dcbac965bf6d22a293259b27eddfe4ca6068cbec62eeb6a58ef982df96a13004fd1c2c88d58dacbea2804571b48ebf9579539bfb89d49f0271c11eb6f273b0e6a81869c884448311688526e31f0f64aa820a9f8a255d64936989a9eb45e8e1d2bd74602fd30418a"
    }
}
