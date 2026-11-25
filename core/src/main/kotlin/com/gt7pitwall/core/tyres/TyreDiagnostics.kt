package com.gt7pitwall.core.tyres

import com.gt7pitwall.core.strategy.TyreCompound
import com.gt7pitwall.core.telemetry.Surface
import com.gt7pitwall.core.telemetry.TelemetryPacket
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * V514-A — diagnostic dynamique des quatre pneus, à partir de ce que GT7 transmet RÉELLEMENT (tous formats, y compris A
 * en Course Sport) :
 * - température de surface (0x60–0x6C, MESURE) : actuelle, moyenne et maximale du tour, évolution depuis la référence
 *   du relais, écart avec l'autre pneu du même essieu ;
 * - glissement roue / voiture (rotation 0xA4–0xB0 × rayon 0xB4–0xC0 / vitesse, CALCUL) : patinage médian des roues
 *   motrices en pleine accélération, patinages forts et blocages, comptés en ÉCHANTILLONS et en ÉVÉNEMENTS ;
 * - surface sous chaque pneu (0x158, format C seulement) : écarte les mesures prises hors asphalte. Sans elle (A, B, ~),
 *   rien n'est bloqué : la confiance est seulement réduite.
 *
 * **Aucune usure n'est mesurée ni affichée** : GT7 ne la transmet pas (V513). Les anomalies sont des constats mesurés,
 * relatifs au début du relais ; « dégradation supposée » reste une hypothèse tant que les chronos ne la démontrent pas.
 *
 * Relais : il ne change QUE sur un changement de pneus confirmé (registre des arrêts, [setStint]). Un ravitaillement
 * seul garde l'âge et la référence thermique ; il ouvre seulement une phase de récupération (pneus refroidis pendant
 * l'arrêt) pendant laquelle aucune anomalie de température n'est relevée. Après un changement confirmé, la nouvelle
 * référence est prise sur le premier tour complet sans arrêt du relais (pneus stabilisés), jamais à la sortie des stands.
 */
class TyreDiagnostics {
    enum class Confidence(val label: String) { LOW("faible"), MEDIUM("moyenne"), HIGH("bonne") }

    enum class IssueKind(val label: String) {
        TEMPERATURE("température"),
        WHEELSPIN("patinage excessif"),
        LOCKING("blocages répétés"),
        GRIP_LOSS("baisse d'adhérence mesurée"),
        DEGRADATION_SUSPECTED("dégradation supposée, non démontrée"),
    }

    /** Une anomalie : [wheel] 0–3 (AVG, AVD, ARG, ARD) ou null pour le train ; [origin] : données qui la fondent. */
    data class Issue(val kind: IssueKind, val wheel: Int?, val text: String, val origin: String)

    enum class DrivenAxle(val label: String) { FRONT("traction avant"), REAR("propulsion"), BOTH("quatre roues motrices"), UNKNOWN("roues motrices non identifiées") }

    data class Wheel(
        val label: String,
        /** MESURE : dernière température reçue. */
        val nowC: Float?,
        /** MESURE (moyenne et maximum de paquets) : dernier tour complet du relais, sinon tour en cours. */
        val lapMeanC: Float?,
        val lapMaxC: Float?,
        /** CALCUL : moyenne du dernier tour moins la référence stabilisée du relais (null sans référence). */
        val sinceReferenceC: Float?,
        /** CALCUL : écart avec l'autre pneu du même essieu (ce pneu moins l'autre), sur le dernier tour. */
        val axleDeltaC: Float?,
        /** CALCUL : patinage médian en pleine accélération, en %, roues motrices seulement (null : non fiable). */
        val slipPct: Float?,
        val driven: Boolean?,
        val spinSamples: Int,
        val spinEvents: Int,
        val lockSamples: Int,
        val lockEvents: Int,
        val confidence: Confidence,
        /** Événements du dernier tour complet (le compte utile en course : une conduite normale en produit déjà). */
        val lastLapSpinEvents: Int? = null,
        val lastLapLockEvents: Int? = null,
    )

