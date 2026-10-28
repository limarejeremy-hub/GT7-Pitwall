package com.gt7pitwall.core.telemetry

import kotlin.math.roundToInt

/**
 * Sonde « touche radio DualSense » (issue #38, phase 1). Le téléphone ne lit pas la manette appairée à la PS5 : la seule
 * trace possible d'un appui est son **effet** dans la télémétrie, par exemple le bit « pleins phares » de 0x8E si la touche
 * d'appel de phares est utilisée. Cette sonde observe seulement : elle ne déclenche **jamais** la radio, ne dit rien, et
 * n'enregistre aucun son. Désactivée par défaut.
 *
 * Elle note, pour chaque bit suivi :
 * - l'état au premier paquet (feux déjà allumés au départ) ;
 * - chaque transition, horodatée, avec l'état de la session (pause, chargement, en piste), le format du paquet et la
 *   vitesse ;
 * - la durée de chaque épisode « allumé », classée en impulsion (1 à 3 paquets), appui bref (< 0,5 s), appui maintenu
 *   (0,5 à 2 s), long (> 2 s) ou permanent (toujours allumé) ;
 * - les coupures du flux (plus de [GAP_MS] sans paquet) : un épisode qui les traverse n'est pas mesurable ;
 * - les changements de voiture.
 *
 * Le pilote pose des **repères** (« j'appuie maintenant ») depuis l'Atelier, voiture à l'arrêt, pour relier un appui à une
 * transition. Sans repère, une transition n'est jamais attribuée à une touche.
 */
class ControllerRadioProbe(private val maxEvents: Int = 2_000) {
    /** Bits de 0x8E suivis : phares (7, 8, 9), frein à main (6), bits non documentés (12 à 15). */
    enum class Bit(val mask: Int, val label: String) {
        LIGHTS(TelemetryPacket.FLAG_LIGHTS, "feux"),
        HIGH_BEAM(TelemetryPacket.FLAG_HIGH_BEAM, "pleins phares"),
        LOW_BEAM(TelemetryPacket.FLAG_LOW_BEAM, "feux de croisement"),
        HANDBRAKE(TelemetryPacket.FLAG_HANDBRAKE, "frein à main"),
        BIT12(1 shl 12, "bit 12 (inconnu)"),
        BIT13(1 shl 13, "bit 13 (inconnu)"),
        BIT14(1 shl 14, "bit 14 (inconnu)"),
        BIT15(1 shl 15, "bit 15 (inconnu)"),
    }

    enum class Kind(val label: String) {
        PULSE("impulsion (1 à 3 paquets)"),
        SHORT("appui bref (< 0,5 s)"),
        HOLD("appui maintenu (0,5 à 2 s)"),
        LONG("long (> 2 s)"),
        PERMANENT("permanent (allumé jusqu'ici)"),
        UNMEASURABLE("non mesurable (coupure du flux, pause ou chargement pendant l'épisode)"),
    }

    data class Episode(val bit: Bit, val startMs: Long, val endMs: Long?, val packets: Int, val kind: Kind, val nearMark: Boolean)

    /** Une ligne du journal : transition, repère, coupure, changement de voiture. */
    data class Event(val timeMs: Long, val text: String)

    @Volatile
    var enabled: Boolean = false
        private set

    private var firstMs = 0L
    private var lastMs = 0L
    private var lastFlags: Int? = null
    private var lastCar = 0
    private var packets = 0L
    private var gaps = 0
    private var formatCounts = HashMap<PacketFormat, Long>()
    private val intervals = ArrayList<Int>()
    private val initial = LinkedHashMap<Bit, Boolean>()
    private val transitions = HashMap<Bit, Int>()
    private val episodes = ArrayList<Episode>()
    /** Épisode « allumé » en cours : début, paquets, perturbé (coupure, pause, chargement), début inconnu (allumé au départ). */
    private class Open(val start: Long, var packets: Int, var disturbed: Boolean, val unknownStart: Boolean = false)
    private val open = HashMap<Bit, Open>()
    private val marks = ArrayList<Long>()
    private val events = ArrayList<Event>()
    private var session: String = "non précisée"

    @Synchronized
    fun setEnabled(on: Boolean, nowMs: Long) {
        if (on == enabled) return
        enabled = on
        if (on) reset() else closeAll(nowMs, Kind.PERMANENT)
        log(nowMs, if (on) "sonde activée" else "sonde arrêtée")
    }

    /** Mode de session connu ou déclaré (hors ligne, Sport qualification, Sport course…), noté dans le journal. */
    @Synchronized
    fun session(label: String, nowMs: Long) {
        if (!enabled || label == session) return
        session = label
        log(nowMs, "session : $label")
    }

