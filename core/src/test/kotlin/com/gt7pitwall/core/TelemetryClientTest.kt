package com.gt7pitwall.core

import com.gt7pitwall.core.telemetry.ConnectionStatus
import com.gt7pitwall.core.telemetry.GT7Crypto
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
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Bout-en-bout en local : une fausse PS5 répond au heartbeat avec des paquets chiffrés. */
class TelemetryClientTest {

    @Test
    fun heartbeatThenReceivesDecodedPackets() = runBlocking {
        val fakePs5 = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        val listenPort = DatagramSocket(0).use { it.localPort }
        val heartbeats = mutableListOf<Byte>()

        val server = thread(isDaemon = true) {
            val buf = ByteArray(16)
            val req = DatagramPacket(buf, buf.size)
            fakePs5.receive(req)
            synchronized(heartbeats) { heartbeats += buf[0] }
            for (i in 1..5) {
                val enc = GT7Crypto.encrypt(TestPackets.plain(lap = i, fuel = 100f - i, packetId = i), iv = 1000 + i)
                fakePs5.send(DatagramPacket(enc, enc.size, req.address, req.port))
            }
            // Un paquet invalide doit être ignoré.
            val junk = ByteArray(0x128) { 1 }
            fakePs5.send(DatagramPacket(junk, junk.size, req.address, req.port))
        }

        val client = TelemetryClient(
            TelemetryClient.Config(ps5Port = fakePs5.localPort, listenPort = listenPort),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val received = scope.async(start = CoroutineStart.UNDISPATCHED) { client.packets.take(5).toList() }
            client.start(scope, "127.0.0.1")
            val packets = withTimeout(10_000) { received.await() }
            assertEquals(listOf(1, 2, 3, 4, 5), packets.map { it.packetId })
            assertEquals(95f, packets.last().fuelLevel)
            assertEquals('A'.code.toByte(), synchronized(heartbeats) { heartbeats.first() })
            val status = withTimeout(5_000) { client.status.first { it is ConnectionStatus.Receiving } }
            assertIs<ConnectionStatus.Receiving>(status)
            assertEquals("127.0.0.1", status.ps5Address)
        } finally {
            client.stop()
            scope.cancel()
            server.join(2_000)
            fakePs5.close()
        }
        assertEquals(ConnectionStatus.Stopped, client.status.value)
    }
}
