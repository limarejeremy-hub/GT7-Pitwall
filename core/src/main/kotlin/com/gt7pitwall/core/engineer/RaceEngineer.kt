package com.gt7pitwall.core.engineer

import com.gt7pitwall.core.analysis.LapAnalyzer
import com.gt7pitwall.core.engineer.FrenchFormat.decimal
import com.gt7pitwall.core.engineer.FrenchFormat.deltaSpeech
import com.gt7pitwall.core.engineer.FrenchFormat.lapTimeSpeech
import com.gt7pitwall.core.engineer.FrenchFormat.laps
import com.gt7pitwall.core.radio.DriverQuery
import com.gt7pitwall.core.session.SessionEvent
import com.gt7pitwall.core.session.SessionSnapshot
import com.gt7pitwall.core.strategy.FuelAdvice
import com.gt7pitwall.core.strategy.FuelReport
import kotlin.math.roundToInt

enum class Priority { INFO, NORMAL, HIGH, CRITICAL }

enum class MessageCategory { RADIO, LAP, FUEL, ALERT, ANSWER }

data class EngineerMessage(
    val text: String,
    val priority: Priority,
    val category: MessageCategory,
    val timestampMs: Long = System.currentTimeMillis(),
)

/**
 * Ingénieur de course : transforme les événements de session en messages radio en français.
 * Chaque phrase s'appuie uniquement sur des valeurs transmises par GT7 ou calculées à partir d'elles.
 */
class RaceEngineer {
    private var warnedTwoLaps = false
    private var warnedOneLap = false
    private var announcedNoConsumption = false

    fun onUpdate(events: List<SessionEvent>, snapshot: SessionSnapshot, fuel: FuelReport?): List<EngineerMessage> {
        val out = mutableListOf<EngineerMessage>()
        val lapCompletedNow = events.any { it is SessionEvent.LapCompleted }
        val finalLapNext = events.any { it is SessionEvent.FinalLapStarted }
        val finishedNow = events.any { it is SessionEvent.RaceFinished }
        for (event in events) {
            when (event) {
                SessionEvent.FirstData ->
                    out += msg("Radio check. Je reçois la télémétrie, je suis avec toi.", Priority.NORMAL, MessageCategory.RADIO)

                SessionEvent.SessionReset -> {
                    resetAlerts()
                    announcedNoConsumption = false
                    out += msg("Nouvelle session détectée, je repars de zéro.", Priority.NORMAL, MessageCategory.RADIO)
                }

                SessionEvent.RefuelDetected -> {
                    resetAlerts()
                    out += msg("Ravitaillement détecté, je recalcule la stratégie au prochain tour.", Priority.NORMAL, MessageCategory.FUEL)
                }

                // Annoncé dans le message du tour qui vient de se terminer, sinon seul.
                is SessionEvent.FinalLapStarted -> if (!lapCompletedNow) {
                    out += msg("Dernier tour ! Donne tout.", Priority.HIGH, MessageCategory.LAP)
                }

                is SessionEvent.RaceFinished -> out += finishMessage(snapshot)

                is SessionEvent.GridPosition ->
                    out += msg(
                        "Tu pars en position ${event.position} sur ${event.cars}. Bonne course !",
                        Priority.NORMAL,
                        MessageCategory.RADIO,
                    )

                is SessionEvent.LapCompleted ->
                    out += lapMessage(event, snapshot, fuel, finalLapNext, finishedNow)
            }
        }
        fuelAlert(fuel)?.let { out += it }
        return out
    }

