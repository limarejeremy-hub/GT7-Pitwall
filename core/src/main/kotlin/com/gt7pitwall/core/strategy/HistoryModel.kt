package com.gt7pitwall.core.strategy

import com.gt7pitwall.core.race.Comparability
import com.gt7pitwall.core.race.Compatibility
import com.gt7pitwall.core.race.HistoryRelation
import com.gt7pitwall.core.race.SessionContext
import com.gt7pitwall.core.race.SessionRecord
import kotlin.math.abs
import kotlin.math.sqrt

/** Niveau de confiance d'une estimation tirée de l'historique, explicable au pilote (pas de pourcentage arbitraire). */
enum class Confidence(val label: String) {
    INSUFFICIENT("historique insuffisant"),
    PROVISIONAL("estimation provisoire"),
    FAIRLY_RELIABLE("estimation relativement fiable"),
}

/** Une grandeur apprise : moyenne, dispersion, et sur quoi elle repose. */
data class Learned(
    val mean: Double,
    /** Écart-type des mesures retenues. */
    val spread: Double,
    val samples: Int,
    val sessions: Int,
    val confidence: Confidence,
) {
    /** Dispersion relative (0,03 = 3 %). */
    val relativeSpread: Double get() = if (mean != 0.0) spread / abs(mean) else 0.0

    /** « 3 courses, 27 tours exploitables, dispersion 2 % ». */
    fun basis(unit: String = "tours"): String =
        "$sessions course${if (sessions > 1) "s" else ""}, $samples $unit exploitables, dispersion ${(relativeSpread * 100).let { Math.round(it) }} %"
}

/**
 * Ce que Pitwall apprend des courses réellement comparables (même épreuve, voiture, circuit, multiplicateurs ; même
 * version de GT7 si elle est déclarée). Les sessions incompatibles sont écartées ; dans les sessions retenues, seuls les
 * tours propres comptent : ni ravitaillement, ni tour d'arrêt ou de sortie des stands, ni premier tour, ni incident
 * déclaré, ni valeur aberrante. Aucune usure de pneus n'est inventée : seule la pente des chronos au fil d'un relais,
 * mesurée, est apprise.
 */
