package com.gt7pitwall.core.telemetry

import kotlin.math.roundToInt

/**
 * Sonde « touche radio DualSense » (issue #38, phase 1). Le téléphone ne lit pas la manette appairée à la PS5 : la seule
 * trace possible d'un appui est son **effet** dans la télémétrie, par exemple le bit « pleins phares » de 0x8E si la touche
 * d'appel de phares est utilisée. 0x8E transmet l'**état des feux**, pas un bouton brut. Cette sonde observe seulement :
 * elle ne déclenche **jamais** la radio, ne dit rien, et n'enregistre aucun son. Désactivée par défaut.
 *
 * Elle note, pour chaque bit suivi :
 * - l'état au premier paquet (feux déjà allumés au départ) ;
 * - chaque transition, horodatée, avec l'état de la session (pause, chargement, en piste), le format et la vitesse ;
 * - la durée de chaque épisode « allumé » : impulsion, bref, maintenu, long, permanent, ou non mesurable (coupure du flux,
 *   pause, chargement, changement de voiture, de mode ou de série pendant l'épisode, début inconnu) ;
 * - les coupures du flux et les changements de voiture.
 *
 * Le pilote organise l'essai en **séries** (témoin sans appui, appels brefs, maintiens) et pose un **repère** avant chaque
 * appui. Chaque repère est apparié à **un seul** épisode, fermé et mesurable, qui commence dans les [MARK_BEFORE_MS] qui
 * suivent ; un épisode commencé avant le repère n'est jamais rattaché (même horloge : le repère précède forcément l'effet).
 * Tout repère ambigu rend la série « non concluante » ; « concordance » n'est jamais écrit sur un signal perturbé.
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

    enum class Kind(val label: String, val measurable: Boolean) {
        PULSE("impulsion (1 à 3 paquets)", true),
        SHORT("appui bref (< 0,5 s)", true),
        HOLD("appui maintenu (0,5 à 2 s)", true),
        LONG("long (> 2 s)", true),
        PERMANENT("permanent (allumé jusqu'ici)", false),
        UNMEASURABLE("non mesurable (coupure, pause, chargement, changement pendant l'épisode, début inconnu)", false),
    }

    /** Mode de jeu **déclaré** par le pilote : Pitwall ne le détecte pas automatiquement. */
    enum class Mode(val label: String) {
        UNSPECIFIED("non déclaré"),
        OFFLINE("hors ligne"),
        SPORT_QUALIFYING("Sport, qualification"),
        SPORT_RACE("Sport, course"),
    }

    enum class SeriesKind(val label: String, val expectsPresses: Boolean) {
        CONTROL("témoin sans appui", false),
        SHORT_PRESSES("appels brefs", true),
        HOLDS("maintiens d'environ 1 s", true),
        FREE("libre", true),
    }

    data class Episode(val bit: Bit, val startMs: Long, val endMs: Long?, val packets: Int, val kind: Kind, val series: Int?)

    data class Event(val timeMs: Long, val text: String)

    /** Bilan d'une série pour un bit. */
    data class SeriesReport(
        val index: Int,
        val kind: SeriesKind,
        val mode: Mode,
        val marks: Int,
        val rises: Int,
        val paired: Int,
        val releases: Int,
        val unmeasurable: Int,
        val unpairedMarks: Int,
        val unpairedRises: Int,
        val ambiguousMarks: Int,
        val concordant: Boolean,
        val verdict: String,
    )

    private class Series(val index: Int, val kind: SeriesKind, val mode: Mode, val startMs: Long) {
        val marks = ArrayList<Long>()
        var endMs: Long? = null
    }

    /** Épisode « allumé » en cours. */
    private class Open(val start: Long, var packets: Int, var disturbed: Boolean, val unknownStart: Boolean, val series: Int?)

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
    private val open = HashMap<Bit, Open>()
    private val series = ArrayList<Series>()
    private val events = ArrayList<Event>()
    private var mode = Mode.UNSPECIFIED
    /** Dernier changement de contexte (mode déclaré, nouvelle série) : un allumage juste après n'est pas mesurable. */
    private var contextChangeMs = Long.MIN_VALUE / 2
    private var profile: String? = null

    val currentMode: Mode get() = mode
    val currentSeries: SeriesKind? get() = series.lastOrNull()?.takeIf { it.endMs == null }?.kind

    @Synchronized
    fun setEnabled(on: Boolean, nowMs: Long) {
        if (on == enabled) return
        enabled = on
        if (on) {
            reset()
        } else {
            series.lastOrNull()?.let { if (it.endMs == null) it.endMs = nowMs }
            closeAll(Kind.PERMANENT)
        }
        log(nowMs, if (on) "sonde activée" else "sonde arrêtée")
    }

    /** Mode de jeu déclaré par le pilote. Un épisode en cours devient non mesurable (contexte changé). */
    @Synchronized
    fun declareMode(m: Mode, nowMs: Long) {
        if (!enabled || m == mode) return
        mode = m
        contextChangeMs = nowMs
        open.values.forEach { it.disturbed = true }
        log(nowMs, "mode déclaré : ${m.label}")
    }

    /** Profil d'épreuve choisi dans Pitwall, noté pour information : ce n'est pas une détection du mode de jeu. */
    @Synchronized
    fun profile(label: String, nowMs: Long) {
        if (!enabled || label == profile) return
        profile = label
        log(nowMs, "profil Pitwall (indicatif, pas une détection du mode) : $label")
    }

    /** Nouvelle série d'essai ; la précédente se termine. Un épisode en cours devient non mesurable. */
    @Synchronized
    fun startSeries(kind: SeriesKind, nowMs: Long) {
        if (!enabled) return
        series.lastOrNull()?.let { if (it.endMs == null) it.endMs = nowMs }
        series += Series(series.size + 1, kind, mode, nowMs)
        contextChangeMs = nowMs
        open.values.forEach { it.disturbed = true }
        log(nowMs, "série ${series.size} : ${kind.label} (${mode.label})")
    }

    /** Repère du pilote : « j'appuie maintenant ». Sans série en cours, une série « libre » est ouverte. */
    @Synchronized
    fun mark(nowMs: Long) {
        if (!enabled) return
        val s = series.lastOrNull()?.takeIf { it.endMs == null } ?: run { startSeries(SeriesKind.FREE, nowMs); series.last() }
        s.marks += nowMs
        log(nowMs, "repère ${s.marks.size} de la série ${s.index}")
    }

    @Synchronized
    fun onPacket(p: TelemetryPacket, nowMs: Long) {
        if (!enabled) return
        if (packets == 0L) firstMs = nowMs
        val gap = lastMs > 0 && nowMs - lastMs > GAP_MS
        if (gap) {
            gaps++
            log(nowMs, "coupure du flux : ${nowMs - lastMs} ms sans paquet")
            open.values.forEach { it.disturbed = true }
        } else if (lastMs > 0 && intervals.size < MAX_INTERVALS) {
            intervals += (nowMs - lastMs).toInt()
        }
        if (lastCar != 0 && p.carCode != lastCar) {
            log(nowMs, "changement de voiture : $lastCar → ${p.carCode}")
            open.values.forEach { it.disturbed = true }
        }
        lastCar = p.carCode
        packets++
        formatCounts[p.format] = (formatCounts[p.format] ?: 0) + 1
        val disturbed = p.isPaused || p.isLoading
        val prev = lastFlags
        lastFlags = p.flags
        lastMs = nowMs
        val current = series.lastOrNull()?.takeIf { it.endMs == null }?.index
        if (prev == null) {
            Bit.entries.forEach { b ->
                val on = p.flags and b.mask != 0
                initial[b] = on
                if (on) open[b] = Open(nowMs, 1, disturbed = false, unknownStart = true, series = current)
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
                val settling = nowMs - contextChangeMs < SETTLE_MS
                open[b] = Open(nowMs, 1, disturbed || gap || settling, unknownStart = false, series = current)
            } else {
                open.remove(b)?.let { o ->
                    val kind = if (o.disturbed || o.unknownStart || disturbed) Kind.UNMEASURABLE else classify(o.packets, nowMs - o.start)
                    add(Episode(b, o.start, nowMs, o.packets, kind, o.series))
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

    private fun add(e: Episode) {
        episodes += e
        if (episodes.size > maxEvents) episodes.removeAt(0)
    }

    private fun closeAll(kind: Kind) {
        open.forEach { (b, o) -> add(Episode(b, o.start, null, o.packets, if (o.disturbed || o.unknownStart) Kind.UNMEASURABLE else kind, o.series)) }
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

    /** Épisodes terminés, puis ceux en cours (permanents s'ils ne sont pas perturbés). */
    @Synchronized
    fun episodes(): List<Episode> = episodes + open.map { (b, o) ->
        // Encore allumé : « permanent » (y compris s'il l'était déjà au départ), sauf si le flux a été perturbé.
        Episode(b, o.start, null, o.packets, if (o.disturbed) Kind.UNMEASURABLE else Kind.PERMANENT, o.series)
    }

    /** Le repère précède l'effet : l'allumage commence entre le repère et [MARK_BEFORE_MS] après. */
    private fun inWindow(start: Long, mark: Long) = start - mark in 0..MARK_BEFORE_MS

    @Synchronized
    fun seriesReports(b: Bit = Bit.HIGH_BEAM): List<SeriesReport> {
        val all = episodes().filter { it.bit == b && it.startMs > firstMs }
        return series.map { s ->
            val eps = all.filter { it.series == s.index }.sortedBy { it.startMs }
            val measurable = eps.filter { it.kind.measurable && it.endMs != null }
            val notMeasurable = eps.filter { !(it.kind.measurable && it.endMs != null) }
            // Appariement chronologique un repère ↔ un épisode distinct.
            val used = HashSet<Episode>()
            var paired = 0
            var ambiguous = 0
            for (m in s.marks.sorted()) {
                val match = measurable.firstOrNull { it !in used && inWindow(it.startMs, m) }
                if (match != null) {
                    used += match
                    paired++
                }
                // Un épisode non mesurable (ou encore ouvert) dans la fenêtre rend ce repère ambigu.
                if (notMeasurable.any { inWindow(it.startMs, m) }) ambiguous++
            }
            val unpairedMarks = s.marks.size - paired
            val unpairedRises = measurable.size - paired
            val concordant = s.kind.expectsPresses && s.marks.size >= MIN_MARKS && unpairedMarks == 0 && unpairedRises == 0 &&
                ambiguous == 0 && notMeasurable.isEmpty()
            val verdict = when {
                !s.kind.expectsPresses ->
                    if (eps.isEmpty()) "témoin propre : aucune montée sans appui" else "témoin : ${eps.size} montée(s) sans appui — le bit bouge sans la touche"
                s.marks.isEmpty() -> "aucun repère : aucune attribution possible"
                concordant -> "concordance sur cette série seulement (${s.marks.size} repères, ${paired} montées appariées)"
                else -> "non concluant : $paired/${s.marks.size} repères appariés, $unpairedRises montée(s) sans repère, " +
                    "${notMeasurable.size} épisode(s) non mesurable(s), $ambiguous repère(s) ambigu(s)" +
                    (if (s.marks.size < MIN_MARKS) ", moins de $MIN_MARKS repères" else "")
            }
            SeriesReport(
                s.index, s.kind, s.mode, s.marks.size, eps.size, paired, measurable.size, notMeasurable.size,
                unpairedMarks, unpairedRises, ambiguous, concordant, verdict,
            )
        }
    }

    /**
     * Conclusion prudente pour un bit, sur tout l'essai : jamais « touche détectée ». « Concordance » seulement si au moins
     * une série d'appuis concorde, qu'aucune série d'appuis n'est non concluante et que chaque témoin est propre.
     */
    @Synchronized
    fun verdict(b: Bit): String {
        val tr = transitions[b] ?: 0
        if (tr == 0) return if (initial[b] == true) "${b.label} : allumé en permanence, aucune transition (indétectable)" else "${b.label} : jamais changé (indétectable)"
        val reports = seriesReports(b)
        val presses = reports.filter { it.kind.expectsPresses && it.marks > 0 }
        if (presses.isEmpty()) return "${b.label} : $tr transitions sans aucun repère du pilote — aucune attribution possible"
        val dirtyControl = reports.filter { !it.kind.expectsPresses && it.rises > 0 }
        val inconclusive = presses.filter { !it.concordant }
        return when {
            dirtyControl.isNotEmpty() -> "${b.label} : non concluant — le témoin montre des montées sans appui (feux automatiques ou autre)"
            inconclusive.isNotEmpty() -> "${b.label} : non concluant — ${inconclusive.size} série(s) d'appuis sans correspondance nette"
            else -> "${b.label} : concordance sur cet essai seulement (${presses.size} série(s), mode ${presses.map { it.mode.label }.distinct().joinToString()}), à refaire en Sport"
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
            (p95?.let { ", écart entre paquets reçus au 95e centile : $it ms (régularité du flux, pas une latence de bout en bout)" } ?: "") + "."
        out += "Formats : " + formatCounts.entries.joinToString { "${it.key.label} ${it.value}" } + " ; mode déclaré : ${mode.label}."
        for (b in listOf(Bit.HIGH_BEAM, Bit.LOW_BEAM, Bit.LIGHTS, Bit.HANDBRAKE)) {
            val eps = episodes().filter { it.bit == b }
            val counts = Kind.entries.mapNotNull { k -> eps.count { it.kind == k }.takeIf { it > 0 }?.let { "$it ${k.label}" } }
            out += "${b.label.replaceFirstChar { it.uppercase() }} : ${transitions[b] ?: 0} transitions" +
                (if (counts.isNotEmpty()) " — ${counts.joinToString(", ")}" else "") + "."
        }
        Bit.entries.filter { it.name.startsWith("BIT") && (transitions[it] ?: 0) > 0 }.forEach { out += "${it.label} : ${transitions[it]} transitions." }
        seriesReports(Bit.HIGH_BEAM).forEach { r ->
            out += "Série ${r.index} (${r.kind.label}, ${r.mode.label}) : ${r.marks} repères, ${r.rises} montées, ${r.paired} appariées, " +
                "${r.releases} relâchements mesurés, ${r.unmeasurable} non mesurables — ${r.verdict}."
        }
        out += verdict(Bit.HIGH_BEAM)
        return out
    }

    /** Journal compact exportable (texte, horodatage relatif au premier paquet). Aucun son, aucune adresse réseau. */
    @Synchronized
    fun export(): String = buildString {
        appendLine("# Pitwall — sonde touche radio DualSense (issue #38). Observations seulement ; 0x8E = état des feux, pas un bouton brut.")
        summary().forEach { appendLine("# $it") }
        appendLine("t_ms;evenement")
        events.forEach { appendLine("${it.timeMs - firstMs};${it.text.replace(';', ',')}") }
        appendLine("t_debut_ms;bit;paquets;duree_ms;type;mesurable;serie")
        episodes().forEach { e ->
            appendLine("${e.startMs - firstMs};${e.bit.name};${e.packets};${e.endMs?.let { it - e.startMs } ?: ""};${e.kind.name};" +
                "${if (e.kind.measurable && e.endMs != null) "oui" else "non"};${e.series ?: ""}")
        }
        appendLine("serie;type;mode;t_debut_ms;reperes_ms")
        series.forEach { s -> appendLine("${s.index};${s.kind.name};${s.mode.name};${s.startMs - firstMs};${s.marks.joinToString(" ") { (it - firstMs).toString() }}") }
    }

    @Synchronized
    fun reset() {
        firstMs = 0; lastMs = 0; lastFlags = null; lastCar = 0; packets = 0; gaps = 0
        formatCounts = HashMap(); intervals.clear(); initial.clear(); transitions.clear(); episodes.clear(); open.clear()
        series.clear(); events.clear(); mode = Mode.UNSPECIFIED; profile = null; contextChangeMs = Long.MIN_VALUE / 2
    }

    companion object {
        const val GAP_MS = 500L
        const val PULSE_PACKETS = 3
        const val SHORT_MS = 500L
        const val HOLD_MS = 2_000L
        /** L'allumage doit commencer au plus 2,5 s après le repère (le temps de poser le Fold). */
        const val MARK_BEFORE_MS = 2_500L
        const val MIN_MARKS = 5
        /** Délai après un changement de mode ou de série pendant lequel un allumage n'est pas mesurable. */
        const val SETTLE_MS = 1_000L
        const val MAX_INTERVALS = 20_000
    }
}
