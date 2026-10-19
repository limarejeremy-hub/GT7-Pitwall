package com.gt7pitwall.core.telemetry

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Diagnostic des champs 0x84 et 0x86 du paquet GT7 (documentés comme position et nombre de voitures sur la grille,
 * -1 une fois la course lancée). Certaines sources signalent une position qui évolue dans certaines épreuves : ce
 * journal sert à le vérifier en vrai, sans rien supposer. Il ne fait qu'observer : aucune valeur n'est transmise à la
 * stratégie ni annoncée à la radio.
 *
 * Une ligne est notée à chaque changement de 0x84 ou 0x86, d'état de session ou de tour, et à chaque repère posé par
 * le pilote (dépassement, arrivée, position lue sur le HUD), pour comparer avec ce que GT7 affiche.
 */
class PositionProbe(private val maxEvents: Int = 600) {
    data class Event(
        val timeMs: Long,
        val lap: Int,
        val totalLaps: Int,
        val raw84: Int,
        val raw86: Int,
        val state: String,
        val reason: String,
    )

    /** Répartition des valeurs d'un champ sur tous les paquets reçus. */
    data class Counts(val valid: Long = 0, val minusOne: Long = 0, val other: Long = 0) {
        val total: Long get() = valid + minusOne + other
        fun share(n: Long): Int = if (total == 0L) 0 else Math.round(n * 100.0 / total).toInt()
    }

    data class Summary(
        val packets: Long,
        val raw84: Int?,
        val raw86: Int?,
        val lap: Int?,
        val state: String?,
        val counts84: Counts,
        val counts86: Counts,
        val events: List<Event>,
        val dropped: Int,
    )

    private val events = ArrayDeque<Event>()
    private var dropped = 0
    private var packets = 0L
    private var c84 = Counts()
    private var c86 = Counts()
    private var last: Event? = null

    @Synchronized
    fun onPacket(p: TelemetryPacket, nowMs: Long) {
        packets++
        c84 = count(c84, p.preRacePosition)
        c86 = count(c86, p.preRaceCarCount)
        val state = state(p)
        val prev = last
        val reason = when {
            prev == null -> "premier paquet"
            prev.raw84 != p.preRacePosition || prev.raw86 != p.preRaceCarCount -> "changement 0x84/0x86"
            prev.state != state -> "état : $state"
            prev.lap != p.currentLap -> "tour ${p.currentLap}"
            else -> null
        }
        val e = Event(nowMs, p.currentLap, p.totalLaps, p.preRacePosition, p.preRaceCarCount, state, reason ?: "")
        last = e
        if (reason != null) add(e)
    }

    /** Repère posé par le pilote : « dépassement », « arrivée », ou la position lue sur le HUD. */
    @Synchronized
    fun mark(label: String, nowMs: Long) {
        val l = last
        add(Event(nowMs, l?.lap ?: -1, l?.totalLaps ?: -1, l?.raw84 ?: 0, l?.raw86 ?: 0, l?.state ?: "aucun paquet", "repère : $label"))
    }

    @Synchronized
    fun reset() {
        events.clear()
        dropped = 0
        packets = 0
        c84 = Counts()
        c86 = Counts()
        last = null
    }

    @Synchronized
    fun summary(recent: Int = 10): Summary =
        Summary(packets, last?.raw84, last?.raw86, last?.lap, last?.state, c84, c86, events.toList().takeLast(recent), dropped)

    /** Journal texte, à partager puis à comparer avec le HUD (aucune donnée personnelle, aucun son). */
    @Synchronized
    fun export(zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        val fmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(zone)
        appendLine("GT7 Pitwall — diagnostic position UDP (0x84 / 0x86)")
        appendLine("Paquets : $packets")
        appendLine(line("0x84", c84))
        appendLine(line("0x86", c86))
        if (dropped > 0) appendLine("Lignes anciennes retirées : $dropped")
        appendLine()
        appendLine("heure;tour;tours;0x84;0x86;état;motif")
        for (e in events) {
            appendLine("${fmt.format(Instant.ofEpochMilli(e.timeMs))};${e.lap};${e.totalLaps};${e.raw84};${e.raw86};${e.state};${e.reason}")
        }
    }

    private fun line(name: String, c: Counts) =
        "$name : ${c.share(c.valid)} % positifs, ${c.share(c.minusOne)} % à -1, ${c.share(c.other)} % autres (0 ou < -1)"

    private fun count(c: Counts, v: Int) = when {
        v > 0 -> c.copy(valid = c.valid + 1)
        v == -1 -> c.copy(minusOne = c.minusOne + 1)
        else -> c.copy(other = c.other + 1)
    }

    private fun add(e: Event) {
        events.addLast(e)
        while (events.size > maxEvents) {
            events.removeFirst()
            dropped++
        }
    }

    companion object {
        fun state(p: TelemetryPacket): String = when {
            p.isLoading -> "chargement"
            p.isPaused -> "pause"
            !p.isOnTrack -> "hors piste"
            p.currentLap == 0 -> "avant départ"
            p.totalLaps > 0 && p.currentLap > p.totalLaps -> "arrivée"
            else -> "en course"
        }
    }
}