data class HistoryModel(
    /** Consommation par tour (unités GT7). */
    val fuelPerLap: Learned?,
    /** Rythme typique (ms). */
    val lapTimeMs: Learned?,
    /** Évolution des chronos par tour de relais (ms par tour, mesurée), si elle est significative. */
    val paceSlopeMsPerLap: Learned?,
    /** Temps perdu par arrêt (ms), mesuré sur les tours d'arrêt et de sortie, tous contenus confondus. */
    val pitLossMs: Learned?,
    /** Erreur moyenne des prévisions de consommation passées (0,04 = 4 %), sur les courses où elle est mesurable. */
    val fuelForecastError: Learned?,
    val sessionsUsed: Int,
    val sessionsExcluded: Int,
    /**
     * Sessions d'un autre type (entraînement hors ligne, type non précisé) retenues parce que tout le reste est
     * identique : BoP et réglages non vérifiés, la confiance ne dépasse pas « provisoire ».
     */
    val relatedUsed: Int = 0,
    /**
     * Temps perdu par arrêt selon son contenu (ravitaillement seul, pneus seuls, les deux, rien), seulement pour les arrêts
     * dont le pilote a dit ce qui a été fait aux pneus. Chaque valeur comprend déjà les interventions de ce type d'arrêt.
     */
    val pitLossByKind: Map<StopKind, Learned> = emptyMap(),
    /** Arrêts dont on ne sait pas ce qui a été fait aux pneus (sessions anciennes, pas de réponse) : contenu inconnu. */
    val pitLossUnknownContent: Learned? = null,
    /**
     * Sessions dont le règlement est contredit par GT7 (issue #36-D2) : seuls leur rythme et leur consommation servent ;
     * arrêts, relais et prévisions n'en sont pas appris. Raison lisible pour chacune.
     */
    val quarantined: List<String> = emptyList(),
) {
    companion object {
        val EMPTY = HistoryModel(null, null, null, null, null, 0, 0)

        /** Mots qui signalent un incident déclaré par le pilote sur un tour (tour écarté). */
        private val INCIDENT = Regex("(?i)accident|accroch|t[eê]te.?[aà].?queue|sortie de piste|touch[eé]|crash|p[eé]nalit")

        fun learn(context: SessionContext, sessions: Collection<SessionRecord>, excludeId: String? = null): HistoryModel {
            val pool = sessions.filter { it.id != excludeId }
            val same = pool.filter { HistoryRelation.of(context.profile.id, it.context.profile.id) == HistoryRelation.SAME }
            val related = pool.filter { HistoryRelation.of(context.profile.id, it.context.profile.id) == HistoryRelation.RELATED }
            val candidates = same + related
            // Un entraînement ne compte que s'il reproduit exactement l'épreuve (règlement, voiture, circuit, multiplicateurs).
            val usable = same.filter { Compatibility.compare(context, it.context).first != Comparability.INCOMPATIBLE } +
                related.filter { Compatibility.compare(context, it.context).first == Comparability.SAME_EVENT && it.rulesQuarantine == null }
            if (usable.isEmpty()) return EMPTY.copy(sessionsExcluded = candidates.size)
            val relatedIds = usable.filter { it in related }.map { it.id }.toSet()

            val fuel = mutableListOf<Pair<String, Double>>()
            val pace = mutableListOf<Pair<String, Double>>()
            val stintPace = mutableListOf<Triple<String, Int, Double>>() // session, âge dans le relais, chrono
            val pitLoss = mutableListOf<Pair<String, Double>>()
            val pitByKind = mutableMapOf<StopKind, MutableList<Pair<String, Double>>>()
            val pitUnknown = mutableListOf<Pair<String, Double>>()
            val forecast = mutableListOf<Pair<String, Double>>()

            val quarantined = mutableListOf<String>()
            for (s in usable) {
                val quarantine = s.rulesQuarantine
                if (quarantine != null) quarantined += "session ${s.id} : $quarantine"
                val stopLaps = s.stops.map { it.lap }.toSet()
                val incidents = s.notes.filter { INCIDENT.containsMatchIn(it.text) }.map { it.lap }.toSet()
                val excluded = { lap: Int -> lap == 1 || lap in stopLaps || (lap - 1) in stopLaps || lap in incidents }
                val clean = s.laps.filter { !it.refuelled && !it.pitLane && !excluded(it.lap) }
                clean.mapNotNull { it.fuelUsed?.toDouble() }.filter { it > 0.01 }.forEach { fuel += s.id to it }
                // Une sortie de piste mesurée (format C) fausse le rythme, pas la consommation.
                clean.filter { (it.offTrackEvents ?: 0) == 0 }.mapNotNull { it.timeMs?.toDouble() }.forEach { pace += s.id to it }
                // Règlement contredit : rythme et consommation seulement (ni relais, ni arrêts, ni prévision).
                if (quarantine != null) continue
                // Âge des pneus : depuis le départ ou le dernier changement de pneus déclaré, jamais depuis un simple
                // ravitaillement. Tours au relais pneus incertain (arrêt sans réponse sur les pneus) écartés.
                for (l in clean) {
                    val t = l.timeMs ?: continue
                    val age = tyreAge(s, l) ?: continue
                    stintPace += Triple(s.id, age, t.toDouble())
                }
                // Perte par arrêt : tour d'arrêt + tour suivant, moins deux tours typiques de cette session.
                val typical = median(clean.mapNotNull { it.timeMs?.toDouble() })
                if (typical != null) {
                    for (stop in s.stops) {
                        val inLap = s.laps.firstOrNull { it.lap == stop.lap }?.timeMs
                        val outLap = s.laps.firstOrNull { it.lap == stop.lap + 1 }?.timeMs
                        if (inLap != null && outLap != null) {
                            val loss = inLap + outLap - 2 * typical
                            pitLoss += s.id to loss
                            // Contenu connu seulement si le pilote a dit ce qu'il a fait des pneus.
                            if (stop.tyres == TyreService.UNKNOWN) {
                                pitUnknown += s.id to loss
                            } else {
                                val fuelled = (stop.fuelAdded ?: 0f) > 0f || stop.evidence == StopEvidence.REFUEL
                                val kind = StopKind.of(fuelled, stop.tyres == TyreService.CHANGED)
                                pitByKind.getOrPut(kind) { mutableListOf() } += s.id to loss
                            }
                        }
                    }
                }
                // Erreur de la prévision de départ, mesurée sur les tours propres de cette course.
                val planned = s.plans.firstOrNull { it.fuelPerLap != null && it.fuelSource != DataSource.MEASURED.name }?.fuelPerLap
                val actual = clean.mapNotNull { it.fuelUsed?.toDouble() }.filter { it > 0.01 }.takeIf { it.size >= 2 }?.average()
                if (planned != null && actual != null && actual > 0) forecast += s.id to abs(planned - actual) / actual
            }

            fun cap(l: Learned?, ids: Collection<String>): Learned? =
                if (l != null && l.confidence == Confidence.FAIRLY_RELIABLE && ids.any { it in relatedIds }) l.copy(confidence = Confidence.PROVISIONAL) else l
            val fuelV = withoutOutliers(fuel)
            val paceV = withoutOutliers(pace, relative = 0.07)
            val fuelL = cap(learned(fuelV), fuelV.map { it.first })
            val paceL = cap(learned(paceV), paceV.map { it.first })
            val slope = cap(slope(stintPace, paceL?.mean), stintPace.map { it.first })
            val pitV = pitLoss.filter { it.second > 0 }
            val pitL = cap(learned(pitV, minSamples = 1), pitV.map { it.first })
            val forecastL = cap(learned(forecast, minSamples = 1), forecast.map { it.first })
            val byKind = pitByKind.mapNotNull { (k, v) -> cap(learned(v.filter { it.second > 0 }, minSamples = 1), v.map { it.first })?.let { k to it } }.toMap()
            val unknownV = pitUnknown.filter { it.second > 0 }
            val unknownL = cap(learned(unknownV, minSamples = 1), unknownV.map { it.first })
            return HistoryModel(fuelL, paceL, slope, pitL, forecastL, usable.size, candidates.size - usable.size, relatedIds.size, byKind, unknownL, quarantined)
        }

        /**
         * Âge du train de pneus au tour [l] : enregistré avec le tour, sinon (sessions anciennes) depuis le dernier
         * changement déclaré ; null si un arrêt sans réponse sur les pneus le rend incertain.
         */
        fun tyreAge(s: SessionRecord, l: com.gt7pitwall.core.race.LapEntry): Int? {
            l.tyreAge?.let { return if (l.tyreAgeCertain) it else null }
            val since = s.tyres.filter { it.lap <= l.lap }.maxOfOrNull { it.lap } ?: 1
            val unknown = s.stops.any { it.tyres == TyreService.UNKNOWN && it.lap >= since - 1 && it.lap < l.lap }
            return if (unknown) null else l.lap - since
        }

        /** Mesures aberrantes écartées : au-delà de 3 écarts absolus médians (ou de [relative] autour de la médiane). */
        private fun withoutOutliers(values: List<Pair<String, Double>>, relative: Double? = null): List<Pair<String, Double>> {
            val med = median(values.map { it.second }) ?: return values
            val mad = median(values.map { abs(it.second - med) }) ?: 0.0
            val limit = maxOf(3 * 1.4826 * mad, relative?.let { med * it } ?: 0.0, med * 0.02)
            return values.filter { abs(it.second - med) <= limit }
        }

        private fun learned(values: List<Pair<String, Double>>, minSamples: Int = 3): Learned? {
            if (values.size < minSamples) return null
            val xs = values.map { it.second }
            val mean = xs.average()
            val sd = if (xs.size > 1) sqrt(xs.sumOf { (it - mean) * (it - mean) } / (xs.size - 1)) else 0.0
            val sessions = values.map { it.first }.distinct().size
            return Learned(mean, sd, xs.size, sessions, confidence(sessions, xs.size, if (mean != 0.0) sd / abs(mean) else 0.0))
        }

        /**
         * Confiance : une course ne vaut pas dix. Insuffisant sous 3 mesures ; relativement fiable à partir de 3 courses,
         * 15 mesures et une dispersion de 5 % au plus ; provisoire entre les deux.
         */
        fun confidence(sessions: Int, samples: Int, relativeSpread: Double): Confidence = when {
            samples < 3 -> Confidence.INSUFFICIENT
            sessions >= 3 && samples >= 15 && relativeSpread <= 0.05 -> Confidence.FAIRLY_RELIABLE
            else -> Confidence.PROVISIONAL
        }

        /**
         * Pente des chronos selon l'âge dans le relais (régression linéaire, chronos aberrants exclus). Retenue seulement
         * si elle dépasse deux fois son incertitude et vient d'au moins deux courses : sinon, aucune dégradation n'est
         * supposée.
         */
        private fun slope(points: List<Triple<String, Int, Double>>, typical: Double?): Learned? {
            if (typical == null) return null
            val pts = points.filter { abs(it.third - typical) <= typical * 0.07 }
            val sessions = pts.map { it.first }.distinct().size
            if (pts.size < 8 || sessions < 2) return null
            val mx = pts.map { it.second.toDouble() }.average()
            val my = pts.map { it.third }.average()
            val sxx = pts.sumOf { (it.second - mx) * (it.second - mx) }
            if (sxx <= 0.0) return null
            val b = pts.sumOf { (it.second - mx) * (it.third - my) } / sxx
            val residual = pts.sumOf { val e = it.third - (my + b * (it.second - mx)); e * e } / (pts.size - 2)
            val se = sqrt(residual / sxx)
            if (abs(b) < 2 * se) return null
            return Learned(b, se, pts.size, sessions, confidence(sessions, pts.size, if (b != 0.0) se / abs(b) else 1.0))
        }

        private fun median(xs: List<Double>): Double? {
            if (xs.isEmpty()) return null
            val s = xs.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        }
    }
}
