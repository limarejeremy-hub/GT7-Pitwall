package com.gt7pitwall.core.race

import com.gt7pitwall.core.analysis.GForce
import com.gt7pitwall.core.analysis.GForceMeter
import com.gt7pitwall.core.analysis.LapAnalyzer
import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.engineer.Priority
import com.gt7pitwall.core.engineer.RaceEngineer
import com.gt7pitwall.core.engineer.RadioFilter
import com.gt7pitwall.core.knowledge.CarTrackMemory
import com.gt7pitwall.core.knowledge.CarTrackRecord
import com.gt7pitwall.core.knowledge.Course
import com.gt7pitwall.core.knowledge.Gt7Database
import com.gt7pitwall.core.radio.DriverCommand
import com.gt7pitwall.core.radio.DriverCommandParser
import com.gt7pitwall.core.radio.DriverQuery
import com.gt7pitwall.core.session.PitAssessment
import com.gt7pitwall.core.session.SessionEvent
import com.gt7pitwall.core.session.SessionSnapshot
import com.gt7pitwall.core.session.SessionTracker
import com.gt7pitwall.core.strategy.DataSource
import com.gt7pitwall.core.strategy.FuelReport
import com.gt7pitwall.core.strategy.FuelStrategy
import com.gt7pitwall.core.strategy.PitLedger
import com.gt7pitwall.core.strategy.PitStopRecord
import com.gt7pitwall.core.strategy.RacePlan
import com.gt7pitwall.core.strategy.RacePlanner
import com.gt7pitwall.core.strategy.RaceRules
import com.gt7pitwall.core.strategy.StopEvidence
import com.gt7pitwall.core.strategy.TyreCompound
import com.gt7pitwall.core.telemetry.TelemetryPacket
import kotlin.math.roundToInt

/** Stockage du règlement et de la mémoire voiture × circuit (implémenté côté Android). */
interface RaceStorage {
    fun loadRules(): RaceRules
    fun saveRules(r: RaceRules)
    fun loadMemory(): Map<String, CarTrackRecord>
    fun saveMemory(records: Collection<CarTrackRecord>)
}

/**
 * Cerveau de course, sans Android : télémétrie, règlement, registre des arrêts et des gommes, plan, radio.
 *
 * Toutes les entrées passent par ici — paquets GT7, boutons, voix, écran du règlement — pour que le
 * règlement soit toujours appliqué, enregistré et la stratégie recalculée de la même façon.
 * Les méthodes ne sont pas thread-safe : l'appelant (PitwallEngine) les sérialise.
 */
