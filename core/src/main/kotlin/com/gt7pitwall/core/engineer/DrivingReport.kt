package com.gt7pitwall.core.engineer

import com.gt7pitwall.core.session.LapRecord
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Bilan de pilotage de fin de course, uniquement à partir des mesures de la session. Il dit aussi quand les données ne
 * suffisent pas à juger : une course silencieuse n'est pas la preuve d'un pilotage parfait.
 */
data class DrivingReport(
    val lines: List<String>,
    /** Synthèse courte pour la radio (deux phrases au plus). */
    val radio: String,
    /** Assez de tours propres pour juger le pilotage. */
    val enoughData: Boolean,
) {
    companion object {
        /**
         * [stopLaps] : tours d'arrêt ; [tyreChanges] : montes confirmées (tour, gomme dite) ; [grid] et [finish] : places
         * mesurées ; [cornerFindings] : constats du profil par virage, s'il y en a.
         */
        fun of(
            laps: List<LapRecord>,
            stopLaps: List<Int>,
            tyreChanges: List<Pair<Int, String?>>,
            grid: Int?,
            finish: Int?,
            cornerFindings: List<String>,
            adviceGiven: Int,
            adviceImproved: Int,
        ): DrivingReport {
            val pitLaps = stopLaps.flatMap { listOf(it, it + 1) }.toSet()
            val clean = DrivingAnalysis.cleanLaps(laps, pitLaps)
            val timed = laps.filter { (it.timeMs ?: 0) > 0 }
            val best = timed.minByOrNull { it.timeMs!! }
            val lines = mutableListOf<String>()
            if (grid != null && finish != null) {
                lines += "Places : P$grid au départ, P$finish à l'arrivée (${signedPlaces(grid - finish)})."
            } else if (finish != null) {
                lines += "Arrivée : P$finish."
            }
            best?.let { lines += "Meilleur tour : ${t(it.timeMs!!.toDouble())} (tour ${it.lapNumber})." }
            val enough = clean.size >= MIN_CLEAN
            if (!enough) {
                lines += "Pilotage non jugé : ${clean.size} tour${if (clean.size > 1) "s" else ""} propre${if (clean.size > 1) "s" else ""} seulement (il en faut $MIN_CLEAN ; départ, arrêts et tours perturbés écartés)."
            }
            val times = clean.map { it.timeMs!!.toDouble() }
            val mean = times.takeIf { it.isNotEmpty() }?.average()
            val spread = mean?.let { m -> sqrt(times.sumOf { (it - m) * (it - m) } / times.size) }
            if (enough && mean != null && spread != null) {
                lines += "Rythme exploitable : ${t(mean)} de moyenne sur ${clean.size} tours propres, régularité à plus ou moins ${s(spread)}."
                DrivingAnalysis.theoreticalMs(clean)?.takeIf { best != null && it < best.timeMs!! }?.let {
                    lines += "Tour théorique avec tes meilleurs secteurs : ${t(it.toDouble())} (indicatif, jamais réalisé tel quel)."
                }
            }
            val losses = DrivingAnalysis.sectorLosses(clean)
            if (losses != null) {
                val worst = losses.withIndex().maxByOrNull { it.value }!!
                val bestSector = losses.withIndex().minByOrNull { it.value }!!
                lines += "Secteurs : le plus régulier est le ${bestSector.index + 1} (${s(bestSector.value)} perdus en moyenne), le moins bon le ${worst.index + 1} (${s(worst.value)})."
            } else if (enough) {
                lines += "Secteurs : pas assez de tours suivis en entier pour les comparer."
            }
            // Avant / après le (premier) arrêt.
            stopLaps.firstOrNull()?.let { stop ->
                val before = clean.filter { it.lapNumber < stop }.map { it.timeMs!!.toDouble() }
                val after = clean.filter { it.lapNumber > stop + 1 }.map { it.timeMs!!.toDouble() }
                if (before.size >= 2 && after.size >= 2) {
                    val d = after.average() - before.average()
                    lines += "Après l'arrêt du tour $stop : ${if (d <= 0) "${s(-d)} plus rapide" else "${s(d)} plus lent"} en moyenne qu'avant."
                }
            }
            val counted = clean.filter { it.lockups != null }
            if (counted.size >= 4) {
                val h = counted.size / 2
                val first = counted.take(h).map { it.lockups!! + (it.wheelspins ?: 0) }.average()
                val last = counted.drop(h).map { it.lockups!! + (it.wheelspins ?: 0) }.average()
                val worstLap = counted.maxByOrNull { it.lockups!! + (it.wheelspins ?: 0) }!!
                lines += "Blocages et patinages : ${n(first)} par tour en première partie, ${n(last)} en fin de course ; pic au tour ${worstLap.lapNumber} (${worstLap.lockups} blocages, ${worstLap.wheelspins ?: 0} patinages)."
            }
            val temps = clean.mapNotNull { l -> l.wheelTemps?.let { l.lapNumber to listOf(it.fl, it.fr, it.rl, it.rr) } }
            if (temps.size >= 2) {
                val (lastLap, lastT) = temps.last()
                val hottest = lastT.withIndex().maxByOrNull { it.value }!!
                val wheel = listOf("avant gauche", "avant droit", "arrière gauche", "arrière droit")[hottest.index]
                lines += "Températures : ${temps.first().second.average().roundToInt()} °C de moyenne au début, ${lastT.average().roundToInt()} °C au tour $lastLap ; pneu le plus chaud : $wheel (${hottest.value.roundToInt()} °C)."
            }
            if (tyreChanges.isNotEmpty()) {
                lines += "Pneus : " + tyreChanges.joinToString(", ") { (lap, c) -> "changés au tour $lap (${c ?: "gomme non précisée"})" } + "."
            }
            if (adviceGiven > 0) lines += "Conseils de virage : $adviceGiven donnés, $adviceImproved suivis d'une amélioration mesurée."
            // Forces, faiblesse, une seule priorité : seulement avec assez de données.
            var priority: String? = null
            if (enough && spread != null) {
                if (spread <= STRONG_SPREAD_MS) lines += "Point fort mesuré : la régularité."
                val worst = losses?.withIndex()?.maxByOrNull { it.value }
                priority = when {
                    cornerFindings.isNotEmpty() -> cornerFindings.first()
                    worst != null && worst.value >= 200 -> "le secteur ${worst.index + 1}, où tu laisses ${s(worst.value)} par tour"
                    spread > 600 -> "la régularité (plus ou moins ${s(spread)})"
                    else -> null
                }
                lines += priority?.let { "Priorité pour la prochaine fois : $it." } ?: "Pas de faiblesse nette mesurée dans cette course."
            }
            val radio = buildString {
                append("Bilan : ")
                if (grid != null && finish != null) append("P$grid à P$finish. ")
                when {
                    !enough -> append("pas assez de tours propres pour juger ton pilotage.")
                    priority != null -> append("Priorité : $priority.")
                    spread != null -> append("course régulière, à plus ou moins ${s(spread)}.")
                }
            }.replace("Bilan : P", "Bilan : de P")
            return DrivingReport(lines, radio.trim(), enough)
        }

        const val MIN_CLEAN = 4
        const val STRONG_SPREAD_MS = 400.0

        private fun signedPlaces(d: Int) = when {
            d > 0 -> "$d place${if (d > 1) "s" else ""} gagnée${if (d > 1) "s" else ""}"
            d < 0 -> "${-d} place${if (d < -1) "s" else ""} perdue${if (d < -1) "s" else ""}"
            else -> "même place"
        }

        private fun t(ms: Double): String {
            val total = ms.roundToInt()
            return "${total / 60_000}:${String.format(java.util.Locale.FRANCE, "%06.3f", (total % 60_000) / 1000.0)}"
        }

        private fun s(ms: Double): String = String.format(java.util.Locale.FRANCE, "%.1f s", ms / 1000)
        private fun n(x: Double): String = String.format(java.util.Locale.FRANCE, "%.1f", x)
    }
}
