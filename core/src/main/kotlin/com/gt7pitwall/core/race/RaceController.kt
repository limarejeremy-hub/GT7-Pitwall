package com.gt7pitwall.core.race

import com.gt7pitwall.core.ai.AiFacts
import com.gt7pitwall.core.ai.AiReply
import com.gt7pitwall.core.ai.Intent
import com.gt7pitwall.core.ai.CoachingObservations
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
import com.gt7pitwall.core.radio.Vetting
import com.gt7pitwall.core.radio.GateContext
import com.gt7pitwall.core.radio.CommandSource
import com.gt7pitwall.core.radio.CommandGate
import com.gt7pitwall.core.radio.DriverCommandParser
import com.gt7pitwall.core.radio.DriverQuery
import com.gt7pitwall.core.session.PitAssessment
import com.gt7pitwall.core.session.SessionEvent
import com.gt7pitwall.core.session.SessionSnapshot
import com.gt7pitwall.core.session.SessionTracker
import com.gt7pitwall.core.sport.Edition
import com.gt7pitwall.core.sport.RuleField
import com.gt7pitwall.core.sport.ScheduleState
import com.gt7pitwall.core.sport.SportAutofill
import com.gt7pitwall.core.strategy.DataSource
import com.gt7pitwall.core.strategy.FuelReport
import com.gt7pitwall.core.strategy.FuelStrategy
import com.gt7pitwall.core.strategy.FuelUnits
import com.gt7pitwall.core.strategy.Confidence
import com.gt7pitwall.core.strategy.HistoryModel
import com.gt7pitwall.core.strategy.OptionKind
import com.gt7pitwall.core.strategy.StrategyOptions
import com.gt7pitwall.core.strategy.StrategyReport
import com.gt7pitwall.core.strategy.PitLedger
import com.gt7pitwall.core.strategy.PitStopRecord
import com.gt7pitwall.core.strategy.RacePlan
import com.gt7pitwall.core.strategy.RacePlanner
import com.gt7pitwall.core.strategy.RaceRules
import com.gt7pitwall.core.strategy.StopEvidence
import com.gt7pitwall.core.strategy.TyreCompound
import com.gt7pitwall.core.strategy.TyreService
import com.gt7pitwall.core.telemetry.TelemetryPacket
import kotlin.math.roundToInt

/** Stockage du règlement et de la mémoire voiture × circuit (implémenté côté Android). */
interface RaceStorage {
    fun loadRules(): RaceRules
    fun saveRules(r: RaceRules)
    fun loadMemory(): Map<String, CarTrackRecord>
    fun saveMemory(records: Collection<CarTrackRecord>)

    /** Règlements par épreuve ; null au premier lancement après la mise à jour (migration de l'ancien règlement). */
    fun loadProfiles(): ProfileBook? = null
    fun saveProfiles(book: ProfileBook) {}

    /** Sessions archivées, entre les lancements de l'application. */
    fun loadSessions(): List<SessionRecord> = emptyList()
    fun saveSession(record: SessionRecord) {}
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
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val tracker = SessionTracker()
    val engineer = RaceEngineer()
    val ledger = PitLedger()
    private val gMeter = GForceMeter()

    /** Règlement par épreuve (Sport A, B, C, autre × qualification, course). */
    private val profiles: ProfileBook = storage?.loadProfiles()
        ?: ProfileBook.migrate(storage?.loadRules() ?: RaceRules()).also { storage?.saveProfiles(it) }

    /** Règlement de l'épreuve active. */
    var rules: RaceRules = profiles.current.rules
        private set
    private val memory = (storage?.loadMemory() ?: emptyMap()).toMutableMap()

    /** Épreuve active et ce que le pilote en a déclaré. */
    val profile: EventProfile get() = profiles.current.profile
    val profileEntries: Map<ProfileId, ProfileEntry> get() = profiles.entries

    /** Sessions archivées (une session laissée « en cours » par une fermeture brutale est close comme interrompue). */
    private val sessions = LinkedHashMap<String, SessionRecord>().also { map ->
        storage?.loadSessions().orEmpty().sortedBy { it.startedAtMs }.forEach { r ->
            val fixed = if (r.outcome == SessionOutcome.IN_PROGRESS) r.copy(outcome = SessionOutcome.INTERRUPTED).also { storage?.saveSession(it) } else r
            map[fixed.id] = fixed
        }
    }
    val archivedSessions: List<SessionRecord> get() = sessions.values.toList()
    val archivedCount: Int get() = sessions.size
    private var sessionId: String? = null
    private var sessionStartMs = 0L
    private var sessionFinished = false
    private val planLog = mutableListOf<PlanEntry>()
    private val driverNotes = mutableListOf<DriverNote>()
    private var rulesReminded = false

    var plan: RacePlan? = null
        private set

    /** Comparaison des stratégies (normale, anticipée, prolongée), recalculée avec le plan. */
    var strategy: StrategyReport? = null
        private set

    /** Ce que l'historique comparable apprend à Pitwall pour cette épreuve, voiture et circuit. */
    var historyModel: HistoryModel = HistoryModel.EMPTY
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
    private var tyreLifeAsked = false

    /** Derniers échanges vérifiés avec l'IA, pour les relances (« et au freinage ? »). */
    private val aiExchanges = ArrayDeque<RadioAi.Exchange>()
    private var lastAiAnswerMs: Long? = null

    val aiHistory: List<RadioAi.Exchange> get() = aiExchanges.toList()

    /** Sujet du dernier échange avec l'IA (une relance courte le garde). */
    val lastAiIntent: Intent? get() = aiExchanges.lastOrNull()?.intent

    private var lastPacketMs: Long? = null
    private var lastPacketOnTrack = false

    /**
     * Télémétrie réellement reçue : un paquet GT7 il y a moins de [TELEMETRY_LIVE_MS], voiture en piste. Ce n'est
     * pas le fait que l'application soit ouverte, ni un ancien paquet resté en mémoire.
     */
    fun telemetryLive(): Boolean =
        lastPacketOnTrack && lastPacketMs?.let { clock() - it < TELEMETRY_LIVE_MS } == true

    /** Une conversation avec l'IA est en cours : la phrase suivante lui revient (sauf commande). */
    fun inAiConversation(nowMs: Long = System.currentTimeMillis()): Boolean =
        lastAiAnswerMs?.let { nowMs - it < AI_CONVERSATION_MS } == true

    /** Position en course transmise par GT7 (0x84 filtré). */
    val positionTracker = PositionTracker()

    val snapshot: SessionSnapshot get() = tracker.snapshot
    val course: Course? get() = rules.trackId?.let(database::course)
    val carName: String? get() = snapshot.packet?.let { database.car(it.carCode)?.fullName }
    /** Mémoire voiture × circuit de l'épreuve active ; écartée si elle a été prise sous une autre version de GT7. */
    val memoryRecord: CarTrackRecord?
        get() = snapshot.packet?.let { p -> rules.trackId?.let { memory[CarTrackRecord.key(p.carCode, it, profile.id.memoryScope)] } }
            ?.takeIf { versionsMatch(it.gameVersion, profile.gameVersion) }

    /** Contexte de la session en cours, pour l'archive et les comparaisons. */
    val sessionContext: SessionContext
        get() = SessionContext(profile, rules, snapshot.packet?.carCode, snapshot.packet?.fuelCapacity?.takeIf { it > 0f })

    /** Sessions archivées comparables à celle-ci. */
    val history: HistorySummary get() = HistorySummary.of(sessionContext, sessions.values, excludeId = sessionId)

    /** Programme Sport de la semaine (sources publiques), null tant qu'il n'a pas été lu. */
    var schedule: ScheduleState? = null
        private set

    /** Édition de la semaine pour l'épreuve active (Sport A, B ou C). */
    val edition: Edition? get() = schedule?.editions?.get(profile.id.series)

    /** Informations du règlement utiles à la stratégie qui restent inconnues ou contradictoires. */
    val ruleGaps: List<RuleField> get() = profile.gaps(rules)

    /** Une course est en cours : le règlement de l'épreuve active ne change pas sous les roues du pilote. */
    private val raceInProgress: Boolean
        get() = sessionId != null && telemetryLive() && snapshot.laps.isNotEmpty() && !sessionFinished
    val tyresUsed: Set<TyreCompound> get() = ledger.tyresUsed(rules.startTyre)
    val missingTyres: Set<TyreCompound> get() = ledger.missingRequired(rules)

