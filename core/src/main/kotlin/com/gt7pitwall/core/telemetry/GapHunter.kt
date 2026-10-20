package com.gt7pitwall.core.telemetry

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.exp

/**
 * Gap Hunter : exploration silencieuse des octets de la télémétrie GT7, à la recherche d'un champ qui porterait un
 * écart avec les autres voitures. Rien n'est supposé : à ce jour aucune source publique ne documente un tel champ.
 *
 * Le module observe chaque paquet déchiffré, mot de 4 octets par mot de 4 octets :
 *  - nombre de changements, valeurs extrêmes (entier et flottant), forme (constant, compteur, flottant plausible…) ;
 *  - changements proches d'un changement de position (0x84) ou de tour : une **corrélation**, jamais une preuve ;
 *  - journal borné : un échantillon par seconde des mots inconnus ou incertains, et une ligne à chaque événement.
 *
 * Il ne transmet rien à la stratégie ni à la radio, ne note ni adresse IP ni identifiant, et ne coûte rien tant qu'il
 * est désactivé (le client UDP ne lui passe les paquets que s'il est branché).
 */
class GapHunter(
    private val maxLines: Int = 1_500,
    private val sampleEveryMs: Long = 1_000,
    private val eventWindowMs: Long = 1_000,
) {
    enum class Status(val label: String) { KNOWN("connu"), UNCERTAIN("incertain"), UNKNOWN("inconnu") }

    data class Field(val offset: Int, val label: String, val status: Status)

    enum class Shape(val label: String) {
        NONE("pas encore vu"),
        CONSTANT("constant"),
        COUNTER("compteur croissant"),
        FLOAT("flottant plausible"),
        INTEGER("entier ou bits"),
    }

    /** Types d'événements synchronisés avec les mots observés. */
    enum class EventKind(val label: String) { POSITION("changement de 0x84"), LAP("changement de tour") }

    data class WordStats(
        val field: Field,
        val shape: Shape,
        val samples: Long,
        val changes: Long,
        /** Valeurs distinctes vues, plafonnées à [DISTINCT_CAP] (au-delà : « beaucoup »). */
        val distinct: Int,
        val lastHex: String,
        val minFloat: Float?,
        val maxFloat: Float?,
        val minInt: Int,
        val maxInt: Int,
    )

    /**
     * Mot inconnu ou incertain qui change presque toujours autour d'un événement, alors que ses changements sont rares
     * le reste du temps. Ce n'est qu'une piste à vérifier sur PS5.
     */
    data class Candidate(
        val field: Field,
        val kind: EventKind,
        val eventsWithChange: Int,
        val events: Int,
        /** Probabilité de changer par hasard dans la même fenêtre, d'après le rythme habituel du mot. */
        val chance: Double,
    )

    data class Summary(
        val enabled: Boolean,
        val frames: Long,
        val invalid: Long,
        val bySize: Map<Int, Long>,
        val invalidBySize: Map<Int, Long>,
        val events: Map<EventKind, Int>,
        val candidates: List<Candidate>,
        val lines: Int,
        val dropped: Int,
        val recent: List<String>,
    )

    @Volatile
    var enabled: Boolean = false
        private set

    private val words = (MAX_SIZE / 4)
    private val has = BooleanArray(words)
    private val last = IntArray(words)
    private val samples = LongArray(words)
    private val changes = LongArray(words)
    private val ups = LongArray(words)
    private val floatOk = LongArray(words)
    private val minF = FloatArray(words) { Float.POSITIVE_INFINITY }
    private val maxF = FloatArray(words) { Float.NEGATIVE_INFINITY }
    private val minI = IntArray(words) { Int.MAX_VALUE }
    private val maxI = IntArray(words) { Int.MIN_VALUE }
    private val lastChangeAt = LongArray(words) { Long.MIN_VALUE / 2 }
    private val distinct = Array(words) { HashSet<Int>() }

    private class Pending(val kind: EventKind, val atMs: Long, val hits: BooleanArray)

    private val pending = ArrayDeque<Pending>()
    private val eventCount = IntArray(EventKind.entries.size)
    private val eventHits = Array(EventKind.entries.size) { IntArray(words) }

    private var frames = 0L
    private var invalid = 0L
    private val bySize = sortedMapOf<Int, Long>()
    private val invalidBySize = sortedMapOf<Int, Long>()
    private var firstAt = -1L
    private var firstFrameAt = -1L
    private var lastFrameAt = 0L
    private var lastSampleAt = Long.MIN_VALUE / 2
    private var last84: Int? = null
    private var lastLap: Int? = null
    private var lastState: String? = null
    private var lastSize = 0

    private val lines = ArrayDeque<String>()
    private var dropped = 0
    private val clockFmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private var zone: ZoneId = ZoneId.systemDefault()

    @Synchronized
    fun setEnabled(on: Boolean) {
        enabled = on
    }

    /** Paquet reçu : [plain] déchiffré, ou `null` s'il est illisible (seule sa taille est comptée). */
    @Synchronized
    fun onFrame(plain: ByteArray?, size: Int, nowMs: Long) {
        if (!enabled) return
        if (firstAt < 0) firstAt = nowMs
        if (plain == null) {
            invalid++
            invalidBySize.merge(size, 1L, Long::plus)
            return
        }
        frames++
        bySize.merge(plain.size, 1L, Long::plus)
        if (firstFrameAt < 0) firstFrameAt = nowMs
        lastFrameAt = nowMs
        lastSize = plain.size
        val b = ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN)
        val n = minOf(plain.size / 4, words)
        for (w in 0 until n) observe(w, b.getInt(w * 4), nowMs)
        settle(nowMs)

        val raw84 = b.getShort(0x84).toInt()
        val raw86 = b.getShort(0x86).toInt()
        val lap = b.getShort(0x74).toInt()
        val flags = b.getShort(0x8E).toInt() and 0xFFFF
        val state = state(flags, lap, b.getShort(0x76).toInt())
        val reasons = mutableListOf<String>()
        val p84 = last84
        if (p84 != null && p84 != raw84) {
            reasons += "0x84 $p84 → $raw84"
            // Seuls les changements de place en course comptent pour la corrélation (pas les chargements, la grille).
            if (state == STATE_RACE && p84 > 0 && raw84 > 0) event(EventKind.POSITION, nowMs)
        }
        val pLap = lastLap
        if (pLap != null && pLap != lap) {
            reasons += "tour $pLap → $lap"
            if (state == STATE_RACE || pLap > 0) event(EventKind.LAP, nowMs)
        }
        if (lastState != null && lastState != state) reasons += "état : $state"
        if (lastState == null) reasons += "premier paquet (${plain.size} octets)"
        last84 = raw84
        lastLap = lap
        lastState = state

        if (reasons.isNotEmpty()) log(nowMs, lap, raw84, raw86, state, reasons.joinToString(", "), b)
        else if (nowMs - lastSampleAt >= sampleEveryMs) log(nowMs, lap, raw84, raw86, state, "échantillon", b)
    }

    /** Repère posé par le pilote (dépassement, écart lu sur le HUD…), pour comparer avec le journal. */
    @Synchronized
    fun mark(label: String, nowMs: Long) {
        if (firstAt < 0) firstAt = nowMs
        addLine("${stamp(nowMs)};${lastLap ?: ""};${last84 ?: ""};;${lastState ?: "aucun paquet"};repère : ${label.take(60)};;;;;;")
    }

    @Synchronized
    fun reset() {
        has.fill(false); samples.fill(0); changes.fill(0); ups.fill(0); floatOk.fill(0)
        minF.fill(Float.POSITIVE_INFINITY); maxF.fill(Float.NEGATIVE_INFINITY)
        minI.fill(Int.MAX_VALUE); maxI.fill(Int.MIN_VALUE)
        lastChangeAt.fill(Long.MIN_VALUE / 2)
        distinct.forEach { it.clear() }
        pending.clear(); eventCount.fill(0); eventHits.forEach { it.fill(0) }
        frames = 0; invalid = 0; bySize.clear(); invalidBySize.clear()
        firstAt = -1; firstFrameAt = -1; lastFrameAt = 0; lastSampleAt = Long.MIN_VALUE / 2
        last84 = null; lastLap = null; lastState = null; lastSize = 0
        lines.clear(); dropped = 0
    }

    @Synchronized
    fun stats(): List<WordStats> {
        val n = minOf(lastSize / 4, words)
        return (0 until n).map { w ->
            WordStats(
                field = fieldAt(w * 4),
                shape = shape(w),
                samples = samples[w],
                changes = changes[w],
                distinct = distinct[w].size,
                lastHex = hex(last[w]),
                minFloat = minF[w].takeIf { floatOk[w] > 0 },
                maxFloat = maxF[w].takeIf { floatOk[w] > 0 },
                minInt = minI[w],
                maxInt = maxI[w],
            )
        }
    }

    @Synchronized
    fun candidates(): List<Candidate> {
        val duration = (lastFrameAt - firstFrameAt).coerceAtLeast(1)
        val out = mutableListOf<Candidate>()
        for (kind in EventKind.entries) {
            val events = eventCount[kind.ordinal]
            if (events < MIN_EVENTS) continue
            for (w in 0 until words) {
                val f = fieldAt(w * 4)
                if (f.status == Status.KNOWN || !has[w]) continue
                val hits = eventHits[kind.ordinal][w]
                val rate = changes[w].toDouble() / duration
                val chance = 1 - exp(-rate * 2 * eventWindowMs)
                if (hits >= events * 0.8 && chance <= 0.3) out += Candidate(f, kind, hits, events, chance)
            }
        }
        return out
    }

    @Synchronized
    fun summary(recent: Int = 8): Summary = Summary(
        enabled, frames, invalid, bySize.toMap(), invalidBySize.toMap(),
        EventKind.entries.associateWith { eventCount[it.ordinal] }, candidates(), lines.size, dropped, lines.toList().takeLast(recent),
    )

    /** Export texte pour une analyse ultérieure (aucune adresse IP, aucun identifiant, aucun son). */
    @Synchronized
    fun export(): String = buildString {
        appendLine("GT7 Pitwall — Gap Hunter (diagnostic expérimental)")
        appendLine("Données réelles de la télémétrie reçue. Une corrélation n'est pas une preuve : aucun écart n'en est déduit.")
        appendLine("Paquets lisibles : $frames ; illisibles : $invalid")
        appendLine("Tailles lisibles : ${sizes(bySize)}")
        if (invalidBySize.isNotEmpty()) appendLine("Tailles illisibles : ${sizes(invalidBySize)}")
        appendLine("Événements : " + EventKind.entries.joinToString(", ") { "${it.label} ${eventCount[it.ordinal]}" })
        appendLine()
        appendLine("Pistes (mots inconnus ou incertains qui changent autour des événements) :")
        val cands = candidates()
        if (cands.isEmpty()) appendLine("  aucune (il faut au moins $MIN_EVENTS événements du même type)")
        for (c in cands) {
            appendLine("  0x%03X %s — %s : %d/%d événements, hasard %.0f %%".format(
                c.field.offset, c.field.label, c.kind.label, c.eventsWithChange, c.events, c.chance * 100,
            ))
        }
        appendLine()
        appendLine("offset;statut;champ;forme;échantillons;changements;distincts;min entier;max entier;min flottant;max flottant;dernier (hex)")
        for (s in stats()) {
            val d = if (s.distinct > DISTINCT_CAP) ">$DISTINCT_CAP" else s.distinct.toString()
            appendLine(
                "0x%03X;%s;%s;%s;%d;%d;%s;%d;%d;%s;%s;%s".format(
                    s.field.offset, s.field.status.label, s.field.label, s.shape.label, s.samples, s.changes, d,
                    s.minInt, s.maxInt, s.minFloat?.let { "%.4g".format(it) } ?: "", s.maxFloat?.let { "%.4g".format(it) } ?: "", s.lastHex,
                ),
            )
        }
        appendLine()
        if (dropped > 0) appendLine("Lignes anciennes retirées : $dropped")
        appendLine("heure;tour;0x84;0x86;état;motif;X;Z;km/h;carburant;chrono C (ms);mots inconnus et incertains (offset=hex)")
        lines.forEach { appendLine(it) }
    }

    private fun observe(w: Int, v: Int, nowMs: Long) {
        samples[w]++
        if (v < minI[w]) minI[w] = v
        if (v > maxI[w]) maxI[w] = v
        val f = java.lang.Float.intBitsToFloat(v)
        if (f.isFinite() && kotlin.math.abs(f) < 1e7f && (f == 0f || kotlin.math.abs(f) > 1e-7f)) {
            floatOk[w]++
            if (f < minF[w]) minF[w] = f
            if (f > maxF[w]) maxF[w] = f
        }
        val set = distinct[w]
        if (set.size <= DISTINCT_CAP) set += v
        if (!has[w]) {
            has[w] = true
            last[w] = v
            return
        }
        if (v == last[w]) return
        changes[w]++
        if (v > last[w]) ups[w]++
        last[w] = v
        lastChangeAt[w] = nowMs
        for (p in pending) if (nowMs - p.atMs <= eventWindowMs) p.hits[w] = true
    }

    private fun event(kind: EventKind, nowMs: Long) {
        val hits = BooleanArray(words) { nowMs - lastChangeAt[it] <= eventWindowMs }
        pending.addLast(Pending(kind, nowMs, hits))
    }

    /** Clôt les événements dont la fenêtre « après » est passée. */
    private fun settle(nowMs: Long) {
        while (pending.isNotEmpty() && nowMs - pending.first().atMs > eventWindowMs) {
            val p = pending.removeFirst()
            eventCount[p.kind.ordinal]++
            val row = eventHits[p.kind.ordinal]
            for (w in 0 until words) if (p.hits[w]) row[w]++
        }
    }

    private fun shape(w: Int): Shape = when {
        samples[w] == 0L -> Shape.NONE
        changes[w] == 0L -> Shape.CONSTANT
        changes[w] >= 10 && ups[w] >= changes[w] * 0.95 -> Shape.COUNTER
        floatOk[w] >= samples[w] * 0.95 -> Shape.FLOAT
        else -> Shape.INTEGER
    }

    private fun log(nowMs: Long, lap: Int, raw84: Int, raw86: Int, state: String, reason: String, b: ByteBuffer) {
        lastSampleAt = nowMs
        val n = minOf(b.capacity() / 4, words)
        val unknown = (0 until n).filter { fieldAt(it * 4).status != Status.KNOWN }
            .joinToString(" ") { "%03X=%s".format(it * 4, hex(b.getInt(it * 4))) }
        val chrono = if (b.capacity() >= PacketFormat.C.size) b.getInt(0x15C).toString() else ""
        val where = "%.1f;%.1f;%.1f;%.1f;%s".format(b.getFloat(0x04), b.getFloat(0x0C), b.getFloat(0x4C) * 3.6f, b.getFloat(0x44), chrono)
        addLine("${stamp(nowMs)};$lap;$raw84;$raw86;$state;$reason;$where;$unknown")
    }

    private fun addLine(line: String) {
        lines.addLast(line)
        while (lines.size > maxLines) {
            lines.removeFirst()
            dropped++
        }
    }

    private fun stamp(nowMs: Long) = clockFmt.format(Instant.ofEpochMilli(nowMs).atZone(zone))

    private fun sizes(m: Map<Int, Long>) = m.entries.joinToString(", ") { "${it.key} o × ${it.value}" }

    companion object {
        /** Taille maximale observée : au-delà du format C (0x170), tout octet serait nouveau. */
        const val MAX_SIZE = GT7Crypto.MAX_LENGTH
        const val DISTINCT_CAP = 32
        const val MIN_EVENTS = 3
        private const val STATE_RACE = "en course"

        private fun hex(v: Int) = "%08X".format(v)

        /**
         * Le drapeau « en piste » (bit 0) n'est pas utilisé : des captures réelles montrent une voiture lancée sans lui
         * (vwhitteron/gt-telemetry, flags 0x0008 ; denwilliams, 0x0418 en roulant).
         */
        private fun state(flags: Int, lap: Int, totalLaps: Int): String = when {
            flags and TelemetryPacket.FLAG_LOADING != 0 -> "chargement"
            flags and TelemetryPacket.FLAG_PAUSED != 0 -> "pause"
            lap == 0 -> "avant départ"
            totalLaps > 0 && lap > totalLaps -> "arrivée"
            else -> STATE_RACE
        }

        private fun k(o: Int, l: String) = Field(o, l, Status.KNOWN)
        private fun u(o: Int, l: String) = Field(o, l, Status.UNCERTAIN)
        private fun x(o: Int, l: String) = Field(o, l, Status.UNKNOWN)

        /**
         * Carte des mots de 4 octets. Sources : PDTools (SimulatorPacket.cs), MacManley/gt7-udp (GT7UDPParser.h),
         * gt7-datalogger (issue 150) et nos essais PS5 pour 0x84.
         */
        val FIELDS: Map<Int, Field> = buildList {
            add(k(0x00, "signature"))
            listOf("position X", "position Y", "position Z").forEachIndexed { i, l -> add(k(0x04 + 4 * i, l)) }
            listOf("vitesse X", "vitesse Y", "vitesse Z").forEachIndexed { i, l -> add(k(0x10 + 4 * i, l)) }
            listOf("rotation X", "rotation Y", "rotation Z", "rotation W").forEachIndexed { i, l -> add(k(0x1C + 4 * i, l)) }
            listOf("vitesse angulaire X", "Y", "Z").forEachIndexed { i, l -> add(k(0x2C + 4 * i, l)) }
            add(k(0x38, "hauteur de caisse")); add(k(0x3C, "régime moteur")); add(k(0x40, "nonce (IV)"))
            add(k(0x44, "carburant")); add(k(0x48, "capacité")); add(k(0x4C, "vitesse")); add(k(0x50, "turbo"))
            add(k(0x54, "pression d'huile")); add(k(0x58, "eau (toujours 85)")); add(k(0x5C, "huile (toujours 110)"))
            listOf("AVG", "AVD", "ARG", "ARD").forEachIndexed { i, l -> add(k(0x60 + 4 * i, "température pneu $l")) }
            add(k(0x70, "numéro de paquet")); add(k(0x74, "tour / tours")); add(k(0x78, "meilleur tour"))
            add(k(0x7C, "dernier tour")); add(k(0x80, "heure en piste")); add(k(0x84, "0x84 position / 0x86 voitures"))
            add(k(0x88, "alertes régime")); add(k(0x8C, "vitesse max / drapeaux"))
            add(u(0x90, "rapport, pédales, octet 0x93 inconnu"))
            add(u(0x94, "plan de la route X")); add(u(0x98, "plan de la route Y")); add(u(0x9C, "plan de la route Z"))
            add(u(0xA0, "distance au plan de la route"))
            listOf("AVG", "AVD", "ARG", "ARD").forEachIndexed { i, l -> add(k(0xA4 + 4 * i, "rotation roue $l")) }
            listOf("AVG", "AVD", "ARG", "ARD").forEachIndexed { i, l -> add(k(0xB4 + 4 * i, "rayon pneu $l")) }
            listOf("AVG", "AVD", "ARG", "ARD").forEachIndexed { i, l -> add(k(0xC4 + 4 * i, "suspension $l")) }
            for (i in 0 until 8) add(x(0xD4 + 4 * i, "réservé ${i + 1}/8 (PDTools : non rempli)"))
            add(k(0xF4, "embrayage")); add(k(0xF8, "engagement embrayage")); add(k(0xFC, "régime embrayage → boîte"))
            add(k(0x100, "vitesse max de boîte"))
            for (i in 0 until 7) add(k(0x104 + 4 * i, "rapport ${i + 1}"))
            add(u(0x120, "8e rapport ou inconnu")); add(k(0x124, "code voiture"))
            add(k(0x128, "volant (B)")); add(x(0x12C, "inconnu (B)"))
            add(k(0x130, "roulis (B)")); add(k(0x134, "pompage (B)")); add(k(0x138, "cavalement (B)"))
            add(u(0x13C, "pédales filtrées, motorisation, drapeau énergie (~)"))
            listOf("AVG", "AVD", "ARG", "ARD").forEachIndexed { i, l -> add(u(0x140 + 4 * i, "couple roue $l (~)")) }
            add(k(0x150, "récupération d'énergie (~)")); add(u(0x154, "couple moteur normalisé ? (~)"))
            add(k(0x158, "surfaces sous les pneus (C)")); add(k(0x15C, "chrono du tour en cours (C)"))
            add(k(0x160, "angle roue AVG (C)")); add(k(0x164, "angle roue AVD (C)"))
            add(k(0x168, "empattement (C)")); add(k(0x16C, "catégorie (C)"))
        }.associateBy { it.offset }

        fun fieldAt(offset: Int): Field = FIELDS[offset]
            ?: Field(offset, if (offset >= PacketFormat.C.size) "au-delà du format C" else "non répertorié", Status.UNKNOWN)
    }
}
