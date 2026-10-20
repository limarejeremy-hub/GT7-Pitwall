package com.gt7pitwall.core

import com.gt7pitwall.core.telemetry.ConnectionStatus
import com.gt7pitwall.core.telemetry.GT7Crypto
import com.gt7pitwall.core.telemetry.PacketFormat
import com.gt7pitwall.core.telemetry.TelemetryClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Bout-en-bout en local : une fausse PS5 répond aux heartbeats avec des paquets chiffrés. */
class TelemetryClientTest {

    /**
     * Fausse PS5 : ne répond qu'aux formats [accepted], avec des paquets de la bonne taille. [extra] : octets de
     * heartbeat hypothétiques → taille des paquets renvoyés (sonde des formats).
     */
    private class FakePs5(private val accepted: Set<PacketFormat>, private val extra: Map<Char, Int> = emptyMap()) {
        val socket = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        val heartbeats = mutableListOf<Byte>()
        private val worker = thread(isDaemon = true) {
            val buf = ByteArray(16)
            var id = 0
            try {
                while (true) {
                    val req = DatagramPacket(buf, buf.size)
                    socket.receive(req)
                    val hb = buf[0]
                    synchronized(heartbeats) { heartbeats += hb }
                    val size = PacketFormat.entries.firstOrNull { it.heartbeat == hb && it in accepted }?.size
                        ?: extra[hb.toInt().toChar()] ?: continue
                    repeat(5) {
                        id++
                        val plain = TestPackets.plain(lap = id, fuel = 100f - id, packetId = id, size = size)
                        val enc = GT7Crypto.encrypt(plain, iv = 1000 + id)
                        socket.send(DatagramPacket(enc, enc.size, req.address, req.port))
                    }
                    // Un paquet invalide doit être ignoré.
                    val junk = ByteArray(size) { 1 }
                    socket.send(DatagramPacket(junk, junk.size, req.address, req.port))
                }
            } catch (e: SocketException) {
                // fermeture
            }
        }

        fun close() {
            socket.close()
            worker.join(2_000)
        }
    }

    private fun config(ps5: FakePs5, fallbackMs: Long = 4_000) = TelemetryClient.Config(
        ps5Port = ps5.socket.localPort,
        listenPort = DatagramSocket(0).use { it.localPort },
        formatFallbackMs = fallbackMs,
    )

    private fun receive(ps5: FakePs5, fallbackMs: Long = 4_000) = runBlocking {
        val client = TelemetryClient(config(ps5, fallbackMs))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val received = scope.async(start = CoroutineStart.UNDISPATCHED) { client.packets.take(5).toList() }
            client.start(scope, "127.0.0.1")
            val packets = withTimeout(10_000) { received.await() }
            val status = withTimeout(5_000) { client.status.first { it is ConnectionStatus.Receiving } }
            Pair(packets, status as ConnectionStatus.Receiving)
        } finally {
            client.stop()
            scope.cancel()
            assertEquals(ConnectionStatus.Stopped, client.status.value)
        }
    }

    @Test
    fun requestsFormatCFirstAndDecodesIt() {
        val ps5 = FakePs5(PacketFormat.entries.toSet())
        try {
            val (packets, status) = receive(ps5)
            assertEquals(listOf(1, 2, 3, 4, 5), packets.map { it.packetId })
            assertEquals(95f, packets.last().fuelLevel)
            assertEquals(PacketFormat.C, packets.last().format)
            assertEquals('C'.code.toByte(), synchronized(ps5.heartbeats) { ps5.heartbeats.first() })
            assertIs<ConnectionStatus.Receiving>(status)
            assertEquals("127.0.0.1", status.ps5Address)
            assertEquals(PacketFormat.C, status.format)
            assertEquals(emptyList(), status.unanswered)
        } finally {
            ps5.close()
        }
    }

    @Test
    fun fallsBackToTildeWhenConsoleIgnoresC() {
        val ps5 = FakePs5(setOf(PacketFormat.A, PacketFormat.B, PacketFormat.TILDE))
        try {
            val (packets, status) = receive(ps5, fallbackMs = 300)
            assertEquals(PacketFormat.TILDE, packets.last().format)
            assertEquals(PacketFormat.TILDE, status.format)
            assertEquals(listOf(PacketFormat.C), status.unanswered)
            assertTrue(status.fullFormatUnanswered)
            val hbs = synchronized(ps5.heartbeats) { ps5.heartbeats.toList() }
            assertEquals('C'.code.toByte(), hbs.first())
            assertEquals('~'.code.toByte(), hbs.last())
        } finally {
            ps5.close()
        }
    }

    @Test
    fun fallsBackToFormatAWhenConsoleIgnoresCAndTilde() {
        val ps5 = FakePs5(setOf(PacketFormat.A))
        try {
            val (packets, status) = receive(ps5, fallbackMs = 300)
            assertEquals(PacketFormat.A, packets.first().format)
            assertEquals(PacketFormat.A, status.format)
            assertEquals(listOf(PacketFormat.C, PacketFormat.TILDE), status.unanswered)
            val hbs = synchronized(ps5.heartbeats) { ps5.heartbeats.toList() }
            assertEquals('C'.code.toByte(), hbs.first())
            assertEquals('A'.code.toByte(), hbs.last())
        } finally {
            ps5.close()
        }
    }

    @Test
    fun formatScanReportsWhichHeartbeatsTheConsoleAnswers() = runBlocking<Unit> {
        // Console synthétique : A, ~ et un format hypothétique « D » de 400 octets (clé de C). Rien de réel ici.
        val ps5 = FakePs5(setOf(PacketFormat.A, PacketFormat.TILDE), extra = mapOf('D' to 400))
        val client = TelemetryClient(
            config(ps5, fallbackMs = 300).copy(scanSilenceMs = 300, scanMaxSilenceMs = 1_500, scanProbeMs = 600, scanHeartbeatMs = 200),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            client.start(scope, "127.0.0.1")
            withTimeout(5_000) { client.status.first { it is ConnectionStatus.Receiving } }
            client.startFormatScan(listOf('A', 'D', 'Q'))
            withTimeout(5_000) { client.scan.first { it.running } }
            val done = withTimeout(20_000) { client.scan.first { !it.running && it.results.size == 3 } }
            val (a, d, q) = done.results
            // 5 paquets + 1 illisible par heartbeat reçu.
            assertTrue(a.packets >= 6 && a.sizes.keys == setOf(296) && PacketFormat.A in a.keys)
            assertTrue(d.packets >= 6 && d.sizes.keys == setOf(400) && PacketFormat.C in d.keys)
            assertEquals(0, q.packets)
            assertEquals(emptyList(), q.keys)
            // Après la sonde, la télémétrie normale reprend.
            withTimeout(10_000) { client.status.first { it is ConnectionStatus.Receiving && it.format == PacketFormat.TILDE } }
        } finally {
            client.stop()
            scope.cancel()
            ps5.close()
        }
    }
}
