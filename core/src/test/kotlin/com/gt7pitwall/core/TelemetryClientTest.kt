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

/** Bout-en-bout en local : une fausse PS5 répond aux heartbeats avec des paquets chiffrés. */
class TelemetryClientTest {

    /** Fausse PS5 : ne répond qu'aux formats [accepted], avec des paquets de la bonne taille. */
    private class FakePs5(private val accepted: Set<PacketFormat>) {
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
                    val format = PacketFormat.entries.firstOrNull { it.heartbeat == hb && it in accepted } ?: continue
                    repeat(5) {
                        id++
                        val plain = TestPackets.plain(lap = id, fuel = 100f - id, packetId = id, size = format.size)
                        val enc = GT7Crypto.encrypt(plain, iv = 1000 + id)
                        socket.send(DatagramPacket(enc, enc.size, req.address, req.port))
                    }
                    // Un paquet invalide doit être ignoré.
                    val junk = ByteArray(format.size) { 1 }
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

    private fun receive(ps5: FakePs5, fallbackMs: Long = 4_000) = runBlocking {
        val listenPort = DatagramSocket(0).use { it.localPort }
        val client = TelemetryClient(
            TelemetryClient.Config(ps5Port = ps5.socket.localPort, listenPort = listenPort, formatFallbackMs = fallbackMs),
        )
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
    fun requestsFullFormatAndDecodesExtendedPackets() {
        val ps5 = FakePs5(setOf(PacketFormat.A, PacketFormat.B, PacketFormat.TILDE))
        try {
            val (packets, status) = receive(ps5)
            assertEquals(listOf(1, 2, 3, 4, 5), packets.map { it.packetId })
            assertEquals(95f, packets.last().fuelLevel)
            assertEquals(PacketFormat.TILDE, packets.last().format)
            assertEquals('~'.code.toByte(), synchronized(ps5.heartbeats) { ps5.heartbeats.first() })
            assertIs<ConnectionStatus.Receiving>(status)
            assertEquals("127.0.0.1", status.ps5Address)
            assertEquals(PacketFormat.TILDE, status.format)
        } finally {
            ps5.close()
        }
    }

    @Test
    fun fallsBackToFormatAWhenConsoleIgnoresTilde() {
        val ps5 = FakePs5(setOf(PacketFormat.A))
        try {
            val (packets, status) = receive(ps5, fallbackMs = 300)
            assertEquals(PacketFormat.A, packets.first().format)
            assertEquals(PacketFormat.A, status.format)
            val hbs = synchronized(ps5.heartbeats) { ps5.heartbeats.toList() }
            assertEquals('~'.code.toByte(), hbs.first())
            assertEquals('A'.code.toByte(), hbs.last())
        } finally {
            ps5.close()
        }
    }
}
