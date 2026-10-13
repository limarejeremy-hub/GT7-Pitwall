package com.gt7pitwall.core.telemetry

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException

/** État de la liaison avec la PS5. */
sealed interface ConnectionStatus {
    data object Stopped : ConnectionStatus

    /** Heartbeats envoyés, en attente des premiers paquets. [target] = null en recherche automatique. */
    data class Searching(val target: String?) : ConnectionStatus

    data class Receiving(
        val ps5Address: String,
        val packetsPerSecond: Int,
        val format: PacketFormat = PacketFormat.A,
    ) : ConnectionStatus

    /** Des paquets ont été reçus, puis plus rien depuis [silenceMs]. */
    data class NoData(val ps5Address: String, val silenceMs: Long) : ConnectionStatus

    data class Error(val message: String) : ConnectionStatus
}

/**
 * Client UDP de la télémétrie GT7.
 *
 * Envoie régulièrement le heartbeat 'A' à la PS5 (port 33739) et écoute les paquets sur le port 33740.
 * Si aucune adresse n'est fournie, le heartbeat est diffusé en broadcast et l'adresse de la PS5
 * est retenue dès le premier paquet valide (recherche automatique, à confirmer sur le réseau réel).
 */
class TelemetryClient(
    private val config: Config = Config(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Config(
        val ps5Port: Int = 33739,
        val listenPort: Int = 33740,
        val heartbeatEveryPackets: Int = 100,
        val heartbeatIntervalMs: Long = 3_000,
        val noDataAfterMs: Long = 2_000,
        val receiveTimeoutMs: Int = 500,
        /** Format demandé en premier : le plus complet. */
        val preferredFormat: PacketFormat = PacketFormat.TILDE,
        /** Sans réponse dans ce délai, on essaie l'autre format (anciennes versions de GT7). */
        val formatFallbackMs: Long = 4_000,
    )

    private val _status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Stopped)
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val _packets = MutableSharedFlow<TelemetryPacket>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val packets: SharedFlow<TelemetryPacket> = _packets.asSharedFlow()

    /** Paquets reçus mais impossibles à déchiffrer (diagnostic). */
    @Volatile
    var invalidPackets: Long = 0
        private set

    @Volatile
    private var socket: DatagramSocket? = null
    private var job: Job? = null

    /** Démarre la réception. [ps5Host] vide ou null = recherche automatique par broadcast. */
    fun start(scope: CoroutineScope, ps5Host: String?) {
        stop()
        val host = ps5Host?.trim().orEmpty()
        _status.value = ConnectionStatus.Searching(host.ifEmpty { null })
        job = scope.launch(Dispatchers.IO) { runLoop(host) }
    }

    fun stop() {
        job?.cancel()
        job = null
        socket?.close()
        socket = null
        _status.value = ConnectionStatus.Stopped
    }

    private suspend fun CoroutineScope.runLoop(host: String) {
        val discovery = host.isEmpty()
        val sock = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = config.receiveTimeoutMs
                bind(InetSocketAddress(config.listenPort))
            }
        } catch (e: Exception) {
            _status.value = ConnectionStatus.Error("Port ${config.listenPort} indisponible : ${e.message}")
            return
        }
        socket = sock
        try {
            var target: InetAddress = try {
                if (discovery) InetAddress.getByName("255.255.255.255") else InetAddress.getByName(host)
            } catch (e: Exception) {
                _status.value = ConnectionStatus.Error("Adresse PS5 invalide : $host")
                return
            }
            var locked = !discovery
            var format = config.preferredFormat
            var formatSince = clock()
            var gotValidPacket = false
            val buffer = ByteArray(4096)
            var lastHeartbeatAt = Long.MIN_VALUE / 2
            var packetsSinceHeartbeat = 0
            var lastPacketAt = 0L
            var rateWindowStart = clock()
            var rateCount = 0
            var currentRate = 0

            while (isActive) {
                val now = clock()
                if (!gotValidPacket && now - formatSince >= config.formatFallbackMs) {
                    format = if (format == PacketFormat.A) config.preferredFormat else PacketFormat.A
                    formatSince = now
                    lastHeartbeatAt = Long.MIN_VALUE / 2
                }
                if (packetsSinceHeartbeat >= config.heartbeatEveryPackets ||
                    now - lastHeartbeatAt >= config.heartbeatIntervalMs
                ) {
                    sendHeartbeat(sock, target, format)
                    lastHeartbeatAt = now
                    packetsSinceHeartbeat = 0
                }

                val datagram = DatagramPacket(buffer, buffer.size)
                try {
                    sock.receive(datagram)
                } catch (e: SocketTimeoutException) {
                    if (lastPacketAt > 0) {
                        val silence = clock() - lastPacketAt
                        if (silence >= config.noDataAfterMs) {
                            _status.value = ConnectionStatus.NoData(target.hostAddress ?: "?", silence)
                        }
                    }
                    continue
                }

                val packet = TelemetryParser.parseEncrypted(buffer, datagram.length)
                if (packet == null) {
                    invalidPackets++
                    continue
                }
                gotValidPacket = true
                if (!locked) {
                    // Recherche automatique : on adopte l'adresse de la machine qui répond.
                    target = datagram.address
                    locked = true
                }
                packetsSinceHeartbeat++
                lastPacketAt = clock()
                rateCount++
                if (lastPacketAt - rateWindowStart >= 1_000) {
                    currentRate = rateCount
                    rateCount = 0
                    rateWindowStart = lastPacketAt
                }
                _status.value = ConnectionStatus.Receiving(target.hostAddress ?: "?", currentRate, packet.format)
                _packets.emit(packet)
            }
        } catch (e: SocketException) {
            // Fermeture volontaire du socket via stop() : pas une erreur.
            if (socket === sock) _status.value = ConnectionStatus.Error("Réseau : ${e.message}")
        } finally {
            sock.close()
        }
    }

    private fun sendHeartbeat(sock: DatagramSocket, target: InetAddress, format: PacketFormat) {
        try {
            val data = byteArrayOf(format.heartbeat)
            sock.send(DatagramPacket(data, data.size, target, config.ps5Port))
        } catch (e: Exception) {
            _status.value = ConnectionStatus.Error("Envoi du heartbeat impossible : ${e.message}")
        }
    }
}