    /** Résumé d'un relais terminé (historique conservé après un changement confirmé). */
    data class StintSummary(val startLap: Int, val endLap: Int, val laps: Int, val meanC: List<Float?>, val spinEvents: List<Int>, val lockEvents: List<Int>)

    data class Report(
        val wheels: List<Wheel>,
        val stintStartLap: Int,
        /** Tours complets mesurés dans ce relais (le tour en cours exclu). */
        val measuredLaps: Int,
        val referenceLap: Int?,
        /** Pneus en récupération thermique après un arrêt : températures non interprétées. */
        val recovering: Boolean,
        val surfacesKnown: Boolean,
        val drivenAxle: DrivenAxle,
        val issues: List<Issue>,
        /** Coupures du flux UDP (> 1 s) dans le relais : la mesure n'est pas continue. */
        val gaps: Int,
        val confidence: Confidence,
        val confidenceWhy: List<String>,
        val history: List<StintSummary>,
    ) {
        /** Pneu le plus sollicité : celui qui porte le plus d'anomalies, puis le plus chaud par rapport à sa référence. */
        val worstWheel: Int?
            get() {
                if (wheels.all { it.lapMeanC == null }) return null
                val byIssues = issues.filter { it.wheel != null }.groupingBy { it.wheel!! }.eachCount()
                return wheels.indices.maxWithOrNull(
                    compareBy<Int> { byIssues[it] ?: 0 }.thenBy { wheels[it].sinceReferenceC ?: 0f }.thenBy { wheels[it].lapMeanC ?: 0f },
                )
            }

        companion object {
            val EMPTY = Report(
                LABELS.map { Wheel(it, null, null, null, null, null, null, null, 0, 0, 0, 0, Confidence.LOW) },
                1, 0, null, false, false, DrivenAxle.UNKNOWN, emptyList(), 0, Confidence.LOW, listOf("aucune télémétrie"), emptyList(),
            )
        }
    }

    /**
     * Le train de pneus : âge depuis le dernier changement CONFIRMÉ, gomme (déclarée, GT7 ne la transmet pas), tendance des
     * chronos comparables et autonomie. [lossDemonstrated] : la perte est établie par les chronos (diagnostic d'adhérence
     * persistant) ; sinon elle n'est jamais présentée comme une perte.
     */
    data class Train(
        val ageLaps: Int,
        val ageCertain: Boolean,
        val compound: TyreCompound?,
        val compoundDeclared: Boolean,
        val grip: GripLevel,
        val paceSlopeMsPerLap: Double?,
        val paceLossMs: Int?,
        val lossDemonstrated: Boolean,
        val autonomy: String,
        val behaviour: String?,
    )

    // ---- État courant ----
    private class LapAcc(val lap: Int) {
        val sum = DoubleArray(4)
        val max = FloatArray(4) { Float.NaN }
        var n = 0
        val slip = Array(4) { ArrayList<Float>() }
        val spinS = IntArray(4)
        val lockS = IntArray(4)
        val spinE = IntArray(4)
        val lockE = IntArray(4)
        var stopped = false
        var gaps = 0
        var offTrackSamples = 0
    }

    private class LapStats(
        val lap: Int, val mean: List<Float>, val max: List<Float>, val slipMedian: List<Float?>, val spinS: List<Int>, val lockS: List<Int>,
        val spinE: List<Int>, val lockE: List<Int>, val samples: Int, val stopped: Boolean, val gaps: Int,
        /** Tour d'arrêt ou tour qui le suit : pneus refroidis, températures non interprétées. */
        val recovering: Boolean,
    )

    private var stintStart = 1
    private var stintCertain = true
    private var cur: LapAcc? = null
    private val laps = mutableListOf<LapStats>()
    private val history = mutableListOf<StintSummary>()
    private var reference: LapStats? = null
    private var lastPacket: TelemetryPacket? = null
    private var lastAtMs = -1L
    private var stillSinceMs = -1L
    /** Récupération thermique : de la fin d'un arrêt à la fin du premier tour complet qui suit. */
    private var recoverUntilLapEnd: Int? = null
    private var surfacesSeen = false
    private val axleSlip = Array(2) { ArrayDeque<Float>() }
    private val spinRun = BooleanArray(4)
    private val lockRun = BooleanArray(4)
    private val spinOffMs = LongArray(4)
    private val lockOffMs = LongArray(4)

