package com.gt7pitwall.core.strategy

import com.gt7pitwall.core.race.SessionOutcome
import com.gt7pitwall.core.race.SessionRecord
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Bilan d'une course, calculé à partir de la session archivée : stratégie initiale et ses hypothèses, adaptations,
 * prévisions et leurs erreurs, positions, coaching. Une position gagnée n'est jamais attribuée à Pitwall : GT7 ne dit
 * pas pourquoi elle a changé.
 */
data class RaceReview(
    val sessionId: String,
    val finished: Boolean,
    /** Consommation prévue au premier plan, et d'où elle venait (MEASURED, HISTORY…). */
    val fuelPredicted: Float?,
    val fuelSource: String?,
    /** Consommation moyenne mesurée sur les tours complets sans ravitaillement. */
    val fuelActual: Float?,
    val plannedStops: List<Int>?,
    val actualStops: List<Int>,
    /** Changements de plan annoncés après le premier. */
    val planChanges: Int,
    val pitLossPredictedMs: Int?,
    /** Temps perdu à chaque arrêt : tour d'entrée + tour de sortie − deux tours propres typiques. */
    val pitLossActualMs: List<Int>,
    val gridPosition: Int?,
    val finishPosition: Int?,
    val adviceGiven: Int,
    val adviceImproved: Int,
) {
    /** Écart relatif de la consommation prévue (positif : la voiture a consommé plus que prévu). */
    val fuelErrorPct: Double?
        get() = if (fuelPredicted != null && fuelActual != null && fuelPredicted > 0f) (fuelActual - fuelPredicted) / fuelPredicted * 100.0 else null

    val pitLossErrorMs: Int?
        get() = pitLossPredictedMs?.let { p -> pitLossActualMs.takeIf { it.isNotEmpty() }?.average()?.roundToInt()?.minus(p) }

    fun lines(): List<String> = buildList {
        if (fuelPredicted != null && fuelActual != null) {
            add(
                "Consommation : prévue ${f2(fuelPredicted)} par tour (${source(fuelSource)}), mesurée ${f2(fuelActual)} : écart de " +
                    "${signed(fuelErrorPct!!)} %.",
            )
        } else if (fuelActual != null) {
            add("Consommation mesurée : ${f2(fuelActual)} par tour (aucune prévision avant la course).")
        }
        plannedStops?.let { p ->
            add(
                "Arrêts prévus au départ : " + (if (p.isEmpty()) "aucun" else "fin des tours ${p.joinToString(", ")}") +
                    " ; réalisés : " + (if (actualStops.isEmpty()) "aucun" else "tours ${actualStops.joinToString(", ")}") +
                    " ; $planChanges changement${if (planChanges > 1) "s" else ""} de plan.",
            )
        }
        if (pitLossActualMs.isNotEmpty()) {
            val actual = pitLossActualMs.average().roundToInt()
            add(
                "Temps perdu au stand : mesuré ${s1(actual)}" + (pitLossPredictedMs?.let { " pour ${s1(it)} prévus" } ?: "") + ".",
            )
        }
        if (gridPosition != null && finishPosition != null) {
            val d = gridPosition - finishPosition
            add(
                "Positions : P$gridPosition au départ, P$finishPosition à l'arrivée" + when {
                    d > 0 -> " ($d place${if (d > 1) "s" else ""} gagnée${if (d > 1) "s" else ""} ; GT7 ne dit pas pourquoi, aucun lien avec la stratégie n'est établi)."
                    d < 0 -> " (${-d} place${if (d < -1) "s" else ""} perdue${if (d < -1) "s" else ""})."
                    else -> "."
                },
            )
        }
        if (adviceGiven > 0) add("Coaching : $adviceGiven consigne${if (adviceGiven > 1) "s" else ""}, $adviceImproved suivie${if (adviceImproved > 1) "s" else ""} d'une amélioration mesurée.")
    }

    companion object {
        private fun f2(f: Float) = String.format(java.util.Locale.FRANCE, "%.2f", f)
        private fun s1(ms: Int) = String.format(java.util.Locale.FRANCE, "%.1f s", ms / 1000.0)
        private fun signed(d: Double) = (if (d >= 0) "+" else "−") + String.format(java.util.Locale.FRANCE, "%.1f", abs(d))
        private fun source(s: String?) = when (s) {
            "MEASURED" -> "mesurée"
            "HISTORY" -> "historique"
            null, "" -> "source inconnue"
            else -> s.lowercase()
        }

        fun of(r: SessionRecord, adviceGiven: Int = 0, adviceImproved: Int = 0): RaceReview {
            val first = r.plans.firstOrNull()
            val fuelLaps = r.laps.filter { !it.refuelled && !it.pitLane && (it.fuelUsed ?: 0f) > 0.01f }.mapNotNull { it.fuelUsed }
            val stopLaps = r.stops.map { it.lap }.toSet()
            val clean = r.laps.filter { it.lap > 1 && !it.refuelled && !it.pitLane && it.lap !in stopLaps && (it.lap - 1) !in stopLaps && (it.timeMs ?: 0) > 0 }
                .mapNotNull { it.timeMs }.sorted()
            val typical = clean.getOrNull(clean.size / 2)
            val losses = if (typical == null) emptyList() else r.stops.mapNotNull { s ->
                val inLap = r.laps.firstOrNull { it.lap == s.lap }?.timeMs ?: return@mapNotNull null
                val outLap = r.laps.firstOrNull { it.lap == s.lap + 1 }?.timeMs ?: return@mapNotNull null
                (inLap + outLap - 2 * typical).takeIf { it > 0 }
            }
            // Un plan qui ne fait que retirer un arrêt déjà passé n'est pas un changement d'avis.
            val changes = r.plans.zipWithNext().count { (a, b) -> b.stopLaps != a.stopLaps.filter { it >= b.lap } }
            return RaceReview(
                sessionId = r.id,
                finished = r.outcome == SessionOutcome.FINISHED,
                fuelPredicted = first?.fuelPerLap?.takeIf { it > 0f },
                fuelSource = first?.fuelSource,
                fuelActual = fuelLaps.takeIf { it.size >= 2 }?.average()?.toFloat(),
                plannedStops = first?.stopLaps,
                actualStops = r.stops.map { it.lap },
                planChanges = changes,
                pitLossPredictedMs = first?.pitLossMs,
                pitLossActualMs = losses,
                gridPosition = r.gridPosition,
                finishPosition = r.finishPosition,
                adviceGiven = adviceGiven,
                adviceImproved = adviceImproved,
            )
        }
    }
}

