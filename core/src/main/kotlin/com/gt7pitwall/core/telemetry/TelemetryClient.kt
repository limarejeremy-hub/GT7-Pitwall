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
        /** Diagnostic : paquets reçus mais indéchiffrables, et taille du dernier. */
        val invalidPackets: Long = 0,
        val lastInvalidSize: Int = 0,
        /** Le format préféré (C) a été demandé sans réponse exploitable : un format plus ancien est utilisé. */
        val fullFormatUnanswered: Boolean = false,
        /** Format demandé au moment de la réception. */
        val requested: PacketFormat = format,
        /** Formats demandés restés sans paquet lisible, dans l'ordre d'essai (diagnostic du repli). */
        val unanswered: List<PacketFormat> = emptyList(),
    ) : ConnectionStatus

    /** Des paquets ont été reçus, puis plus rien depuis [silenceMs]. */
    data class NoData(val ps5Address: String, val silenceMs: Long) : ConnectionStatus

    data class Error(val message: String) : ConnectionStatus
}

/**
 * Client UDP de la télémétrie GT7.
 *
 * Envoie régulièrement un heartbeat à la PS5 (port 33739) et écoute les paquets sur le port 33740. Le format le plus
 * complet (C) est demandé d'abord ; sans paquet lisible dans [Config.formatFallbackMs], le suivant de
 * [Config.fallbackFormats] est essayé (~, puis A), et le statut indique les formats restés sans réponse.
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
        val preferredFormat: PacketFormat = PacketFormat.C,
        /** Sans réponse dans ce délai, on essaie le format suivant (anciennes versions de GT7, modes sans C). */
        val formatFallbackMs: Long = 4_000,
        /** Ordre des replis après [preferredFormat]. */
        val fallbackFormats: List<PacketFormat> = listOf(PacketFormat.TILDE, PacketFormat.A),
        /** Sonde des formats : flux considéré arrêté après ce silence. */
        val scanSilenceMs: Long = 2_000,
        /** Sonde : attente maximale de l'arrêt du flux précédent. */
        val scanMaxSilenceMs: Long = 15_000,
        /** Sonde : durée d'écoute de chaque octet de heartbeat. */
        val scanProbeMs: Long = 6_000,
        val scanHeartbeatMs: Long = 1_000,
    ) {
        val formatOrder: List<PacketFormat> get() = (listOf(preferredFormat) + fallbackFormats).distinct()
    }

    /** Réponse de la PS5 à un octet de heartbeat essayé par la sonde. */
    data class ProbeResult(
        val heartbeat: Char,
        val packets: Int,
        /** Taille des paquets reçus → nombre. */
        val sizes: Map<Int, Int>,
        /** Constantes XOR connues qui déchiffrent ces paquets (vide : chiffrement inconnu ou aucun paquet). */
        val keys: List<PacketFormat>,
        /** Le flux précédent ne s'était pas arrêté : la réponse peut lui appartenir. */
        val previousStreamStillOn: Boolean,
    )

    data class FormatScan(
        val running: Boolean = false,
        val current: Char? = null,
        val done: Int = 0,
        val total: Int = 0,
        val results: List<ProbeResult> = emptyList(),
    )

    private val _scan = MutableStateFlow(FormatScan())

    /** Sonde des formats (diagnostic Gap Hunter) : quels octets de heartbeat la PS5 accepte, et avec quelle taille. */
    val scan: StateFlow<FormatScan> = _scan.asStateFlow()

    @Volatile
    private var scanRequest: List<Char>? = null

    @Volatile
    private var scanCancel = false

    /**
     * Essaie chaque octet de [heartbeats] tour à tour : silence jusqu'à l'arrêt du flux, puis heartbeat pendant
     * [Config.scanProbeMs]. La télémétrie normale est interrompue pendant la sonde (à lancer dans les menus) : les
     * paquets reçus ne servent qu'au diagnostic et ne sont publiés nulle part. Ensuite, ou après [cancelFormatScan],
     * la télémétrie reprend avec le format préféré, après l'arrêt du dernier flux sondé. N'utilise que l'interface publique de GT7 (un octet UDP vers le port 33739).
     */
    fun startFormatScan(heartbeats: List<Char> = DEFAULT_SCAN) {
        scanCancel = false
        scanRequest = heartbeats.distinct()
    }

    fun cancelFormatScan() {
        scanCancel = true
    }

    private class ScanRun(val list: List<Char>, start: Long) {
        var index = 0
        var probing = false
        var phaseSince = start
        var lastHeartbeat = Long.MIN_VALUE / 2
        var count = 0
        val sizes = sortedMapOf<Int, Int>()
        val keys = linkedSetOf<PacketFormat>()
        var persisted = false
        val results = mutableListOf<ProbeResult>()
    }

    /** Reçoit chaque paquet déchiffré (ou `null` s'il est illisible) : diagnostic uniquement (Gap Hunter). */
    fun interface RawTap {
        fun onFrame(plain: ByteArray?, size: Int, nowMs: Long)
    }

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

    /** Branché seulement quand un diagnostic l'utilise : sinon aucun coût supplémentaire. */
    @Volatile
    var rawTap: RawTap? = null

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
        // Une sonde en cours est abandonnée avec le client : son état ne doit pas rester « en cours ».
        scanRequest = null
        scanCancel = false
        if (_scan.value.running) _scan.value = _scan.value.copy(running = false, current = null)
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
            val order = config.formatOrder
            var formatIndex = 0
            var format = order[0]
            val unanswered = mutableListOf<PacketFormat>()
            var formatSince = clock()
            var gotValidPacket = false
            var lastInvalidSize = 0
            var fullFormatUnanswered = false
            val buffer = ByteArray(4096)
            var lastHeartbeatAt = Long.MIN_VALUE / 2
            var packetsSinceHeartbeat = 0
            var lastPacketAt = 0L
            var lastDatagramAt = 0L
            var scanRun: ScanRun? = null
            var rateWindowStart = clock()
            var rateCount = 0
            var currentRate = 0

            while (isActive) {
                val now = clock()
                scanRequest?.let { list ->
                    scanRequest = null
                    scanRun = ScanRun(list, now)
                    _scan.value = FormatScan(true, null, 0, list.size)
                }
                val run = scanRun
                if (run != null) {
                    val silent = now - lastDatagramAt >= config.scanSilenceMs && now - run.phaseSince >= config.scanSilenceMs
                    if (!run.probing) {
                        if (scanCancel) run.index = run.list.size
                        if (silent || now - run.phaseSince >= config.scanMaxSilenceMs) {
                            if (run.index >= run.list.size) {
                                // Fin de la sonde : reprise normale, format préféré d'abord.
                                scanRun = null
                                scanCancel = false
                                _scan.value = FormatScan(false, null, run.results.size, run.list.size, run.results.toList())
                                formatIndex = 0
                                format = order[0]
                                formatSince = now
                                gotValidPacket = false
                                fullFormatUnanswered = false
                                unanswered.clear()
                                lastHeartbeatAt = Long.MIN_VALUE / 2
                            } else {
                                run.probing = true
                                run.persisted = !silent
                                run.phaseSince = now
                                run.count = 0
                                run.sizes.clear()
                                run.keys.clear()
                                run.lastHeartbeat = Long.MIN_VALUE / 2
                                _scan.value = FormatScan(true, run.list[run.index], run.index, run.list.size, run.results.toList())
                            }
                        }
                    } else if (now - run.phaseSince >= config.scanProbeMs || scanCancel) {
                        run.results += ProbeResult(run.list[run.index], run.count, run.sizes.toMap(), run.keys.toList(), run.persisted)
                        run.index++
                        run.probing = false
                        run.phaseSince = now
                        _scan.value = FormatScan(true, null, run.index, run.list.size, run.results.toList())
                    } else if (now - run.lastHeartbeat >= config.scanHeartbeatMs) {
                        sendRaw(sock, target, run.list[run.index].code.toByte())
                        run.lastHeartbeat = now
                    }
                }
                if (scanRun == null && !gotValidPacket && now - formatSince >= config.formatFallbackMs) {
                    if (format == config.preferredFormat) fullFormatUnanswered = true
                    if (format !in unanswered) unanswered += format
                    formatIndex = (formatIndex + 1) % order.size
                    format = order[formatIndex]
                    formatSince = now
                    lastHeartbeatAt = Long.MIN_VALUE / 2
                }
                if (scanRun == null && (packetsSinceHeartbeat >= config.heartbeatEveryPackets ||
                    now - lastHeartbeatAt >= config.heartbeatIntervalMs)
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

                lastDatagramAt = clock()
                val probing = scanRun
                if (probing != null) {
                    // Pendant la sonde, les paquets ne servent qu'à son diagnostic : rien n'est publié vers le moteur de
                    // course, la stratégie, la radio ni Gap Hunter, et le statut de connexion n'est pas modifié.
                    if (probing.probing) {
                        probing.count++
                        val seen = (probing.sizes[datagram.length] ?: 0) + 1
                        probing.sizes[datagram.length] = seen
                        if (seen <= 3) probing.keys += GT7Crypto.keysThatDecrypt(buffer, datagram.length)
                    }
                    continue
                }
                val plain = GT7Crypto.decrypt(buffer, datagram.length)
                val packet = plain?.let(TelemetryParser::parseDecrypted)
                rawTap?.let { tap -> runCatching { tap.onFrame(if (packet != null) plain else null, datagram.length, clock()) } }
                if (packet == null) {
                    invalidPackets++
                    lastInvalidSize = datagram.length
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
                _status.value = ConnectionStatus.Receiving(
                    target.hostAddress ?: "?",
                    currentRate,
                    packet.format,
                    invalidPackets,
                    lastInvalidSize,
                    fullFormatUnanswered && packet.format != config.preferredFormat,
                    format,
                    unanswered.toList(),
                )
                _packets.emit(packet)
            }
        } catch (e: SocketException) {
            // Fermeture volontaire du socket via stop() : pas une erreur.
            if (socket === sock) _status.value = ConnectionStatus.Error("Réseau : ${e.message}")
        } finally {
            sock.close()
        }
    }

    private fun sendHeartbeat(sock: DatagramSocket, target: InetAddress, format: PacketFormat) = sendRaw(sock, target, format.heartbeat)

    private fun sendRaw(sock: DatagramSocket, target: InetAddress, heartbeat: Byte) {
        try {
            val data = byteArrayOf(heartbeat)
            sock.send(DatagramPacket(data, data.size, target, config.ps5Port))
        } catch (e: Exception) {
            _status.value = ConnectionStatus.Error("Envoi du heartbeat impossible : ${e.message}")
        }
    }

    companion object {
        /** Lettres majuscules et '~' : A, B, ~ et C servent de témoins connus. */
        val DEFAULT_SCAN: List<Char> = ('A'..'Z').toList() + '~'
    }
}
