package com.gt7pitwall.core.engineer

import com.gt7pitwall.core.analysis.LapAnalyzer
import com.gt7pitwall.core.analysis.SectorComparison
import com.gt7pitwall.core.engineer.FrenchFormat.decimal
import com.gt7pitwall.core.engineer.FrenchFormat.deltaSpeech
import com.gt7pitwall.core.engineer.FrenchFormat.lapTimeSpeech
import com.gt7pitwall.core.engineer.FrenchFormat.laps
import com.gt7pitwall.core.radio.DriverCommand
import com.gt7pitwall.core.radio.DriverQuery
import com.gt7pitwall.core.session.SessionEvent
import com.gt7pitwall.core.session.SessionSnapshot
import com.gt7pitwall.core.strategy.FuelAdvice
import com.gt7pitwall.core.strategy.FuelReport
import com.gt7pitwall.core.strategy.RacePlan
import kotlin.math.roundToInt

enum class Priority { INFO, NORMAL, HIGH, CRITICAL }

enum class MessageCategory { RADIO, LAP, FUEL, ALERT, ANSWER, DRIVER }

data class EngineerMessage(
    val text: String,
    val priority: Priority,
    val category: MessageCategory,
    val timestampMs: Long = System.currentTimeMillis(),
    /** L'ingénieur pose une question : la radio écoute la réponse sans mot de réveil. */
    val expectsReply: Boolean = false,
)

/**
 * Ingénieur de course : transforme les événements de session en messages radio en français.
 * Chaque phrase s'appuie uniquement sur des valeurs transmises par GT7 ou calculées à partir d'elles.
 */
class RaceEngineer {
    private var warnedTwoLaps = false
    private var warnedOneLap = false
    private var announcedNoConsumption = false

    /** Position annoncée par GT7 sur la grille, puis tenue à jour d'après ce que dit le pilote. */
    var driverPosition: Int? = null
        private set

    /** Nombre de voitures au départ, transmis par GT7 avant la course. */
    var carsInRace: Int? = null
        private set

    /** Mode silence : seules les alertes et les réponses passent. */
    var quiet: Boolean = false
        private set

    private var lastSpoken: EngineerMessage? = null
    private val tyres = TyreCoach()

    /** Plan de course calculé par le moteur à partir du règlement (null sans règlement exploitable). */
    var plan: RacePlan? = null

    /** Arrêts imposés par le règlement (pour les annonces). */
    var mandatoryStops: Int = 0
    private var announcedPlanKey: String? = null

    /** Pluie signalée par le pilote, et tour où elle a commencé. */
    var raining: Boolean = false
        private set
    private var rainSinceLap = 0