    /** Réponse à une question du pilote (boutons de l'écran, et demain la voix). */
    fun answer(query: DriverQuery, snapshot: SessionSnapshot, fuel: FuelReport?): EngineerMessage {
        val p = snapshot.packet
        val text = when (query) {
            DriverQuery.FUEL -> when {
                p == null -> NO_DATA
                fuel == null -> NO_DATA
                else -> fuelSentence(fuel, verbose = true)
            }

            DriverQuery.LAST_LAP -> {
                val ms = p?.lastLapMs?.takeIf { it > 0 }
                if (ms == null) "Pas encore de tour chronométré." else "Dernier tour : ${lapTimeSpeech(ms)}."
            }

            DriverQuery.BEST_LAP -> {
                val ms = p?.bestLapMs?.takeIf { it > 0 }
                if (ms == null) "Pas encore de meilleur tour." else "Meilleur tour : ${lapTimeSpeech(ms)}."
            }

            DriverQuery.RACE_STATUS -> when {
                p == null -> NO_DATA
                p.totalLaps > 0 && p.currentLap > p.totalLaps -> "Course terminée."
                p.totalLaps > 0 && p.currentLap >= 1 -> {
                    val left = p.totalLaps - p.currentLap
                    "Tour ${p.currentLap} sur ${p.totalLaps}. " +
                        if (left == 0) "C'est le dernier tour." else "Encore $left ${if (left > 1) "tours" else "tour"} après celui-ci."
                }
                p.currentLap >= 1 -> "Tour ${p.currentLap}. GT7 n'indique pas de nombre de tours pour cette épreuve."
                else -> "En attente du départ."
            }

            DriverQuery.PACE -> {
                val stats = LapAnalyzer.analyze(snapshot.laps)
                val avg = stats.recentAverageMs
                val std = stats.recentStdDevMs
                if (avg == null || std == null) {
                    "Il me faut au moins deux tours chronométrés pour analyser le rythme."
                } else {
                    val best = stats.bestMs?.let { " Meilleur : ${lapTimeSpeech(it)}." } ?: ""
                    "Moyenne des derniers tours : ${lapTimeSpeech(avg)}, régularité à plus ou moins ${deltaSpeech(std)}.$best"
                }
            }
        }
        return msg(text, Priority.HIGH, MessageCategory.ANSWER)
    }

    private fun lapMessage(
        e: SessionEvent.LapCompleted,
        snapshot: SessionSnapshot,
        fuel: FuelReport?,
        finalLapNext: Boolean,
        finished: Boolean,
    ): EngineerMessage {
        val lap = e.lap
        val sb = StringBuilder()
        val time = lap.timeMs
        if (time != null) {
            sb.append("Tour ${lap.lapNumber} : ${lapTimeSpeech(time)}. ")
            val best = e.previousBestMs
            when {
                best == null -> Unit
                time < best -> sb.append("Meilleur tour, ${deltaSpeech(best - time)} de mieux ! ")
                time == best -> sb.append("Égalité avec ton meilleur. ")
                else -> sb.append("Plus ${deltaSpeech(time - best)} sur ton meilleur. ")
            }
        } else {
            sb.append("Tour ${lap.lapNumber} bouclé. ")
        }
        if (finished) return msg(sb.toString().trim(), Priority.NORMAL, MessageCategory.LAP)

        val totalLaps = snapshot.packet?.totalLaps ?: 0
        when {
            finalLapNext -> sb.append("Dernier tour, donne tout ! ")
            totalLaps > 0 && totalLaps - lap.lapNumber > 1 -> sb.append("Encore ${totalLaps - lap.lapNumber} tours. ")
        }

        // Point rythme tous les 3 tours, à partir des chronos réels.
        val stats = LapAnalyzer.analyze(snapshot.laps)
        if (!finalLapNext && lap.lapNumber % 3 == 0 && stats.recentAverageMs != null && stats.recentStdDevMs != null) {
            sb.append("Rythme moyen ${lapTimeSpeech(stats.recentAverageMs)}, régularité à plus ou moins ${deltaSpeech(stats.recentStdDevMs)}. ")
        }

        if (fuel != null) {
            when (fuel.advice) {
                FuelAdvice.Measuring -> if (lap.fuelUsed == null) sb.append("Je mesure la consommation sur le prochain tour complet.")
                FuelAdvice.NoConsumption -> if (!announcedNoConsumption) {
                    announcedNoConsumption = true
                    sb.append("Le niveau d'essence ne baisse pas : pas de consommation sur cette épreuve.")
                }
                FuelAdvice.NotAvailable, FuelAdvice.RaceFinished -> Unit
                else -> sb.append(fuelSentence(fuel, verbose = false))
            }
        }
        val priority = if (fuel?.advice is FuelAdvice.PitRequired || finalLapNext) Priority.HIGH else Priority.NORMAL
        return msg(sb.toString().trim(), priority, MessageCategory.LAP)
    }

