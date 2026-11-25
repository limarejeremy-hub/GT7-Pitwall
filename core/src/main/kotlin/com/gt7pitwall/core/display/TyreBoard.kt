package com.gt7pitwall.core.display

import com.gt7pitwall.core.tyres.GripLevel
import com.gt7pitwall.core.tyres.TyreDiagnostics
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * V514-A — carte PNEUS (vue compacte en course, vue détaillée pour l'analyse), calculée en Kotlin pur à partir de
 * [TyreDiagnostics]. Chaque ligne porte sa nature : MESURE (paquet GT7), CALCUL (Pitwall), ESTIMATION, HYPOTHÈSE.
 * Aucune couleur ni aucun texte ne prétend mesurer une usure : GT7 ne la transmet pas, la carte le dit en clair.
 */
data class TyreBoard(
    val cells: List<Cell>,
    /** « Moyenne · 6 tours », âge depuis le dernier changement CONFIRMÉ. */
    val headline: String,
    /** Messages de diagnostic courts (au plus [MAX_MESSAGES] en vue compacte), les plus importants d'abord. */
    val messages: List<Line>,
    /** Vue détaillée : train de pneus, comportement, confiance, historique des relais. */
    val details: List<Line>,
    val confidence: String,
    val autonomy: String,
) {
    enum class Nature(val label: String) {
        MESURE("mesure"), CALCUL("calcul"), ESTIMATION("estimation"), HYPOTHESE("hypothèse"),
        DECLARATION("déclaré"), ABSENT("non transmis"),
    }

    enum class Trend(val symbol: String) { UP("▲"), DOWN("▼"), FLAT("="), UNKNOWN("—") }

    data class Line(val text: String, val nature: Nature, val alert: Boolean = false)

    data class Cell(
        val label: String,
        val nowC: Int?,
        val lapMeanC: Int?,
        val lapMaxC: Int?,
        val sinceRefC: Int?,
        val trend: Trend,
        val axleDeltaC: Int?,
        /** « 1,4 % » (roue motrice), « libre » (roue non motrice), null si inconnu. */
        val slip: String?,
        val spinsLastLap: Int?,
        val locksLastLap: Int?,
        /** Anomalie MESURÉE sur ce pneu (température, patinage, blocages) : jamais une usure. */
        val flagged: Boolean,
    )

    companion object {
        const val MAX_MESSAGES = 3
        const val TREND_C = 3
        const val NO_WEAR = "Usure : non transmise par GT7 (aucun pourcentage)"

        fun of(r: TyreDiagnostics.Report, t: TyreDiagnostics.Train?): TyreBoard {
            val flaggedWheels = r.issues.mapNotNull { it.wheel }.toSet()
            val cells = r.wheels.mapIndexed { i, w ->
                val since = w.sinceReferenceC?.roundToInt()
                Cell(
                    w.label, w.nowC?.roundToInt(), w.lapMeanC?.roundToInt(), w.lapMaxC?.roundToInt(), since,
                    when {
                        since == null -> Trend.UNKNOWN
                        since >= TREND_C -> Trend.UP
                        since <= -TREND_C -> Trend.DOWN
                        else -> Trend.FLAT
                    },
                    w.axleDeltaC?.roundToInt(),
                    when (w.driven) {
                        true -> w.slipPct?.let { String.format(java.util.Locale.FRANCE, "%.1f %%", it) }
                        false -> "libre"
                        null -> null
                    },
                    w.lastLapSpinEvents, w.lastLapLockEvents, i in flaggedWheels,
                )
            }
            val headline = buildString {
                append(t?.compound?.spoken?.replaceFirstChar { it.uppercase() } ?: "Gomme non déclarée")
                if (t != null && t.compound != null && !t.ageCertain) append(" (non confirmée)")
                t?.let { append(" · ${it.ageLaps} tour${if (it.ageLaps > 1) "s" else ""}"); if (!it.ageCertain) append(" (âge incertain)") }
            }
            val messages = buildList {
                if (r.recovering) add(Line("Après un arrêt : pneus en récupération, températures non interprétées", Nature.CALCUL))
                r.issues.sortedBy { it.kind.ordinal }.forEach { issue ->
                    val nature = when (issue.kind) {
                        TyreDiagnostics.IssueKind.TEMPERATURE -> Nature.MESURE
                        TyreDiagnostics.IssueKind.DEGRADATION_SUSPECTED -> Nature.HYPOTHESE
                        else -> Nature.CALCUL
                    }
                    add(Line("${issue.kind.label.replaceFirstChar { it.uppercase() }} — ${issue.text}", nature, alert = issue.kind != TyreDiagnostics.IssueKind.DEGRADATION_SUSPECTED))
                }
                if (isEmpty()) add(
                    if (r.measuredLaps == 0) Line("En attente d'un tour complet", Nature.MESURE)
                    else Line("Rien d'anormal mesuré sur ce relais", Nature.CALCUL),
                )
            }
            val details = buildList {
                add(Line(NO_WEAR, Nature.ABSENT))
                t?.let { tr ->
                    add(Line("Train : ${tr.ageLaps} tours depuis le dernier changement ${if (tr.ageCertain) "confirmé" else "NON confirmé"}", Nature.CALCUL))
                    add(Line("Gomme : ${tr.compound?.spoken ?: "non déclarée"}${if (tr.compoundDeclared) " (déclarée, GT7 ne la transmet pas)" else ""}${if (!tr.ageCertain) ", non confirmée depuis le dernier arrêt" else ""}", if (tr.compoundDeclared) Nature.DECLARATION else Nature.ABSENT))
                    val pace = tr.paceSlopeMsPerLap?.let { s -> "${if (s >= 0) "+" else "−"}${String.format(java.util.Locale.FRANCE, "%.2f", abs(s) / 1000)} s par tour" }
                    add(when {
                        tr.grip == GripLevel.INSUFFICIENT -> Line("Chronos comparables : pas encore assez de tours propres", Nature.CALCUL)
                        tr.lossDemonstrated -> Line("Perte de performance démontrée : ${tr.grip.label}${pace?.let { ", $it" } ?: ""}${tr.paceLossMs?.let { ", ${String.format(java.util.Locale.FRANCE, "%.1f", it / 1000.0)} s sur la référence" } ?: ""}", Nature.CALCUL, alert = true)
                        tr.grip == GripLevel.POSSIBLE -> Line("Chronos : ${tr.grip.label}${pace?.let { " ($it)" } ?: ""}, non démontrée", Nature.HYPOTHESE)
                        else -> Line("Chronos comparables : ${tr.grip.label}${pace?.let { " ($it)" } ?: ""}", Nature.CALCUL)
                    })
                    tr.behaviour?.let { add(Line("Comportement : $it", Nature.CALCUL)) }
                    add(Line("Autonomie : ${tr.autonomy}", when {
                        tr.autonomy.startsWith("inconnue") -> Nature.ABSENT
                        "déclarée" in tr.autonomy -> Nature.DECLARATION
                        else -> Nature.ESTIMATION
                    }))
                }
                add(Line("Roues motrices : ${r.drivenAxle.label}", Nature.CALCUL))
                r.referenceLap?.let { add(Line("Référence thermique : tour $it (pneus stabilisés)", Nature.CALCUL)) }
                    ?: add(Line("Référence thermique : pas encore (pneus en montée de température, départ ou sortie des stands)", Nature.CALCUL))
                r.wheels.forEachIndexed { i, w ->
                    add(Line(
                        "${w.label} : tour ${w.lapMeanC.fmt()} moy / ${w.lapMaxC.fmt()} max" +
                            (w.axleDeltaC?.let { ", essieu ${if (it >= 0) "+" else "−"}${abs(it).roundToInt()} °C" } ?: "") +
                            ", patinages ${w.spinEvents} (${w.spinSamples} éch.), blocages ${w.lockEvents} (${w.lockSamples} éch.) sur le relais",
                        Nature.CALCUL, alert = i in flaggedWheels,
                    ))
                }
                if (r.history.isNotEmpty()) r.history.forEach { h ->
                    add(Line("Relais précédent (tours ${h.startLap}–${h.endLap}) : ${h.meanC.joinToString(" / ") { it.fmt() }}, patinages ${h.spinEvents.sum()}, blocages ${h.lockEvents.sum()}", Nature.CALCUL))
                }
                add(Line("Confiance ${r.confidence.label}" + if (r.confidenceWhy.isNotEmpty()) " : ${r.confidenceWhy.joinToString(" ; ")}" else "", Nature.CALCUL))
            }
            return TyreBoard(
                cells, headline, messages, details, r.confidence.label,
                t?.autonomy ?: "inconnue",
            )
        }

        private fun Float?.fmt() = this?.let { "${it.roundToInt()} °C" } ?: "—"
    }
}
