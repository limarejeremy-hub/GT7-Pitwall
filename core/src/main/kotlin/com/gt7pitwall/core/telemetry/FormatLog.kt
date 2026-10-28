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
        val unanswered: List<PacketFormat> = emptyList(),
    )

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
                open(Segment(nowMs, nowMs, s.requested, s.format, profile, unanswered = s.unanswered))
                log(nowMs, "reçu ${label(s.format)} (heartbeat demandé : ${label(s.requested)})" +
                    (if (s.unanswered.isNotEmpty()) ", sans réponse : ${s.unanswered.joinToString { label(it) }}" else "") + ", profil : $profile")
            }
            is ConnectionStatus.NoData -> log(nowMs, "plus de paquet depuis ${s.silenceMs} ms")
            is ConnectionStatus.Searching -> log(nowMs, "recherche de la PS5")
            is ConnectionStatus.Error -> log(nowMs, "erreur : ${s.message.replace(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"), "[adresse masquée]")}")
            ConnectionStatus.Stopped -> log(nowMs, "télémétrie arrêtée")
        }
    }

    /** Un paquet lisible : compté dans la période en cours. */
    @Synchronized
    fun onPacket(nowMs: Long) {
        segments.lastOrNull()?.let { it.packets++; it.endMs = nowMs }
    }

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
            val unanswered = segs.flatMap { it.unanswered }.distinct()
            "$profile — ${byFormat.joinToString(" ; ")} (demandé : $requested" +
                (if (unanswered.isNotEmpty()) ", sans réponse : ${unanswered.joinToString { label(it) }}" else "") + ")"
        }
    }

    @Synchronized
    fun export(startMs: Long = events.firstOrNull()?.timeMs ?: 0): String = buildString {
        appendLine("# Pitwall — journal des formats de télémétrie (passif). Profil = choix dans Pitwall, pas une détection du mode.")
        summary().forEach { appendLine("# $it") }
        appendLine("t_ms;evenement")
        events.forEach { appendLine("${it.timeMs - startMs};${it.text.replace(';', ',')}") }
    }
}
