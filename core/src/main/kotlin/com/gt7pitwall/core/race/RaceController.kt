package com.gt7pitwall.core.race

import com.gt7pitwall.core.ai.AiFacts
import com.gt7pitwall.core.ai.AiReply
import com.gt7pitwall.core.ai.RadioAi
import com.gt7pitwall.core.analysis.GForce
import com.gt7pitwall.core.analysis.GForceMeter
import com.gt7pitwall.core.analysis.LapAnalyzer
import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.engineer.FrenchFormat
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

    /**
     * Paquets restants pendant lesquels le plan est gelé après la ligne d'un tour d'arrêt prévu : selon le circuit,
     * on franchit la ligne dans la voie des stands avant d'avoir ravitaillé. Sans ce gel, l'ingénieur relancerait
     * « box » et annoncerait un nouveau plan en pleine voie des stands.
     */
    private var pitWindowPackets = 0

    /** Derniers échanges vérifiés avec l'IA, pour les relances (« et au freinage ? »). */
    private val aiExchanges = ArrayDeque<RadioAi.Exchange>()
    private var lastAiAnswerMs: Long? = null

    val aiHistory: List<RadioAi.Exchange> get() = aiExchanges.toList()

    /** Une conversation avec l'IA est en cours : la phrase suivante lui revient (sauf commande). */
    fun inAiConversation(nowMs: Long = System.currentTimeMillis()): Boolean =
        lastAiAnswerMs?.let { nowMs - it < AI_CONVERSATION_MS } == true

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
        val windowWasOpen = pitWindowPackets > 0
        if (pitWindowPackets > 0) pitWindowPackets--
        if (events.isNotEmpty()) {
            if (events.any { it is SessionEvent.SessionReset }) {
                ledger.reset()
                radio.reset()
                trackCandidates = emptyList()
                candidatesAnnounced = false
                pitWindowPackets = 0
                aiExchanges.clear()
                lastAiAnswerMs = null
            }
            out += learn(events)
            for (e in events) {
                if (e is SessionEvent.LapCompleted && plan?.stops?.any { it.endOfLap == e.lap.lapNumber } == true) {
                    pitWindowPackets = PIT_WINDOW_PACKETS
                }
                if (e is SessionEvent.PitVisitEnded) {
                    pitWindowPackets = 0
                    out += onPitVisit(e)
                    // Une question est posée (arrêt, pneus) : le plan attend la réponse, au plus 30 s.
                    if (awaitingAnswer) pitWindowPackets = QUESTION_WINDOW_PACKETS
                }
            }
        }
        // Plan gelé pendant un passage aux stands (le niveau monte) et juste après la ligne d'un tour d'arrêt.
        val frozen = tracker.pitVisitOpen || pitWindowPackets > 0
        if (!frozen && (events.isNotEmpty() || windowWasOpen)) out += replan()
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
            DriverCommand.Yes -> {
                val confirmed = ledger.confirmPending()
                out += answer(
                    when {
                        confirmed != null -> stopRecorded(confirmed) + afterStopQuestions()
                        ledger.awaitingTyreChangeAnswer -> tyresChanged(lap)
                        else -> listOf("Je n'attendais pas de confirmation.")
                    },
                )
                out += replanOrWait()
            }
            DriverCommand.No -> {
                out += answer(
                    when {
                        ledger.rejectPending() -> listOf("D'accord, je ne compte pas cet arrêt.")
                        ledger.awaitingTyreDeclaration || ledger.awaitingTyreChangeAnswer -> {
                            ledger.awaitingTyreDeclaration = false
                            ledger.awaitingTyreChangeAnswer = false
                            listOf("D'accord, mêmes pneus.")
                        }
                        else -> listOf("Je n'attendais pas de réponse.")
                    },
                )
                out += replanOrWait()
            }
            DriverCommand.TyresChanged -> {
                out += answer(tyresChanged(lap))
                out += replanOrWait()
            }
            DriverCommand.DeclareStop -> {
                val (lines, recorded) = declareStop(lap)
                out += answer(lines + if (recorded) afterStopQuestions() else emptyList())
                out += replanOrWait()
            }
            is DriverCommand.StopAndTyres -> {
                val tyreLines = when (val t = command.tyres) {
                    is DriverCommand.Tyres -> tyres(t, lap)
                    else -> tyresChanged(lap)
                }
                out += answer(declareStop(lap).first + tyreLines)
                out += replanOrWait()
            }
            DriverCommand.CancelStop -> {
                val removed = ledger.cancelLast()
                out += answer(listOf(if (removed != null) "Arrêt ${removed.number} retiré du décompte." else "Aucun arrêt à retirer."))
                out += replan()
            }
            is DriverCommand.Tyres -> {
                out += answer(tyres(command, lap))
                out += replanOrWait()
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

    /**
     * Réponse de l'IA (Gemini Nano) à une phrase que l'analyseur n'a pas comprise. Une commande reconnue passe
     * par le même chemin que la voix ; une réponse n'est dite que si elle a passé le contrôle des chiffres.
     */
    fun aiReply(question: String, reply: AiReply, nowMs: Long = System.currentTimeMillis()): List<EngineerMessage> {
        val echo = EngineerMessage("Toi : $question", Priority.INFO, MessageCategory.DRIVER)
        lastAiAnswerMs = if (reply is AiReply.Command) null else nowMs
        val out = when (reply) {
            is AiReply.Command -> command(reply.command)
            is AiReply.Answer -> {
                aiExchanges.addLast(RadioAi.Exchange(question, reply.text))
                while (aiExchanges.size > AI_HISTORY) aiExchanges.removeFirst()
                // La radio écoute la relance sans mot de réveil : c'est une conversation.
                deliver(listOf(EngineerMessage(reply.text, Priority.HIGH, MessageCategory.ANSWER, expectsReply = true, fromAi = true)))
            }
            is AiReply.Rejected -> deliver(
                listOf(
                    when (reply.reason) {
                        AiReply.Reason.UNKNOWN -> EngineerMessage(
                            "Je n'ai pas cette information : GT7 ne me la transmet pas.",
                            Priority.HIGH,
                            MessageCategory.ANSWER,
                        )
                        AiReply.Reason.INVENTED_NUMBER -> EngineerMessage(
                            "Je n'ai pas de donnée fiable pour te répondre.",
                            Priority.HIGH,
                            MessageCategory.ANSWER,
                        )
                        else -> engineer.notUnderstood(question)
                    },
                ),
            )
        }
        return listOf(echo) + out
    }

    /**
     * Faits donnés à l'IA : les réponses que l'ingénieur ferait lui-même à chaque question, plus les tours
     * mesurés. Uniquement des valeurs GT7, des mesures ou des déclarations du pilote.
     */
    fun aiFacts(): AiFacts {
        val snap = snapshot
        val lines = mutableListOf<String>()
        lines += "GT7 ne transmet pas : les positions en course, les adversaires, les écarts, les dégâts, les gommes montées, la météo."
        carName?.let { lines += "Voiture : $it." }
        course?.let { lines += "Circuit : ${it.label}." }
        for (q in DriverQuery.entries) {
            val text = if (q == DriverQuery.OBLIGATIONS) obligationsSentence() else engineer.answer(q, snap, fuel).text
            lines += "${AI_TOPICS[q]} : $text"
        }
        lines += "Arrêts aux stands enregistrés : ${ledger.stopCount}."
        rules.tyreStintLaps?.let { life ->
            val age = ledger.tyreAge((snap.packet?.currentLap ?: 1).coerceAtLeast(1))
            lines += "Pneus : ils tiennent $life tours d'après toi ; $age tours faits avec le train actuel."
        }
        lines += if (engineer.raining) "Pluie signalée par le pilote." else "Pas de pluie signalée par le pilote."
        for (lap in snap.laps.takeLast(AI_LAPS)) {
            val parts = mutableListOf<String>()
            parts += lap.timeMs?.takeIf { it > 0 }?.let { FrenchFormat.lapTimeSpeech(it) } ?: "chrono inconnu"
            lap.fuelUsed?.let { parts += "${FrenchFormat.decimal(it)} d'essence" }
            if (lap.refuelled) parts += "ravitaillement"
            lap.lockups?.let { parts += "$it blocages de roue" }
            lap.wheelspins?.let { parts += "$it patinages" }
            lap.maxSpeedKmh?.let { parts += "vitesse maximale ${it.roundToInt()} km/h" }
            lap.tcsShare?.let { parts += "antipatinage actif ${(it * 100).roundToInt()} % du tour" }
            lines += "Tour ${lap.lapNumber} : ${parts.joinToString(", ")}."
        }
        return AiFacts(lines)
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
        is DriverCommand.SetTyreStint -> rules.copy(tyreStintLaps = c.laps.takeIf { it > 0 })
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
    /** Recalcule le plan, sauf si une question sur l'arrêt ou les pneus attend sa réponse (le plan en dépend). */
    private fun replanOrWait(): List<EngineerMessage> {
        if (awaitingAnswer) {
            pitWindowPackets = maxOf(pitWindowPackets, QUESTION_WINDOW_PACKETS)
            return emptyList()
        }
        pitWindowPackets = 0
        return replan()
    }

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
        plan = if (p.fuelCapacity > 0f || rules.mandatoryStops > 0 || missing.isNotEmpty() || rules.tyreStintLaps != null) {
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
                    tyreAgeLaps = ledger.tyreAge(p.currentLap.coerceAtLeast(1)),
                    pitLaneDeltaS = course?.pitLaneDeltaS,
                    previousStops = plan?.stops?.map { it.endOfLap } ?: emptyList(),
                ),
            )
        } else {
            null
        }
        return engineer.onPlan(plan, p.currentLap)
    }

    // ------------------------------------------------------------------ Arrêts et gommes

    private fun onPitVisit(e: SessionEvent.PitVisitEnded): List<EngineerMessage> {
        val v = e.visit
        // Avec une durée de vie des pneus, un arrêt sans essence compte aussi : il peut s'agir d'un changement de pneus.
        val hasObligations = rules.mandatoryStops > 0 || rules.requiredTyres.isNotEmpty() || rules.tyreStintLaps != null
        return when (v.assessment) {
            PitAssessment.REFUEL -> {
                val rec = ledger.recordRefuel(v.lap, v.refuelLiters)
                info(stopRecorded(rec) + afterStopQuestions(), Priority.HIGH, expectsReply = awaitingAnswer)
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

    /**
     * « J'ai fait mon arrêt » : confirme l'arrêt en attente, sinon l'enregistre comme déclaré — sauf si un arrêt
     * vient déjà d'être enregistré (ravitaillement détecté) : on ne compte jamais deux fois le même passage.
     * Renvoie les phrases à dire et si un arrêt a été enregistré.
     */
    private fun declareStop(lap: Int): Pair<List<String>, Boolean> {
        if (ledger.pending == null) {
            ledger.stopList.lastOrNull()?.takeIf { lap - it.lap in 0..1 }?.let {
                return listOf("Arrêt ${it.number} déjà enregistré au tour ${it.lap}, je ne le compte pas deux fois.") to false
            }
        }
        return stopRecorded(ledger.declare(lap)) to true
    }

    /**
     * Après un arrêt : si des gommes sont imposées, on demande lesquelles ont été montées ; si le pilote a donné
     * une durée de vie des pneus, on demande s'il les a changés. GT7 ne transmet pas les pneus montés.
     */
    private fun afterStopQuestions(): List<String> {
        if (rules.requiredTyres.isNotEmpty()) {
            ledger.awaitingTyreDeclaration = true
            return listOf("Quels pneus as-tu montés ?")
        }
        if (rules.tyreStintLaps != null) {
            ledger.awaitingTyreChangeAnswer = true
            return listOf("As-tu changé les pneus ?")
        }
        return emptyList()
    }

    /** Pneus neufs sans gomme précisée : l'âge des pneus repart de zéro. */
    private fun tyresChanged(lap: Int): List<String> {
        ledger.fitTyres(lap.coerceAtLeast(1), null)
        val out = mutableListOf("Noté : pneus neufs au tour ${lap.coerceAtLeast(1)}.")
        if (rules.requiredTyres.isNotEmpty()) {
            ledger.awaitingTyreDeclaration = true
            out += "Quelle gomme as-tu montée ?"
        }
        return out
    }

    private companion object {
        const val AI_LAPS = 5
        const val AI_HISTORY = 3
        const val AI_CONVERSATION_MS = 45_000L

        val AI_TOPICS = mapOf(
            DriverQuery.FUEL to "Carburant",
            DriverQuery.LAST_LAP to "Dernier tour",
            DriverQuery.BEST_LAP to "Meilleur tour",
            DriverQuery.RACE_STATUS to "Course",
            DriverQuery.PACE to "Rythme",
            DriverQuery.SECTORS to "Secteurs",
            DriverQuery.POSITION to "Position",
            DriverQuery.TYRES to "Pneus",
            DriverQuery.PLAN to "Stratégie",
            DriverQuery.DETAILS to "Détail du dernier tour",
            DriverQuery.OBLIGATIONS to "Obligations du règlement",
        )

        /** 45 s après la ligne d'un tour d'arrêt prévu, le temps d'atteindre son stand. */
        const val PIT_WINDOW_PACKETS = 45 * 60

        /** Délai laissé au pilote pour répondre à une question sur l'arrêt ou les pneus avant de recalculer. */
        const val QUESTION_WINDOW_PACKETS = 30 * 60
    }

    private val awaitingAnswer: Boolean
        get() = ledger.awaitingTyreDeclaration || ledger.awaitingTyreChangeAnswer || ledger.pending != null

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
                expectsReply = awaitingAnswer,
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
