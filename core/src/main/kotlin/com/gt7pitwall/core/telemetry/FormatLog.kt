package com.gt7pitwall.core.telemetry

/**
 * Journal **passif et automatique** des formats de télémétrie (revue PR #37, P0-1). Question à trancher sur PS5 : quels
 * formats GT7 envoie-t-il en course hors ligne, en qualification Sport et en course Sport ? Le README de gt7-udp dit
 * seulement du format B (316 octets) « not available in Sport Mode » ; il ne dit rien de ~ ni de C. Rien ne permet donc
 * de conclure « Sport = format A seul ».
 *
 * Ce journal lit les statuts que le client publie déjà (format demandé par le heartbeat, format reçu, formats restés sans
 * réponse, silences, erreurs) : il ne change rien au protocole et n'interrompt pas l'analyse (contrairement à la sonde des
 * formats de Gap Hunter, qui suspend le traitement pendant son balayage). Aucune manipulation du Fold : il tourne seul,
 * et se lit après la session. Aucune adresse réseau n'est gardée.
 *
 * V508 : trois choses distinctes par période — le DERNIER heartbeat envoyé, le format REÇU, et le statut de la réponse.
 * Un format reçu n'est plus jamais affiché « sans réponse » (capture Sport : A puis 90 482 paquets C, et pourtant
 * « sans réponse : C »). Chaque période garde les numéros GT7 (0x70) de son premier et dernier paquet, la même clé que
 * le diagnostic de position : un changement de 0x84 se rattache ainsi au format reçu à ce moment-là.
 */
class FormatLog(private val maxSegments: Int = 200) {
    /** Une période continue avec le même format demandé et reçu, dans le même profil d'épreuve. */
    data class Segment(
        val startMs: Long,
        var endMs: Long,
        val requested: PacketFormat?,
        val received: PacketFormat?,
        val profile: String,
        var packets: Long = 0,
        /** Formats que le client a demandés sans paquet lisible pendant sa négociation (statut brut du client). */
        val unanswered: List<PacketFormat> = emptyList(),
        var firstPacketId: Int? = null,
        var lastPacketId: Int? = null,
    ) {
        /** Statut de la réponse au heartbeat. */
        val response: Response
            get() = when {
                received == null -> Response.NONE
                received == requested -> Response.MATCHES_HEARTBEAT
                received in unanswered -> Response.EARLIER_HEARTBEAT
                else -> Response.OTHER_FORMAT
            }

        /** Formats restés sans réponse, hors celui qui arrive dans cette période. */
        val stillUnanswered: List<PacketFormat> get() = unanswered.filter { it != received }
    }

    enum class Response(val text: String) {
        NONE("aucun paquet"),
        MATCHES_HEARTBEAT("répond au dernier heartbeat"),
        EARLIER_HEARTBEAT("format demandé plus tôt (pas le dernier heartbeat envoyé)"),
        OTHER_FORMAT("format différent du heartbeat"),
    }

    data class Event(val timeMs: Long, val text: String)

    private val segments = ArrayList<Segment>()
    private val events = ArrayList<Event>()
    private var lastStatus: String? = null