    /** Repère du pilote : « j'appuie maintenant ». */
    @Synchronized
    fun mark(nowMs: Long, label: String = "appui annoncé par le pilote") {
        if (!enabled) return
        marks += nowMs
        log(nowMs, "repère : $label")
    }

    @Synchronized
    fun onPacket(p: TelemetryPacket, nowMs: Long) {
        if (!enabled) return
        if (packets == 0L) firstMs = nowMs
        val gap = lastMs > 0 && nowMs - lastMs > GAP_MS
        if (gap) {
            gaps++
            log(nowMs, "coupure du flux : ${nowMs - lastMs} ms sans paquet")
            // Un épisode en cours ne peut plus être mesuré : on ne sait pas ce qui s'est passé pendant la coupure.
            open.values.forEach { it.disturbed = true }
        } else if (lastMs > 0 && intervals.size < MAX_INTERVALS) {
            intervals += (nowMs - lastMs).toInt()
        }
        if (lastCar != 0 && p.carCode != lastCar) log(nowMs, "changement de voiture : $lastCar → ${p.carCode}")
        lastCar = p.carCode
        packets++
        formatCounts[p.format] = (formatCounts[p.format] ?: 0) + 1
        val disturbed = p.isPaused || p.isLoading
        val prev = lastFlags
        lastFlags = p.flags
        lastMs = nowMs
        if (prev == null) {
            Bit.entries.forEach { b ->
                val on = p.flags and b.mask != 0
                initial[b] = on
                if (on) open[b] = Open(nowMs, 1, disturbed = false, unknownStart = true) // déjà allumé au départ
            }
            log(nowMs, "état initial : " + Bit.entries.filter { initial[it] == true }.joinToString { it.label }.ifEmpty { "aucun bit suivi allumé" } +
                " (format ${p.format.label}, ${state(p)})")
            return
        }
        for (b in Bit.entries) {
            val was = prev and b.mask != 0
            val now = p.flags and b.mask != 0
            if (was == now) {
                if (now) open[b]?.let { it.packets++; if (disturbed) it.disturbed = true }
                continue
            }
            transitions[b] = (transitions[b] ?: 0) + 1
            log(nowMs, "${b.label} ${if (now) "allumé" else "éteint"} (format ${p.format.label}, ${state(p)}, ${p.speedKmh.roundToInt()} km/h)")
            if (now) {
                open[b] = Open(nowMs, 1, disturbed || gap)
            } else {
                open.remove(b)?.let { o ->
                    close(b, o.start, nowMs, o.packets, if (o.disturbed || o.unknownStart || disturbed) Kind.UNMEASURABLE else classify(o.packets, nowMs - o.start))
                }
            }
        }
    }

    private fun classify(packets: Int, ms: Long): Kind = when {
        packets <= PULSE_PACKETS -> Kind.PULSE
        ms < SHORT_MS -> Kind.SHORT
        ms <= HOLD_MS -> Kind.HOLD
        else -> Kind.LONG
    }

    /** Le repère précède l'appui : le pilote touche le Fold, puis la manette. */
    private fun near(start: Long, mark: Long) = start - mark in -MARK_AFTER_MS..MARK_BEFORE_MS

    private fun close(b: Bit, start: Long, end: Long?, n: Int, kind: Kind) {
        val near = marks.any { m -> near(start, m) }
        episodes += Episode(b, start, end, n, kind, near)
        if (episodes.size > maxEvents) episodes.removeAt(0)
    }

    private fun closeAll(nowMs: Long, kind: Kind) {
        open.forEach { (b, o) -> close(b, o.start, null, o.packets, if (o.disturbed) Kind.UNMEASURABLE else kind) }
        open.clear()
    }

    private fun state(p: TelemetryPacket) = when {
        p.isLoading -> "chargement"
        p.isPaused -> "pause"
        p.isOnTrack -> "en piste"
        else -> "hors piste ou menu"
    }

    private fun log(t: Long, text: String) {
        events += Event(t, text)
        if (events.size > maxEvents) events.removeAt(0)
    }

    /** Épisodes terminés et en cours (ceux en cours comptés « permanents » s'ils durent depuis le début). */
    @Synchronized
    fun episodes(): List<Episode> = episodes + open.map { (b, o) ->
        Episode(b, o.start, null, o.packets, if (o.disturbed) Kind.UNMEASURABLE else Kind.PERMANENT, !o.unknownStart && marks.any { near(o.start, it) })
    }