    /** Demander la position au pilote à chaque tour (GT7 ne la transmet pas). */
    var askPositionEachLap: Boolean = false

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
                    driverPosition = null
                    carsInRace = null
                    tyres.reset()
                    raining = false
                    out += msg("Nouvelle session détectée, je repars de zéro.", Priority.NORMAL, MessageCategory.RADIO)
                }

                SessionEvent.RefuelDetected -> {
                    resetAlerts()
                    tyres.pitStop()
                    out += msg("Ravitaillement détecté, je recalcule la stratégie au prochain tour.", Priority.NORMAL, MessageCategory.FUEL)
                }

                // Annoncé dans le message du tour qui vient de se terminer, sinon seul.
                is SessionEvent.FinalLapStarted -> if (!lapCompletedNow) {
                    out += msg("Dernier tour ! Donne tout.", Priority.HIGH, MessageCategory.LAP)
                }

                is SessionEvent.RaceFinished -> out += finishMessage(snapshot)

                is SessionEvent.PitStopDetected -> if (mandatoryStops > 0) {
                    val left = (mandatoryStops - event.stopsSoFar).coerceAtLeast(0)
                    out += msg(
                        "Arrêt ${event.stopsSoFar} enregistré. " +
                            if (left == 0) "Arrêts obligatoires faits." else "Encore $left arrêt${if (left > 1) "s" else ""} obligatoire${if (left > 1) "s" else ""}.",
                        Priority.NORMAL,
                        MessageCategory.FUEL,
                    )
                }

                is SessionEvent.GridPosition -> {
                    driverPosition = event.position
                    carsInRace = event.cars
                    out += msg(
                        "Tu pars en position ${event.position} sur ${event.cars}. Bonne course !",
                        Priority.NORMAL,
                        MessageCategory.RADIO,
                    )
                }

                is SessionEvent.LapCompleted ->
                    out += lapMessage(event, snapshot, fuel, finalLapNext, finishedNow)
            }
        }
        fuelAlert(fuel)?.let { out += it }
        snapshot.packet?.let { p -> tyres.onPacket(p)?.let { out += msg(it, Priority.HIGH, MessageCategory.LAP) } }
        val result = if (quiet) out.filter { it.category == MessageCategory.ALERT } else out
        result.lastOrNull()?.let { lastSpoken = it }
        return result
    }

    /** Ce que le pilote dit à la radio. */
    fun handle(command: DriverCommand, snapshot: SessionSnapshot, fuel: FuelReport?): EngineerMessage {
        val reply = when (command) {
            is DriverCommand.Ask -> return answer(command.query, snapshot, fuel).also { lastSpoken = it }
            is DriverCommand.ReportPosition -> {
                driverPosition = command.position
                "Bien reçu, P${command.position}."
            }
            DriverCommand.ReportLast -> carsInRace?.let {
                driverPosition = it
                "Compris, dernier, P$it. On remonte."
            } ?: "Compris, tu es dernier. Dis-moi ta place en chiffre, par exemple « je suis vingtième »."

            DriverCommand.Overtook -> {
                val pos = driverPosition?.let { (it - 1).coerceAtLeast(1) }
                driverPosition = pos
                if (pos == null) "Bien joué ! Donne-moi ta position quand tu peux." else "Bien joué ! Tu es P$pos."
            }
            DriverCommand.Overtaken -> {
                val pos = driverPosition?.let { p -> (p + 1).let { n -> carsInRace?.let { minOf(n, it) } ?: n } }
                driverPosition = pos
                if (pos == null) "Compris. Donne-moi ta position quand tu peux." else "Compris, tu es P$pos. On reste concentré."
            }
            is DriverCommand.SetLaps -> "Noté : course de ${command.laps} tours."
            is DriverCommand.SetDuration -> "Noté : course de ${command.minutes} minutes. J'estime le nombre de tours avec ton rythme."
            is DriverCommand.SetMandatoryStops -> when (command.stops) {
                0 -> "Noté : pas d'arrêt obligatoire."
                1 -> "Noté : un arrêt obligatoire."
                else -> "Noté : ${command.stops} arrêts obligatoires."
            }
            is DriverCommand.SetFuelMultiplier -> "Noté : consommation fois ${command.factor}."
            is DriverCommand.SetTyreMultiplier -> "Noté : usure des pneus fois ${command.factor}."
            DriverCommand.Rain -> {
                raining = true
                rainSinceLap = snapshot.packet?.currentLap ?: 0
                "Compris, piste mouillée. Je compare maintenant tes chronos aux tours sous la pluie. Sois doux sur les pédales."
            }
            DriverCommand.Dry -> {
                raining = false
                "Compris, la piste sèche. Je reviens aux références sur le sec."
            }
            DriverCommand.Quiet -> {
                quiet = true
                "Compris, je ne parle que pour les alertes ou si tu m'appelles."
            }
            DriverCommand.Talk -> {
                quiet = false
                "Je reprends les annonces."
            }
            DriverCommand.Repeat -> return lastSpoken?.copy(timestampMs = System.currentTimeMillis())
                ?: msg("Je n'ai encore rien dit.", Priority.HIGH, MessageCategory.ANSWER)
        }
        return msg(reply, Priority.HIGH, MessageCategory.ANSWER).also { lastSpoken = it }
    }

    /** Phrase non comprise : on le dit franchement plutôt que d'improviser. */
    fun notUnderstood(text: String): EngineerMessage =
        msg("Je n'ai pas compris : « $text ».", Priority.HIGH, MessageCategory.ANSWER)

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

            DriverQuery.POSITION -> driverPosition?.let {
                "D'après ce que tu m'as dit, tu es P$it. GT7 ne me transmet pas les positions en course."
            } ?: "GT7 ne me transmet pas les positions en course. Dis-moi par exemple « je suis troisième »."

            DriverQuery.SECTORS -> sectorReport(snapshot)

            DriverQuery.PLAN -> plan?.let { planSentence(it) }
                ?: "Je n'ai pas de plan : donne-moi le règlement, par exemple « course de 30 minutes » ou « arrêt obligatoire »."

            DriverQuery.TYRES -> p?.let {
                val t = it.tyreTemps
                "Température de surface des pneus : avant gauche ${t.fl.roundToInt()}, avant droit ${t.fr.roundToInt()}, " +
                    "arrière gauche ${t.rl.roundToInt()}, arrière droit ${t.rr.roundToInt()} degrés."
            } ?: NO_DATA

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
            // Sous la pluie, on compare aux tours sous la pluie, pas au meilleur tour sur le sec.
            val best = if (raining) {
                snapshot.laps.filter { it.lapNumber in rainSinceLap until lap.lapNumber }.mapNotNull { it.timeMs }.filter { it > 0 }.minOrNull()
            } else {
                e.previousBestMs
            }
            when {
                best == null && raining -> sb.append("Premier tour de référence sous la pluie. ")
                best == null -> Unit
                time < best -> sb.append("Meilleur tour, ${deltaSpeech(best - time)} de mieux ! ")
                time == best -> sb.append("Égalité avec ton meilleur. ")
                else -> sb.append("Plus ${deltaSpeech(time - best)} sur ton meilleur. ")
            }
        } else {
            sb.append("Tour ${lap.lapNumber} bouclé. ")
        }
        if (!raining) {
            sectorFeedback(lap.sectorsMs, lap.lapNumber, snapshot, isNewBest = time != null && e.previousBestMs != null && time < e.previousBestMs)
                ?.let { sb.append(it).append(' ') }
        }
        tyres.onLap(snapshot.laps, raining)?.let { sb.append(it).append(' ') }
        if (finished) return msg(sb.toString().trim(), Priority.NORMAL, MessageCategory.LAP)

        driverPosition?.let { sb.append("Tu es P$it. ") }

        val totalLaps = snapshot.packet?.totalLaps?.takeIf { it > 0 } ?: plan?.totalLaps ?: 0
        when {
            finalLapNext -> sb.append("Dernier tour, donne tout ! ")
            totalLaps > 0 && totalLaps - lap.lapNumber > 1 ->
                sb.append(if (plan?.estimatedLaps == true) "Encore environ ${totalLaps - lap.lapNumber} tours. " else "Encore ${totalLaps - lap.lapNumber} tours. ")
        }
        val box = boxCall(lap.lapNumber + 1, fuel)
        box?.let { sb.append(it).append(' ') }

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
                // L'appel au stand remplace le point carburant détaillé sur ces tours-là.
                else -> if (box == null) sb.append(fuelSentence(fuel, verbose = false))
            }
        }
        val priority = if (fuel?.advice is FuelAdvice.PitRequired || finalLapNext) Priority.HIGH else Priority.NORMAL
        if (askPositionEachLap && !quiet) {
            sb.append(" Ta position ?")
            return EngineerMessage(sb.toString().trim(), priority, MessageCategory.LAP, expectsReply = true)
        }
        return msg(sb.toString().trim(), priority, MessageCategory.LAP)
    }

    /** Meilleur tour ayant des secteurs (référence de comparaison), hors tour [excludeLap]. */
    private fun referenceSectors(snapshot: SessionSnapshot, excludeLap: Int?): List<Int>? =
        snapshot.laps
            .filter { it.sectorsMs != null && it.timeMs != null && it.lapNumber != excludeLap }
            .minByOrNull { it.timeMs!! }
            ?.sectorsMs

    /** "Tu perds surtout dans le secteur 2, 45 centièmes." — seulement si l'écart est significatif. */
    private fun sectorFeedback(sectors: List<Int>?, lapNumber: Int, snapshot: SessionSnapshot, isNewBest: Boolean): String? {
        if (sectors == null || isNewBest) return null
        val deltas = SectorComparison.deltas(sectors, referenceSectors(snapshot, lapNumber)) ?: return null
        val worst = deltas.withIndex().maxByOrNull { it.value } ?: return null
        if (worst.value < SECTOR_THRESHOLD_MS) return null
        val best = deltas.withIndex().minByOrNull { it.value }!!
        val gain = if (best.value <= -SECTOR_THRESHOLD_MS) {
            " Tu gagnes ${deltaSpeech(best.value)} dans le ${best.index + 1}."
        } else {
            ""
        }
        return "Tu perds surtout dans le secteur ${worst.index + 1}, ${deltaSpeech(worst.value)}.$gain"
    }

    private fun sectorReport(snapshot: SessionSnapshot): String {
        val last = snapshot.laps.lastOrNull { it.sectorsMs != null }
            ?: return "Pas encore de tour complet découpé en secteurs. Il faut un tour suivi depuis la ligne."
        val deltas = SectorComparison.deltas(last.sectorsMs, referenceSectors(snapshot, last.lapNumber))
            ?: return "Tour ${last.lapNumber} enregistré, il me faut un deuxième tour pour comparer les secteurs."
        val parts = deltas.mapIndexed { i, d ->
            val sign = if (d > 0) "plus" else if (d < 0) "moins" else ""
            "secteur ${i + 1} ${if (d == 0) "identique" else "$sign ${deltaSpeech(d)}"}"
        }
        return "Tour ${last.lapNumber} contre ton meilleur : ${parts.joinToString(", ")}."
    }

    /** Annonce le plan quand il change (tours d'arrêt), pas à chaque recalcul de litres. */
    fun onPlan(newPlan: RacePlan?): EngineerMessage? {
        plan = newPlan
        val key = newPlan?.let { p -> "${p.totalLaps}:" + p.stops.joinToString(",") { "${it.endOfLap}/${it.mandatoryOnly}" } }
        if (key == null || key == announcedPlanKey) return null
        val first = announcedPlanKey == null
        announcedPlanKey = key
        return msg((if (first) "" else "Nouveau plan. ") + planSentence(newPlan), Priority.HIGH, MessageCategory.FUEL)
    }

    private fun planSentence(p: RacePlan): String = buildString {
        append(if (p.estimatedLaps) "Course estimée à ${p.totalLaps} tours. " else "Course de ${p.totalLaps} tours. ")
        if (p.stops.isEmpty()) {
            append("Stratégie : pas d'arrêt, le carburant suffit.")
        } else {
            append("Stratégie : ${p.stops.size} arrêt${if (p.stops.size > 1) "s" else ""}. ")
            append(
                p.stops.joinToString(", ") { s ->
                    when {
                        s.mandatoryOnly -> "fin du tour ${s.endOfLap}, arrêt obligatoire sans essence"
                        s.liters != null -> "fin du tour ${s.endOfLap}, ${s.liters} litres"
                        else -> "fin du tour ${s.endOfLap}"
                    }
                },
            )
            append(".")
            if (p.perLap == null) append(" Les litres seront calculés après un tour mesuré.")
            p.pitLaneDeltaS?.let { append(" Chaque passage par les stands coûte environ ${it.roundToInt()} secondes plus le plein.") }
        }
        if (p.requiredTyres.isNotBlank()) append(" Gommes imposées : ${p.requiredTyres}.")
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
                append("ajoute environ ${a.fuelToAdd.roundToInt()} litres.")
                if (a.stopsNeeded > 1) append(" Il faudra ${a.stopsNeeded} arrêts.")
                if (a.savingNeeded in 0f..0.10f) {
                    append(" Sinon, économise ${(a.savingNeeded * 100).roundToInt()} pour cent par tour pour aller au bout.")
                }
            }
        }
        return (range + perLap + advice).trim()
    }

    /** Appel au stand au bon tour : celui du plan de course s'il existe, sinon le dernier tour possible. */
    private fun boxCall(currentLap: Int, fuel: FuelReport?): String? {
        val planned = plan
        if (planned != null && planned.stops.isNotEmpty()) {
            val next = planned.nextStop(currentLap) ?: return null
            return when (next.endOfLap) {
                currentLap -> buildString {
                    append("Box, box ! Rentre au stand à la fin de ce tour")
                    when {
                        next.mandatoryOnly -> append(", arrêt obligatoire, pas besoin d'essence.")
                        next.liters != null -> append(", ajoute environ ${next.liters} litres.")
                        else -> append(".")
                    }
                    if (planned.requiredTyres.isNotBlank()) append(" Gommes imposées : ${planned.requiredTyres}.")
                }
                currentLap + 1 -> "Stand au prochain tour, prépare-toi."
                else -> null
            }
        }
        val a = fuel?.advice as? FuelAdvice.PitRequired ?: return null
        val liters = a.fuelToAdd.roundToInt()
        return when (currentLap) {
            a.latestPitLap -> "Box, box ! Rentre au stand à la fin de ce tour, ajoute environ $liters litres."
            a.latestPitLap - 1 -> "Stand au prochain tour, prépare-toi."
            else -> null
        }
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
        const val SECTOR_THRESHOLD_MS = 100
    }
}