class RaceController(
    val database: Gt7Database = Gt7Database.EMPTY,
    private val storage: RaceStorage? = null,
    private val radio: RadioFilter = RadioFilter(),
) {
    val tracker = SessionTracker()
    val engineer = RaceEngineer()
    val ledger = PitLedger()
    private val gMeter = GForceMeter()

    var rules: RaceRules = storage?.loadRules() ?: RaceRules()
        private set
    private val memory = (storage?.loadMemory() ?: emptyMap()).toMutableMap()

    var plan: RacePlan? = null
        private set
    var fuel: FuelReport? = null
        private set
    var gForce: GForce? = null
        private set
    var trackCandidates: List<Course> = emptyList()
        private set
    private var candidatesAnnounced = false

    val snapshot: SessionSnapshot get() = tracker.snapshot
    val course: Course? get() = rules.trackId?.let(database::course)
    val carName: String? get() = snapshot.packet?.let { database.car(it.carCode)?.fullName }
    val memoryRecord: CarTrackRecord?
        get() = snapshot.packet?.let { p -> rules.trackId?.let { memory[CarTrackRecord.key(p.carCode, it)] } }
    val tyresUsed: Set<TyreCompound> get() = ledger.tyresUsed(rules.startTyre)
    val missingTyres: Set<TyreCompound> get() = ledger.missingRequired(rules)

    // ------------------------------------------------------------------ Entrées

    fun onPacket(p: TelemetryPacket): List<EngineerMessage> {
        val events = tracker.update(p)
        gMeter.update(p)?.let { gForce = it }
        val out = mutableListOf<EngineerMessage>()
        if (events.isNotEmpty()) {
            if (events.any { it is SessionEvent.SessionReset }) {
                ledger.reset()
                radio.reset()
                trackCandidates = emptyList()
                candidatesAnnounced = false
            }
            out += learn(events)
            for (e in events) if (e is SessionEvent.PitVisitEnded) out += onPitVisit(e)
            out += replan()
        }
        fuel = FuelStrategy.compute(tracker.snapshot, plan?.totalLaps)
        out.addAll(0, engineer.onUpdate(events, tracker.snapshot, fuel))
        return deliver(out)
    }

    /** Même traitement pour les boutons de l'écran et les commandes vocales. */
    fun command(command: DriverCommand): List<EngineerMessage> {
        val out = mutableListOf<EngineerMessage>()
        val lap = snapshot.packet?.currentLap ?: 0
        when (command) {
            is DriverCommand.Rule -> {
                applyRules(ruleChange(command))
                out += engineer.handle(command, snapshot, fuel)
                out += replan()
            }
            DriverCommand.Yes -> out += answer(
                ledger.confirmPending()?.let { stopRecorded(it) + afterStopQuestions() }
                    ?: listOf("Je n'attendais pas de confirmation."),
            )
            DriverCommand.No -> out += answer(
                when {
                    ledger.rejectPending() -> listOf("D'accord, je ne compte pas cet arrêt.")
                    ledger.awaitingTyreDeclaration -> {
                        ledger.awaitingTyreDeclaration = false
                        listOf("D'accord, pas de changement de pneus noté.")
                    }
                    else -> listOf("Je n'attendais pas de réponse.")
                },
            )
            DriverCommand.DeclareStop -> {
                val rec = ledger.declare(lap)
                out += answer(stopRecorded(rec) + afterStopQuestions())
                out += replan()
            }
            DriverCommand.CancelStop -> {
                val removed = ledger.cancelLast()
                out += answer(listOf(if (removed != null) "Arrêt ${removed.number} retiré du décompte." else "Aucun arrêt à retirer."))
                out += replan()
            }
            is DriverCommand.Tyres -> {
                out += answer(tyres(command, lap))
                out += replan()
            }
            is DriverCommand.Ask -> out += if (command.query == DriverQuery.OBLIGATIONS) {
                answer(listOf(obligationsSentence()))
            } else {
                listOf(engineer.handle(command, snapshot, fuel))
            }
            else -> out += engineer.handle(command, snapshot, fuel)
        }
        return deliver(out)
    }

    /** Phrase reconnue par la radio vocale. */
    fun utterance(text: String): List<EngineerMessage> {
        val echo = EngineerMessage("Toi : $text", Priority.INFO, MessageCategory.DRIVER)
        val cmd = DriverCommandParser.parse(text)
        return listOf(echo) + if (cmd == null) deliver(listOf(engineer.notUnderstood(text))) else command(cmd)
    }

    /** Règlement saisi à l'écran. */
    fun setRules(newRules: RaceRules): List<EngineerMessage> {
        applyRules(newRules)
        return deliver(replan())
    }

    // ------------------------------------------------------------------ Règlement et plan

    private fun ruleChange(c: DriverCommand.Rule): RaceRules = when (c) {
        is DriverCommand.SetLaps -> rules.copy(totalLaps = c.laps, durationMin = null)
        is DriverCommand.SetDuration -> rules.copy(durationMin = c.minutes, totalLaps = null)
        is DriverCommand.SetMandatoryStops -> rules.copy(mandatoryStops = c.stops.coerceAtLeast(0))
        is DriverCommand.SetFuelMultiplier -> rules.copy(fuelMultiplier = c.factor.coerceAtLeast(1))
        is DriverCommand.SetTyreMultiplier -> rules.copy(tyreMultiplier = c.factor.coerceAtLeast(1))
    }

    private fun applyRules(newRules: RaceRules) {
        if (newRules.trackId != rules.trackId) trackCandidates = emptyList()
        rules = newRules
        storage?.saveRules(newRules)
    }

    /**
     * Recalcule le plan. Consommation : mesurée en direct (jamais multipliée), sinon historique voiture × circuit
     * ramené au multiplicateur du règlement. Rythme : mesuré, sinon historique.
     */
    private fun replan(): List<EngineerMessage> {
        val snap = tracker.snapshot
        val p = snap.packet ?: return emptyList()
        val live = FuelStrategy.compute(snap)
        val rec = memoryRecord
        val (perLap, fuelSource) = when {
            live?.perLap != null -> live.perLap to DataSource.MEASURED
            rec?.fuelPerLapX1 != null -> rec.fuelPerLapX1 * rules.fuelMultiplier to DataSource.HISTORY
            else -> null to null
        }
        val measuredAvg = LapAnalyzer.analyze(snap.laps).recentAverageMs
            ?: snap.laps.lastOrNull { (it.timeMs ?: 0) > 0 && !it.refuelled }?.timeMs
        val avg = measuredAvg ?: rec?.typicalLapMs
        val start = snap.fuelAtLapStart
        val currentLapRemaining = if (live?.perLap != null && start != null && !snap.refuelledThisLap) {
            1f - ((start - p.fuelLevel) / live.perLap).coerceIn(0f, 1f)
        } else {
            1f
        }
        val missing = missingTyres
        engineer.missingTyres = missing
        plan = if (p.fuelCapacity > 0f || rules.mandatoryStops > 0 || missing.isNotEmpty()) {
            RacePlanner.plan(
                RacePlanner.Input(
                    rules = rules,
                    gameTotalLaps = p.totalLaps,
                    currentLap = p.currentLap,
                    currentLapRemaining = currentLapRemaining,
                    fuelNow = p.fuelLevel,
                    tank = p.fuelCapacity,
                    perLap = perLap,
                    fuelSource = fuelSource,
                    avgLapMs = avg,
                    raceElapsedMs = snap.raceElapsedMs,
                    stopsDone = ledger.stopCount,
                    tyresMissing = missing.toList(),
                    pitLaneDeltaS = course?.pitLaneDeltaS,
                    previousStops = plan?.stops?.map { it.endOfLap } ?: emptyList(),
                ),
            )
        } else {
            null
        }
        return engineer.onPlan(plan)
    }

    // ------------------------------------------------------------------ Arrêts et gommes

    private fun onPitVisit(e: SessionEvent.PitVisitEnded): List<EngineerMessage> {
        val v = e.visit
        val hasObligations = rules.mandatoryStops > 0 || rules.requiredTyres.isNotEmpty()
        return when (v.assessment) {
            PitAssessment.REFUEL -> {
                val rec = ledger.recordRefuel(v.lap, v.refuelLiters)
                info(stopRecorded(rec) + afterStopQuestions(), Priority.HIGH, expectsReply = ledger.awaitingTyreDeclaration)
            }
            PitAssessment.PIT_LIKE_STOP -> if (hasObligations) {
                ledger.propose(v.lap, v.locationKnown)
                listOf(
                    EngineerMessage(
                        "Je t'ai vu immobilisé ${v.stoppedSeconds.roundToInt()} secondes sans ravitaillement. Tu as bien effectué ton arrêt ?",
                        Priority.CRITICAL,
                        MessageCategory.ALERT,
                        expectsReply = true,
                        dedupKey = "confirm-stop:${v.lap}",
                    ),
                )
            } else {
                emptyList()
            }
            PitAssessment.TRACK_STOP -> if (hasObligations) {
                info(listOf("Arrêt en piste : je ne le compte pas comme un passage aux stands. Dis « arrêt fait » si je me trompe."), Priority.NORMAL)
            } else {
                emptyList()
            }
        }
    }

    private fun stopRecorded(rec: PitStopRecord): List<String> {
        val proof = when (rec.evidence) {
            StopEvidence.REFUEL -> "ravitaillement de ${(rec.litersAdded ?: 0f).roundToInt()} litres"
            StopEvidence.DRIVER_CONFIRMED -> "confirmé par toi"
            StopEvidence.DRIVER_DECLARED -> "déclaré par toi"
        }
        val sb = StringBuilder("Arrêt ${rec.number} enregistré : $proof.")
        if (rules.mandatoryStops > 0) {
            val done = ledger.stopCount.coerceAtMost(rules.mandatoryStops)
            sb.append(" Arrêts obligatoires : $done sur ${rules.mandatoryStops}.")
        }
        return listOf(sb.toString())
    }

    /** Après un arrêt, si des gommes sont encore imposées, on demande lesquelles ont été montées. */
    private fun afterStopQuestions(): List<String> {
        if (rules.requiredTyres.isEmpty()) return emptyList()
        ledger.awaitingTyreDeclaration = true
        return listOf("Quels pneus as-tu montés ?")
    }

    private fun tyres(c: DriverCommand.Tyres, lap: Int): List<String> {
        val compound = TyreCompound.fromSpeech(c.text, rules.allowedTyres, rules.requiredTyres)
            ?: return listOf("Précise la gomme : course, sport ou confort, et dure, medium ou tendre.")
        val out = mutableListOf<String>()
        if (rules.allowedTyres.isNotEmpty() && compound !in rules.allowedTyres) {
            out += "Attention : les ${compound.spoken} ne sont pas dans les gommes autorisées."
        }
        val atStart = c.atStart || lap < 1
        if (atStart) {
            applyRules(rules.copy(startTyre = compound))
            out += "Noté : départ en ${compound.spoken}."
        } else {
            ledger.fitTyres(lap, compound)
            out += "Noté : ${compound.spoken} montés au tour $lap."
        }
        if (rules.requiredTyres.isNotEmpty()) {
            val missing = missingTyres
            out += if (missing.isEmpty()) {
                "Gommes imposées : toutes déclarées (${tyresUsed.filter { it in rules.requiredTyres }.joinToString(", ") { it.spoken }})."
            } else {
                "Il te manque encore les ${missing.joinToString(" et ") { it.spoken }}."
            }
        }
        return out
    }

    /** État des obligations avec leurs preuves. N'affirme jamais « règlement respecté ». */
    fun obligationsSentence(): String {
        if (rules.mandatoryStops == 0 && rules.requiredTyres.isEmpty()) {
            return "Aucune obligation dans le règlement que tu m'as donné."
        }
        val parts = mutableListOf<String>()
        if (rules.mandatoryStops > 0) {
            val stops = ledger.stopList
            val proofs = stops.groupingBy { it.evidence }.eachCount().map { (ev, n) ->
                when (ev) {
                    StopEvidence.REFUEL -> "$n par ravitaillement"
                    StopEvidence.DRIVER_CONFIRMED -> "$n confirmé${if (n > 1) "s" else ""} par toi"
                    StopEvidence.DRIVER_DECLARED -> "$n déclaré${if (n > 1) "s" else ""} par toi"
                }
            }
            parts += "Arrêts obligatoires : ${stops.size.coerceAtMost(rules.mandatoryStops)} sur ${rules.mandatoryStops}" +
                if (proofs.isEmpty()) "" else " (${proofs.joinToString(", ")})"
            ledger.pending?.let { parts += "un arrêt attend ta confirmation" }
        }
        if (rules.requiredTyres.isNotEmpty()) {
            val used = tyresUsed.filter { it in rules.requiredTyres }
            val missing = missingTyres
            parts += buildString {
                append("Gommes imposées : ")
                append(if (used.isEmpty()) "aucune déclarée" else "déclarées par toi : ${used.joinToString(", ") { it.spoken }}")
                if (missing.isNotEmpty()) append(" ; il manque ${missing.joinToString(" et ") { it.spoken }}")
            }
            if (rules.startTyre == null) parts += "je ne connais pas tes pneus de départ"
        }
        return parts.joinToString(". ") + "."
    }

    // ------------------------------------------------------------------ Mémoire et circuit

    private fun learn(events: List<SessionEvent>): List<EngineerMessage> {
        val out = mutableListOf<EngineerMessage>()
        val p = tracker.snapshot.packet ?: return out
        for (e in events) {
            if (e !is SessionEvent.LapCompleted) continue
            val trackId = rules.trackId
            if (trackId != null) {
                val key = CarTrackRecord.key(p.carCode, trackId)
                memory[key] = CarTrackMemory.update(memory[key], p.carCode, trackId, listOf(e.lap), rules.fuelMultiplier)
                storage?.saveMemory(memory.values.toList())
                continue
            }
            val dist = e.lap.distanceM ?: continue
            val found = database.coursesMatching(dist)
            when {
                found.size == 1 -> {
                    applyRules(rules.copy(trackId = found[0].id))
                    out += EngineerMessage("Circuit reconnu : ${found[0].label}.", Priority.NORMAL, MessageCategory.RADIO)
                }
                found.isNotEmpty() -> {
                    trackCandidates = found
                    if (!candidatesAnnounced) {
                        candidatesAnnounced = true
                        val names = found.map { it.name }.distinct().take(3).joinToString(" ou ")
                        out += EngineerMessage("Circuit probable : $names. Choisis-le dans la carte Course.", Priority.INFO, MessageCategory.RADIO)
                    }
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------------ Radio

    private fun answer(lines: List<String>): List<EngineerMessage> =
        listOf(
            EngineerMessage(
                lines.joinToString(" "),
                Priority.HIGH,
                MessageCategory.ANSWER,
                expectsReply = ledger.awaitingTyreDeclaration || ledger.pending != null,
            ),
        )

    private fun info(lines: List<String>, priority: Priority, expectsReply: Boolean = false): List<EngineerMessage> =
        listOf(EngineerMessage(lines.joinToString(" "), priority, MessageCategory.FUEL, expectsReply = expectsReply))

    /** Filtre de niveau, anti-répétition et tri : les messages critiques passent en premier. */
    private fun deliver(messages: List<EngineerMessage>): List<EngineerMessage> {
        radio.level = engineer.radioLevel
        return radio.apply(messages)
    }
}