    /**
     * Relais en cours d'après le registre des arrêts : [startLap] change seulement sur un changement de pneus confirmé.
     * L'ancien relais passe dans l'historique ; la référence thermique est reprise après stabilisation.
     */
    fun setStint(startLap: Int, certain: Boolean) {
        stintCertain = certain
        if (startLap == stintStart) return
        // La confirmation arrive souvent un tour après l'arrêt : les tours déjà mesurés du nouveau relais sont gardés.
        val old = laps.filter { it.lap < startLap }
        val kept = laps.filter { it.lap >= startLap }
        if (old.isNotEmpty() && startLap > stintStart) {
            history += StintSummary(
                stintStart, old.last().lap, old.size,
                (0 until 4).map { w -> old.map { it.mean[w] }.average().toFloat() },
                (0 until 4).map { w -> old.sumOf { it.spinE[w] } },
                (0 until 4).map { w -> old.sumOf { it.lockE[w] } },
            )
        }
        stintStart = startLap
        laps.clear()
        laps += kept
        reference = kept.firstOrNull { !it.stopped && it.lap > startLap && !it.recovering }
    }

    /** Nouvelle course : tout est remis à zéro, historique compris. */
    fun reset() {
        stintStart = 1; stintCertain = true; cur = null; laps.clear(); history.clear(); reference = null
        lastPacket = null; lastAtMs = -1L; stillSinceMs = -1L; recoverUntilLapEnd = null; surfacesSeen = false
        axleSlip.forEach { it.clear() }
        spinRun.fill(false); lockRun.fill(false)
    }

    /** Un paquet en course (hors menus, pause et chargement : à filtrer par l'appelant). */
    fun onPacket(p: TelemetryPacket, atMs: Long) {
        val lap = p.currentLap
        if (lap < 1) return
        val acc = cur?.takeIf { it.lap == lap } ?: run {
            cur?.let { closeLap(it) }
            LapAcc(lap).also { cur = it }
        }
        val dt = if (lastAtMs < 0) 0L else atMs - lastAtMs
        val gap = dt > GAP_MS
        if (gap) {
            acc.gaps++
            // Coupure UDP : aucun événement ne traverse la coupure.
            spinRun.fill(false); lockRun.fill(false)
        }
        lastAtMs = atMs
        lastPacket = p
        val t = p.tyreTemps.toList()
        if (t.all { it > 0f }) {
            acc.n++
            for (w in 0 until 4) {
                acc.sum[w] += t[w].toDouble()
                if (acc.max[w].isNaN() || t[w] > acc.max[w]) acc.max[w] = t[w]
            }
        }
        // Arrêt : immobile ≥ 3 s (stand ou incident). Ouvre la récupération thermique jusqu'à la fin du tour complet suivant.
        if (p.speedKmh < 1f) {
            if (stillSinceMs < 0) stillSinceMs = atMs
            if (atMs - stillSinceMs >= STOP_MS) { acc.stopped = true; recoverUntilLapEnd = lap + 1 }
        } else stillSinceMs = -1L

        val surfaces = p.surfaces?.toList()
        if (surfaces != null) surfacesSeen = true
        val slip = p.wheelSlip?.toList() ?: return
        if (p.tyreRadius.toList().any { it <= 0f }) return
        val straight = abs(p.angularVelocityY) < STRAIGHT_YAW
        for (w in 0 until 4) {
            val onTarmac = surfaces == null || surfaces[w] == Surface.TARMAC
            if (!onTarmac) { acc.offTrackSamples++; continue }
            val s = slip[w]
            if (p.throttle >= FULL_THROTTLE && p.brake == 0 && straight && p.speedKmh > MIN_KMH) {
                acc.slip[w] += s
                val q = axleSlip[w / 2]
                q.addLast(s); if (q.size > AXLE_SAMPLES) q.removeFirst()
            }
            val spinning = s > SPIN_RATIO && p.throttle > PEDAL_MIN
            val locking = s < LOCK_RATIO && p.brake > PEDAL_MIN
            if (spinning) acc.spinS[w]++
            if (locking) acc.lockS[w]++
            // Événement : début d'une série ; fin après EVENT_GAP_MS sans le signal.
            if (spinning) { if (!spinRun[w]) { spinRun[w] = true; acc.spinE[w]++ }; spinOffMs[w] = atMs }
            else if (spinRun[w] && atMs - spinOffMs[w] > EVENT_GAP_MS) spinRun[w] = false
            if (locking) { if (!lockRun[w]) { lockRun[w] = true; acc.lockE[w]++ }; lockOffMs[w] = atMs }
            else if (lockRun[w] && atMs - lockOffMs[w] > EVENT_GAP_MS) lockRun[w] = false
        }
    }

