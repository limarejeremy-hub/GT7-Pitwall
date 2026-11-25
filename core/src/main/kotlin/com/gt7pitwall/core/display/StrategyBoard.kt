package com.gt7pitwall.core.display

import com.gt7pitwall.core.strategy.StrategyAssurance
import com.gt7pitwall.core.strategy.StrategyEngine
import com.gt7pitwall.core.strategy.StrategyOptions
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * V515-C — Strategy Board : ce que verrait un ingénieur au muret, calculé en Kotlin pur depuis le moteur de stratégie
 * et la décision du contrôleur. Une option sans données suffisantes est affichée INCONNUE, jamais mauvaise ou
 * impossible ; une option impossible dit pourquoi.
 */
data class StrategyBoard(
    val verdict: StrategyAssurance.Verdict,
    /** Plan actuel (scénario A) et plan retenu par le contrôleur. */
    val current: String,
    val chosen: String,
    val changed: Boolean,
    /** Tour d'arrêt du plan des rappels « Box » (plan actuel), toujours affiché quand le choix V515 diffère. */
    val boxLap: Int?,
    val nextStopLap: Int?,
    val nextStopFuel: Int?,
    val nextStopTyres: Boolean?,
    val window: String?,
    val statement: String,
    val alternatives: List<Row>,
    val reasons: List<String>,
    val checks: List<String>,
    val undercut: String?,
    val overcut: String?,
    val unknowns: List<String>,
) {
    enum class Status(val label: String) { BETTER("plus rapide"), EQUAL("équivalent"), WORSE("plus lent"), UNKNOWN("inconnu"), IMPOSSIBLE("impossible"), REFERENCE("plan actuel") }

    data class Row(val code: Char, val label: String, val plan: String, val delta: String, val status: Status)

    companion object {
        fun of(report: StrategyEngine.Report, d: StrategyAssurance.Decision): StrategyBoard {
            val ref = report.reference
            val next = d.chosen?.stops?.firstOrNull()
            val rows = report.scenarios.filter { it.kind != StrategyEngine.Kind.BEST || it.stopLaps != ref?.stopLaps }.map { s ->
                val status = when {
                    s.kind == StrategyEngine.Kind.CURRENT -> Status.REFERENCE
                    !s.feasible && s.issues.isEmpty() -> Status.UNKNOWN
                    !s.feasible -> Status.IMPOSSIBLE
                    ref?.feasible == false -> Status.UNKNOWN
                    s.deltaMs == null || s.uncertaintyMs == null -> Status.UNKNOWN
                    -s.deltaMs > s.uncertaintyMs -> Status.BETTER
                    s.deltaMs > s.uncertaintyMs -> Status.WORSE
                    else -> Status.EQUAL
                }
                val delta = when (status) {
                    Status.REFERENCE -> if (s.feasible) "référence" else "infaisable : ${s.issues.firstOrNull() ?: ""}"
                    Status.IMPOSSIBLE -> s.issues.firstOrNull() ?: "infaisable"
                    Status.UNKNOWN -> if (ref?.feasible == false && s.feasible) "faisable (plan actuel infaisable)" else "inconnu : ${s.unknowns.firstOrNull() ?: "données insuffisantes"}"
                    else -> "${if (s.deltaMs!! <= 0) "−" else "+"}${StrategyOptions.seconds(kotlin.math.abs(s.deltaMs).roundToLong())} ± ${StrategyOptions.seconds(s.uncertaintyMs!!.roundToLong())}"
                }
                Row(s.kind.code, s.kind.label, s.describe(), delta, status)
            }
            return StrategyBoard(
                verdict = d.verdict,
                current = ref?.describe() ?: "aucun plan",
                chosen = d.chosen?.describe() ?: "aucune stratégie fiable",
                changed = d.chosen != null && ref != null && d.chosen.key != ref.key,
                boxLap = ref?.stopLaps?.firstOrNull(),
                nextStopLap = next?.lap,
                nextStopFuel = next?.fuelAdded?.roundToInt(),
                nextStopTyres = next?.tyres,
                window = report.window?.let { w ->
                    (if (w.from == w.to) "Arrêt au tour ${w.from}" else "Arrêt possible entre les tours ${w.from} et ${w.to}") +
                        (w.preferred?.let { p -> " ; tour $p préféré selon les données actuelles" } ?: " (temps non chiffrable : fenêtre de faisabilité)")
                },
                statement = d.statement,
                alternatives = rows,
                reasons = d.reasons,
                checks = d.checks.map { "${it.name} : ${when (it.ok) { true -> "ok"; false -> "échec"; null -> "réserve" }} — ${it.detail}" },
                undercut = report.undercut?.verdict,
                overcut = report.overcut?.verdict,
                unknowns = report.scenarios.flatMap { it.unknowns }.distinct().take(5),
            )
        }
    }
}