/**
 * Justesse des prévisions sur les courses comparables : biais et erreur moyenne de la consommation prévue avant le départ
 * (seulement quand elle venait de l'historique), erreur sur le temps perdu au stand. Sert à corriger la prochaine
 * prévision de départ, jamais une mesure de la course en cours.
 */
data class ForecastTrack(val fuelSessions: Int, val fuelBiasPct: Double?, val fuelMeanAbsPct: Double?, val pitSessions: Int, val pitBiasMs: Int?) {
    /** Correction à appliquer à une consommation tirée de l'historique : seulement si le biais est net et répété. */
    val fuelCorrection: Double?
        get() = fuelBiasPct?.takeIf { fuelSessions >= MIN_SESSIONS && abs(it) >= MIN_BIAS_PCT }?.coerceIn(-MAX_CORRECTION_PCT, MAX_CORRECTION_PCT)

    fun text(): String? {
        val parts = mutableListOf<String>()
        if (fuelSessions > 0 && fuelMeanAbsPct != null) {
            parts += "mes prévisions de consommation se sont trompées de ${String.format(java.util.Locale.FRANCE, "%.1f", fuelMeanAbsPct)} % en moyenne sur $fuelSessions course${if (fuelSessions > 1) "s" else ""}"
        }
        if (pitSessions > 0 && pitBiasMs != null) {
            parts += "le temps perdu au stand a été ${if (pitBiasMs >= 0) "sous-estimé" else "surestimé"} de ${String.format(java.util.Locale.FRANCE, "%.1f", abs(pitBiasMs) / 1000.0)} s en moyenne"
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ; ")?.replaceFirstChar { it.uppercase() }?.plus(".")
    }

    companion object {
        const val MIN_SESSIONS = 2
        const val MIN_BIAS_PCT = 2.0
        const val MAX_CORRECTION_PCT = 8.0
        val EMPTY = ForecastTrack(0, null, null, 0, null)

        fun of(reviews: List<RaceReview>): ForecastTrack {
            val fuel = reviews.filter { it.fuelSource == "HISTORY" }.mapNotNull { it.fuelErrorPct }
            val pit = reviews.mapNotNull { it.pitLossErrorMs }
            return ForecastTrack(
                fuelSessions = fuel.size,
                fuelBiasPct = fuel.takeIf { it.isNotEmpty() }?.average(),
                fuelMeanAbsPct = fuel.takeIf { it.isNotEmpty() }?.map { abs(it) }?.average(),
                pitSessions = pit.size,
                pitBiasMs = pit.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
            )
        }
    }
}