    private fun closeLap(a: LapAcc) {
        if (a.n < MIN_LAP_SAMPLES) return
        val stats = LapStats(
            a.lap,
            (0 until 4).map { (a.sum[it] / a.n).toFloat() },
            (0 until 4).map { a.max[it] },
            (0 until 4).map { w -> a.slip[w].takeIf { it.size >= MIN_SLIP_SAMPLES }?.sorted()?.let { it[it.size / 2] } },
            a.spinS.toList(), a.lockS.toList(), a.spinE.toList(), a.lockE.toList(), a.n, a.stopped, a.gaps,
            recovering = a.stopped || recoverUntilLapEnd?.let { a.lap <= it } == true,
        )
        if (a.lap < stintStart) return
        laps += stats
        // Référence stabilisée : premier tour complet du relais sans arrêt, après le tour de départ ou de sortie.
        if (reference == null && !stats.recovering && a.lap > stintStart) reference = stats
        if (recoverUntilLapEnd != null && a.lap >= recoverUntilLapEnd!!) recoverUntilLapEnd = null
    }

    private fun drivenAxle(): DrivenAxle {
        val med = axleSlip.map { q -> if (q.size < MIN_AXLE_SAMPLES) null else q.sorted()[q.size / 2] }
        val f = med[0] ?: return DrivenAxle.UNKNOWN
        val r = med[1] ?: return DrivenAxle.UNKNOWN
        return when {
            f - r > DRIVEN_GAP -> DrivenAxle.FRONT
            r - f > DRIVEN_GAP -> DrivenAxle.REAR
            f > 1f + DRIVEN_GAP && r > 1f + DRIVEN_GAP -> DrivenAxle.BOTH
            else -> DrivenAxle.UNKNOWN
        }
    }