    // ------------------------------------------------------------------ Entrées

    fun onPacket(p: TelemetryPacket): List<EngineerMessage> {
        lastPacketMs = clock()
        // Essai réel : à 91 km/h en piste, le drapeau « en piste » de GT7 n'était pas levé. On retient donc toute session
        // qui envoie des paquets hors chargement (voiture en piste, en mouvement, ou course en cours).
        lastPacketOnTrack = !p.isLoading && (p.isOnTrack || p.speedKmh > 1f || p.currentLap > 0)
        val events = tracker.update(p)
        gMeter.update(p)?.let { gForce = it }
        // Position mesurée (0x84) : seulement en course ; en qualification, elle n'a pas été validée.
        if (events.any { it is SessionEvent.SessionReset }) positionTracker.reset()
        positionTracker.enabled = profile.id.kind == SessionKind.RACE
        val nowMs = clock()
        val positionChanges = runCatching { positionTracker.onPacket(p, nowMs) }.getOrDefault(emptyList())
        // Nouvelle session : la précédente est archivée avant que le registre des arrêts soit vidé.
        if (events.any { it is SessionEvent.SessionReset }) {
            closeSession(SessionOutcome.INTERRUPTED)
            // Une nouvelle semaine différée pendant la course précédente s'applique maintenant.
            schedule?.let { pendingSchedule = it }
        }
        if (events.any { it is SessionEvent.SessionReset || it is SessionEvent.FirstData }) startSession()
        val out = mutableListOf<EngineerMessage>()
        val windowWasOpen = pitWindowPackets > 0
        if (pitWindowPackets > 0) pitWindowPackets--
        // « As-tu changé les pneus ? » sans réponse : la question expire, les pneus de cet arrêt restent « non précisés »
        // (un « oui » bien plus tard ne doit pas les déclarer neufs).
        if (ledger.awaitingTyreChangeAnswer) {
            if (++tyreQuestionPackets > TYRE_QUESTION_PACKETS) {
                ledger.awaitingTyreChangeAnswer = false
                tyreQuestionPackets = 0
            }
        } else {
            tyreQuestionPackets = 0
        }
        if (events.isNotEmpty()) {
            if (events.any { it is SessionEvent.SessionReset }) {
                ledger.reset()
                radio.reset()
                trackCandidates = emptyList()
                candidatesAnnounced = false
                pitWindowPackets = 0
                aiExchanges.clear()
                intentions.clear()
                observations.clear()
                lastStrategyAnswerMs = null
                rulesReminded = false
                pendingConfirmation = null
                lastAiAnswerMs = null
                tyreLifeAsked = false
            }
            out += learn(events)
            if (events.any { it is SessionEvent.RaceFinished }) sessionFinished = true
            if (events.any { it is SessionEvent.LapCompleted }) out += rulesReminder()
            for (e in events) {
                if (e is SessionEvent.LapCompleted && plan?.stops?.any { it.endOfLap == e.lap.lapNumber } == true) {
                    pitWindowPackets = PIT_WINDOW_PACKETS
                }
                if (e is SessionEvent.PitVisitEnded) {
                    pitWindowPackets = 0
                    out += onPitVisit(e)
                    // Une question est posée (arrêt, pneus) : le plan attend la réponse, au plus 30 s.
                    if (awaitingPlanAnswer) pitWindowPackets = QUESTION_WINDOW_PACKETS
                }
            }
        }
        // Plan gelé pendant un passage aux stands (le niveau monte) et juste après la ligne d'un tour d'arrêt.
        // Usure des pneus activée sans durée de vie connue : on la demande une fois, au premier tour bouclé.
        if (!tyreLifeAsked && rules.tyreMultiplier > 1 && rules.tyreStintLaps == null &&
            events.any { it is SessionEvent.LapCompleted }
        ) {
            tyreLifeAsked = true
            out += EngineerMessage(
                "Usure des pneus fois ${rules.tyreMultiplier}, mais GT7 ne me donne pas leur usure. Combien de tours tiennent " +
                    "tes pneus ? Dis par exemple « les pneus tiennent huit tours », et je te dirai quand les changer.",
                Priority.HIGH,
                MessageCategory.FUEL,
                expectsReply = true,
            )
        }
        pendingSchedule?.let {
            pendingSchedule = null
            out += applySchedule(it)
        }
        val frozen = tracker.pitVisitOpen || pitWindowPackets > 0
        if (!frozen && (events.isNotEmpty() || windowWasOpen)) out += replan()
        fuel = FuelStrategy.compute(tracker.snapshot, plan?.totalLaps)
        engineer.measured = positionTracker.snapshot(nowMs)
        engineer.stintContext = tyreStintContext()
        out.addAll(0, engineer.onUpdate(events, tracker.snapshot, fuel))
        out += engineer.onPosition(positionChanges, nowMs)
        if (events.any { it is SessionEvent.LapCompleted || it is SessionEvent.RaceFinished || it is SessionEvent.PitVisitEnded }) saveSession()
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
                // Déclaration confirmée par le pilote : appliquée telle quelle.
                pendingConfirmation?.let { pending ->
                    pendingConfirmation = null
                    return command(pending)
                }
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
                if (pendingConfirmation != null) {
                    pendingConfirmation = null
                    return deliver(answer(listOf("D'accord, je ne change rien.")))
                }
                out += answer(
                    when {
                        ledger.rejectPending() -> listOf("D'accord, je ne compte pas cet arrêt.")
                        ledger.awaitingTyreDeclaration || ledger.awaitingTyreChangeAnswer -> {
                            ledger.keepTyres(lap)
                            listOf("D'accord, mêmes pneus : leur relais continue.")
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
            } else if (command.query == DriverQuery.TYRE_CHANGE) {
                answer(listOf(tyreChangeSentence()))
            } else {
                listOf(engineer.handle(command, snapshot, fuel))
            }
            else -> out += engineer.handle(command, snapshot, fuel)
        }
        saveSession()
        return deliver(out)
    }

    /** Phrase reconnue par la radio vocale. */
    /**
     * Phrase reconnue par la radio vocale. [original] : la phrase complète (reconnaissance libre), dont la forme
     * (négation, futur, question…) décide si la commande s'applique.
     */
    fun utterance(text: String, original: String = text): List<EngineerMessage> {
        val echo = EngineerMessage("Toi : $text", Priority.INFO, MessageCategory.DRIVER)
        val cmd = DriverCommandParser.parse(text)
        return listOf(echo) + if (cmd == null) deliver(listOf(engineer.notUnderstood(text))) else voiceCommand(cmd, original, CommandSource.VOICE)
    }

    /** Commande en attente du « oui » du pilote (déclaration incertaine, ou proposée par Gemini). */
    private var pendingConfirmation: DriverCommand? = null

    /** Intentions et préférences du pilote (pas des faits) : données à l'IA pour discuter la stratégie. */
    private val intentions = ArrayDeque<String>()

    private fun gateContext(): GateContext {
        val lap = snapshot.packet?.currentLap ?: 0
        val recentDeclared = ledger.stopList.lastOrNull()
            ?.takeIf { it.evidence == StopEvidence.DRIVER_DECLARED && lap - it.lap in 0..1 }?.number
        return GateContext(telemetryLive(), recentDeclared, ledger.awaitingTyreChangeAnswer, engineer.driverPosition)
    }

    /** Décision du garde pour une commande vocale, sans l'appliquer (la conversation choisit qui répond). */
    fun vet(command: DriverCommand, text: String, source: CommandSource = CommandSource.VOICE): Vetting =
        CommandGate.vet(command, text, source, gateContext())

    /**
     * Commande vocale (Vosk, Google ou Gemini) : validée par [CommandGate] avant de modifier quoi que ce soit.
     * Les boutons de l'écran passent par [command] directement.
     */
    fun voiceCommand(command: DriverCommand, text: String, source: CommandSource): List<EngineerMessage> {
        // Une autre phrase que « oui » / « non » abandonne la confirmation en attente : rien n'est appliqué.
        if (command != DriverCommand.Yes && command != DriverCommand.No) pendingConfirmation = null
        return when (val v = vet(command, text, source)) {
            is Vetting.Execute -> command(v.command)
            is Vetting.Redirect -> command(v.command)
            is Vetting.Confirm -> {
                pendingConfirmation = v.command
                deliver(listOf(EngineerMessage(v.question, Priority.HIGH, MessageCategory.ANSWER, expectsReply = true)))
            }
            is Vetting.Discuss -> {
                v.intention?.let(::noteIntention)
                deliver(listOf(EngineerMessage(v.reply, Priority.HIGH, MessageCategory.ANSWER)))
            }
        }
    }

    /** Intention ou préférence du pilote, gardée pour la discussion stratégique (jamais un événement de course). */
    fun noteIntention(text: String) {
        intentions.addLast(text)
        while (intentions.size > MAX_INTENTIONS) intentions.removeFirst()
        driverNotes += DriverNote(snapshot.packet?.currentLap ?: 0, text)
        while (driverNotes.size > MAX_NOTES) driverNotes.removeAt(0)
        saveSession()
    }

    /**
     * Réponse de l'IA (Gemini Nano) à une phrase que l'analyseur n'a pas comprise. Une commande reconnue passe
     * par le même chemin que la voix ; une réponse n'est dite que si elle a passé le contrôle des chiffres.
     */
    fun aiReply(
        question: String,
        reply: AiReply,
        nowMs: Long = System.currentTimeMillis(),
        intent: Intent = Intent.OTHER,
    ): List<EngineerMessage> {
        val echo = EngineerMessage("Toi : $question", Priority.INFO, MessageCategory.DRIVER)
        lastAiAnswerMs = if (reply is AiReply.Command) null else nowMs
        val out = when (reply) {
            // Gemini ne fait que reformuler : sa commande passe par le même garde, et attend le « oui » du pilote.
            is AiReply.Command -> voiceCommand(reply.command, question, CommandSource.GEMINI)
            is AiReply.Answer -> {
                aiExchanges.addLast(RadioAi.Exchange(question, reply.text, intent))
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
                        // Conseil écarté : plutôt que d'improviser, l'ingénieur cherche le vrai problème avec le pilote.
                        // Déjà dit : on ne rabâche pas, on demande ce que le pilote veut vraiment.
                        AiReply.Reason.REPEAT -> EngineerMessage(
                            "Je t'ai déjà donné ce conseil. Tu veux parler de quoi exactement : pilotage, stratégie, autre chose ?",
                            Priority.HIGH,
                            MessageCategory.ANSWER,
                            expectsReply = true,
                        )
                        AiReply.Reason.NONSENSE, AiReply.Reason.BANALITY, AiReply.Reason.UNPROVEN_DEFECT -> EngineerMessage(
                            "Je préfère ne pas te dire de bêtise. Pour t'aider précisément : c'est dans quel virage, et ça se " +
                                "passe à l'entrée, au point de corde ou en sortie ?",
                            Priority.HIGH,
                            MessageCategory.ANSWER,
                            expectsReply = true,
                        )
                        else -> engineer.notUnderstood(question)
                    },
                ),
            )
        }
        return listOf(echo) + out
    }

    /** Réponse de l'ingénieur lui-même, sans IA (test radio, salut, merci, pas de données de course…). */
    fun localReply(question: String, text: String): List<EngineerMessage> {
        val echo = EngineerMessage("Toi : $question", Priority.INFO, MessageCategory.DRIVER)
        return listOf(echo) + deliver(listOf(EngineerMessage(text, Priority.HIGH, MessageCategory.ANSWER)))
    }

    /**
     * Faits donnés à l'IA : les réponses que l'ingénieur ferait lui-même à chaque question, plus les tours
     * mesurés. Uniquement des valeurs GT7, des mesures ou des déclarations du pilote.
     */
    fun aiFacts(): AiFacts {
        val snap = snapshot
        val lines = mutableListOf<String>()
        lines += "GT7 ne transmet pas : les positions en course, les adversaires, les écarts, les dégâts, les gommes montées, la météo."
        lines += "Épreuve : ${profile.id.label}" + if (profile.confirmed) ", règlement confirmé par le pilote." else ", règlement non confirmé."
        edition?.let { ed ->
            lines += "Programme de la semaine (${ed.sources.joinToString { it.name }}) : " +
                listOfNotNull(ed.trackName.value, ed.cars.value?.let { "voitures $it" }, ed.bop.value?.let { "BoP $it" }).joinToString(", ") + "."
        }
        ruleGaps.takeIf { it.isNotEmpty() }?.let { g -> lines += "Règlement inconnu sur : ${g.joinToString(", ") { it.label }}." }
        strategyBrief?.let { lines += it.facts() }
        activeObservations.forEach { o -> lines += "Déclaré par le pilote au tour ${o.lap} (non mesuré) : ${o.kind.label}." }
        history.let { h -> lines += "Historique : ${h.comparable} session(s) comparable(s) archivée(s), dont ${h.sameEvent} avec le même règlement." }
        carName?.let { lines += "Voiture : $it." }
        course?.let { lines += "Circuit : ${it.label}." }
        for (q in DriverQuery.entries) {
            val text = when (q) {
                DriverQuery.OBLIGATIONS -> obligationsSentence()
                DriverQuery.TYRE_CHANGE -> tyreChangeSentence()
                else -> engineer.answer(q, snap, fuel).text
            }
            lines += "${AI_TOPICS[q]} : $text"
        }
        lines += "Arrêts aux stands enregistrés : ${ledger.stopCount}."
        rules.tyreStintLaps?.let { life ->
            val age = ledger.tyreAge((snap.packet?.currentLap ?: 1).coerceAtLeast(1))
            lines += "Pneus : ils tiennent $life tours d'après toi ; $age tours faits avec le train actuel."
        }
        lines += if (engineer.raining) "Pluie signalée par le pilote." else "Pas de pluie signalée par le pilote."
        intentions.forEach { lines += "Intention du pilote, pas encore réalisée : $it." }
        for (lap in snap.laps.takeLast(AI_LAPS)) {
            val parts = mutableListOf<String>()
            parts += lap.timeMs?.takeIf { it > 0 }?.let { FrenchFormat.lapTimeSpeech(it) } ?: "chrono inconnu"
            lap.fuelUsed?.let { parts += "${FuelUnits.amount(it, snap.packet?.fuelCapacity)} de carburant consommés" }
            if (lap.refuelled) parts += "ravitaillement"
            lap.lockups?.let { parts += "$it blocages de roue" }
            lap.wheelspins?.let { parts += "$it patinages" }
            lap.maxSpeedKmh?.let { parts += "vitesse maximale ${it.roundToInt()} km/h" }
            lap.tcsShare?.let { parts += "antipatinage actif ${(it * 100).roundToInt()} % du tour" }
            lines += "Tour ${lap.lapNumber} : ${parts.joinToString(", ")}."
        }
        return AiFacts(lines, CoachingObservations.from(snap.laps, snap.packet))
    }

    /** L'ingénieur attend un oui / non (arrêt à confirmer, pneus changés ?) : la réponse n'est pas pour l'IA. */
    val awaitingDriverAnswer: Boolean get() = awaitingAnswer

    /** Règlement saisi à l'écran. */
    fun setRules(newRules: RaceRules): List<EngineerMessage> {
        applyRules(newRules)
        return deliver(replan())
    }

    /**
     * Programme Sport lu (au lancement, au changement de semaine, ou à la demande) : chaque épreuve A, B, C reçoit
     * l'édition de la semaine. L'épreuve active n'est pas modifiée en pleine course : elle le sera à la fin de la session.
     */
    fun applySchedule(state: ScheduleState): List<EngineerMessage> {
        schedule = state
        var activeChanged = false
        for ((series, ed) in state.editions) {
            // L'entraînement reçoit aussi l'édition de la semaine, comme modèle : jamais confirmé d'office.
            for (id in listOf(ProfileId(series, SessionKind.QUALIFYING), ProfileId(series, SessionKind.RACE), ProfileId(series, SessionKind.RACE, SessionMode.SPORT_PRACTICE))) {
                if (id == profiles.active && raceInProgress) continue
                if (profiles.applyEdition(id, ed) { SportAutofill.rules(ed, it.kind) } && id == profiles.active) activeChanged = true
            }
        }
        storage?.saveProfiles(profiles)
        if (!activeChanged) return emptyList()
        rules = profiles.current.rules
        storage?.saveRules(rules)
        plan = null
        return deliver(listOf(editionAnnouncement()) + replan())
    }

    /** Ce que l'ingénieur dit d'une nouvelle édition : l'essentiel, la source, et ce qui reste à confirmer. */
    fun editionAnnouncement(): EngineerMessage {
        val ed = edition
        val text = if (ed == null) {
            "Programme ${profile.id.series.label} inconnu pour cette semaine. Saisis le règlement dans la carte Course."
        } else {
            buildString {
                append("${profile.id.label}, semaine du ${FrenchFormat.dayMonth(ed.validFrom)} : ")
                append(listOfNotNull(
                    course?.label ?: ed.trackName.value,
                    rules.totalLaps?.takeIf { profile.id.kind == SessionKind.RACE }?.let { "$it tours" },
                    ed.cars.value,
                ).joinToString(", "))
                append(". Source : ${ed.sources.joinToString(" et ") { it.name }}, à confirmer.")
                val gaps = ruleGaps
                if (gaps.isNotEmpty()) append(" Inconnu : ${gaps.joinToString(", ") { it.label }}.")
            }
        }
        return EngineerMessage(text, Priority.INFO, MessageCategory.RADIO, dedupKey = "edition:${ed?.id}:${profile.id.code}")
    }

    /** Le pilote donne lui-même des champs du règlement (même inchangés : « aucun arrêt obligatoire »). */
    fun declareRules(newRules: RaceRules, fields: Set<RuleField>): List<EngineerMessage> {
        if (newRules.trackId != rules.trackId) trackCandidates = emptyList()
        rules = profiles.declare(newRules, fields).rules
        storage?.saveRules(rules)
        storage?.saveProfiles(profiles)
        return deliver(replan())
    }

    /** Épreuve choisie à l'écran : son propre règlement est repris, celui de l'épreuve quittée est rangé. */
    fun selectProfile(id: ProfileId): List<EngineerMessage> {
        if (id == profiles.active) return emptyList()
        profiles.select(id)
        // L'édition de la semaine passe avant toute ancienne fiche enregistrée.
        val ed = schedule?.editions?.get(id.series)
        if (ed != null) profiles.applyEdition(id, ed) { SportAutofill.rules(ed, it.kind) }
        val entry = profiles.current
        if (entry.rules.trackId != rules.trackId) trackCandidates = emptyList()
        rules = entry.rules
        storage?.saveRules(rules)
        storage?.saveProfiles(profiles)
        plan = null
        // L'épreuve choisie est annoncée avec son édition de la semaine (ou l'absence de programme).
        val out = (if (id.series != Series.OTHER) listOf(editionAnnouncement()) else emptyList()) + replan()
        saveSession()
        return deliver(out)
    }

    /** Version de GT7, contexte de réglages / BoP. */
    fun updateProfile(p: EventProfile) {
        profiles.updateProfile(p)
        storage?.saveProfiles(profiles)
        saveSession()
    }

    /** Le pilote confirme le règlement de l'épreuve active ([today] : aaaa-mm-jj). */
    fun confirmRules(today: String) {
        profiles.confirm(today)
        storage?.saveProfiles(profiles)
        saveSession()
    }

    /** Télémétrie arrêtée : la session en cours est archivée comme interrompue (sauf si la course est finie). */
    fun endSession() {
        closeSession(SessionOutcome.INTERRUPTED)
        sessionId = null
        schedule?.let { applySchedule(it) }
    }

    private var pendingSchedule: ScheduleState? = null

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
        rules = profiles.updateRules(newRules).rules
        storage?.saveRules(newRules)
        storage?.saveProfiles(profiles)
    }

    // ------------------------------------------------------------------ Archive des sessions

    private fun startSession() {
        sessionStartMs = clock()
        sessionId = "$sessionStartMs-${snapshot.packet?.carCode ?: 0}"
        sessionFinished = false
        planLog.clear()
        driverNotes.clear()
    }

    /** Session en cours, telle qu'elle serait archivée maintenant ; null tant qu'aucun tour n'est bouclé. */
    fun currentRecord(outcome: SessionOutcome = if (sessionFinished) SessionOutcome.FINISHED else SessionOutcome.IN_PROGRESS): SessionRecord? {
        val id = sessionId ?: return null
        // Les tours bouclés après l'arrivée (retour au stand) ne sont pas des tours de course.
        val total = snapshot.packet?.totalLaps?.takeIf { it > 0 } ?: rules.totalLaps
        val laps = snapshot.laps.filter { total == null || it.lapNumber <= total }
        if (laps.isEmpty()) return null
        return SessionRecord(
            id = id,
            context = sessionContext,
            startedAtMs = sessionStartMs,
            updatedAtMs = clock(),
            outcome = outcome,
            laps = laps.map(::lapEntry),
            stops = ledger.stopList.map { StopEntry(it.lap, it.evidence, it.fuelAdded, it.tyres) },
            startTyre = rules.startTyre,
            tyres = ledger.changes.map { TyreEntry(it.lap, it.compound) },
            plans = planLog.toList(),
            notes = driverNotes.toList(),
        )
    }

    /** Tour archivé avec son relais pneus (d'après les seuls changements déclarés) et ses mesures d'adhérence. */
    private fun lapEntry(l: com.gt7pitwall.core.session.LapRecord): LapEntry {
        val change = ledger.changes.lastOrNull { it.lap <= l.lapNumber }
        val since = change?.lap ?: 1
        val certain = ledger.stopList.none { it.tyres == TyreService.UNKNOWN && it.lap >= since - 1 && it.lap < l.lapNumber }
        fun ev(a: Int?, b: Int?) = if (a == null && b == null) null else (a ?: 0) + (b ?: 0)
        return LapEntry(
            l.lapNumber, l.timeMs, l.fuelUsed, l.refuelled,
            tyreAge = (l.lapNumber - since).coerceAtLeast(0),
            tyreCompound = if (change != null) change.compound else rules.startTyre,
            tyreCertain = certain,
            tcsShare = l.tcsShare,
            lockups = l.lockups,
            wheelspins = l.wheelspins,
            frontEvents = ev(l.frontLockups, l.frontWheelspins),
            rearEvents = ev(l.rearLockups, l.rearWheelspins),
            wheelTemps = l.wheelTemps?.toList(),
        )
    }

    /** Enregistre la session au fil de l'eau : une fermeture brutale ne perd au plus que le tour en cours. */
    private fun saveSession() {
        val r = currentRecord() ?: return
        sessions[r.id] = r
        storage?.saveSession(r)
    }

    /**
     * Clôt la session en cours. Au changement de session, les tours sont déjà effacés du suivi : on reprend la dernière
     * version enregistrée (au dernier tour), complétée par le registre des arrêts, encore intact à ce moment.
     */
    private fun closeSession(outcome: SessionOutcome) {
        val final = if (sessionFinished) SessionOutcome.FINISHED else outcome
        val r = currentRecord(final) ?: sessions[sessionId]?.copy(
            outcome = final,
            updatedAtMs = clock(),
            stops = ledger.stopList.map { StopEntry(it.lap, it.evidence, it.fuelAdded, it.tyres) },
            tyres = ledger.changes.map { TyreEntry(it.lap, it.compound) },
            plans = planLog.toList(),
            notes = driverNotes.toList(),
        ) ?: return
        sessions[r.id] = r
        storage?.saveSession(r)
    }

    /** Une fois par session, au premier tour : règlement non confirmé pour l'épreuve choisie. */
    private fun rulesReminder(): List<EngineerMessage> {
        // Épreuves Sport seulement : un règlement « autre épreuve » saisi à la main n'est pas rappelé à chaque course.
        if (rulesReminded || profile.confirmed || profile.id.series == Series.OTHER) return emptyList()
        rulesReminded = true
        val text = if (profile.id.mode == SessionMode.SPORT_PRACTICE) {
            "${profile.id.label} : je prends le règlement de la Course Sport comme modèle. Vérifie que ta course hors ligne a " +
                "les mêmes réglages (tours, multiplicateurs, gommes) et confirme-les dans la carte Course."
        } else {
            "Règlement de l'épreuve ${profile.id.label} non confirmé. Vérifie-le dans la carte Course : je ne m'appuie " +
                "sur l'historique que pour des courses comparables."
        }
        return listOf(
            EngineerMessage(
                text,
                Priority.INFO,
                MessageCategory.RADIO,
                dedupKey = "rules-unconfirmed",
            ),
        )
    }

    private fun versionsMatch(a: String?, b: String?): Boolean {
        val x = a?.trim()?.takeIf { it.isNotEmpty() }
        val y = b?.trim()?.takeIf { it.isNotEmpty() }
        return x == null || y == null || x == y
    }

    /**
     * Recalcule le plan. Consommation : mesurée en direct (jamais multipliée), sinon historique voiture × circuit
     * ramené au multiplicateur du règlement. Rythme : mesuré, sinon historique.
     */
    /** Recalcule le plan, sauf si une question sur l'arrêt ou les pneus attend sa réponse (le plan en dépend). */
    private fun replanOrWait(): List<EngineerMessage> {
        if (awaitingPlanAnswer) {
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
        // Historique : sessions comparables archivées d'abord (avec leur niveau de confiance), sinon l'ancienne mémoire.
        val model = HistoryModel.learn(sessionContext, sessions.values, excludeId = sessionId).also { historyModel = it }
        val learnedFuel = model.fuelPerLap?.takeIf { it.confidence != Confidence.INSUFFICIENT }
        val (perLap, fuelSource) = when {
            live?.perLap != null -> live.perLap to DataSource.MEASURED
            learnedFuel != null -> learnedFuel.mean.toFloat() to DataSource.HISTORY
            rec?.fuelPerLapX1 != null -> rec.fuelPerLapX1 * rules.fuelMultiplier to DataSource.HISTORY
            else -> null to null
        }
        val measuredAvg = LapAnalyzer.analyze(snap.laps).recentAverageMs
            ?: snap.laps.lastOrNull { (it.timeMs ?: 0) > 0 && !it.refuelled }?.timeMs
        val learnedPace = model.lapTimeMs?.takeIf { it.confidence != Confidence.INSUFFICIENT }
        val avg = measuredAvg ?: learnedPace?.mean?.toInt() ?: rec?.typicalLapMs
        val start = snap.fuelAtLapStart
        val currentLapRemaining = if (live?.perLap != null && start != null && !snap.refuelledThisLap) {
            1f - ((start - p.fuelLevel) / live.perLap).coerceIn(0f, 1f)
        } else {
            1f
        }
        val missing = missingTyres
        engineer.missingTyres = missing
        val input = RacePlanner.Input(
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
        )
        val planning = p.fuelCapacity > 0f || rules.mandatoryStops > 0 || missing.isNotEmpty() || rules.tyreStintLaps != null
        val previous = plan
        var next = if (planning) RacePlanner.plan(input) else null
        val report = if (planning && next != null) compareStrategies(input, model, measuredAvg, learnedFuel != null && live?.perLap == null) else null
        var reason: String? = null
        // Une alternative ne remplace le plan que si elle reste recommandée d'un tour sur l'autre, ou si son gain dépasse
        // nettement l'incertitude : pas de changement de stratégie pour un avantage théorique à chaque tour.
        if (report != null && !report.indistinguishable && report.recommended != OptionKind.NORMAL) {
            val opt = report.recommendedOption
            val key = "${opt.kind}:${opt.stopLaps}"
            if (key != switchKey) {
                switchKey = key
                switchSinceLap = p.currentLap
            }
            val gain = -(opt.deltaMs ?: 0)
            val strong = report.uncertaintyMs != null && gain > 2 * report.uncertaintyMs
            if ((p.currentLap > switchSinceLap || strong) && opt.plan != null) {
                next = opt.plan
                reason = report.reason
                switchKey = null
            }
        } else {
            switchKey = null
        }
        if (reason == null && previous != null && next != null) reason = planChangeReason(previous, next, p.currentLap)
        plan = next
        strategy = report
        if (reason != null) {
            engineer.planReason = reason
            lastPlanChange = PlanChange(p.currentLap, reason)
        }
        val stopLaps = plan?.stops?.map { it.endOfLap } ?: emptyList()
        if (plan != null && planLog.lastOrNull()?.stopLaps != stopLaps) {
            planLog += PlanEntry(p.currentLap, stopLaps, reason, fuelPerLap = plan?.perLap, fuelSource = plan?.fuelSource?.name)
            while (planLog.size > MAX_PLANS) planLog.removeAt(0)
        }
        return engineer.onPlan(plan, p.currentLap)
    }

    /** Dernier changement de plan, et pourquoi (pour « pourquoi tu changes notre stratégie ? »). */
    data class PlanChange(val lap: Int, val reason: String)

    var lastPlanChange: PlanChange? = null
        private set

    /** Réponses stratégiques structurées pour la radio : calculées ici, jamais par l'IA. */
    val strategyBrief: com.gt7pitwall.core.strategy.StrategyBrief?
        get() {
            val report = strategy ?: return null
            val p = plan ?: return null
            val lap = (snapshot.packet?.currentLap ?: 0).coerceAtLeast(1)
            return com.gt7pitwall.core.strategy.StrategyBrief.of(
                report, p, lap, fuel, snapshot.packet?.fuelCapacity,
                lastPlanChange?.let { "tour ${it.lap} : ${it.reason}" },
            )
        }
    private var switchKey: String? = null
    private var switchSinceLap = 0

    /**
     * Pourquoi les tours d'arrêt à venir ont changé, d'après ce qui a été mesuré ou déclaré ; null si rien n'a changé
     * (un arrêt effectué qui sort du plan n'est pas un changement).
     */
    private fun planChangeReason(old: RacePlan, new: RacePlan, lap: Int): String? {
        val before = old.stops.map { it.endOfLap }.filter { it >= lap }
        val after = new.stops.map { it.endOfLap }
        if (before == after) return null
        // Un arrêt qui vient d'être fait sort du plan : le reste est inchangé.
        if (ledger.stopList.lastOrNull()?.let { lap - it.lap in 0..1 } == true && before.drop(1) == after) return null
        val cap = snapshot.packet?.fuelCapacity
        val causes = mutableListOf<String>()
        val op = old.perLap
        val np = new.perLap
        if (old.fuelSource != DataSource.MEASURED && new.fuelSource == DataSource.MEASURED) {
            causes += "La consommation mesurée remplace l'estimation" + (np?.let { " : ${FuelUnits.perLap(it, cap)}" } ?: "")
        } else if (op != null && np != null && kotlin.math.abs(np - op) / op > 0.03f) {
            causes += "Notre consommation est ${if (np > op) "supérieure" else "inférieure"} à la prévision : " +
                "${FuelUnits.perLap(np, cap)} au lieu de ${FuelUnits.perLap(op, cap)}"
        }
        if (new.stops.count { it.tyreToFit != null } > old.stops.count { it.tyreToFit != null && it.endOfLap >= lap }) {
            causes += "Il reste des gommes imposées à monter"
        }
        val firstBefore = before.firstOrNull()
        val firstAfter = after.firstOrNull()
        val effect = when {
            firstAfter == null -> "Plus d'arrêt nécessaire."
            firstBefore == null -> "Un arrêt devient nécessaire, fin du tour $firstAfter."
            after.size > before.size -> "Il faut un arrêt de plus."
            after.size < before.size -> "On économise un arrêt."
            firstAfter < firstBefore -> "J'avance l'arrêt au tour $firstAfter : le plan précédent devenait risqué."
            firstAfter > firstBefore -> "On peut retarder l'arrêt au tour $firstAfter : l'autonomie le permet."
            else -> "Les arrêts suivants sont recalculés."
        }
        return (causes.joinToString(". ").let { if (it.isEmpty()) "" else "$it. " } + effect).trim()
    }

    /**
     * Trois stratégies comparées avec le même calcul que le plan. Rythme : mesuré dans cette course, sinon appris ; dérive
     * des chronos dans un relais et perte par arrêt : apprises sur les courses comparables, sinon voie des stands de la base.
     */
    private fun compareStrategies(input: RacePlanner.Input, model: HistoryModel, measuredAvg: Int?, fuelFromHistory: Boolean): StrategyReport? {
        val learnedPace = model.lapTimeMs?.takeIf { it.confidence != Confidence.INSUFFICIENT }
        val laneMs = course?.pitLaneDeltaS?.let { it * 1000.0 }
        // Dérive des chronos : mesurée dans le relais en cours si elle est nette, sinon apprise sur les courses comparables.
        val stint = currentStintSlope()
        val pace = StrategyOptions.Pace(
            lapMs = measuredAvg?.toDouble() ?: learnedPace?.mean,
            lapSpreadMs = learnedPace?.spread ?: 0.0,
            slopeMsPerLap = stint?.first ?: model.paceSlopeMsPerLap?.mean,
            slopeSpread = stint?.second ?: model.paceSlopeMsPerLap?.spread ?: 0.0,
            pitLossMs = model.pitLossMs?.mean ?: laneMs,
            // Voie des stands seule : le temps du plein n'y est pas ; marge d'incertitude d'autant.
            pitLossSpread = model.pitLossMs?.spread ?: laneMs?.let { it * 0.5 } ?: 0.0,
            pitLossFromLaneOnly = model.pitLossMs == null && laneMs != null,
        )
        val basis = buildList {
            when {
                input.fuelSource == DataSource.MEASURED -> add("consommation mesurée dans cette course")
                fuelFromHistory -> model.fuelPerLap?.let { add("consommation apprise (${it.confidence.label} : ${it.basis()})") }
                input.fuelSource == DataSource.HISTORY -> add("consommation de l'ancienne mémoire, à confirmer")
                else -> add("consommation pas encore connue")
            }
            when {
                measuredAvg != null -> add("rythme mesuré dans cette course")
                learnedPace != null -> add("rythme appris (${learnedPace.confidence.label} : ${learnedPace.basis()})")
                else -> add("rythme inconnu")
            }
            when {
                stint != null -> add("chronos qui ${if (stint.first > 0) "se dégradent" else "s'améliorent"} de ${StrategyOptions.seconds(stint.first.toLong())} par tour dans ce relais (mesuré)")
                model.paceSlopeMsPerLap != null -> add("chronos qui évoluent de ${StrategyOptions.seconds(model.paceSlopeMsPerLap.mean.toLong())} par tour de relais (mesuré, ${model.paceSlopeMsPerLap.sessions} courses)")
            }
            when {
                model.pitLossMs != null -> add("perte par arrêt apprise (${model.pitLossMs.basis("arrêts")})")
                laneMs != null -> add("perte par arrêt : voie des stands seulement (base GT7), sans le temps du plein")
                else -> add("perte par arrêt inconnue")
            }
            if (model.sessionsExcluded > 0) add("${model.sessionsExcluded} course(s) de cette épreuve écartée(s) car non comparables")
            if (model.relatedUsed > 0) add("dont ${model.relatedUsed} entraînement(s) ou session(s) au type non précisé : BoP et réglages non vérifiés, confiance limitée")
        }
        return StrategyOptions.compare(input, pace, basis, ruleGaps.map { it.label })
    }

    /**
     * Dérive des chronos dans le relais en cours (ms par tour) et son incertitude, mesurée sur au moins 4 tours propres ;
     * null si elle n'est pas nette (moins de deux fois son incertitude). Jamais déduite d'une déclaration.
     */
    private fun currentStintSlope(): Pair<Double, Double>? {
        val lastStop = (ledger.stopList.map { it.lap } + ledger.changes.map { it.lap }).maxOrNull() ?: 0
        val laps = snapshot.laps.filter { it.lapNumber > maxOf(1, lastStop + 1) && !it.refuelled && (it.timeMs ?: 0) > 0 }
        if (laps.size < 4) return null
        val xs = laps.map { it.lapNumber.toDouble() }
        val ys = laps.map { it.timeMs!!.toDouble() }
        val mx = xs.average()
        val my = ys.average()
        val sxx = xs.sumOf { (it - mx) * (it - mx) }
        if (sxx <= 0) return null
        val b = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / sxx
        val res = xs.indices.sumOf { val e = ys[it] - (my + b * (xs[it] - mx)); e * e } / (xs.size - 2)
        val se = kotlin.math.sqrt(res / sxx)
        return if (kotlin.math.abs(b) >= 2 * se && kotlin.math.abs(b) >= 100) b to se else null
    }

    // ------------------------------------------------------------------ Dialogue stratégique

    /** Observation du pilote, gardée quelques tours : une déclaration, jamais une mesure. */
    data class Observation(val lap: Int, val kind: StrategyDialogue.ObservationKind, val text: String)

    private val observations = ArrayDeque<Observation>()
    private var lastStrategyAnswerMs: Long? = null
    private var lastStrategyAsk: StrategyDialogue.Ask? = null

    /** Observations encore valables (les [OBSERVATION_LAPS] derniers tours). */
    val activeObservations: List<Observation>
        get() {
            val lap = snapshot.packet?.currentLap ?: 0
            return observations.filter { lap - it.lap <= OBSERVATION_LAPS }
        }

    /** Une réponse stratégique vient d'être donnée : une relance courte (« pourquoi ? ») s'y rapporte. */
    fun afterStrategyAnswer(nowMs: Long = clock()): Boolean = lastStrategyAnswerMs?.let { nowMs - it < STRATEGY_FOLLOW_UP_MS } == true

    /**
     * Réponse de l'ingénieur à une question stratégique ou à une observation : lue dans le plan, la comparaison des
     * stratégies et le carburant ; rien n'est modifié dans le registre (ni arrêt, ni pneus). Une observation est
     * mémorisée comme déclaration ; la stratégie n'est réévaluée que sur des mesures.
     */
    fun strategyAnswer(ask: StrategyDialogue.Ask, question: String, nowMs: Long = clock(), intention: Boolean = false): List<EngineerMessage> {
        // Comme toute autre phrase, une question abandonne une confirmation en attente : un « oui » plus tard ne l'applique pas.
        pendingConfirmation = null
        val echo = EngineerMessage("Toi : $question", Priority.INFO, MessageCategory.DRIVER)
        val extra = mutableListOf<EngineerMessage>()
        val text = if (!telemetryLive() && ask !is StrategyDialogue.Ask.Observe && ask !is StrategyDialogue.Ask.Unclear) {
            generalAdvice(ask)
        } else {
            when (ask) {
                StrategyDialogue.Ask.Best -> answerBest()
                is StrategyDialogue.Ask.WhyStop -> answerWhyStop(ask.lap)
                StrategyDialogue.Ask.Extend -> answerExtend()
                StrategyDialogue.Ask.Early -> answerEarly()
                StrategyDialogue.Ask.OthersPitting -> answerOthersPitting()
                StrategyDialogue.Ask.FuelToFinish -> answerFuelToFinish()
                StrategyDialogue.Ask.WhatChanged -> answerWhatChanged()
                StrategyDialogue.Ask.FollowUp -> answerFollowUp()
                is StrategyDialogue.Ask.Observe -> answerObservation(ask, question, extra)
                StrategyDialogue.Ask.Unclear -> "Je n'ai pas bien compris. Tu me demandes la stratégie, le carburant ou l'arrêt ?"
            }
        }
        val said = if (intention) "Noté comme intention, je n'enregistre rien tant que ce n'est pas fait. $text" else text
        if (ask !is StrategyDialogue.Ask.Unclear) lastStrategyAnswerMs = nowMs
        if (ask !is StrategyDialogue.Ask.FollowUp && ask !is StrategyDialogue.Ask.Unclear) lastStrategyAsk = ask
        val answer = EngineerMessage(said, Priority.HIGH, MessageCategory.ANSWER, expectsReply = ask is StrategyDialogue.Ask.Unclear || awaitingAnswer)
        return listOf(echo) + deliver(listOf(answer) + extra)
    }

    /** Sans télémétrie : conseils de principe, annoncés comme tels, sans aucun chiffre de course. */
    private fun generalAdvice(ask: StrategyDialogue.Ask): String = "Conseil général, sans télémétrie : " + when (ask) {
        StrategyDialogue.Ask.Best -> "je n'ai pas de données de course. Je bâtirai le plan sur le règlement et ta consommation mesurée dès le premier tour."
        is StrategyDialogue.Ask.WhyStop -> "un arrêt se place quand l'autonomie ou le règlement l'imposent ; je n'ai pas de plan en cours."
        StrategyDialogue.Ask.Extend -> "on ne prolonge un relais que si l'autonomie mesurée garde au moins un demi-tour de marge."
        StrategyDialogue.Ask.Early -> "avancer un arrêt ne vaut que si le rythme chute nettement ou si le règlement l'impose."
        StrategyDialogue.Ask.OthersPitting -> "on suit notre propre plan tant que notre autonomie le permet ; je n'ai de données ni sur eux ni sur nous."
        StrategyDialogue.Ask.FuelToFinish -> "je ne connais pas ton carburant tant que GT7 ne m'envoie rien."
        StrategyDialogue.Ask.WhatChanged -> "pas de course en cours, donc aucun plan à comparer."
        else -> "je n'ai pas de données de course."
    }

    private val noPlan = "Je n'ai pas encore de plan : il me faut le nombre de tours et un tour mesuré."

    private fun firstSentence(s: String): String = s.substringBefore(". ").trimEnd('.') + "."

    private fun rulesWarning(): String = strategy?.unverifiedRules?.takeIf { it.isNotEmpty() }
        ?.let { " Attention, règlement non vérifié : ${it.joinToString(", ")}." } ?: ""

    private fun answerBest(): String {
        val brief = strategyBrief ?: return noPlan
        return "${brief.recommendation} ${firstSentence(brief.reason)}${rulesWarning()}"
    }

    private fun answerWhyStop(asked: Int?): String {
        val p = plan ?: return noPlan
        val lap = (snapshot.packet?.currentLap ?: 1).coerceAtLeast(1)
        val next = p.nextStop(lap) ?: return "Aucun arrêt n'est prévu : le carburant suffit jusqu'à l'arrivée."
        val cap = snapshot.packet?.fuelCapacity
        val why = buildList {
            if (next.forFuel) {
                add(
                    "le carburant" + (fuel?.lapsOfFuel?.let { ", il te reste ${FrenchFormat.decimal(it)} tours d'autonomie" } ?: "") +
                        (next.fuel?.let { ", il faudra ${FuelUnits.amount(it.toFloat(), cap)}" } ?: ""),
                )
            }
            if (next.mandatory) add("l'arrêt obligatoire du règlement")
            next.tyreToFit?.let { add("monter les ${it.spoken} imposés") }
            if (next.changeTyres && next.tyreToFit == null) add("tes pneus atteignent la durée que tu m'as donnée")
        }
        return buildString {
            append("On rentre fin du tour ${next.endOfLap} pour ")
            append(if (why.isEmpty()) "suivre le plan" else why.joinToString(", et "))
            append(".")
            if (asked != null && asked != next.endOfLap) append(" Le plan prévoit le tour ${next.endOfLap}, pas le $asked.")
        }
    }

    private fun answerExtend(): String {
        val brief = strategyBrief ?: return noPlan
        return if (plan?.nextStop((snapshot.packet?.currentLap ?: 1).coerceAtLeast(1)) == null) "Rien à prolonger : aucun arrêt n'est prévu."
        else "Prolonger d'un tour : ${brief.extendOneLap}"
    }

    private fun answerEarly(): String {
        val report = strategy ?: return noPlan
        val o = report.option(OptionKind.EARLY) ?: return "Aucun arrêt n'est prévu : rien à avancer."
        if (!o.feasible) return "Avancer l'arrêt : non, ${o.issues.firstOrNull() ?: "plan irréalisable"}."
        val delta = o.deltaMs
        val u = report.uncertaintyMs
        val effect = when {
            delta == null || u == null -> "le gain n'est pas chiffrable avec nos données"
            -delta > u -> "plus rapide d'environ ${StrategyOptions.seconds(-delta)}"
            delta > u -> "plus lent d'environ ${StrategyOptions.seconds(delta)}"
            else -> "sans gain démontré"
        }
        return "Avancer l'arrêt au tour ${o.stopLaps.first()} : possible, $effect."
    }

    private fun answerOthersPitting(): String {
        note(DriverNote(snapshot.packet?.currentLap ?: 0, "voitures devant aux stands (déclaré)"))
        val brief = strategyBrief ?: return "Je n'ai aucune information sur leurs écarts ni leurs pneus, et pas encore de plan pour nous."
        return brief.othersPitting.replaceFirstChar { it.uppercase() }
    }

    private fun answerFuelToFinish(): String {
        val brief = strategyBrief ?: return engineer.answer(com.gt7pitwall.core.radio.DriverQuery.FUEL, snapshot, fuel).text
        val next = plan?.nextStop((snapshot.packet?.currentLap ?: 1).coerceAtLeast(1))
        val stop = next?.takeIf { it.forFuel }?.fuel?.let { " Arrêt prévu fin du tour ${next.endOfLap}, environ ${FuelUnits.amount(it.toFloat(), snapshot.packet?.fuelCapacity)}." } ?: ""
        return brief.fuelToFinish.replaceFirstChar { it.uppercase() } + stop
    }

    private fun answerWhatChanged(): String {
        val initial = planLog.firstOrNull() ?: return noPlan
        val now = plan?.stops?.map { it.endOfLap } ?: emptyList()
        fun laps(l: List<Int>) = if (l.isEmpty()) "pas d'arrêt" else "arrêt${if (l.size > 1) "s" else ""} tour ${l.joinToString(" et ")}"
        val lap = (snapshot.packet?.currentLap ?: 1).coerceAtLeast(1)
        val initialAhead = initial.stopLaps
        return buildString {
            if (initialAhead == now || (now.isEmpty() && ledger.stopCount >= initialAhead.size && initialAhead.all { it < lap })) {
                append("Rien n'a changé depuis le plan initial : ${laps(initialAhead)}.")
            } else {
                append("Plan initial : ${laps(initialAhead)}. Maintenant : ${laps(now)}.")
                lastPlanChange?.let { append(" Raison : ${it.reason}") }
            }
            activeObservations.takeIf { it.isNotEmpty() }?.let { obs ->
                append(" Tu m'as signalé : ${obs.joinToString(", ") { it.kind.label }}.")
            }
        }.trim()
    }

    private fun answerFollowUp(): String {
        val report = strategy ?: return noPlan
        val basis = report.basis.take(3).joinToString(", ")
        return when (lastStrategyAsk) {
            StrategyDialogue.Ask.Best, StrategyDialogue.Ask.Extend, StrategyDialogue.Ask.Early ->
                "${firstSentence(report.reason)} Je me base sur : $basis."
            else -> "Je me base sur : $basis.${rulesWarning()}"
        }
    }

    /**
     * Observation du pilote : notée comme déclaration, confrontée aux chronos mesurés. La stratégie n'est réévaluée que
     * si les chronos montrent quelque chose ; aucun pourcentage d'usure n'est inventé.
     */
    private fun answerObservation(ask: StrategyDialogue.Ask.Observe, question: String, extra: MutableList<EngineerMessage>): String {
        if (ask.negated) return "D'accord, rien à signaler de ce côté."
        val lap = snapshot.packet?.currentLap ?: 0
        observations.addLast(Observation(lap, ask.kind, question.trim()))
        while (observations.size > MAX_OBSERVATIONS) observations.removeFirst()
        note(DriverNote(lap, "${ask.kind.label} (déclaré)"))
        if (!telemetryLive()) return "Noté : ${ask.kind.label}. Sans télémétrie, je ne peux pas le confronter à tes chronos."
        val trend = recentTrend()
        val measured = when {
            trend == null -> "Pas encore assez de tours pour le confirmer sur les chronos."
            trend >= 300 -> "Tes chronos le confirment : plus ${StrategyOptions.seconds(trend.toLong())} sur les derniers tours par rapport à ton meilleur du relais."
            else -> "Tes chronos ne le montrent pas pour l'instant."
        }
        val before = plan?.stops?.map { it.endOfLap }
        // Réévaluation : sur les chronos mesurés uniquement (la déclaration seule ne change rien).
        if (ask.kind != StrategyDialogue.ObservationKind.TRAFFIC) extra += replan()
        val after = plan?.stops?.map { it.endOfLap }
        val decision = when {
            ask.kind == StrategyDialogue.ObservationKind.TRAFFIC ->
                "GT7 ne me donne pas les écarts : je ne change pas le plan pour le trafic. Si ton rythme chute, je réévalue."
            before != after -> "Je réévalue : ${lastPlanChange?.reason ?: "le plan change"}"
            strategy?.recommended == OptionKind.EARLY -> "Avancer l'arrêt devient intéressant : ${firstSentence(strategy!!.reason)}"
            else -> "Je garde le plan : aucun gain démontré à changer. Je surveille tes chronos."
        }
        return "Noté : ${ask.kind.label}, d'après toi. $measured $decision"
    }

    private fun note(n: DriverNote) {
        driverNotes += n
        while (driverNotes.size > MAX_NOTES) driverNotes.removeAt(0)
        saveSession()
    }

    /** Écart (ms) entre la moyenne des deux derniers tours propres et le meilleur tour du relais ; null si trop tôt. */
    private fun recentTrend(): Int? {
        val lastStop = (ledger.stopList.map { it.lap } + ledger.changes.map { it.lap }).maxOrNull() ?: 0
        val clean = snapshot.laps.filter { it.lapNumber > maxOf(1, lastStop + 1) && !it.refuelled && (it.timeMs ?: 0) > 0 }
        if (clean.size < 3) return null
        val best = clean.minOf { it.timeMs!! }
        return clean.takeLast(2).map { it.timeMs!! }.average().toInt() - best
    }

    // ------------------------------------------------------------------ Arrêts et gommes

    private fun onPitVisit(e: SessionEvent.PitVisitEnded): List<EngineerMessage> {
        val v = e.visit
        // Avec une durée de vie des pneus, un arrêt sans essence compte aussi : il peut s'agir d'un changement de pneus.
        val hasObligations = rules.mandatoryStops > 0 || rules.requiredTyres.isNotEmpty() || rules.tyreStintLaps != null
        return when (v.assessment) {
            PitAssessment.REFUEL -> {
                val rec = ledger.recordRefuel(v.lap, v.refuelAmount)
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
            StopEvidence.REFUEL -> "ravitaillement de ${FuelUnits.amount(rec.fuelAdded ?: 0f, snapshot.packet?.fuelCapacity)}"
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
        // Toujours demandé : un ravitaillement ne dit rien des pneus, et le suivi des pneus en dépend.
        ledger.awaitingTyreChangeAnswer = true
        return listOf("As-tu changé les pneus ?")
    }

    /** Pneus neufs sans gomme précisée : l'âge des pneus repart de zéro. */
    private fun tyresChanged(lap: Int): List<String> {
        ledger.fitTyres(lap.coerceAtLeast(1), null)
        engineer.onTyresChanged(lap.coerceAtLeast(1))
        val out = mutableListOf("Noté : pneus neufs au tour ${lap.coerceAtLeast(1)}.")
        if (rules.requiredTyres.isNotEmpty()) {
            ledger.awaitingTyreDeclaration = true
            out += "Quelle gomme as-tu montée ?"
        }
        return out
    }

    private var tyreQuestionPackets = 0

    /** Relais pneus en cours pour le diagnostic d'adhérence : indépendant des ravitaillements. */
    private fun tyreStintContext(): com.gt7pitwall.core.tyres.StintContext {
        val lap = (snapshot.packet?.currentLap ?: 1).coerceAtLeast(1)
        val st = ledger.tyreStint(lap, rules.startTyre)
        val excluded = ledger.stopList.flatMap { listOf(it.lap, it.lap + 1) }.toSet() +
            driverNotes.filter { INCIDENT_NOTE.containsMatchIn(it.text) }.map { it.lap }
        return com.gt7pitwall.core.tyres.StintContext(st.startLap, st.certain, excluded, activeObservations.map { it.kind }, engineer.raining)
    }

    private companion object {
        val INCIDENT_NOTE = Regex("(?i)accident|accroch|t[eê]te.?[aà].?queue|sortie de piste|touch[eé]|crash|abîm|abim")
        const val TYRE_QUESTION_PACKETS = 60 * 60
        const val AI_LAPS = 5
        const val AI_HISTORY = 3
        const val AI_CONVERSATION_MS = 45_000L
        const val TELEMETRY_LIVE_MS = 3_000L
        const val MAX_INTENTIONS = 3
        const val MAX_NOTES = 30
        const val MAX_OBSERVATIONS = 6
        const val OBSERVATION_LAPS = 5
        const val STRATEGY_FOLLOW_UP_MS = 45_000L
        const val MAX_PLANS = 40

        val AI_TOPICS = mapOf(
            DriverQuery.FUEL to "Carburant",
            DriverQuery.LAST_LAP to "Dernier tour",
            DriverQuery.BEST_LAP to "Meilleur tour",
            DriverQuery.RACE_STATUS to "Course",
            DriverQuery.PACE to "Rythme",
            DriverQuery.SECTORS to "Secteurs",
            DriverQuery.POSITION to "Position",
            DriverQuery.PLACES to "Places gagnées ou perdues",
            DriverQuery.TYRES to "Pneus",
            DriverQuery.PLAN to "Stratégie",
            DriverQuery.DETAILS to "Détail du dernier tour",
            DriverQuery.OBLIGATIONS to "Obligations du règlement",
            DriverQuery.TYRE_CHANGE to "Changement de pneus",
        )

        /** 45 s après la ligne d'un tour d'arrêt prévu, le temps d'atteindre son stand. */
        const val PIT_WINDOW_PACKETS = 45 * 60

        /** Délai laissé au pilote pour répondre à une question sur l'arrêt ou les pneus avant de recalculer. */
        const val QUESTION_WINDOW_PACKETS = 30 * 60
    }

    /** Réponse attendue qui change le plan (la question sur les pneus ne compte que si leur durée de vie est connue). */
    private val awaitingPlanAnswer: Boolean
        get() = pendingConfirmation != null || ledger.awaitingTyreDeclaration || ledger.pending != null ||
            (ledger.awaitingTyreChangeAnswer && rules.tyreStintLaps != null)

    private val awaitingAnswer: Boolean
        get() = pendingConfirmation != null || ledger.awaitingTyreDeclaration || ledger.awaitingTyreChangeAnswer || ledger.pending != null

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
            engineer.onTyresChanged(lap)
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

    /**
     * Quand changer les pneus. GT7 ne transmet pas leur usure : on s'appuie sur la durée de vie donnée par le pilote,
     * le plan, et les signes d'usure mesurés (chronos qui se dégradent avec plus de glisse).
     */
    fun tyreChangeSentence(): String {
        val lap = (snapshot.packet?.currentLap ?: 1).coerceAtLeast(1)
        val life = rules.tyreStintLaps
        val lastChange = ledger.changes.lastOrNull()?.lap
        val wear = engineer.tyreWearSuspectedLap?.takeIf { w -> lastChange == null || w > lastChange }
        return buildString {
            if (life == null) {
                append("GT7 ne transmet pas l'usure des pneus, je ne peux pas deviner quand les changer.")
                wear?.let { append(" Mais tes chronos se dégradent depuis le tour $it avec plus de glisse : tes pneus fatiguent, change-les au prochain arrêt.") }
                append(" Dis-moi combien de tours ils tiennent, par exemple « les pneus tiennent huit tours », et je planifierai les changements comme pour l'essence.")
                return@buildString
            }
            val age = ledger.tyreAge(lap)
            append("Tes pneus ont $age tour${if (age > 1) "s" else ""} sur $life.")
            val next = plan?.stops?.firstOrNull { it.changeTyres && it.endOfLap >= lap }
            when {
                next != null -> {
                    append(" Changement prévu à la fin du tour ${next.endOfLap}")
                    append(if (next.forFuel) ", avec le ravitaillement." else ", sans essence.")
                }
                age >= life -> append(" Ils ont dépassé leur durée de vie : change-les au prochain arrêt.")
                else -> append(" Ils tiennent jusqu'à l'arrivée.")
            }
            wear?.let { append(" Attention, tes chronos se dégradent depuis le tour $it : ils s'usent peut-être plus vite que prévu.") }
        }
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
                val scope = profile.id.memoryScope
                val key = CarTrackRecord.key(p.carCode, trackId, scope)
                // Mesures prises sous une autre version de GT7 : on repart de zéro plutôt que de les mélanger.
                val old = memory[key]?.takeIf { versionsMatch(it.gameVersion, profile.gameVersion) }
                memory[key] = CarTrackMemory.update(old, p.carCode, trackId, listOf(e.lap), rules.fuelMultiplier, scope, profile.gameVersion)
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
