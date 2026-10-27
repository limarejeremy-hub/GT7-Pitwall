package com.gt7pitwall.core.session

/**
 * Tour affiché à l'écran. Après la ligne d'arrivée, GT7 passe au tour suivant (course du 27/10 : « Tour 6 sur 5 ») :
 * la course est alors terminée et l'écran le dit, avec le dernier tour couru (« 5/5 »), jamais un tour inexistant.
 */
data class LapDisplay(val value: String, val hint: String, val finished: Boolean) {
    /** Une ligne : « 3 / 5 », « Course terminée — 5/5 », « Avant départ ». */
    val line: String get() = when {
        finished -> "$hint — $value"
        value == "—" -> hint.replaceFirstChar { it.uppercase() }
        hint.startsWith("sur ") -> "$value / ${hint.removePrefix("sur ")}"
        else -> value
    }

    companion object {
        /** [currentLap], [totalLaps] : tels que GT7 les envoie ; [raceFinished] : arrivée détectée par Pitwall. */
        fun of(currentLap: Int?, totalLaps: Int?, raceFinished: Boolean = false): LapDisplay {
            val total = totalLaps?.takeIf { it > 0 }
            val lap = currentLap?.takeIf { it > 0 }
            if (total != null && ((lap != null && lap > total) || raceFinished)) {
                return LapDisplay("$total/$total", "Course terminée", finished = true)
            }
            if (lap == null) return LapDisplay("—", "avant départ", finished = false)
            return LapDisplay(lap.toString(), total?.let { "sur $it" } ?: "", finished = false)
        }
    }
}