    /** Diagnostic courant. [grip] et [tractionLevel] : constats des moniteurs existants (chronos, motricité comparée). */
    fun report(grip: GripReport = GripReport.NONE, tractionLevel: TractionMonitor.Level = TractionMonitor.Level.INSUFFICIENT): Report {
        val p = lastPacket ?: return Report.EMPTY
        val axle = drivenAxle()
        val last = laps.lastOrNull()
        val live = cur?.takeIf { it.n >= 1 && it.lap >= stintStart }
        val recovering = recoverUntilLapEnd != null
        val ref = reference
        val issues = mutableListOf<Issue>()
        val nowT = p.tyreTemps.toList()
        val wheels = (0 until 4).map { w ->
            val mean = last?.mean?.get(w) ?: live?.let { (it.sum[w] / it.n).toFloat() }
            val max = last?.max?.get(w) ?: live?.max?.get(w)?.takeIf { !it.isNaN() }
            val other = w xor 1
            val axleDelta = last?.let { it.mean[w] - it.mean[other] }
            val driven = when (axle) {
                DrivenAxle.FRONT -> w < 2
                DrivenAxle.REAR -> w >= 2
                DrivenAxle.BOTH -> true
                DrivenAxle.UNKNOWN -> null
            }
            val slipPct = if (driven == true) last?.slipMedian?.get(w)?.let { (it - 1f) * 100f } else null
            Wheel(
                LABELS[w], nowT[w].takeIf { it > 0f }, mean, max,
                if (ref != null && last != null && last !== ref) last.mean[w] - ref.mean[w] else null,
                axleDelta, slipPct, driven,
                laps.sumOf { it.spinS[w] } + (live?.spinS?.get(w) ?: 0), laps.sumOf { it.spinE[w] } + (live?.spinE?.get(w) ?: 0),
                laps.sumOf { it.lockS[w] } + (live?.lockS?.get(w) ?: 0), laps.sumOf { it.lockE[w] } + (live?.lockE?.get(w) ?: 0),
                Confidence.LOW,
                last?.spinE?.get(w), last?.lockE?.get(w),
            )
        }
        // --- Anomalies de température (jamais en récupération après un arrêt, jamais sans référence stabilisée).
        val recent = laps.filter { !it.recovering }.takeLast(2)
        if (!recovering && ref != null && recent.size == 2 && recent.none { it === ref }) {
            for (w in 0 until 4) {
                val rise = recent.map { it.mean[w] - ref.mean[w] }
                if (rise.all { it >= HOT_RISE_C }) issues += Issue(IssueKind.TEMPERATURE, w,
                    "${NAMES[w].replaceFirstChar { it.uppercase() }} : +${rise.last().roundToInt()} °C sur sa référence du tour ${ref.lap}, deux tours de suite",
                    "MESURE température de surface, comparée au début du relais (pas une plage idéale de la gomme)")
                if (rise.all { it <= -COLD_DROP_C }) issues += Issue(IssueKind.TEMPERATURE, w,
                    "${NAMES[w].replaceFirstChar { it.uppercase() }} : ${rise.last().roundToInt()} °C sous sa référence, deux tours de suite",
                    "MESURE température de surface, comparée au début du relais")
            }
            for (a in listOf(0, 2)) {
                val d = recent.map { it.mean[a] - it.mean[a + 1] }
                val refD = ref.mean[a] - ref.mean[a + 1]
                if (d.all { abs(it - refD) >= AXLE_SHIFT_C }) {
                    val hot = if (d.last() > 0) a else a + 1
                    issues += Issue(IssueKind.TEMPERATURE, hot,
                        "Essieu ${if (a == 0) "avant" else "arrière"} : écart gauche / droite de ${abs(d.last()).roundToInt()} °C, ${abs(d.last() - refD).roundToInt()} de plus qu'en début de relais",
                        "MESURE température de surface des deux pneus du même essieu")
                }
            }
        }
        // --- Patinage et blocages : par tour, comparés au début du relais (au moins 2 tours de suite).
        val cleanLaps = laps.filter { !it.stopped && it.lap > stintStart }
        if (cleanLaps.size >= 3) {
            val base = cleanLaps.take(cleanLaps.size - 2)
            val last2 = cleanLaps.takeLast(2)
            for (w in 0 until 4) {
                val spinBase = base.map { it.spinE[w] }.average()
                if (wheels[w].driven != false && last2.all { it.spinE[w] >= SPIN_EVENTS_MIN && it.spinE[w] >= 2 * spinBase + 1 }) {
                    issues += Issue(IssueKind.WHEELSPIN, w,
                        "${NAMES[w].replaceFirstChar { it.uppercase() }} : ${last2.last().spinE[w]} patinages forts au dernier tour, contre ${"%.1f".format(java.util.Locale.FRANCE, spinBase)} en début de relais",
                        "CALCUL glissement roue / voiture > ${((SPIN_RATIO - 1) * 100).roundToInt()} % à l'accélération")
                }
                val lockBase = base.map { it.lockE[w] }.average()
                if (last2.all { it.lockE[w] >= LOCK_EVENTS_MIN && it.lockE[w] >= 2 * lockBase + 1 }) {
                    issues += Issue(IssueKind.LOCKING, w,
                        "${NAMES[w].replaceFirstChar { it.uppercase() }} : ${last2.last().lockE[w]} blocages au dernier tour, contre ${"%.1f".format(java.util.Locale.FRANCE, lockBase)} en début de relais",
                        "CALCUL glissement roue / voiture < ${((1 - LOCK_RATIO) * 100).roundToInt()} % au freinage")
                }
            }
        }
        // --- Adhérence : constats des moniteurs existants (chronos, motricité à commande comparable).
        if (tractionLevel == TractionMonitor.Level.DEGRADATION_REPEATED || grip.level >= GripLevel.PERSISTENT) {
            issues += Issue(IssueKind.GRIP_LOSS, null,
                if (grip.level >= GripLevel.PERSISTENT) "Chronos en baisse persistante (${grip.level.label})" else "Motricité en baisse répétée à accélérateur comparable",
                if (grip.level >= GripLevel.PERSISTENT) "CALCUL chronos comparables du relais" else "CALCUL motricité en sortie de virage")
        } else if (grip.level == GripLevel.POSSIBLE || tractionLevel == TractionMonitor.Level.DEGRADATION_SUSPECTED) {
            issues += Issue(IssueKind.DEGRADATION_SUSPECTED, null,
                "Signes possibles de baisse de performance, non démontrés",
                "CALCUL sur peu de tours : à confirmer")
        }
        // --- Confiance.
        val why = mutableListOf<String>()
        val gaps = laps.sumOf { it.gaps } + (live?.gaps ?: 0)
        var score = 2
        if (laps.size < 2) { score--; why += "moins de 2 tours complets dans ce relais" }
        if (!surfacesSeen) { score--; why += "surface sous les pneus non transmise (format A, B ou ~)" }
        if (!stintCertain) { score--; why += "changement de pneus non confirmé après un arrêt" }
        if (gaps > 0) { score--; why += "$gaps coupure${if (gaps > 1) "s" else ""} du flux UDP" }
        if (axle == DrivenAxle.UNKNOWN) why += "roues motrices pas encore identifiées : patinage non chiffré"
        val conf = when { score >= 2 -> Confidence.HIGH; score == 1 -> Confidence.MEDIUM; else -> Confidence.LOW }
        val wheelsOut = wheels.map { it.copy(confidence = if (it.lapMeanC == null) Confidence.LOW else conf) }
        return Report(
            wheelsOut, stintStart, laps.size, ref?.lap, recovering, surfacesSeen, axle, issues, gaps, conf, why, history.toList(),
        )
    }