    /**
     * Conclusion prudente, par bit : jamais « touche détectée » sans repères concordants. Concordant = au moins
     * [MIN_MARKS] repères, chacun suivi d'un allumage dans les [MARK_BEFORE_MS], et aucun allumage sans repère.
     */
    @Synchronized
    fun verdict(b: Bit): String {
        val eps = episodes().filter { it.bit == b }
        // Épisodes réellement commencés pendant l'essai (pas l'état déjà allumé au premier paquet).
        val started = eps.filter { it.startMs > firstMs }
        val tr = transitions[b] ?: 0
        if (tr == 0) return if (initial[b] == true) "${b.label} : allumé en permanence, aucune transition (indétectable)" else "${b.label} : jamais changé (indétectable)"
        val withMark = marks.count { m -> started.any { near(it.startMs, m) } }
        val withoutMark = started.count { !it.nearMark && it.kind != Kind.UNMEASURABLE }
        return when {
            marks.size >= MIN_MARKS && withMark == marks.size && withoutMark == 0 ->
                "${b.label} : ${marks.size} repères sur ${marks.size} suivis d'un allumage, aucun allumage sans repère — concordance sur cet essai seulement, à refaire en Sport"
            marks.isEmpty() -> "${b.label} : $tr transitions sans aucun repère du pilote — aucune attribution possible"
            else -> "${b.label} : $withMark repère(s) sur ${marks.size} suivis d'un allumage, $withoutMark allumage(s) sans repère — pas de correspondance démontrée"
        }
    }

    @Synchronized
    fun summary(): List<String> {
        if (!enabled && packets == 0L) return listOf("Sonde désactivée.")
        val out = mutableListOf<String>()
        val secs = ((lastMs - firstMs) / 1000.0).coerceAtLeast(0.001)
        val sorted = intervals.sorted()
        val p95 = sorted.getOrNull((sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1))
        out += "$packets paquets en ${secs.roundToInt()} s (${(packets / secs).roundToInt()}/s), $gaps coupure(s) du flux" +
            (p95?.let { ", écart entre paquets au 95e centile : $it ms" } ?: "") + "."
        out += "Formats : " + formatCounts.entries.joinToString { "${it.key.label} ${it.value}" } + " ; session : $session ; ${marks.size} repère(s)."
        for (b in listOf(Bit.HIGH_BEAM, Bit.LOW_BEAM, Bit.LIGHTS, Bit.HANDBRAKE)) {
            val eps = episodes().filter { it.bit == b }
            val counts = Kind.entries.mapNotNull { k -> eps.count { it.kind == k }.takeIf { it > 0 }?.let { "$it ${k.label}" } }
            out += "${b.label.replaceFirstChar { it.uppercase() }} : ${transitions[b] ?: 0} transitions" +
                (if (counts.isNotEmpty()) " — ${counts.joinToString(", ")}" else "") + "."
        }
        Bit.entries.filter { it.name.startsWith("BIT") && (transitions[it] ?: 0) > 0 }.forEach { out += "${it.label} : ${transitions[it]} transitions." }
        out += verdict(Bit.HIGH_BEAM)
        return out
    }

    /** Journal compact exportable (texte, horodatage relatif au premier paquet). Aucun son, aucune adresse réseau. */
    @Synchronized
    fun export(): String = buildString {
        appendLine("# Pitwall — sonde touche radio DualSense (issue #38). Observations seulement.")
        summary().forEach { appendLine("# $it") }
        appendLine("t_ms;evenement")
        events.forEach { appendLine("${it.timeMs - firstMs};${it.text.replace(';', ',')}") }
        appendLine("t_debut_ms;bit;paquets;duree_ms;type;repere_proche")
        episodes().forEach { e ->
            appendLine("${e.startMs - firstMs};${e.bit.name};${e.packets};${e.endMs?.let { it - e.startMs } ?: ""};${e.kind.name};${if (e.nearMark) "oui" else "non"}")
        }
    }

    @Synchronized
    fun reset() {
        firstMs = 0; lastMs = 0; lastFlags = null; lastCar = 0; packets = 0; gaps = 0
        formatCounts = HashMap(); intervals.clear(); initial.clear(); transitions.clear(); episodes.clear(); open.clear()
        marks.clear(); events.clear(); session = "non précisée"
    }

    companion object {
        const val GAP_MS = 500L
        const val PULSE_PACKETS = 3
        const val SHORT_MS = 500L
        const val HOLD_MS = 2_000L
        /** Allumage attendu au plus 2,5 s après le repère (le temps de poser le Fold), ou 0,5 s avant. */
        const val MARK_BEFORE_MS = 2_500L
        const val MARK_AFTER_MS = 500L
        const val MIN_MARKS = 5
        const val MAX_INTERVALS = 20_000
    }
}