    private fun finishMessage(snapshot: SessionSnapshot): EngineerMessage {
        val p = snapshot.packet
        val best = p?.bestLapMs?.takeIf { it > 0 } ?: snapshot.bestRecordedLapMs
        val bestLap = snapshot.laps.firstOrNull { it.timeMs != null && it.timeMs == best }?.lapNumber
        val text = buildString {
            append("Drapeau à damier, course terminée ! ")
            if (best != null) {
                append("Meilleur tour : ${lapTimeSpeech(best)}")
                if (bestLap != null) append(", au tour $bestLap")
                append(". ")
            }
            append("Beau boulot.")
        }
        return msg(text.trim(), Priority.HIGH, MessageCategory.RADIO)
    }

    private fun fuelSentence(f: FuelReport, verbose: Boolean): String {
        val range = f.lapsOfFuel?.let { "Carburant pour ${laps(it)}. " } ?: ""
        val perLap = if (verbose && f.perLap != null) {
            "Consommation ${decimal(f.perLap)} par tour sur ${f.samples} ${if (f.samples > 1) "tours" else "tour"}. "
        } else {
            ""
        }
        val advice = when (val a = f.advice) {
            FuelAdvice.Measuring -> "Pas encore de tour complet mesuré, je calcule la consommation."
            FuelAdvice.NoConsumption -> "Le niveau d'essence ne baisse pas : pas de consommation sur cette épreuve."
            FuelAdvice.NotAvailable -> "GT7 ne transmet pas de capacité de réservoir pour cette voiture."
            FuelAdvice.RaceFinished -> "Course terminée."
            is FuelAdvice.RangeOnly -> "Je ne connais pas la distance restante, seulement l'autonomie."
            is FuelAdvice.Comfortable -> "On finit la course, marge de ${laps(a.marginLaps)}."
            is FuelAdvice.Tight ->
                "Ça va être juste : vise ${decimal(a.targetPerLap)} par tour."
            is FuelAdvice.PitRequired -> buildString {
                append("Il faudra ravitailler : rentre au plus tard à la fin du tour ${a.latestPitLap}, ")
                append("ajoute environ ${a.fuelToAdd.roundToInt()}.")
                if (a.stopsNeeded > 1) append(" Il faudra ${a.stopsNeeded} arrêts.")
                if (a.savingNeeded in 0f..0.10f) {
                    append(" Sinon, économise ${(a.savingNeeded * 100).roundToInt()} pour cent par tour pour aller au bout.")
                }
            }
        }
        return (range + perLap + advice).trim()
    }

    /** Alertes de bas niveau, une seule fois par seuil (réarmées après ravitaillement). */
    private fun fuelAlert(f: FuelReport?): EngineerMessage? {
        val lapsOfFuel = f?.lapsOfFuel ?: return null
        val relevant = f.advice is FuelAdvice.PitRequired || f.advice is FuelAdvice.RangeOnly
        if (!relevant) return null
        return when {
            lapsOfFuel < 1f && !warnedOneLap -> {
                warnedOneLap = true
                warnedTwoLaps = true
                msg("Carburant critique : moins d'un tour !", Priority.CRITICAL, MessageCategory.ALERT)
            }
            lapsOfFuel < 2f && !warnedTwoLaps -> {
                warnedTwoLaps = true
                msg("Attention, carburant pour moins de deux tours.", Priority.HIGH, MessageCategory.ALERT)
            }
            else -> null
        }
    }

    private fun resetAlerts() {
        warnedTwoLaps = false
        warnedOneLap = false
    }

    private fun msg(text: String, priority: Priority, category: MessageCategory) =
        EngineerMessage(text, priority, category)

    companion object {
        const val NO_DATA = "Je n'ai pas encore de télémétrie."
    }
}