    /** Statut de connexion publié par le client ; [profile] : profil d'épreuve choisi dans Pitwall (indicatif). */
    @Synchronized
    fun onStatus(s: ConnectionStatus, profile: String, nowMs: Long) {
        val key = when (s) {
            is ConnectionStatus.Receiving -> "R:${s.requested}:${s.format}:${s.unanswered}:$profile"
            is ConnectionStatus.NoData -> "N"
            is ConnectionStatus.Searching -> "S"
            is ConnectionStatus.Error -> "E:${s.message.take(40)}"
            ConnectionStatus.Stopped -> "X"
        }
        if (key == lastStatus) {
            segments.lastOrNull()?.endMs = nowMs
            return
        }
        lastStatus = key
        when (s) {
            is ConnectionStatus.Receiving -> {
                val seg = Segment(nowMs, nowMs, s.requested, s.format, profile, unanswered = s.unanswered)
                open(seg)
                log(nowMs, "dernier heartbeat envoyé : ${label(s.requested)} ; reçu : ${label(s.format)} ; réponse : ${seg.response.text}" +
                    (if (seg.stillUnanswered.isNotEmpty()) " ; sans réponse : ${seg.stillUnanswered.joinToString { label(it) }}" else "") +
                    " ; profil : $profile")
            }
            is ConnectionStatus.NoData -> log(nowMs, "plus de paquet depuis ${s.silenceMs} ms")
            is ConnectionStatus.Searching -> log(nowMs, "recherche de la PS5")
            is ConnectionStatus.Error -> log(nowMs, "erreur : ${s.message.replace(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"), "[adresse masquée]")}")
            ConnectionStatus.Stopped -> log(nowMs, "télémétrie arrêtée")
        }
    }

    /** Un paquet lisible : compté dans la période en cours ; [packetId] = numéro GT7 (0x70), clé commune des diagnostics. */
    @Synchronized
    fun onPacket(nowMs: Long, packetId: Int? = null) {
        segments.lastOrNull()?.let {
            it.packets++
            it.endMs = nowMs
            if (packetId != null) {
                if (it.firstPacketId == null) it.firstPacketId = packetId
                it.lastPacketId = packetId
            }
        }
    }

    /** Format reçu au moment d'un événement : la période qui contient ce numéro de paquet GT7, ou cette heure. */
    @Synchronized
    fun receivedAt(packetId: Int? = null, timeMs: Long? = null): PacketFormat? = segments.lastOrNull { seg ->
        val f = seg.firstPacketId
        val l = seg.lastPacketId
        when {
            packetId != null && f != null && l != null -> packetId in f..l
            timeMs != null -> timeMs in seg.startMs..seg.endMs
            else -> false
        }
    }?.received

    private fun open(seg: Segment) {
        segments += seg
        if (segments.size > maxSegments) segments.removeAt(0)
    }

    private fun log(t: Long, text: String) {
        events += Event(t, text)
        if (events.size > maxSegments * 2) events.removeAt(0)
    }

    private fun label(f: PacketFormat?) = f?.let { "${it.label} (${it.size} octets)" } ?: "?"

    /** Par profil d'épreuve : formats reçus et durée. Le mode réel (Sport ou non) reste celui que le pilote indique. */
    @Synchronized
    fun summary(): List<String> {
        if (segments.isEmpty()) return listOf("Aucun paquet reçu depuis le lancement.")
        return segments.groupBy { it.profile }.map { (profile, segs) ->
            val byFormat = segs.groupBy { it.received }.map { (f, l) ->
                "${label(f)} : ${l.sumOf { it.packets }} paquets, ${l.sumOf { it.endMs - it.startMs } / 1000} s"
            }
            val requested = segs.mapNotNull { it.requested }.distinct().joinToString { label(it) }
            val received = segs.filter { it.packets > 0 || it.received != null }.mapNotNull { it.received }.toSet()
            // Un format reçu dans ce profil n'est jamais « sans réponse », même si le client l'a noté pendant le repli.
            val unanswered = segs.flatMap { it.unanswered }.distinct().filter { it !in received }
            val late = segs.filter { it.response == Response.EARLIER_HEARTBEAT }.mapNotNull { it.received }.distinct()
            "$profile — ${byFormat.joinToString(" ; ")} (heartbeat envoyé : $requested" +
                (if (unanswered.isNotEmpty()) ", sans réponse : ${unanswered.joinToString { label(it) }}" else "") +
                (if (late.isNotEmpty()) ", reçu sans être le dernier heartbeat : ${late.joinToString { label(it) }}" else "") + ")"
        }
    }

    @Synchronized
    fun export(startMs: Long = events.firstOrNull()?.timeMs ?: 0): String = buildString {
        appendLine("# Pitwall — journal des formats de télémétrie (passif). Profil = choix dans Pitwall, pas une détection du mode.")
        summary().forEach { appendLine("# $it") }
        appendLine("t_ms;evenement")
        events.forEach { appendLine("${it.timeMs - startMs};${it.text.replace(';', ',')}") }
        appendLine("# Périodes : debut_ms;fin_ms;heartbeat_envoye;recu;reponse;paquets;premier_paquet;dernier_paquet")
        segments.forEach {
            appendLine("${it.startMs - startMs};${it.endMs - startMs};${it.requested?.label ?: "?"};${it.received?.label ?: "?"};" +
                "${it.response.name};${it.packets};${it.firstPacketId ?: ""};${it.lastPacketId ?: ""}")
        }
    }
}
