package com.gt7pitwall.core.tyres

import com.gt7pitwall.core.analysis.CornerPass
import kotlin.math.roundToInt

/**
 * Baisse de rythme : pneus ou pilotage ? (issue #30-D). GT7 ne transmet ni l'usure ni la gomme : rien n'est affirmé,
 * on compare des passages équivalents (mêmes virages, même relais) au début et à la fin du relais.
 *
 * - Pneus probables : la perte est RÉPARTIE sur la plupart des virages, avec des vitesses minimales qui baissent, et
 *   souvent plus de blocages ou de patinages (signe d'adhérence, pas d'une trajectoire ratée).
 * - Pilotage probable : la perte est CONCENTRÉE sur un ou deux virages (freinage manqué, trajectoire), le reste du
 *   tour au niveau du début de relais.
 * - Sinon : mixte ou indéterminé, et c'est dit. Jamais de pourcentage d'usure.
 */
object TyreOrDriving {
    enum class Verdict(val label: String) {
        UNKNOWN("pas assez de passages comparables"),
        STABLE("rythme stable dans les virages"),
        /** Une perte répartie ne prouve pas les pneus (carburant, trafic, trajectoire) : « possibles », à recouper. */
        TYRES("baisse répartie sur la plupart des virages : pneus possibles"),
        DRIVING("perte concentrée sur peu de virages : pilotage probable"),
        MIXED("baisse partagée : pneus et pilotage possibles"),
    }

    /**
     * Recoupe avec la motricité mesurée à commande égale (revue PR #37, attribution des pertes) : une perte répartie
     * devient « pneus probables » seulement si la motricité baisse aussi ; si elle est mesurée stable, les pneus ne sont
     * pas démontrés.
     */
    fun crossCheck(r: Report, traction: TractionMonitor.Report): Report {
        if (r.verdict != Verdict.TYRES) return r
        return when (traction.level) {
            TractionMonitor.Level.DEGRADATION_SUSPECTED, TractionMonitor.Level.DEGRADATION_REPEATED ->
                r.copy(evidence = r.evidence + "motricité en baisse mesurée en sortie de virage : pneus probables")
            TractionMonitor.Level.STABLE ->
                r.copy(verdict = Verdict.MIXED, evidence = r.evidence + "motricité mesurée stable à commande égale : pneus non démontrés (carburant, trafic, trajectoire possibles)")
            TractionMonitor.Level.INSUFFICIENT -> r
        }
    }

    data class Report(
        val verdict: Verdict,
        val evidence: List<String> = emptyList(),
        val confidence: String = "faible",
        val comparedCorners: Int = 0,
    ) {
        val text: String get() = verdict.label.replaceFirstChar { it.uppercase() } +
            (if (evidence.isNotEmpty()) " : ${evidence.joinToString(", ")}" else "") +
            (if (verdict == Verdict.TYRES || verdict == Verdict.DRIVING || verdict == Verdict.MIXED) " (confiance $confidence)." else ".")
    }

    const val MIN_LAPS = 4
    const val MIN_CORNERS = 3
    const val STABLE_TOTAL_MS = 150
    const val CORNER_LOSS_MS = 40

    /**
     * [passes] : passages mesurés de la session ; [stintStartLap] : premier tour du relais en cours (après le dernier
     * changement de pneus) ; [label] : libellé affiché d'un virage (« T5 »).
     */
    fun analyze(passes: List<CornerPass>, stintStartLap: Int, label: (Int) -> String = { "V$it" }): Report {
        // Premier tour du relais (pneus froids) et sorties de piste mesurées écartés.
        val stint = passes.filter { it.lap > stintStartLap && it.offTrack != true }
        val laps = stint.map { it.lap }.distinct().sorted()
        if (laps.size < MIN_LAPS) return Report(Verdict.UNKNOWN)
        val early = laps.take(2).toSet()
        val late = laps.takeLast(2).toSet()
        data class C(val corner: Int, val loss: Double, val minDrop: Double, val slipRise: Int)
        val perCorner = stint.groupBy { it.corner }.mapNotNull { (k, ps) ->
            val e = ps.filter { it.lap in early }
            val l = ps.filter { it.lap in late }
            if (e.isEmpty() || l.isEmpty()) return@mapNotNull null
            C(
                k,
                l.map { it.timeMs }.average() - e.map { it.timeMs }.average(),
                e.map { it.minKmh.toDouble() }.average() - l.map { it.minKmh.toDouble() }.average(),
                l.count { it.lockup == true || it.wheelspin == true } - e.count { it.lockup == true || it.wheelspin == true },
            )
        }
        if (perCorner.size < MIN_CORNERS) return Report(Verdict.UNKNOWN, comparedCorners = perCorner.size)
        val total = perCorner.sumOf { it.loss.coerceAtLeast(0.0) }
        val confidence = if (laps.size >= 6 && perCorner.size >= 6) "moyenne" else "faible"
        if (total < STABLE_TOTAL_MS) return Report(Verdict.STABLE, comparedCorners = perCorner.size)
        val slower = perCorner.filter { it.loss >= CORNER_LOSS_MS && it.minDrop >= 1.0 }
        val share = slower.size.toDouble() / perCorner.size
        val top = perCorner.maxBy { it.loss }
        val concentration = top.loss.coerceAtLeast(0.0) / total
        val minDrop = slower.map { it.minDrop }.average().takeIf { !it.isNaN() }
        val slipRise = perCorner.sumOf { it.slipRise }
        val evidence = mutableListOf<String>()
        val verdict = when {
            share >= 0.5 && concentration < 0.5 -> {
                evidence += "${slower.size} virages sur ${perCorner.size} plus lents qu'en début de relais"
                minDrop?.let { evidence += "vitesse minimale ${it.roundToInt()} km/h plus basse en moyenne" }
                if (slipRise > 0) evidence += "$slipRise blocage${if (slipRise > 1) "s" else ""} ou patinage${if (slipRise > 1) "s" else ""} de plus"
                Verdict.TYRES
            }
            concentration >= 0.6 -> {
                evidence += "${label(top.corner)} : ${String.format(java.util.Locale.FRANCE, "%.2f", top.loss / 1000)} s sur ${String.format(java.util.Locale.FRANCE, "%.2f", total / 1000)} s perdues"
                evidence += "le reste du tour au niveau du début de relais"
                Verdict.DRIVING
            }
            else -> {
                evidence += "${slower.size} virages sur ${perCorner.size} plus lents, dont ${label(top.corner)} en tête"
                Verdict.MIXED
            }
        }
        return Report(verdict, evidence, confidence, perCorner.size)
    }
}