    companion object {
        val LABELS = listOf("AVG", "AVD", "ARG", "ARD")
        val NAMES = listOf("avant gauche", "avant droit", "arrière gauche", "arrière droit")
        const val GAP_MS = 1_000L
        const val STOP_MS = 3_000L
        const val MIN_LAP_SAMPLES = 60
        const val MIN_SLIP_SAMPLES = 30
        const val MIN_AXLE_SAMPLES = 200
        const val AXLE_SAMPLES = 4_000
        const val FULL_THROTTLE = 200
        const val PEDAL_MIN = 51
        const val MIN_KMH = 50f
        const val STRAIGHT_YAW = 0.05f
        /** Seuils de l'affichage existant (CarCards) : > 15 % de patinage, < 85 % au blocage. */
        const val SPIN_RATIO = 1.15f
        const val LOCK_RATIO = 0.85f
        const val EVENT_GAP_MS = 300L
        /** Écart minimal entre les deux essieux pour dire lesquels sont motrices (V513 : 1,2 à 1,6 % contre ± 0,1 %). */
        const val DRIVEN_GAP = 0.004f
        const val HOT_RISE_C = 10f
        const val COLD_DROP_C = 10f
        const val AXLE_SHIFT_C = 8f
        const val SPIN_EVENTS_MIN = 3
        const val LOCK_EVENTS_MIN = 3
    }
}
