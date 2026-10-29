package com.gt7pitwall.core.race

import com.gt7pitwall.core.ai.AiFacts
import com.gt7pitwall.core.ai.AiReply
import com.gt7pitwall.core.ai.Intent
import com.gt7pitwall.core.ai.CoachingObservations
import com.gt7pitwall.core.ai.RadioAi
import com.gt7pitwall.core.ai.ConversationRouter
import com.gt7pitwall.core.ai.EngineerPrompt
import com.gt7pitwall.core.ai.Provenance
import com.gt7pitwall.core.ai.RaceMemory
import com.gt7pitwall.core.ai.Topic
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
import com.gt7pitwall.core.radio.EchoFilter
import com.gt7pitwall.core.session.LapRecord
import com.gt7pitwall.core.session.PitAssessment
import com.gt7pitwall.core.session.SessionEvent
import com.gt7pitwall.core.session.SessionSnapshot
import com.gt7pitwall.core.session.SessionTracker
import com.gt7pitwall.core.sport.Edition
import com.gt7pitwall.core.sport.RuleField
import com.gt7pitwall.core.sport.ScheduleState
import com.gt7pitwall.core.sport.SessionMatcher
import com.gt7pitwall.core.sport.SportAnnouncement
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
import com.gt7pitwall.core.strategy.PitApproachReminder
import com.gt7pitwall.core.strategy.RacePlanner
import com.gt7pitwall.core.strategy.RaceRules
import com.gt7pitwall.core.strategy.StopEvidence
import com.gt7pitwall.core.strategy.TyreCompound
import com.gt7pitwall.core.strategy.TyreService
import com.gt7pitwall.core.strategy.TyreInputs
import com.gt7pitwall.core.strategy.StopCosts
import com.gt7pitwall.core.strategy.StopKind
import com.gt7pitwall.core.strategy.Cost
import com.gt7pitwall.core.tyres.TyreModel
import com.gt7pitwall.core.tyres.CompoundBook
import com.gt7pitwall.core.tyres.TyreScenarioReport
import com.gt7pitwall.core.tyres.TyreScenarios
import com.gt7pitwall.core.tyres.ThermalCapture
import com.gt7pitwall.core.tyres.TyreChangeHunter
import com.gt7pitwall.core.tyres.TyreChangeVerdict
import com.gt7pitwall.core.tyres.TyreHunterCalibration
import com.gt7pitwall.core.tyres.GripLevel
import com.gt7pitwall.core.tyres.GripDiagnosis
import com.gt7pitwall.core.telemetry.TelemetryPacket
import kotlin.math.roundToInt
import com.gt7pitwall.core.tyres.TractionMonitor.Level as TractionLevel
import kotlin.math.sqrt

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

    /** Journal de course ([SessionJournal]) : une ligne ajoutée aussitôt, sous l'identifiant de la session archivée. */
    fun appendJournal(sessionId: String, line: String) {}
    fun loadJournal(sessionId: String): List<String> = emptyList()
    fun journalSessionIds(): List<String> = emptyList()

    /** Circuits appris par Pitwall (absents de la base communautaire), JSON ; null au premier lancement. */
    fun loadLearnedTracks(): String? = null
    fun saveLearnedTracks(json: String) {}

    /** Persisted Driver DNA profiles; independent from race session archives. */
    fun loadDriverBook(): String? = null
    fun saveDriverBook(json: String) {}

    /** Mémoire durable du pilote (JSON) ; null au premier lancement. */
    fun loadPilotMemory(): String? = null
    fun savePilotMemory(json: String) {}
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
    val engineer = RaceEngineer().also { e -> e.regulationDoubt = { regulationDoubt() } }
    val ledger = PitLedger()
    val driverCoach = com.gt7pitwall.core.driver.DriverCoach(
        load = { storage?.loadDriverBook() },
        save = { storage?.saveDriverBook(it) },
    )
    private var lastPositionChangeClockMs: Long? = null
    private var gridPosition: Int? = null

    /**
     * Place de départ : la place mesurée stable avant le départ, sinon la grille lue dans le paquet. Le bilan et
     * l'archive utilisent la même (SUGO 27/10 : l'un disait P8, l'autre P1 d'après une grille pas encore chargée).
     */
    private fun startGrid(): Int? = engineer.measured.startPosition ?: gridPosition
    private var finishPosition: Int? = null
    private var briefed = false
    var lastReview: com.gt7pitwall.core.strategy.RaceReview? = null
        private set
    var fuelCorrectionPct: Double? = null
        private set
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

    /** L'arrivée de la session en cours a été franchie (affichage « Course terminée »). */
    val raceFinished: Boolean get() = sessionFinished

    /**
     * Rediffusion (replay) d'une course déjà archivée : GT7 renvoie la télémétrie de la course rejouée, tour par tour.
     * Reconnue quand les deux premiers chronos reproduisent à la milliseconde près ceux d'une course archivée avec la même
     * voiture : rien n'est alors archivé, appris ni annoncé.
     */
    var replay = false
        private set
    private var replaySuspect: SessionRecord? = null
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

    /** Relais pneus comparables appris (gomme actuelle), indépendants du carburant. */
    /** Performance apprise par gomme (rythme, perte par tour d'âge, durée compétitive), courses comparables seulement. */
    var compoundBook: CompoundBook = CompoundBook.EMPTY
        private set
    var tyreModel: TyreModel = TyreModel.EMPTY
        private set

    /** Tyre Change Hunter : diagnostic thermique expérimental des passages aux stands (n'agit jamais seul sur les pneus). */
    val tyreHunter = TyreChangeHunter()

    /** Diagnostic du dernier passage, en attente de l'étiquette du pilote (« pneus neufs » / « mêmes pneus »). */
    private var lastThermal: ThermalCapture? = null

    /** L'arrêt sans ravitaillement en attente a été proposé à cause d'une signature thermique : « oui » = pneus changés. */
    private var pendingStopTyreSuspect = false
    private var tyreDeclarationPackets = 0
    private var calibrationCache: List<TyreHunterCalibration.Example>? = null
    private val sessionExamples = mutableListOf<TyreHunterCalibration.Example>()

    /** Changements de pneus de performance retenus par la stratégie (fin de ces tours). */
    var performanceTyreLaps: List<Int> = emptyList()
        private set
    var fuel: FuelReport? = null
        private set
    var gForce: GForce? = null
        private set
    var trackCandidates: List<Course> = emptyList()
        private set
    private var candidatesAnnounced = false

    init {
        database.restoreLearned(Gt7Database.learnedFrom(storage?.loadLearnedTracks()))
    }

    /**
     * Paquets restants pendant lesquels le plan est gelé après la ligne d'un tour d'arrêt prévu : selon le circuit,
     * on franchit la ligne dans la voie des stands avant d'avoir ravitaillé. Sans ce gel, l'ingénieur relancerait
     * « box » et annoncerait un nouveau plan en pleine voie des stands.
     */
    private var pitWindowPackets = 0

    /** Dernière fois que la voiture roulait en course (horloge du contrôleur). */
    private var lastDrivenMs = 0L

    /** Heure de déclaration de la gomme de départ ; null : venue d'un règlement mémorisé, sans date. */
    private var startTyreDeclaredMs: Long? = null

    /**
     * Course de Fuji du 28/10 (#47) : le règlement mémorisé portait « durée 2 min » (deux commandes fantômes de la course
     * précédente, #45) alors que GT7 annonçait 10 tours. Une course en tours n'a pas de durée : la valeur est retirée du
     * règlement actif, jamais exportée comme règle de cette course.
     */
    private fun discardContradictedDuration(p: TelemetryPacket) {
        val minutes = rules.durationMin ?: return
        if (p.totalLaps <= 0) return
        rules = profiles.discard(rules.copy(durationMin = null), RuleField.LAPS).rules
        storage?.saveRules(rules)
        storage?.saveProfiles(profiles)
        journal.record(
            JournalType.DECISION, JournalSource.TELEMETRY,
            "Durée mémorisée de $minutes min écartée : GT7 annonce une course de ${p.totalLaps} tours",
            decision = "règlement actif sans durée ; à reconfirmer", details = mapOf("duree_min_ecartee" to minutes.toString(), "tours_gt7" to p.totalLaps.toString()),
        )
    }

    /**
     * Gomme de départ d'une course précédente (#47) : déclarée avant que la voiture ne roule à nouveau, ou mémorisée sans
     * date. Elle n'est pas reprise comme « montée » pour la nouvelle course ; une déclaration faite depuis la dernière
     * fois que la voiture a roulé (menus, grille) est gardée.
     */
    private fun forgetStaleStartTyre() {
        val tyre = rules.startTyre ?: return
        val declared = startTyreDeclaredMs
        val stale = if (declared == null) lastDrivenMs > 0 || sessions.isNotEmpty() else declared < lastDrivenMs
        if (!stale) return
        rules = profiles.discard(rules.copy(startTyre = null), RuleField.ALLOWED_TYRES).rules
        startTyreDeclaredMs = null
        storage?.saveRules(rules)
        storage?.saveProfiles(profiles)
        journal.record(
            JournalType.DECISION, JournalSource.ENGINE,
            "Gomme de départ ${tyre.name} d'une course précédente non reprise",
            decision = "gomme inconnue tant que tu ne la déclares pas pour cette course",
        )
    }

    /** Un recalcul a été demandé pendant la visite au stand : il sera fait à sa sortie (issue #47). */
    private var replanDeferred = false
    private val pitApproachReminder = PitApproachReminder()
    private var driverRequestedPitLap: Int? = null
    private var sportRuleMismatchWarned = false
    /** Déclaration vocale pendant CE passage physique aux stands, à rapprocher du plein mesuré. */
    private var driverDeclaredDuringPitNumber: Int? = null
    private var tyreLifeAsked = false

    /** Derniers échanges vérifiés avec l'IA, pour les relances (« et au freinage ? »). */
    private val aiExchanges = ArrayDeque<RadioAi.Exchange>()
    private var lastAiAnswerMs: Long? = null

    val aiHistory: List<RadioAi.Exchange> get() = aiExchanges.toList()

    /** Mémoire de la course (intentions, plans, décisions, faits confirmés, échanges) pour la conversation. */
    val raceMemory = RaceMemory()

    /** Session en cours (pour invalider une réponse de l'IA préparée pendant une autre session). */
    val currentSessionId: String? get() = sessionId

    /** Contexte sportif de la course (ton de l'ingénieur), déduit des événements mesurés. */
    val mood = com.gt7pitwall.core.ai.MoodTracker(clock)

    /** Mémoire durable de la relation pilote-ingénieur (préférences confirmées), consultable et effaçable. */
    val pilotMemory = com.gt7pitwall.core.ai.PilotMemory(load = { storage?.loadPilotMemory() }, save = { storage?.savePilotMemory(it) }, clock = clock)

    // Dialogue : désaccord en attente de la raison du pilote, décision ou préférence durable à confirmer.
    private var awaitingReasonUntil = 0L
    private var pendingPilotDecision: Pair<String, Long>? = null
    private var pendingLasting: Pair<String, Long>? = null
    private var lastSaidPosition: Int? = null
    private var lastCelebrationMs = 0L

    /** Sujet du dernier échange avec l'IA (une relance courte le garde). */
    val lastAiIntent: Intent? get() = aiExchanges.lastOrNull()?.intent

    private var lastPacketMs: Long? = null
    private var lastRawPacket: TelemetryPacket? = null
    private var frozenPackets = 0
    private var frozenNoted = false
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

    /** Journal de course intégral, enregistré au fil de l'eau sous l'identifiant de la session archivée. */
    val journal = SessionJournal(storage, clock) { journalTelemetry() }

    /** Origine de la commande en cours d'application (écran par défaut ; voix ou Gemini via [voiceCommand]). */
    private var commandOrigin = "écran"

    private fun journalTelemetry(): JournalTelemetry? {
        val p = snapshot.packet ?: return null
        val m = engineer.measured
        return JournalTelemetry(
            lap = p.currentLap,
            totalLaps = p.totalLaps.takeIf { it > 0 },
            speedKmh = p.speedKmh,
            fuel = p.fuelLevel,
            fuelCapacity = p.fuelCapacity.takeIf { it > 0f },
            // Position mesurée seulement (0x84) : une place déclarée n'est pas une mesure.
            position = m.finalPosition ?: m.position,
            cars = m.finalCars ?: m.cars,
            lapTimeMs = p.currentLapTimeMs?.takeIf { it > 0 },
            tyreTemps = p.tyreTemps.toList(),
            gear = p.currentGear,
            inPit = tracker.pitVisitOpen,
        )
    }

    val snapshot: SessionSnapshot get() = tracker.snapshot
    val course: Course? get() = rules.trackId?.let(database::course)
    /** Voiture lue dans 0x124 sur tous les paquets, menus compris (issue #40). */
    val carIdentity = com.gt7pitwall.core.telemetry.CarIdentity()

    /** Voiture à afficher : la dernière détectée par GT7, sinon celle de la session en cours. */
    val displayCarCode: Int? get() = carIdentity.current ?: snapshot.packet?.carCode?.takeIf { it > 0 }

    /**
     * Voiture changée depuis la session suivie (changement au garage, GT7 dans les menus) : la session, son historique et
     * son plan concernent encore l'ancienne voiture. Rien de tout cela n'est présenté pour la nouvelle.
     */
    val carChangePending: Boolean
        get() = carIdentity.current.let { c -> c != null && snapshot.packet?.carCode?.let { it > 0 && it != c } == true }

    val carName: String? get() = displayCarCode?.let { database.car(it)?.fullName }
    /** Mémoire voiture × circuit de l'épreuve active ; écartée si elle a été prise sous une autre version de GT7. */
    val memoryRecord: CarTrackRecord?
        get() = snapshot.packet?.let { p -> rules.trackId?.let { memory[CarTrackRecord.key(p.carCode, it, profile.id.memoryScope)] } }
            ?.takeIf { versionsMatch(it.gameVersion, profile.gameVersion) }

    /** Contexte de la session en cours, pour l'archive et les comparaisons. */
    val sessionContext: SessionContext
        get() = SessionContext(
            profile, rules, snapshot.packet?.carCode, snapshot.packet?.fuelCapacity?.takeIf { it > 0f },
            snapshot.packet?.totalLaps?.takeIf { it > 0 },
        )

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
        val packetMs = clock()
        lastPacketMs = packetMs
        if (zeroTouch.first(Milestone.UDP_CONNECTED) == null) milestone(Milestone.UDP_CONNECTED, "télémétrie", "mesurée")
        // Avant tout filtre : la voiture se lit aussi dans les menus (tour négatif) et sur une télémétrie figée.
        carIdentity.onPacket(p, packetMs)?.let(::journalCarChange)
        // Données figées (course réelle du 21/10 : après l'arrivée, puis au menu, GT7 renvoie le même instantané pendant
        // des minutes, vitesse 79,6 puis 178 km/h, position immobile) ou tour négatif (menus) : la voiture ne roule pas.
        // Rien n'est suivi ni annoncé, aucune session ne commence.
        val prevRaw = lastRawPacket
        lastRawPacket = p
        frozenPackets = if (prevRaw != null && p.speedKmh > 1f && p.positionX == prevRaw.positionX &&
            p.positionZ == prevRaw.positionZ && p.speedMps == prevRaw.speedMps
        ) {
            frozenPackets + 1
        } else {
            0
        }
        val stale = frozenPackets >= FROZEN_PACKETS
        if (stale || p.currentLap < 0) {
            lastPacketOnTrack = false
            if (stale && !frozenNoted && journal.sessionId != null) {
                frozenNoted = true
                journal.record(JournalType.SESSION, JournalSource.TELEMETRY, "Télémétrie figée : voiture considérée à l'arrêt, plus rien n'est suivi")
            }
            return emptyList()
        }
        frozenNoted = false
        // Essai réel : à 91 km/h en piste, le drapeau « en piste » de GT7 n'était pas levé. On retient donc toute session
        // qui envoie des paquets hors chargement (voiture en piste, en mouvement, ou course en cours).
        lastPacketOnTrack = !p.isLoading && (p.isOnTrack || p.speedKmh > 1f || p.currentLap > 0)
        if (!p.isLoading && !p.isPaused && p.currentLap >= 1 && p.speedKmh > 5f) lastDrivenMs = clock()
        val events = tracker.update(p)
        if (events.any { it is SessionEvent.SessionReset }) {
            // Une transition de session ne reprend rien : ni capture thermique, ni diagnostic en attente d'étiquette.
            tyreHunter.reset()
            lastThermal = null
            pendingStopTyreSuspect = false
        }
        // Mesure thermique seulement en course : ni pause, ni chargement, ni après l'arrivée (essai réel : tour 11/10).
        val raceActive = p.currentLap >= 1 && !sessionFinished && (p.totalLaps <= 0 || p.currentLap <= p.totalLaps) &&
            !p.isPaused && !p.isLoading
        tyreHunter.onPacket(p, tracker.pitVisitOpen, raceActive)?.let(::journalThermal)
        gMeter.update(p)?.let { gForce = it }
        // Position mesurée (0x84) : seulement en course ; en qualification, elle n'a pas été validée.
        if (events.any { it is SessionEvent.SessionReset }) positionTracker.reset()
        positionTracker.enabled = profile.id.kind == SessionKind.RACE
        val nowMs = clock()
        val positionChanges = runCatching { positionTracker.onPacket(p, nowMs) }.getOrElse { diagnostic("position", it); emptyList() }
        // Nouvelle session : la précédente est archivée avant que le registre des arrêts soit vidé.
        if (events.any { it is SessionEvent.SessionReset }) {
            closeSession(SessionOutcome.INTERRUPTED)
            // Une nouvelle semaine différée pendant la course précédente s'applique maintenant.
            schedule?.let { pendingSchedule = it }
        }
        val finishedBefore = sessionFinished && events.any { it is SessionEvent.SessionReset }
        if (events.any { it is SessionEvent.SessionReset || it is SessionEvent.FirstData }) startSession()
        // Le plan de la session précédente ne sert pas de référence au premier plan de celle-ci (issue #30).
        if (events.any { it is SessionEvent.SessionReset }) engineer.newSession()
        // Le journal ne s'ouvre qu'une fois la course lancée : un passage par la grille ou un menu ne crée pas de session.
        if (sessionId != null) discardContradictedDuration(p)
        if (sessionId != null && journal.sessionId != sessionId && p.currentLap >= 1) beginJournal()
        if (!replay) events.filterIsInstance<SessionEvent.LapCompleted>().forEach { checkReplay(it.lap) }
        if (replay) return emptyList()
        val coached = if (sessionFinished) emptyList() else runCatching { driverCoach.onPacket(p, tracker.pitVisitOpen) }.getOrElse { diagnostic("profil de pilotage (paquet)", it); emptyList() }
        if (positionChanges.isNotEmpty()) lastPositionChangeClockMs = pacer.packetClockMs
        journalPositions(positionChanges)
        val out = mutableListOf<EngineerMessage>()
        if (events.isEmpty()) out += journalCoaching(coached)
        if (!sessionFinished) out += runCatching { tractionCheck() }.getOrElse { diagnostic("motricité", it); emptyList() }
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
        // « Tu as monté quelle gomme ? » sans réponse : la question expire, la gomme reste inconnue (jamais devinée).
        if (ledger.awaitingTyreDeclaration && rules.requiredTyres.isEmpty()) {
            if (++tyreDeclarationPackets > TYRE_QUESTION_PACKETS) {
                ledger.awaitingTyreDeclaration = false
                tyreDeclarationPackets = 0
            }
        } else {
            tyreDeclarationPackets = 0
        }
        if (events.isNotEmpty()) {
            if (events.any { it is SessionEvent.SessionReset }) {
                ledger.reset()
                radio.reset()
                trackCandidates = emptyList()
                candidatesAnnounced = false
                pitWindowPackets = 0
                pitApproachReminder.reset()
                driverRequestedPitLap = null
                sportRuleMismatchWarned = false
                aiExchanges.clear()
                raceMemory.clear()
                intentions.clear()
                observations.clear()
                lastStrategyAnswerMs = null
                rulesReminded = false
                pendingConfirmation = null
                mood.reset()
                awaitingReasonUntil = 0L
                pendingPilotDecision = null
                lastSaidPosition = null
                lastAiAnswerMs = null
                tyreLifeAsked = false
                pendingTyreFamily = null
            }
            out += learn(events)
            if (!sessionFinished) out += coachLaps(events)
            out += journalCoaching(coached)
            journalEvents(events)
            if (events.any { it is SessionEvent.RaceFinished }) sessionFinished = true
            if (events.any { it is SessionEvent.LapCompleted }) out += rulesReminder()
            for (e in events) {
                if (e is SessionEvent.LapCompleted && plan?.stops?.any { it.endOfLap == e.lap.lapNumber } == true) {
                    pitWindowPackets = PIT_WINDOW_PACKETS
                }
                if (e is SessionEvent.PitVisitEnded) {
                    driverRequestedPitLap = null
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
        if (sessionId != null && !sessionFinished && !replay) trackGeometry(p, raceActive, out)
        if (sessionId != null && !sessionFinished && !replay) gridPositionProbe(p, out)
        if (sessionId != null && !sessionFinished && !sessionIdentitySettled && snapshot.laps.size <= 2) identifySession(out)
        if (sessionId != null && !sessionFinished) applyHandoff(p, out)
        if (sessionId != null && !sessionFinished) sportCandidateWithoutLaps(p, out)
        lastTotalLaps = p.totalLaps
        lastSessionPacketMs = packetMs
        if (p.carCode > 0) lastCarCode = p.carCode
        phaseMilestones(p, events)
        val frozen = tracker.pitVisitOpen || pitWindowPackets > 0
        if (!frozen && (events.isNotEmpty() || windowWasOpen)) out += replan()
        plan?.let { pl ->
            if (sessionId != null) {
                milestone(Milestone.STRATEGY_READY, "plan", if (pl.startTyreUnknown) "conditionnelle" else "calculée", "${pl.totalLaps} tours, ${pl.stops.size} arrêt(s)")
                if (pl.startTyreUnknown) milestone(Milestone.TYRE_UNCONFIRMED, "pilote", "inconnue", "gommes imposées : ${pl.requiredTyres.joinToString(", ") { it.name }}")
            }
        }
        fuel = FuelStrategy.compute(tracker.snapshot, plan?.totalLaps)
        engineer.measured = positionTracker.snapshot(nowMs)
        engineer.stintContext = tyreStintContext()
        // Retour au menu après une course finie : pas de « nouvelle session » annoncée.
        out.addAll(0, engineer.onUpdate(events, tracker.snapshot, fuel).filterNot { finishedBefore && it.text.startsWith("Nouvelle session détectée") })
        out += celebrate(engineer.onPosition(positionChanges, nowMs), nowMs)
        if (events.any { it is SessionEvent.RaceFinished }) {
            finishPosition = engineer.measured.finalPosition ?: engineer.driverPosition
            out += drivingReportAtFinish()
        }
        out += briefingIfDue(p)
        out += localRulesQuestionIfDue(p)
        out += finalSectorPitCall(p)
        updateMood(events, positionChanges, p)
        if (events.any { it is SessionEvent.LapCompleted || it is SessionEvent.RaceFinished || it is SessionEvent.PitVisitEnded }) saveSession()
        // Messages non urgents différés pendant un gros freinage ou un virage (au plus quelques secondes).
        if (events.any { it is SessionEvent.SessionReset }) pacer.clear()
        val busy = pacer.busy(p)
        return deliver(pacer.pace(out, busy, pacer.packetClockMs, p.currentLap))
    }


    /** A second, one-shot call in Pitwall's last equal-distance sector. */
    private fun finalSectorPitCall(p: TelemetryPacket): List<EngineerMessage> {
        if (!raceMode || sessionFinished || p.isPaused || p.isLoading || p.speedKmh < 20f) return emptyList()
        // Wait for a calmer point in the last sector; never call "Box" at a heavy braking point.
        if (pacer.busy(p)) return emptyList()
        val end = p.totalLaps.takeIf { it > 0 } ?: return emptyList()
        // Manual, *confirmed* "I pit this lap" is independent of a potentially
        // misidentified weekly profile. Automated calls require a matching event.
        val voluntary = driverRequestedPitLap == p.currentLap
        val reference = if (voluntary) null
            else if (profile.id.sport) edition?.laps?.value ?: return emptyList()
            else null
        val planned = if (voluntary) com.gt7pitwall.core.strategy.PlannedStop(
            endOfLap = p.currentLap, fuel = null, forFuel = false, mandatory = false,
        ) else plan?.nextStop(p.currentLap)
        val progress = tracker.liveLapProgress()
        val stop = pitApproachReminder.onProgress(
            lap = p.currentLap, totalLaps = end, referenceLaps = reference,
            progress = progress, plannedStop = planned,
            pitVisitOpen = tracker.pitVisitOpen,
        ) ?: return emptyList()
        // Course du 27/10 : « Dernier secteur. Box à la fin de ce tour. Sans ravitaillement. » était trop long. Le pilote
        // veut l'ordre seul ; une précision seulement si elle change ce qu'il fait au stand (gomme à monter, pneus).
        // Le carburant et la réserve réglementaire ont été dits au début du tour.
        val instruction = buildString {
            append("Box à la fin de ce tour.")
            // Règlement non confirmé (Sport reconnue, fiche incomplète, règles locales) : la gomme n'est pas un ordre.
            if (stop.tyreToFit != null) append(" Monte les ${stop.tyreToFit.spoken}" + (if (regulationDoubt() != null) ", si le règlement l'impose." else "."))
            else if (stop.changeTyres) append(" Change les pneus.")
        }
        journal.record(
            JournalType.STRATEGY, JournalSource.ENGINE,
            "Rappel de stand dernier secteur Pitwall : ${stop.endOfLap}",
            lap = p.currentLap, details = mapOf(
                "fraction_piste" to "%.2f".format(java.util.Locale.ROOT, progress ?: 0f),
                "preuve" to "tour complet précédent et arrêt du plan",
                "reglement" to (regulationDoubt()?.let { "à confirmer : $it" } ?: "confirmé ou sans obligation"),
            ),
        )
        return listOf(
            EngineerMessage(instruction, Priority.CRITICAL, MessageCategory.ALERT,
                dedupKey = "approche-stand:$sessionId:${stop.endOfLap}", topic = "pit-approach",
                validUntilLap = p.currentLap),
        )
    }

    private val pacer = com.gt7pitwall.core.radio.RadioPacer()

    /** Même traitement pour les boutons de l'écran et les commandes vocales. */
    fun command(command: DriverCommand): List<EngineerMessage> {
        // Les questions ne changent rien : seules les commandes qui modifient l'état sont notées comme décisions.
        if (command !is DriverCommand.Ask && command != DriverCommand.Repeat) {
            journal.record(
                JournalType.DECISION, JournalSource.ENGINE, "Commande appliquée : $command",
                decision = "appliquée", details = mapOf("origine" to commandOrigin),
            )
        }
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
                pendingConfirmation?.takeIf { clock() - pendingConfirmationMs <= CONFIRMATION_WINDOW_MS }?.let { pending ->
                    pendingConfirmation = null
                    return command(pending)
                }
                pendingConfirmation = null
                val suspect = pendingStopTyreSuspect
                pendingStopTyreSuspect = false
                val confirmed = ledger.confirmPending()
                out += answer(
                    when {
                        // Arrêt proposé sur une signature thermique : « oui » répondait à « tu as changé les pneus ? ».
                        confirmed != null && suspect -> stopRecorded(confirmed) + tyresChanged(lap)
                        confirmed != null -> stopRecorded(confirmed) + afterStopQuestions()
                        ledger.awaitingTyreChangeAnswer -> tyresChanged(lap)
                        localRulesQuestionOpen() -> {
                            localRulesPendingMs = null
                            localRulesConfirmed = true
                            journal.record(JournalType.DECISION, JournalSource.TRANSCRIPT, "Règlement local confirmé par le pilote pour cette course",
                                decision = "obligations mémorisées appliquées", details = localRulesDetails())
                            listOf("Compris, je garde ce règlement pour cette course.")
                        }
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
                        ledger.rejectPending() -> if (pendingStopTyreSuspect) {
                            pendingStopTyreSuspect = false
                            labelThermal(changed = false)
                            listOf("D'accord, pas de changement de pneus : je ne compte pas cet arrêt. Dis « arrêt fait » si c'en était un.")
                        } else {
                            listOf("D'accord, je ne compte pas cet arrêt.")
                        }
                        ledger.awaitingTyreChangeAnswer || (ledger.awaitingTyreDeclaration && ledger.changeForPassage(lap.coerceAtLeast(1)) == null) -> {
                            ledger.keepTyres(lap)
                            labelThermal(changed = false)
                            val refuel = ledger.stopList.lastOrNull()?.takeIf { lap - it.lap in 0..2 }?.refuelled == true
                            listOf(if (refuel) "Plein terminé, on conserve le même train de pneus." else "D'accord, mêmes pneus : leur relais continue.")
                        }
                        ledger.awaitingTyreDeclaration -> {
                            // « Tu as monté quelle gomme ? » → « non » : la gomme reste inconnue, le changement reste noté.
                            ledger.awaitingTyreDeclaration = false
                            listOf("D'accord, gomme non précisée : je ne la devine pas.")
                        }
                        localRulesQuestionOpen() -> {
                            localRulesPendingMs = null
                            localRulesConfirmed = true
                            val fields = mutableSetOf(RuleField.MANDATORY_STOPS)
                            if (rules.requiredTyres.isNotEmpty()) fields += RuleField.REQUIRED_TYRES
                            val was = profile.confirmed
                            rules = profiles.declare(rules.copy(mandatoryStops = 0, requiredTyres = emptySet()), fields).rules
                            noteConfirmationDrop(was, "obligations locales écartées à la radio")
                            storage?.saveRules(rules)
                            storage?.saveProfiles(profiles)
                            journal.record(JournalType.DECISION, JournalSource.TRANSCRIPT, "Règlement local écarté par le pilote pour cette course",
                                decision = "aucune obligation d'arrêt ni de gomme", details = mapOf("provenance" to "déclaré par le pilote"))
                            listOf("D'accord : pas d'arrêt obligatoire ni de gomme imposée pour cette course.")
                        }
                        else -> listOf("Je n'attendais pas de réponse.")
                    },
                )
                out += replanOrWait()
            }
            is DriverCommand.TyresKept -> {
                // Arrêt proposé sur une signature thermique : « mêmes pneus » y répond comme « non ».
                if (pendingStopTyreSuspect && ledger.pending != null) return command(DriverCommand.No)
                val recent = ledger.stopList.lastOrNull()?.takeIf { lap - it.lap in 0..2 }
                val open = ledger.awaitingTyreChangeAnswer ||
                    (ledger.awaitingTyreDeclaration && ledger.changeForPassage(lap.coerceAtLeast(1)) == null) ||
                    recent?.tyres == TyreService.UNKNOWN
                out += answer(
                    if (open) {
                        ledger.keepTyres(lap)
                        labelThermal(changed = false)
                        listOf(if (recent?.refuelled == true) "Plein terminé, on conserve le même train de pneus." else "D'accord, mêmes pneus : leur relais continue.")
                    } else {
                        listOf("D'accord, je ne note aucun changement de pneus.")
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
                // « Annule l'arrêt » : l'arrêt enregistré s'il est récent (déclaré ou détecté ce tour-ci ou le
                // précédent) ; sinon, c'est l'arrêt PRÉVU dont le pilote parle (course du 27/10).
                val last = ledger.stopList.lastOrNull()
                if (last != null && last.lap >= lap - 1) {
                    val removed = ledger.cancelLast()
                    out += answer(listOf(if (removed != null) "Arrêt ${removed.number} retiré du décompte." else "Aucun arrêt à retirer."))
                    out += replan()
                } else {
                    out += cancelPlannedStop(lap)
                }
            }
            DriverCommand.CancelPlannedStop -> out += cancelPlannedStop(lap)
            is DriverCommand.Tyres -> {
                out += answer(tyres(command, lap))
                out += replanOrWait()
            }
            is DriverCommand.Ask -> out += if (command.query in com.gt7pitwall.core.engineer.DrivingAnalysis.QUERIES) {
                answer(listOf(drivingAnswer(command.query)))
            } else if (command.query == DriverQuery.DETAILS && engineer.quietRace) {
                // Analyse complète, seulement sur demande : tour, carburant, pneus et plan.
                answer(
                    listOfNotNull(
                        engineer.answer(DriverQuery.DETAILS, snapshot, fuel).text,
                        tyreSummary().takeIf { snapshot.packet != null },
                        plan?.let { engineer.answer(DriverQuery.PLAN, snapshot, fuel).text },
                    ),
                )
            } else if (command.query == DriverQuery.OBLIGATIONS) {
                answer(listOf(obligationsSentence()))
            } else if (command.query == DriverQuery.STOP_STATUS) {
                answer(listOf(stopStatusSentence()))
            } else if (command.query == DriverQuery.TYRE_CHANGE) {
                answer(listOf(tyreChangeSentence()))
            } else if (command.query == DriverQuery.NEXT_TYRE) {
                answer(listOf(nextTyreSentence()))
            } else if (command.query == DriverQuery.TYRES && snapshot.packet != null) {
                answer(listOf(tyreSummary()))
            } else {
                listOf(engineer.handle(command, snapshot, fuel))
            }.map { m ->
                // Question simple en course : une ou deux phrases ; le détail reste sur « détails ».
                if (engineer.quietRace && command.query !in DETAILED_QUERIES && m.category == MessageCategory.ANSWER) {
                    m.copy(text = com.gt7pitwall.core.radio.Brevity.short(m.text))
                } else {
                    m
                }
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
    fun utterance(text: String, original: String = text, source: CommandSource = CommandSource.VOICE): List<EngineerMessage> {
        val echo = EngineerMessage("Toi : $text", Priority.INFO, MessageCategory.DRIVER)
        // « La C », « B » juste après « Course de 11 tours : B ou C ? » : c'est la réponse.
        (seriesAnswer(original) ?: seriesAnswer(text))?.let { return listOf(echo) + chooseSeries(it) }
        // « Course » seul, juste après « tendres : course, sport ou confort ? » : c'est la réponse.
        tyreFamilyAnswer(original)?.let { return listOf(echo) + voiceCommand(it, it.text, source) }
        // « Moyens », « medium », « course tendres » juste après « Tu as monté quelle gomme ? » : c'est la réponse.
        (compoundAnswer(original) ?: compoundAnswer(text))?.let { return listOf(echo) + voiceCommand(it, it.text, source) }
        // Question de pilotage reconnue sur la phrase libre quand le vocabulaire radio l'a déformée (26/10 : « est ce
        // quelle tour sont mon » pour « puisque mes tours sont bons »). Seulement une question : jamais une commande.
        val parsed = DriverCommandParser.parse(text)
        val driving = com.gt7pitwall.core.engineer.DrivingAnalysis.classify(original)
            ?.takeIf { parsed == null || parsed is DriverCommand.Ask }?.let { DriverCommand.Ask(it) }
        val cmd = driving ?: parsed
        return listOf(echo) + if (cmd == null) deliver(listOf(engineer.notUnderstood(text))) else voiceCommand(cmd, original, source)
    }

    /** Commande en attente du « oui » du pilote (déclaration incertaine, ou proposée par Gemini). */
    private var pendingConfirmation: DriverCommand? = null
        set(value) {
            field = value
            pendingConfirmationMs = clock()
        }
    /** Heure de la question de confirmation : un « oui » plus tardif ne l'applique plus. */
    private var pendingConfirmationMs = 0L

    /** Intentions et préférences du pilote (pas des faits) : données à l'IA pour discuter la stratégie. */
    private val intentions = ArrayDeque<String>()

    private fun gateContext(): GateContext {
        val lap = snapshot.packet?.currentLap ?: 0
        val recentDeclared = ledger.stopList.lastOrNull()
            ?.takeIf { it.evidence == StopEvidence.DRIVER_DECLARED && lap - it.lap in 0..1 }?.number
        return GateContext(
            telemetryLive(), recentDeclared, ledger.awaitingTyreChangeAnswer,
            awaitingTyreDeclaration = ledger.awaitingTyreDeclaration,
            noStopYet = ledger.stopCount == 0 && ledger.changes.isEmpty(),
            position = engineer.driverPosition,
            finalPosition = engineer.measured.finalPosition ?: engineer.currentPosition?.takeIf { sessionFinished },
            recentEngineer = if (echoSensitive) recentEngineer() else emptyList(),
            gt7TotalLaps = snapshot.packet?.totalLaps?.takeIf { it > 0 && telemetryLive() },
        )
    }

    /** Décision du garde pour une commande vocale, sans l'appliquer (la conversation choisit qui répond). */
    fun vet(command: DriverCommand, text: String, source: CommandSource = CommandSource.VOICE): Vetting =
        CommandGate.vet(command, text, source, gateContext())

    /**
     * Commande vocale (Vosk, Google ou Gemini) : validée par [CommandGate] avant de modifier quoi que ce soit.
     * Les boutons de l'écran passent par [command] directement.
     */
    fun voiceCommand(command: DriverCommand, text: String, source: CommandSource): List<EngineerMessage> {
        // Une autre phrase que « oui » / « non » abandonne la confirmation en attente : rien n'est appliqué. Sauf une
        // question sur l'arrêt dont on parle (27/10 : « as-tu enlevé l'arrêt ? » entre la demande et le « oui »).
        val aboutPendingStop = command is DriverCommand.Ask &&
            command.query in setOf(DriverQuery.STOP_STATUS, DriverQuery.OBLIGATIONS, DriverQuery.PLAN) &&
            (pendingConfirmation == DriverCommand.CancelPlannedStop || pendingConfirmation == DriverCommand.CancelStop)
        if (command != DriverCommand.Yes && command != DriverCommand.No && !aboutPendingStop) pendingConfirmation = null
        if (command !is DriverCommand.Tyres && command != DriverCommand.Yes) pendingTyreFamily = null
        val v = vet(command, text, source)
        journal.record(
            JournalType.DECISION, JournalSource.ENGINE, "« ${text.trim()} » compris comme ${commandName(command)}",
            decision = when (v) {
                is Vetting.Execute -> if (v.command is DriverCommand.Ask || v.command == DriverCommand.Repeat) {
                    "question : réponse calculée par le moteur, rien modifié"
                } else {
                    "appliquée"
                }
                is Vetting.Redirect -> "redirigée vers ${commandName(v.command)}"
                is Vetting.Confirm -> "confirmation demandée : ${v.question}"
                is Vetting.Discuss -> "rien modifié (${v.mode.name.lowercase()}) : ${v.reply}"
                is Vetting.Ignore -> "rien modifié, pas de réponse parlée : ${v.reason}"
            },
            details = mapOf("origine" to source.name.lowercase()),
        )
        commandOrigin = source.name.lowercase()
        try {
            return voiceDecision(v)
        } finally {
            commandOrigin = "écran"
        }
    }

    private fun commandName(c: DriverCommand): String = c.toString().substringBefore('(').substringAfterLast('.').substringAfterLast('$')

    private fun voiceDecision(v: Vetting): List<EngineerMessage> {
        return when (v) {
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
            is Vetting.Ignore -> listOf(EngineerMessage("🎙 Rien modifié (${v.reason})", Priority.INFO, MessageCategory.DRIVER))
        }
    }

    /** Intention ou préférence du pilote, gardée pour la discussion stratégique (jamais un événement de course). */
    fun noteIntention(text: String) {
        raceMemory.intention(snapshot.packet?.currentLap ?: 0, clock(), text)
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
                raceMemory.exchange(
                    RaceMemory.Exchange(
                        currentLap(), nowMs, question, reply.text, "Gemini Nano",
                        when (intent) { Intent.COACHING -> Topic.COACHING; Intent.STRATEGY -> Topic.PLAN; else -> Topic.OTHER },
                    ),
                )
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
        lines += "GT7 transmet ta place en course (champ 0x84) et le nombre de voitures (0x86), mais pas les écarts, les arrêts ni les pneus des adversaires. GT7 ne transmet pas l’usure des pneus, la gomme réellement montée ni la météo. Les seules températures ne prouvent pas combien de tours un train peut encore tenir."
        lines += "Épreuve : ${profile.id.label}" + if (profile.confirmed) ", règlement confirmé par le pilote." else ", règlement non confirmé."
        edition?.let { ed ->
            lines += "Programme de la semaine (${ed.sources.joinToString { it.name }}) : " +
                listOfNotNull(ed.trackName.value, ed.cars.value?.let { "voitures $it" }, ed.bop.value?.let { "BoP $it" }).joinToString(", ") + "."
        }
        ruleGaps.takeIf { it.isNotEmpty() }?.let { g -> lines += "Règlement inconnu sur : ${g.joinToString(", ") { it.label }}." }
        // Sans télémétrie en direct, aucune valeur « du moment » (position, carburant, plan, tour en cours) : ce serait
        // celle d'une ancienne session, présentée comme actuelle (essai réel : « Tu es P8 » télémétrie arrêtée).
        val live = telemetryLive()
        if (!live) {
            lines += "Aucune télémétrie de course en ce moment : aucune position, aucun tour en cours, aucun carburant ni plan en direct."
            carName?.let { lines += "Voiture de la dernière session : $it." }
            course?.let { lines += "Circuit de la dernière session : ${it.label}." }
            lines += "Obligations du règlement : ${obligationsSentence()}"
            for (lap in snap.laps.takeLast(AI_LAPS)) {
                lap.timeMs?.takeIf { it > 0 }?.let { lines += "Dernière session, tour ${lap.lapNumber} : ${FrenchFormat.lapTimeSpeech(it)} (mesuré, course terminée ou interrompue)." }
            }
            return AiFacts(lines)
        }
        strategyBrief?.let { lines += it.facts() }
        lines += runCatching { driverCoach.facts(driverKey) }.getOrElse { diagnostic("profil de pilotage (faits)", it); emptyList() }
        forecastTrack().text()?.let { lines += "Justesse de mes prévisions comparables : $it" }
        lastReview?.lines()?.takeIf { it.isNotEmpty() }?.let { lines += "Dernier bilan : ${it.joinToString(" ")}" }
        activeObservations.forEach { o -> lines += "Déclaré par le pilote au tour ${o.lap} (non mesuré) : ${o.kind.label}." }
        history.let { h -> lines += "Historique : ${h.comparable} session(s) comparable(s) archivée(s), dont ${h.sameEvent} avec le même règlement." }
        carName?.let { lines += "Voiture : $it." }
        course?.let { lines += "Circuit : ${it.label}." }
        for (q in DriverQuery.entries) {
            val text = when (q) {
                DriverQuery.OBLIGATIONS -> obligationsSentence()
                DriverQuery.STOP_STATUS -> stopStatusSentence()
                DriverQuery.TYRE_CHANGE -> tyreChangeSentence()
                DriverQuery.NEXT_TYRE -> nextTyreSentence()
                else -> engineer.answer(q, snap, fuel).text
            }
            lines += "${AI_TOPICS[q]} : $text"
        }
        lines += "Arrêts aux stands enregistrés : ${ledger.stopCount}."
        tyreScenarios().basis.takeIf { it.isNotEmpty() }?.let { b ->
            lines += "Performance des gommes (estimée sur l'historique comparable, pas une mesure de cette course) : ${b.joinToString(" ; ")}."
        }
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
            for (id in listOf(
                ProfileId(series, SessionKind.QUALIFYING), ProfileId(series, SessionKind.RACE),
                ProfileId(series, SessionKind.RACE, SessionMode.SPORT_PRACTICE), ProfileId(series, SessionKind.RACE, SessionMode.SPORT_IDENTIFIED),
            )) {
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
        val was = profile.confirmed
        if (newRules.startTyre != null && newRules.startTyre != rules.startTyre) startTyreDeclaredMs = clock()
        rules = profiles.declare(newRules, fields).rules
        noteConfirmationDrop(was, fields.joinToString(", ") { it.label } + " saisi par le pilote")
        storage?.saveRules(rules)
        storage?.saveProfiles(profiles)
        return deliver(replan())
    }

    /** Épreuve choisie à l'écran : son propre règlement est repris, celui de l'épreuve quittée est rangé. */
    fun selectProfile(id: ProfileId): List<EngineerMessage> {
        if (id == profiles.active) return emptyList()
        // Choix du pilote : la reconnaissance automatique de l'épreuve ne revient plus dessus pour cette session.
        sessionIdentitySettled = true
        return deliver(switchProfile(id))
    }

    /** Changement d'épreuve (pilote ou reconnaissance automatique) ; messages non filtrés. */
    private fun switchProfile(id: ProfileId, announce: Boolean = true): List<EngineerMessage> {
        profiles.select(id)
        confirmationDropped = null
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
        val out = (if (announce && id.series != Series.OTHER) listOf(editionAnnouncement()) else emptyList()) + replan()
        // Une course terminée garde le type sous lequel elle a été courue : choisir « Sport officiel » après l'arrivée
        // ne la requalifie jamais (carrière locale). Pendant la course, le choix du pilote s'applique à la session.
        if (!sessionFinished) saveSession()
        return out
    }

    /** Épreuve reconnue automatiquement pour la session en cours (A, B, C, hors programme, ou pas encore). */
    var sessionIdentity: SessionMatcher.Result = SessionMatcher.Result.Unknown
        private set
    private var sessionIdentitySettled = false
    /** Circuit reconnu par la télémétrie dans cette session (pas celui d'un profil enregistré). */
    private var sessionTrackEvidence: Int? = null

    /** D'où vient [sessionTrackEvidence] (issue #51) : seule une mesure peut faire une épreuve « reconnue ». */
    private var sessionTrackSource: TrackEvidence? = null

    /**
     * Issue #30, zéro touch Sport (29/10) : le pilote choisit sa course dans GT7 seulement. Activé (préréglé une fois par
     * le pilote), une épreuve de la semaine compatible est préparée depuis le profil neutre, sans choix dans Pitwall,
     * comme « Sport reconnu » probable ; jamais comme course officielle : un salon identique ne se distingue pas en UDP.
     * Désactivé : comportement d'avant (une course personnalisée choisie reste personnalisée sans preuve mesurée).
     */
    @Volatile
    var sportFirst = false

    /** Jalons horodatés de la session (issue #30 : mesures d'acceptation à exporter). */
    val zeroTouch = ZeroTouchLog(clock)

    /** Épreuve de la semaine compatible avec ce que GT7 envoie, même sans nombre de tours (essais, qualification). */
    var sportCandidate: SessionMatcher.Result.Match? = null
        private set
    private var pitOpenBefore = false

    private var imposedCarsFor: ScheduleState? = null
    private var imposedCarsCache: Map<Series, Int> = emptyMap()

    /**
     * Voiture imposée de chaque épreuve de la semaine : la fiche nomme une voiture (pas une catégorie) et un seul modèle
     * de la base porte exactement ce nom (clé [SportNames.carKey], sans ressemblance approximative). Sinon rien.
     */
    private fun imposedCars(st: ScheduleState): Map<Series, Int> {
        if (imposedCarsFor === st) return imposedCarsCache
        imposedCarsCache = st.editions.mapNotNull { (series, ed) ->
            val text = ed.cars.takeIf { it.known }?.value ?: return@mapNotNull null
            if (!com.gt7pitwall.core.sport.SportNames.meaningfulCar(text) || com.gt7pitwall.core.sport.SportNames.carGroups(text) != null) return@mapNotNull null
            val key = com.gt7pitwall.core.sport.SportNames.carKey(text)
            val hits = database.cars.values.filter { com.gt7pitwall.core.sport.SportNames.carKey(it.fullName) == key }
            hits.singleOrNull()?.let { series to it.id }
        }.toMap()
        imposedCarsFor = st
        return imposedCarsCache
    }

    private fun milestone(m: Milestone, source: String, confidence: String, detail: String? = null, unique: Boolean = true) {
        val e = zeroTouch.mark(m, source, confidence, detail, unique) ?: return
        journal.record(
            JournalType.SESSION, JournalSource.ENGINE, "Jalon ${m.name} : ${m.label}",
            details = listOfNotNull(
                "source" to source, "certitude" to confidence, detail?.let { "detail" to it },
                e.sinceUdpMs?.let { "depuis_premier_paquet_ms" to it.toString() },
            ).toMap(),
        )
    }

    /** Jalons de phase lus dans les paquets : grille, course lancée, session sans tours annoncés, stands. */
    private fun phaseMilestones(p: TelemetryPacket, events: List<SessionEvent>) {
        if (sessionId == null || p.isLoading) return
        when {
            p.totalLaps > 0 && p.currentLap == 0 && p.speedKmh < 1f ->
                milestone(Milestone.GRID_DETECTED, "télémétrie", "mesurée", "${p.totalLaps} tours annoncés par GT7")
            p.totalLaps > 0 && p.currentLap >= 1 ->
                milestone(Milestone.RACE_STARTED, "télémétrie", "mesurée", "${p.totalLaps} tours annoncés par GT7")
            // totalLaps = 0 ne prouve pas une qualification (chrono, essais libres, course au temps) : supposée seulement.
            p.totalLaps <= 0 && p.currentLap >= 1 ->
                milestone(Milestone.QUALIFYING_SUSPECTED, "télémétrie", "supposée", "GT7 n'annonce aucun nombre de tours")
        }
        val open = tracker.pitVisitOpen
        if (open && !pitOpenBefore) milestone(Milestone.PIT_VISIT_STARTED, "télémétrie", "mesurée", "tour ${p.currentLap}", unique = false)
        pitOpenBefore = open
        events.filterIsInstance<SessionEvent.PitVisitEnded>().forEach {
            milestone(Milestone.PIT_VISIT_ENDED, "télémétrie", "mesurée", "tour ${it.visit.lap}", unique = false)
        }
    }

    /**
     * Sans nombre de tours (essais, qualification) : l'épreuve compatible est gardée comme candidate pour préparer la
     * course qui suit, sans changer de profil ni inventer la phase.
     */
    private fun sportCandidateWithoutLaps(p: TelemetryPacket, out: MutableList<EngineerMessage>) {
        if (!sportFirst || p.totalLaps > 0 || sportCandidate != null || p.isLoading || p.carCode <= 0) return
        val st = schedule ?: return
        val today = java.time.Instant.ofEpochMilli(clock()).atZone(com.gt7pitwall.core.sport.GtPlanetFeed.PARIS).toLocalDate()
        val m = SessionMatcher.match(
            st.editions, null, sessionTrackEvidence, today, database.carGroup(p.carCode),
            trackMeasured = sessionTrackSource == TrackEvidence.GEOMETRY_MEASURED,
            compatibleLabel = sessionTrackSource?.label ?: "longueur du tour compatible",
            carCode = p.carCode.takeIf { it > 0 }, imposedCars = imposedCars(st),
        ) as? SessionMatcher.Result.Match ?: return
        sportCandidate = m
        milestone(Milestone.SPORT_CANDIDATE, "fiche de la semaine + voiture GT7", m.confidence.label, "${m.series.label} : ${m.evidence.joinToString(", ")}")
        // Issue #58 (30/10) : 0 m roulé en qualification suffit à préparer la course ; rien n'est prouvé pour autant.
        val ed = m.edition
        val track = ed.trackName.takeIf { it.known }?.value
        val (raceRules, _) = SportAutofill.rules(ed, SessionKind.RACE)
        val text = buildString {
            append("Sans tours annoncés : ${m.series.label} probable d'après le programme (${m.evidence.joinToString(", ")})")
            track?.let { append(", circuit attendu $it") }
            raceRules.totalLaps?.let { append(", course de $it tours") }
            append(".")
            if (raceRules.requiredTyres.size >= 2) {
                append(" Gommes ${raceRules.requiredTyres.sortedBy { it.ordinal }.joinToString(" et ") { it.spoken }} imposées : au moins un arrêt.")
            }
            append(" Plan précis sur la grille.")
        }
        journal.record(
            JournalType.DECISION, JournalSource.ENGINE, "Épreuve candidate sans tours : ${m.series.label}",
            decision = "course préparée d'après le programme, rien n'est prouvé (circuit attendu, pas mesuré)",
            details = listOfNotNull(
                "preuves" to m.evidence.joinToString(", "), "certitude" to m.confidence.name,
                track?.let { "circuit_attendu" to it }, "source" to TrackEvidence.SCHEDULE_EXPECTED.label,
            ).toMap(),
        )
        out += EngineerMessage(text, Priority.NORMAL, MessageCategory.RADIO, dedupKey = "candidat:$sessionId:${m.series.name}")
    }

    /**
     * Issue #58 (revue #62) : ce qu'une session sans tours (qualification, essais) a appris et que la course qui suit doit
     * retrouver sans rien réapprendre. Durée bornée, même voiture ; un circuit mesuré en qualification n'est jamais
     * compté comme mesuré dans la course : il y devient « mesuré en qualification, à confirmer ».
     */
    private data class SessionHandoff(val carCode: Int, val trackId: Int?, val candidate: SessionMatcher.Result.Match?, val atMs: Long)
    private var handoff: SessionHandoff? = null
    private var lastTotalLaps = 0
    private var lastCarCode = 0
    /** Heure du dernier paquet traité (celui de la session qui se termine, quand la suivante commence). */
    private var lastSessionPacketMs = 0L

    /** Fin d'une session sans tours : ce qu'elle a appris est gardé pour la suivante. */
    private fun keepHandoff() {
        // Appelé au début de la session suivante : la voiture et les tours sont ceux de la session qui se termine.
        if (sessionId == null || lastTotalLaps > 0 || lastCarCode <= 0) return
        val measured = sessionTrackEvidence.takeIf { sessionTrackSource == TrackEvidence.GEOMETRY_MEASURED }
        if (measured == null && sportCandidate == null) return
        handoff = SessionHandoff(lastCarCode, measured, sportCandidate, lastSessionPacketMs)
    }

    private fun applyHandoff(p: TelemetryPacket, out: MutableList<EngineerMessage>) {
        val h = handoff ?: return
        if (p.isLoading || p.carCode <= 0) return
        handoff = null
        val why = when {
            p.carCode != h.carCode -> "autre voiture (${h.carCode} → ${p.carCode})"
            clock() - h.atMs > HANDOFF_MAX_MS -> "session précédente trop ancienne"
            else -> null
        }
        if (why != null) {
            journal.record(JournalType.DECISION, JournalSource.ENGINE, "Acquis de la session précédente non repris", decision = why)
            return
        }
        val id = h.trackId ?: return
        if (sessionTrackSource != null) return
        val c = database.course(id) ?: return
        sessionTrackEvidence = id
        sessionTrackSource = TrackEvidence.PREVIOUS_SESSION_MEASURED
        milestone(Milestone.TRACK_CANDIDATE, "tracé mesuré en qualification", "probable", "${c.label}, à confirmer dans cette course")
        journal.record(
            JournalType.DECISION, JournalSource.ENGINE, "Circuit mesuré en qualification : ${c.label}",
            decision = "repris pour la course, à confirmer par le tracé de cette course",
            details = mapOf("source" to TrackEvidence.PREVIOUS_SESSION_MEASURED.label, "voiture" to p.carCode.toString()),
        )
        if (rules.trackId != id) {
            applyRules(rules.copy(trackId = id))
            out += replan()
        }
    }
    private var ambiguityAnnounced = false

    /** Une ligne pour l'écran : ce que Pitwall sait de l'épreuve en cours, sans rien deviner. */
    val sessionIdentityText: String?
        get() = when (val r = sessionIdentity) {
            is SessionMatcher.Result.Match -> "${r.series.label} ${r.confidence.label} (${r.evidence.joinToString(", ")})"
            is SessionMatcher.Result.Ambiguous -> "Épreuve à confirmer : ${r.candidates.joinToString(" ou ") { it.label }}"
            is SessionMatcher.Result.NoMatch -> "Hors programme Sport : ${r.reason}"
            SessionMatcher.Result.Unknown -> sportCandidate?.let { m ->
                "${m.series.label} probable d'après le programme (${m.evidence.joinToString(", ")})" +
                    (m.edition.trackName.takeIf { it.known }?.value?.let { ", circuit attendu $it" } ?: "")
            }
        }

    /** « Course B ou C ? » posée au pilote : candidates et heure de la question. */
    private var pendingSeriesChoice: List<Series>? = null
    private var pendingSeriesChoiceMs = 0L
    private var recognizedAnnounced = false

    /**
     * Reconnaissance de l'épreuve par la télémétrie : nombre de tours annoncé par GT7 et catégorie de la voiture dès
     * la grille, puis circuit reconnu au premier tour ([SessionMatcher]).
     * - Une seule épreuve compatible : son règlement est appliqué ; « probable » tant que tours et circuit n'ont pas
     *   tous deux concordé, « reconnue » ensuite. L'annonce ne dit que des éléments vérifiés ([SportAnnouncement]).
     * - Épreuve active contredite : son règlement est retiré (jamais deux règlements mélangés).
     * - Plusieurs épreuves possibles aux règlements différents : question simple au pilote (« B ou C ? »).
     * - Course personnalisée : ne passe en Sport que si l'épreuve est reconnue (tours et circuit).
     * Le choix du pilote (écran ou réponse) reste prioritaire pour la session.
     */
    private fun identifySession(out: MutableList<EngineerMessage>) {
        if (replay) return
        val p = snapshot.packet ?: return
        val active = profile.id
        // Chrono, qualification : GT7 n'annonce pas de nombre de tours, et ces sessions n'ont pas de règlement de course.
        if (active.kind != SessionKind.RACE || p.totalLaps <= 0) return
        val st = schedule ?: return
        val today = java.time.Instant.ofEpochMilli(clock()).atZone(com.gt7pitwall.core.sport.GtPlanetFeed.PARIS).toLocalDate()
        val result = SessionMatcher.match(
            st.editions, p.totalLaps, sessionTrackEvidence, today, database.carGroup(p.carCode),
            trackMeasured = sessionTrackSource == TrackEvidence.GEOMETRY_MEASURED,
            compatibleLabel = sessionTrackSource?.label ?: "longueur du tour compatible",
            carCode = p.carCode.takeIf { it > 0 }, imposedCars = imposedCars(st),
        )
        // Course personnalisée : même nombre de tours ou même catégorie par hasard ; il faut l'épreuve reconnue.
        // Zéro touch (issue #30) : avec « Sport d'abord », le profil neutre n'est pas un choix de course personnalisée ;
        // l'épreuve compatible est préparée, comme « Sport reconnu » probable (jamais officielle, voir autoMode).
        val decided = if (result is SessionMatcher.Result.Match && active.mode == SessionMode.CUSTOM && !sportFirst &&
            result.confidence != SessionMatcher.Confidence.RECOGNIZED
        ) {
            SessionMatcher.Result.Ambiguous(listOf(result.series))
        } else {
            result
        }
        if (decided == sessionIdentity) return
        sessionIdentity = decided
        when (result) {
            is SessionMatcher.Result.Match -> {
                if (decided !is SessionMatcher.Result.Match) return
                val recognized = result.confidence == SessionMatcher.Confidence.RECOGNIZED
                sessionIdentitySettled = recognized
                pendingSeriesChoice = null
                sportCandidate = result
                milestone(Milestone.SPORT_CANDIDATE, "fiche de la semaine", result.confidence.label, "${result.series.label} : ${result.evidence.joinToString(", ")}")
                val target = ProfileId(result.series, SessionKind.RACE, autoMode(active, result.series))
                journal.record(
                    JournalType.DECISION, JournalSource.ENGINE, "Épreuve ${result.confidence.label} : ${target.label}",
                    // Issue #51 : « probable » ne s'écrit jamais « confirmée » ; une épreuve choisie n'est pas une épreuve détectée.
                    decision = when {
                        target != active -> "règlement de la semaine appliqué (${result.confidence.label})"
                        recognized -> "épreuve choisie reconnue : tours et circuit mesuré concordent"
                        else -> "épreuve choisie compatible, non prouvée"
                    },
                    details = mapOf(
                        "preuves" to result.evidence.joinToString(", "),
                        "certitude" to result.confidence.name,
                        "epreuve_precedente" to active.label,
                        "limite" to "aucune preuve UDP qu'il s'agit de la course officielle plutôt que d'un salon identique",
                    ),
                )
                when {
                    target != active -> {
                        out += switchProfile(target, announce = false)
                        out += sportMessage(SportAnnouncement.text(result, p.totalLaps), result)
                        recognizedAnnounced = recognized
                    }
                    recognized && !recognizedAnnounced -> {
                        recognizedAnnounced = true
                        out += sportMessage("${result.series.label} reconnue : tours et circuit mesuré concordent.", result)
                    }
                }
            }
            is SessionMatcher.Result.NoMatch -> {
                sessionIdentitySettled = true
                pendingSeriesChoice = null
                if (!active.sport) return
                journal.record(
                    JournalType.DECISION, JournalSource.ENGINE, "Course hors programme Sport",
                    decision = "règlement ${active.label} retiré pour cette course",
                    details = mapOf("raison" to result.reason, "tours_gt7" to p.totalLaps.toString()),
                )
                out += EngineerMessage(
                    "Cette course ne correspond pas à la ${active.series.label} de la semaine (${result.reason}) : je n'applique pas son règlement.",
                    Priority.NORMAL, MessageCategory.RADIO,
                )
                out += switchProfile(ProfileId.of(SessionMode.CUSTOM, Series.OTHER))
            }
            is SessionMatcher.Result.Ambiguous -> {
                val contradicted = active.sport && active.series !in result.candidates
                if (contradicted) {
                    // L'épreuve active est contredite : son règlement est retiré en attendant une preuve ou le pilote.
                    journal.record(
                        JournalType.DECISION, JournalSource.ENGINE, "Épreuve à confirmer : ${result.candidates.joinToString(" ou ") { it.label }}",
                        decision = "règlement ${active.label} retiré (contredit par GT7)",
                        details = mapOf("tours_gt7" to p.totalLaps.toString()),
                    )
                    out += switchProfile(ProfileId.of(SessionMode.CUSTOM, Series.OTHER))
                }
                // Ambiguïté importante : les règlements possibles diffèrent (ou l'épreuve active ne tient plus).
                val rulesDiffer = result.candidates.mapNotNull { st.editions[it] }
                    .map { SportAutofill.rules(it, SessionKind.RACE).first }.distinct().size > 1
                if (!ambiguityAnnounced && result.candidates.size > 1 && (contradicted || rulesDiffer)) {
                    ambiguityAnnounced = true
                    pendingSeriesChoice = result.candidates
                    pendingSeriesChoiceMs = clock()
                    val letters = result.candidates.map { it.letter }
                    out += EngineerMessage(
                        "Course de ${p.totalLaps} tours : ${letters.dropLast(1).joinToString(", ")} ou ${letters.last()} ? Dis-moi la lettre.",
                        Priority.NORMAL, MessageCategory.RADIO, expectsReply = true,
                    )
                }
            }
            SessionMatcher.Result.Unknown -> Unit
        }
    }

    /**
     * Type de session après identification de l'épreuve [series]. Reconnaître A, B ou C ne dit pas si la course est
     * officielle : « Sport officiel » n'est gardé que si le pilote l'avait choisi pour cette même épreuve ; un
     * entraînement reste un entraînement ; sinon « Sport reconnu », jamais compté comme course officielle.
     */
    private fun autoMode(active: ProfileId, series: Series): SessionMode = when {
        active.mode == SessionMode.SPORT_PRACTICE -> SessionMode.SPORT_PRACTICE
        active.mode == SessionMode.SPORT_OFFICIAL && active.series == series -> SessionMode.SPORT_OFFICIAL
        else -> SessionMode.SPORT_IDENTIFIED
    }

    private fun sportMessage(text: String, m: SessionMatcher.Result.Match) = EngineerMessage(
        text, Priority.NORMAL, MessageCategory.RADIO, dedupKey = "sport-id:$sessionId:${m.series.name}:${m.confidence.name}",
    )

    /** Réponse à « B ou C ? » : la lettre seule, ou « la C », « course B »… (null sinon, ou question expirée). */
    private fun seriesAnswer(text: String): Series? {
        val choices = pendingSeriesChoice ?: return null
        if (clock() - pendingSeriesChoiceMs > SERIES_QUESTION_WINDOW_MS) {
            pendingSeriesChoice = null
            return null
        }
        val t = DriverCommandParser.normalize(text).replace(Regex("[.,!?]"), " ").replace(Regex("\\s+"), " ").trim()
        val m = Regex("^(?:c'?est |c est )?(?:la |le )?(?:course )?(?:sport )?(?:daily )?([a-z'é]+)$").matchEntire(t) ?: return null
        val letter = when (m.groupValues[1]) {
            "a", "ah", "premiere" -> 'A'
            "b", "be", "bé", "bee", "deuxieme", "seconde" -> 'B'
            "c", "ce", "cé", "ces", "ses", "troisieme" -> 'C'
            else -> return null
        }
        return choices.firstOrNull { it.letter == letter }
    }

    /** Le pilote désigne l'épreuve : son règlement est appliqué pour la session, sans autre question. */
    private fun chooseSeries(series: Series): List<EngineerMessage> {
        pendingSeriesChoice = null
        sessionIdentitySettled = true
        val p = snapshot.packet
        val ed = schedule?.editions?.get(series)
        val target = ProfileId(series, SessionKind.RACE, autoMode(profile.id, series))
        journal.record(
            JournalType.DECISION, JournalSource.TRANSCRIPT, "Épreuve désignée par le pilote : ${target.label}",
            decision = "règlement de la semaine appliqué",
        )
        val out = mutableListOf<EngineerMessage>()
        if (target != profile.id) out += switchProfile(target, announce = false)
        if (ed != null) {
            val m = SessionMatcher.Result.Match(series, ed, listOf("confirmée par toi"), SessionMatcher.Confidence.RECOGNIZED)
            sessionIdentity = m
            recognizedAnnounced = true
            out += EngineerMessage(
                SportAnnouncement.text(m, p?.totalLaps).replaceFirst("${series.label} reconnue.", "Noté, ${series.label}."),
                Priority.HIGH, MessageCategory.ANSWER,
            )
        }
        return deliver(out)
    }

    /** Version de GT7, contexte de réglages / BoP. */
    fun updateProfile(p: EventProfile) {
        val was = profile.confirmed
        profiles.updateProfile(p)
        noteConfirmationDrop(was, "version de GT7 ou réglages modifiés")
        storage?.saveProfiles(profiles)
        saveSession()
    }

    /** Le pilote confirme le règlement de l'épreuve active ([today] : aaaa-mm-jj). */
    fun confirmRules(today: String) {
        profiles.confirm(today)
        confirmationDropped = null
        raceMemory.fact(snapshot.packet?.currentLap ?: 0, clock(), "règlement de l'épreuve confirmé", Provenance.DRIVER)
        storage?.saveProfiles(profiles)
        saveSession()
    }

    /**
     * Dernière confirmation du règlement retirée et pourquoi (essai du 28/10 : l'écran disait « confirmé », l'archive
     * `confirmed=false`). Affichée avec le statut ; null après une nouvelle confirmation.
     */
    var confirmationDropped: String? = null
        private set

    private fun noteConfirmationDrop(wasConfirmed: Boolean, why: String) {
        if (!wasConfirmed || profile.confirmed) return
        confirmationDropped = why
        journal.record(JournalType.DECISION, JournalSource.ENGINE, "Confirmation du règlement retirée : $why",
            decision = "règlement à reconfirmer, plus présenté comme confirmé")
    }

    /**
     * Statut du règlement tel qu'il est archivé, pour l'écran : une confirmation est toujours celle du pilote dans
     * Pitwall, jamais une lecture de la PS5 (GT7 ne transmet pas le règlement).
     */
    val ruleStatusText: String
        get() = when {
            profile.id.mode == SessionMode.CUSTOM && profile.confirmed && !localRulesConfirmed ->
                "Règlement local confirmé par toi le ${profile.eventDate ?: "?"}, pas encore reconfirmé pour cette course. GT7 ne transmet pas le règlement."
            profile.confirmed -> "Règlement confirmé par toi dans Pitwall" + (profile.eventDate?.let { " le $it" } ?: "") +
                ". GT7 ne transmet pas le règlement : ce n'est pas une lecture de la PS5."
            confirmationDropped != null -> "Confirmation retirée ($confirmationDropped) : règlement à reconfirmer."
            else -> "Règlement non confirmé : " + ruleSourceLabel() + "."
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
        val was = profile.confirmed
        val changed = ProfileBook.changedFields(rules, newRules)
        if (newRules.startTyre != null && newRules.startTyre != rules.startTyre) startTyreDeclaredMs = clock()
        rules = profiles.updateRules(newRules).rules
        noteConfirmationDrop(was, changed.joinToString(", ") { it.label } + " modifié")
        storage?.saveRules(newRules)
        storage?.saveProfiles(profiles)
    }

    // ------------------------------------------------------------------ Archive des sessions

    private fun startSession() {
        keepHandoff()
        forgetStaleStartTyre()
        driverDeclaredDuringPitNumber = null
        gridPosition = null
        finishPosition = null
        internalErrors.clear()
        trackInconsistencyNoted = false
        geometry?.reset()
        geometryLocked = false
        geometryHandled.clear()
        firstGeometryPosition = "aucune"
        sessionIdentity = SessionMatcher.Result.Unknown
        sessionIdentitySettled = false
        sessionTrackEvidence = null
        sessionTrackSource = null
        sportCandidate = null
        zeroTouch.newSession()
        gridProbeDone = false
        gridProbes = 0
        gridProbedX = Float.NaN
        gridProbedZ = Float.NaN
        gridStillPackets = 0
        gridLastX = Float.NaN
        gridLastZ = Float.NaN
        pitOpenBefore = false
        ambiguityAnnounced = false
        pendingSeriesChoice = null
        recognizedAnnounced = false
        localRulesAsked = false
        localRulesConfirmed = false
        localRulesPendingMs = null
        lastDrivingReport = null
        briefed = false
        pitApproachReminder.reset()
        driverRequestedPitLap = null
        sportRuleMismatchWarned = false
        lastPositionChangeClockMs = null
        replay = false
        replaySuspect = null
        sessionExamples.clear()
        calibrationCache = null
        sessionStartMs = clock()
        sessionId = "$sessionStartMs-${snapshot.packet?.carCode ?: 0}"
        driverCoach.start(sessionId!!)
        driverCoach.tractionContext = {
            tyreStintContext().let { com.gt7pitwall.core.driver.DriverCoach.TractionContext(it.startLap, it.excludedLaps, it.raining) }
        }
        tractionVersion = 0
        tractionLevel = com.gt7pitwall.core.tyres.TractionMonitor.Level.INSUFFICIENT
        sessionFinished = false
        planLog.clear()
        raceMemory.clear()
        driverNotes.clear()
    }

    private fun beginJournal() {
        val p = snapshot.packet
        journal.begin(
            sessionId!!,
            "Début de session : ${carName ?: "voiture ${p?.carCode ?: "?"}"} — ${course?.name ?: "circuit non choisi"} — ${profile.id.label}",
            details = listOfNotNull(
                p?.carCode?.let { "voiture" to it.toString() },
                rules.trackId?.let { "circuit" to it.toString() },
                "epreuve" to profile.id.label,
                // Course du 26/10 : « tours=10 » (règlement mémorisé d'une course précédente) pour une course de 14 tours.
                // GT7 donne le nombre de tours réel : il est noté à part, et c'est lui que le plan utilise.
                p?.totalLaps?.takeIf { it > 0 }?.let { "tours_gt7" to it.toString() },
                rules.totalLaps?.let { "tours_reglement" to it.toString() },
                rules.durationMin?.let { "duree_min" to it.toString() },
                "multiplicateur_carburant" to rules.fuelMultiplier.toString(),
                "multiplicateur_pneus" to rules.tyreMultiplier.toString(),
                "arrets_obligatoires" to rules.mandatoryStops.toString(),
                // Provenance du règlement (issue #30-B) : la PS5 ne transmet que les tours ; le reste vient d'une fiche
                // publique, du pilote ou d'un règlement mémorisé, jamais « détecté par la PS5 ».
                "source_reglement" to ruleSourceLabel(),
            ).toMap(),
        )
    }

    /** D'où vient le règlement appliqué (journal, écran) : jamais « la PS5 » pour autre chose que les tours. */
    fun ruleSourceLabel(): String = when {
        profile.id.mode == SessionMode.CUSTOM -> if (localRulesConfirmed) "règlement local confirmé par le pilote pour cette course"
            else "règlement local mémorisé, non vérifié par la PS5"
        profile.confirmed -> "règlement confirmé par le pilote"
        profile.id.sport -> "fiche publique de la semaine (" + profile.ruleStatus.entries
            .filter { it.key in setOf(RuleField.LAPS, RuleField.MANDATORY_STOPS, RuleField.REQUIRED_TYRES, RuleField.FUEL_MULTIPLIER, RuleField.TYRE_MULTIPLIER) }
            .joinToString(", ") { "${it.key.label} ${it.value.label}" } + ")"
        else -> "saisi ou mémorisé, non vérifié par la PS5"
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
            stops = ledger.stopList.map { StopEntry(it.lap, it.evidence, it.fuelAdded, it.tyres, it.thermal) },
            startTyre = rules.startTyre,
            tyres = ledger.changes.map { TyreEntry(it.lap, it.compound) },
            plans = planLog.toList(),
            notes = driverNotes.toList(),
            gridPosition = startGrid(),
            report = lastDrivingReport?.lines.orEmpty(),
            finishPosition = finishPosition,
        )
    }

    /** Tour archivé avec son relais pneus (d'après les seuls changements déclarés) et ses mesures d'adhérence. */
    private fun lapEntry(l: com.gt7pitwall.core.session.LapRecord): LapEntry {
        val change = ledger.changes.lastOrNull { it.lap <= l.lapNumber }
        val since = change?.lap ?: 1
        val certain = ledger.stopList.none { it.tyres == TyreService.UNKNOWN && it.lap >= since - 1 && it.lap < l.lapNumber } &&
            ledger.pending?.let { it.lap >= since - 1 && it.lap < l.lapNumber } != true
        fun ev(a: Int?, b: Int?) = if (a == null && b == null) null else (a ?: 0) + (b ?: 0)
        return LapEntry(
            l.lapNumber, l.timeMs, l.fuelUsed, l.refuelled,
            tyreAge = (l.lapNumber - since).coerceAtLeast(0),
            tyreCompound = if (change != null) change.compound else rules.startTyre,
            // Une gomme inconnue n'est jamais certaine ; l'âge garde sa propre certitude.
            tyreCertain = certain && (if (change != null) change.compound else rules.startTyre) != null,
            tyreSource = when {
                change != null -> TyreSource.DECLARED_CHANGE.takeIf { change.compound != null }
                rules.startTyre != null -> TyreSource.DECLARED_START
                else -> null
            },
            tyreAgeCertain = certain,
            fuelAfter = l.fuelAfter,
            pitLane = l.pitLane,
            kerbShare = l.kerbShare,
            offTrackEvents = l.offTrackEvents,
            tcsShare = l.tcsShare,
            lockups = l.lockups,
            wheelspins = l.wheelspins,
            frontEvents = ev(l.frontLockups, l.frontWheelspins),
            rearEvents = ev(l.rearLockups, l.rearWheelspins),
            wheelTemps = l.wheelTemps?.toList(),
        )
    }

    /** Enregistre la session au fil de l'eau : une fermeture brutale ne perd au plus que le tour en cours. */
    /** Deux chronos identiques à la milliseconde à ceux d'une course archivée (même voiture) : c'est sa rediffusion. */
    private fun checkReplay(lap: LapRecord) {
        val t = lap.timeMs?.takeIf { it > 0 } ?: return
        val car = snapshot.packet?.carCode
        when (lap.lapNumber) {
            1 -> replaySuspect = sessions.values.filter { it.id != sessionId && it.context.carCode == car }
                .firstOrNull { r -> r.laps.firstOrNull { it.lap == 1 }?.timeMs == t }
            2 -> {
                val prev = replaySuspect ?: return
                if (prev.laps.firstOrNull { it.lap == 2 }?.timeMs == t) {
                    replay = true
                    journal.record(
                        JournalType.SESSION, JournalSource.TELEMETRY,
                        "Rediffusion de la course ${prev.id} détectée : rien n'est archivé, appris ni annoncé",
                    )
                    sessions.remove(sessionId)
                } else {
                    replaySuspect = null
                }
            }
            else -> replaySuspect = null
        }
    }

    /**
     * Course (Sport officielle ou personnalisée) : l'ingénieur est un stratège, coaching de virage rare et bref ; sinon
     * (entraînement Sport, chrono, qualification) un coach. Les courses réelles du pilote sont des courses personnalisées
     * à 20 voitures : y donner une consigne tous les deux tours contredirait la radio silencieuse en course.
     */
    val raceMode: Boolean get() = profile.id.kind == SessionKind.RACE && profile.id.mode != SessionMode.SPORT_PRACTICE

    /** Clé du profil de pilotage : voiture × circuit × type de session (jamais mélangés). */
    val driverKey: String
        get() = com.gt7pitwall.core.driver.DriverProfile.key(snapshot.packet?.carCode ?: 0, rules.trackId?.toString() ?: "piste-inconnue", profile.id.kind.name)

    private fun coachLaps(events: List<SessionEvent>): List<EngineerMessage> {
        val out = mutableListOf<EngineerMessage>()
        for (e in events) {
            if (e !is SessionEvent.LapCompleted) continue
            val lap = e.lap
            val stopped = ledger.stopList.any { it.lap == lap.lapNumber || it.lap == lap.lapNumber - 1 }
            val recent = lastPositionChangeClockMs?.let { pacer.packetClockMs - it < POSITION_CALM_MS } == true
            val ctx = com.gt7pitwall.core.driver.DriverCoach.LapContext(
                profileKey = driverKey,
                race = raceMode,
                totalLaps = snapshot.packet?.totalLaps ?: 0,
                positionChangedRecently = recent,
                pitLap = stopped,
                wheelBaseM = snapshot.packet?.wheelBaseM,
            )
            out += journalCoaching(runCatching { driverCoach.onLap(lap, ctx) }.getOrElse { diagnostic("profil de pilotage (tour)", it); emptyList() })
        }
        return out
    }

    private fun journalCoaching(raw: List<EngineerMessage>): List<EngineerMessage> {
        // La voix et la carte d'Analyse parlent du même virage (numérotation du circuit quand elle est connue).
        val said = if (raw.isEmpty()) raw else raw.map { it.copy(text = relabelCorners(it.text)) }
        for (m in said) {
            journal.record(
                JournalType.ENGINEER, JournalSource.ENGINE, "Coaching : ${m.text}",
                details = mapOf("mode" to if (raceMode) "course" else "entrainement"),
            )
        }
        return said
    }

    private fun saveSession() {
        // Premier tour identique à une course archivée : on attend le deuxième avant d'archiver quoi que ce soit.
        if (replay || replaySuspect != null) return
        val r = currentRecord() ?: return
        sessions[r.id] = r
        storage?.saveSession(r)
    }

    /**
     * Clôt la session en cours. Au changement de session, les tours sont déjà effacés du suivi : on reprend la dernière
     * version enregistrée (au dernier tour), complétée par le registre des arrêts, encore intact à ce moment.
     */
    private fun closeSession(outcome: SessionOutcome) {
        if (replay) return
        val dna = driverCoach.close(fuel?.perLap)
        val final = if (sessionFinished) SessionOutcome.FINISHED else outcome
        // A finished session may have been reset by GT7 before the journal is closed.
        // Use the last saved session instead of falsely reporting zero measured laps.
        val measuredLapsAtClose = snapshot.laps.size.takeIf { it > 0 }
            ?: sessions[sessionId]?.laps?.size ?: 0
        val r = currentRecord(final) ?: sessions[sessionId]?.copy(
            outcome = final,
            updatedAtMs = clock(),
            stops = ledger.stopList.map { StopEntry(it.lap, it.evidence, it.fuelAdded, it.tyres, it.thermal) },
            tyres = ledger.changes.map { TyreEntry(it.lap, it.compound) },
            plans = planLog.toList(),
            notes = driverNotes.toList(),
            gridPosition = startGrid(),
            report = lastDrivingReport?.lines.orEmpty(),
            finishPosition = finishPosition,
        )
        if (r != null) {
            sessions[r.id] = r
            storage?.saveSession(r)
            val advice = dna?.advice?.filter { it.sessionId == r.id }.orEmpty()
            val review = com.gt7pitwall.core.strategy.RaceReview.of(
                r, advice.size, advice.count { a -> a.lossAfterMs?.let { it < a.lossBeforeMs - com.gt7pitwall.core.driver.DriverProfile.IMPROVED_MS } == true },
            )
            lastReview = review
            // Le bilan appartient à la course qui se termine : écrit AVANT la clôture du journal. Écrit après, il
            // attendait en mémoire et rejoignait la course suivante.
            review.lines().forEach {
                val details = mapOf("session" to r.id)
                if (journal.sessionId == r.id) journal.record(JournalType.DECISION, JournalSource.ENGINE, "Bilan : $it", details = details)
                else journal.recordFor(r.id, JournalType.DECISION, JournalSource.ENGINE, "Bilan : $it", details = details)
            }
        }
        journal.end(
            when (final) {
                SessionOutcome.FINISHED -> "Fin de session : course terminée"
                SessionOutcome.INTERRUPTED -> "Fin de session : interrompue (télémétrie arrêtée ou nouvelle session)"
                SessionOutcome.IN_PROGRESS -> "Fin de session"
            },
            details = mapOf("issue" to final.name.lowercase(), "tours_mesures" to measuredLapsAtClose.toString()),
        )
    }

    /** Justesse des prévisions sur les courses comparables archivées (hors session en cours). */
    fun forecastTrack(): com.gt7pitwall.core.strategy.ForecastTrack {
        val ctx = sessionContext
        val comparable = sessions.values.filter {
            it.id != sessionId && it.context.profile.id == ctx.profile.id && Compatibility.compare(ctx, it.context).first != Comparability.INCOMPATIBLE
        }
        return com.gt7pitwall.core.strategy.ForecastTrack.of(comparable.map { com.gt7pitwall.core.strategy.RaceReview.of(it) })
    }

    /**
     * Briefing avant une épreuve Sport (une fois, sur la grille) : règlement, circuit, historique, consommation, pneus,
     * plan et fiabilité, profil de pilotage. Chaque phrase dit sur quoi elle repose.
     */
    fun briefing(): List<String> {
        val out = mutableListOf<String>()
        val gaps = ruleGaps
        out += when {
            !profile.confirmed -> "Briefing ${profile.id.label} : règlement non confirmé, vérifie-le dans la carte Course."
            gaps.isNotEmpty() -> "Briefing ${profile.id.label} : règlement à compléter (${gaps.joinToString(", ") { it.label }})."
            else -> "Briefing ${profile.id.label} : règlement confirmé."
        }
        if (rules.trackId == null) out += "Circuit non identifié : je ne peux pas utiliser ton historique."
        if (carChangePending) {
            out += "Nouvelle voiture détectée (${carName ?: "code ${carIdentity.current}"}) : historique, consommation et plan seront recalculés au départ."
            return out
        }
        val h = history
        val learned = historyModel.fuelPerLap?.takeIf { it.confidence != Confidence.INSUFFICIENT }
        out += if (h.comparable == 0) {
            "Aucune course comparable : je me baserai sur tes premiers tours."
        } else {
            "${h.comparable} course${if (h.comparable > 1) "s" else ""} comparable${if (h.comparable > 1) "s" else ""}" +
                (learned?.let { l ->
                    ", consommation de référence ${String.format(java.util.Locale.FRANCE, "%.2f", l.mean)} par tour (${l.confidence.label})" +
                        (fuelCorrectionPct?.let { c -> ", corrigée de ${if (c >= 0) "+" else "−"}${String.format(java.util.Locale.FRANCE, "%.1f", kotlin.math.abs(c))} % d'après mes erreurs passées" } ?: "")
                } ?: "") + "."
        }
        val tyre = compoundBook[ledger.currentTyre(rules.startTyre)]
        if (tyre != null && tyre.confidence != Confidence.INSUFFICIENT) out += "Pneus : ${tyre.summary()}."
        strategy?.let { rep ->
            val o = rep.recommendedOption
            val gain = o.deltaMs?.takeIf { it < 0 }
            out += when {
                rep.indistinguishable -> "Plusieurs plans se valent : écarts inférieurs à l'incertitude."
                gain != null && rep.uncertaintyMs != null -> "${o.kind.label.replaceFirstChar { it.uppercase() }} : environ ${StrategyOptions.seconds(-gain)} de gagnés, à ± ${StrategyOptions.seconds(rep.uncertaintyMs)}."
                else -> null
            } ?: return@let
        }
        if (!raceMode) driverCoach.known(driverKey)?.traits()?.text?.let { out += it }
        return out
    }

    /**
     * Complete pre-grid dossier for the Fold. The historical radio briefing()
     * contract remains untouched to preserve concise speech and tested facts.
     */
    fun victoryBriefing(): List<String> = buildList {
        addAll(briefing())
        val p = snapshot.packet
        val ed = edition
        if (profile.id.sport && ed?.laps?.known == true && p != null &&
            p.totalLaps > 0 && ed.laps.value != p.totalLaps) {
            add("ALERTE : GT7 annonce ${p.totalLaps} tours, mais la course ${profile.id.series.label} de la semaine en prévoit ${ed.laps.value}. Règlement incompatible : ne pas calculer les arrêts sur cette fiche.")
        }
        if (p != null) {
            val code = displayCarCode ?: p.carCode
            add("Voiture : " + (database.car(code)?.fullName ?: "code $code, absent de la base") + ".")
            if (p.currentLap == 0 && p.preRacePosition > 0 && p.preRaceCarCount > 0) {
                add("Matchmaking provisoire : P${p.preRacePosition} sur ${p.preRaceCarCount} ; la grille définitive n'est pas encore confirmée.")
            }
        }
        val length = p?.totalLaps?.takeIf { it > 0 } ?: rules.totalLaps
        if (length != null) add("Distance de course : $length tours (source : " +
            (if (p?.totalLaps?.takeIf { it > 0 } != null) "GT7" else "règlement") + ").")
        if (rules.allowedTyres.isNotEmpty()) {
            add("Pneus autorisés : " + rules.allowedTyres.joinToString(", ") { it.name } + ".")
        }
        if (RuleField.REQUIRED_TYRES in ruleGaps) {
            add("Pneus obligatoires : règlement non confirmé ; impossible de garantir qu'un plan d'arrêt respecte les règles.")
        } else if (rules.requiredTyres.isNotEmpty()) {
            add("Pneus obligatoires : " + rules.requiredTyres.joinToString(", ") { it.name } + ".")
        } else {
            add("Aucun composé obligatoire confirmé dans le règlement enregistré.")
        }
        plan?.let { rp ->
            val valid = !profile.id.sport || ed?.laps?.value == null || p?.totalLaps == null ||
                p.totalLaps <= 0 || ed.laps.value == p.totalLaps
            if (!valid) {
                add("Plan d'arrêt suspendu : incohérence de l'épreuve.")
            } else {
                add(if (rp.stops.isEmpty()) "Plan de base : aucun arrêt prévu, sous réserve des règles."
                else "Plan de base : arrêt(s) à la fin des tours " +
                    rp.stops.joinToString(", ") { it.endOfLap.toString() } + ".")
            }
        }
        historyModel.pitLossMs?.takeIf { it.confidence != Confidence.INSUFFICIENT }?.let {
            add("Coût historique moyen d'un arrêt : " + StrategyOptions.seconds(it.mean.toLong()) +
                " (" + it.confidence.label + ") ; le contenu des arrêts peut différer.")
        }
        // Avoid mixing unknown circuits, cars and rule profiles in Driver DNA.
        if (rules.trackId != null) {
            driverCoach.known(driverKey)?.traits()?.text?.let { add("Mémoire du pilote : $it") }
        }
        add("Objectif : gagner des places par le rythme et les bonnes décisions. Écarts, noms et pneus des adversaires : non accessibles en UDP GT7.")
    }

    /**
     * Course personnalisée : la PS5 ne transmet ni les arrêts obligatoires ni les gommes imposées. Un règlement mémorisé
     * d'une course précédente (27/10 : « 1 arrêt obligatoire ») n'est jamais présenté comme confirmé : au départ,
     * une seule question courte, et seulement si ce règlement contient une obligation qui change la stratégie.
     * Sans réponse, il reste appliqué mais signalé « à confirmer » dans les appels au stand.
     */
    private var localRulesAsked = false
    private var localRulesConfirmed = false
    private var localRulesPendingMs: Long? = null

    private fun localObligations(): Boolean = rules.mandatoryStops > 0 || rules.requiredTyres.isNotEmpty()

    private fun localRulesQuestionOpen(): Boolean =
        localRulesPendingMs?.let { clock() - it <= LOCAL_RULES_QUESTION_MS } == true && profile.id.mode == SessionMode.CUSTOM

    private fun localRulesDetails(): Map<String, String> = mapOf(
        "arrets_obligatoires" to rules.mandatoryStops.toString(),
        "gommes_imposees" to rules.requiredTyres.joinToString(",") { it.name }.ifEmpty { "aucune" },
        "provenance" to "règlement local mémorisé",
    )

    private fun localRulesQuestionIfDue(p: TelemetryPacket): List<EngineerMessage> {
        if (localRulesAsked || replay || sessionFinished || profile.id.mode != SessionMode.CUSTOM || profile.id.kind != SessionKind.RACE) return emptyList()
        if (p.totalLaps <= 0 || p.currentLap > 1 || !localObligations()) return emptyList()
        localRulesAsked = true
        localRulesPendingMs = clock()
        val what = listOfNotNull(
            rules.mandatoryStops.takeIf { it > 0 }?.let { "$it arrêt${if (it > 1) "s" else ""} obligatoire${if (it > 1) "s" else ""}" },
            rules.requiredTyres.takeIf { it.isNotEmpty() }?.let { r -> "gommes imposées ${r.joinToString(" et ") { it.spoken }}" },
        ).joinToString(", ")
        journal.record(JournalType.DECISION, JournalSource.ENGINE, "Règlement local mémorisé à confirmer : $what",
            decision = "non transmis par la PS5 ; question au pilote", details = localRulesDetails())
        return listOf(EngineerMessage(
            "Course personnalisée : j'ai en mémoire $what. Toujours valable pour cette course ?",
            Priority.NORMAL, MessageCategory.RADIO, expectsReply = true,
        ))
    }

    private fun briefingIfDue(p: TelemetryPacket): List<EngineerMessage> {
        if (briefed || !profile.id.sport || profile.id.kind != SessionKind.RACE || p.currentLap != 0 || p.speedKmh > 1f) return emptyList()
        briefed = true
        val lines = briefing()
        // Le briefing a déjà signalé le règlement non confirmé : pas de second rappel au premier tour.
        if (!profile.confirmed) rulesReminded = true
        journal.record(JournalType.STRATEGY, JournalSource.ENGINE, "Briefing : ${lines.joinToString(" ")}")
        // Full briefing stays on screen: the grid radio must not read a long technical report.
        return listOf(EngineerMessage(lines.joinToString(" "), Priority.NORMAL, MessageCategory.FUEL, topic = "briefing"))
    }

    /** Événements mesurés du tracker inscrits au journal (tours, stands, arrivée, grille). */
    private fun journalEvents(events: List<SessionEvent>) {
        for (e in events) when (e) {
            is SessionEvent.LapCompleted -> {
                val l = lapEntry(e.lap)
                journal.record(
                    JournalType.LAP, JournalSource.TELEMETRY,
                    "Tour ${l.lap} : ${l.timeMs?.let(JournalExport::lapTime) ?: "temps non lu"}" +
                        (e.previousBestMs?.let { b -> l.timeMs?.let { " (meilleur précédent ${JournalExport.lapTime(b)})" } } ?: ""),
                    lap = l.lap,
                    details = listOfNotNull(
                        l.timeMs?.let { "temps_ms" to it.toString() },
                        l.fuelUsed?.let { "carburant_consomme" to "%.2f".format(java.util.Locale.ROOT, it) },
                        l.fuelAfter?.let { "carburant_restant" to "%.1f".format(java.util.Locale.ROOT, it) },
                        "ravitaillement" to if (l.refuelled) "oui" else "non",
                        e.lap.maxSpeedKmh?.let { "vitesse_max" to Math.round(it).toString() },
                        e.lap.sectorsMs?.let { "secteurs_ms" to it.joinToString("/") },
                        l.tcsShare?.let { "tcs_pourcent" to Math.round(it * 100).toString() },
                        l.lockups?.let { "blocages" to it.toString() },
                        l.wheelspins?.let { "patinages" to it.toString() },
                        l.frontEvents?.let { "evenements_avant" to it.toString() },
                        l.rearEvents?.let { "evenements_arriere" to it.toString() },
                        l.wheelTemps?.let { t -> "temperatures_pneus" to t.joinToString("/") { Math.round(it).toString() } },
                        l.tyreCompound?.let { "pneus" to it.name },
                        l.tyreAge?.let { "age_pneus_tours" to it.toString() },
                        "gomme_certaine" to if (l.tyreCertain) "déclaration certaine (non mesurée)" else "non",
                        // GT7 ne transmet pas la gomme : elle vient toujours d'une déclaration (pilote, réglage, règlement).
                        "source_gomme" to (l.tyreSource?.let { it.label + ", non transmise par GT7" } ?: if (l.tyreCompound != null) "déclarée (pilote, réglage ou règlement), non transmise par GT7" else "inconnue"),
                        "age_pneus_certain" to if (l.tyreAgeCertain) "oui" else "non",
                        if (l.pitLane) "sortie_des_stands" to "oui (consommation exclue des moyennes)" else null,
                        l.kerbShare?.let { "vibreurs_pourcent" to Math.round(it * 100).toString() },
                        l.offTrackEvents?.let { "sorties_de_piste" to it.toString() },
                    ).toMap(),
                )
            }
            is SessionEvent.PitVisitEnded -> journal.record(
                JournalType.PIT, JournalSource.TELEMETRY,
                // Issue #51 (SUGO, 28/10) : box annoncé fin T7, passage constaté T8 (le compteur passe dans la voie des
                // stands). C'est le même arrêt : on le dit, jamais « arrêt raté ».
                (plan?.stops?.firstOrNull { it.endOfLap == e.visit.lap - 1 }
                    ?.let { "Arrêt prévu fin du tour ${it.endOfLap}, constaté au tour ${e.visit.lap} (compteur passé dans la voie des stands)" }
                    ?: "Passage au stand détecté (tour ${e.visit.lap})") +
                    (if (e.visit.refuelAmount > 0f) " : +%.1f de carburant".format(e.visit.refuelAmount) else "") +
                    ", immobile %.1f s".format(e.visit.stoppedSeconds),
                lap = e.visit.lap,
                details = mapOf("analyse" to e.visit.assessment.name.lowercase(), "lieu_connu" to if (e.visit.locationKnown) "oui" else "non"),
            )
            is SessionEvent.RaceFinished -> journal.record(JournalType.SESSION, JournalSource.TELEMETRY, "Arrivée : ${e.totalLaps} tours")
            is SessionEvent.GridPosition -> {
                gridPosition = e.position
                journal.record(JournalType.POSITION, JournalSource.TELEMETRY, "Grille : P${e.position} sur ${e.cars}")
            }
            else -> {}
        }
    }

    private fun journalCarChange(t: com.gt7pitwall.core.telemetry.CarIdentity.Transition) {
        fun name(code: Int?) = code?.let { database.car(it)?.fullName ?: "code $it" } ?: "aucune"
        zeroTouch.carChanged()
        milestone(Milestone.CARCODE_CONFIRMED, "télémétrie (code voiture)", "mesurée", "${t.to} ${name(t.to)}")
        journal.record(
            JournalType.SESSION, JournalSource.TELEMETRY,
            if (t.from == null) "Voiture détectée : ${name(t.to)}" else "Changement de voiture : ${name(t.from)} → ${name(t.to)}",
            details = buildMap {
                t.from?.let { put("voiture_precedente", it.toString()) }
                put("voiture", t.to.toString())
                put("format_udp", t.format.label)
                put("etat", t.state)
            },
        )
    }

    private fun journalPositions(changes: List<PositionTracker.Change>) {
        for (c in changes) {
            val text = when (c) {
                is PositionTracker.Change.Moved -> "Position P${c.from ?: "?"} → P${c.to} sur ${c.cars}"
                is PositionTracker.Change.CarsChanged -> "Participants : ${c.from ?: "?"} → ${c.to}"
                is PositionTracker.Change.Finished -> "Classement final : P${c.position} sur ${c.cars}"
                PositionTracker.Change.Lost -> "Position plus transmise par GT7"
            }
            journal.record(JournalType.POSITION, JournalSource.TELEMETRY, text)
        }
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
        // Issue #47 (Fuji, 28/10) : « oui » à la gomme déclarée pendant le plein relançait le plan (« arrêt fin du tour 7 »,
        // « carburant critique ») alors que la voiture se ravitaillait. Point unique pour TOUS les chemins (paquets,
        // commandes, « oui », règlement) : aucun plan ni arrêt annoncé pendant la visite au stand ; le recalcul attend
        // sa fin (PitVisitEnded) et se fait une seule fois avec tout ce qui a été déclaré entre-temps.
        if (tracker.pitVisitOpen) {
            if (!replanDeferred) {
                replanDeferred = true
                journal.record(
                    JournalType.STRATEGY, JournalSource.ENGINE, "Plan gelé : passage au stand en cours",
                    decision = "recalcul après la sortie du stand", lap = p.currentLap,
                )
            }
            return emptyList()
        }
        replanDeferred = false
        // Never optimize stops using the wrong weekly regulations (e.g. Sport A
        // selected while actual GT7 race has 14 laps, corresponding to Sport C).
        // Telemetry fuel is still monitored independently and the event can be
        // reidentified after a complete track lap.
        val publishedLaps = if (profile.id.sport) edition?.laps?.takeIf { it.known }?.value else null
        if (publishedLaps != null && p.totalLaps > 0 && p.totalLaps != publishedLaps) {
            plan = null
            strategy = null
            engineer.onPlan(null, p.currentLap)
            if (sportRuleMismatchWarned) return emptyList()
            sportRuleMismatchWarned = true
            val warning = "Règlement Sport incompatible : GT7 annonce " + p.totalLaps +
                " tours, la course sélectionnée en prévoit " + publishedLaps +
                ". Je suspends les arrêts planifiés jusqu'à reconnaissance de l'épreuve."
            journal.record(JournalType.STRATEGY, JournalSource.ENGINE, warning,
                details = mapOf("tours_gt7" to p.totalLaps.toString(), "tours_programme" to publishedLaps.toString()))
            return listOf(EngineerMessage(warning, Priority.HIGH, MessageCategory.ALERT,
                dedupKey = "sport-laps-mismatch:" + sessionId))
        }
        sportRuleMismatchWarned = false
        val live = FuelStrategy.compute(snap)
        val rec = memoryRecord
        // Historique : sessions comparables archivées d'abord (avec leur niveau de confiance), sinon l'ancienne mémoire.
        val model = HistoryModel.learn(sessionContext, sessions.values, excludeId = sessionId).also { historyModel = it }
        tyreModel = TyreModel.learn(sessionContext, sessions.values, ledger.currentTyre(rules.startTyre), excludeId = sessionId)
        compoundBook = CompoundBook.learn(sessionContext, sessions.values, excludeId = sessionId)
        // Arrêt pneus retenu déjà passé : il sort de la liste.
        performanceTyreLaps = performanceTyreLaps.filter { it >= p.currentLap.coerceAtLeast(1) }
        val learnedFuel = model.fuelPerLap?.takeIf { it.confidence != Confidence.INSUFFICIENT }
        fuelCorrectionPct = if (live?.perLap == null && learnedFuel != null) forecastTrack().fuelCorrection else null
        val (perLap, fuelSource) = when {
            live?.perLap != null -> live.perLap to DataSource.MEASURED
            learnedFuel != null -> (learnedFuel.mean * (1 + (fuelCorrectionPct ?: 0.0) / 100.0)).toFloat() to DataSource.HISTORY
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
            previousStops = plan?.stops?.filterNot { it.tyresOnly && it.forTyres }?.map { it.endOfLap } ?: emptyList(),
            tyreStopLaps = performanceTyreLaps.filter { it >= p.currentLap.coerceAtLeast(1) },
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
            val key = "${opt.kind}:${opt.stopLaps}:${opt.tyreLaps}"
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
                // Changement de pneus de performance adopté (ou abandonné) : il fait désormais partie du plan.
                if (opt.kind in StrategyOptions.TYRE_KINDS) performanceTyreLaps = opt.plan.stops.filter { it.forTyres }.map { it.endOfLap }
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
            val pitLoss = stopCosts(model, course?.pitLaneDeltaS?.let { it * 1000.0 })?.cost(StopKind.FUEL_ONLY)?.ms?.roundToInt()
            planLog += PlanEntry(p.currentLap, stopLaps, reason, fuelPerLap = plan?.perLap, fuelSource = plan?.fuelSource?.name, pitLossMs = pitLoss)
            raceMemory.planProposed(p.currentLap, clock(), stopLaps, reason)
            journal.record(
                JournalType.STRATEGY, JournalSource.ENGINE,
                if (stopLaps.isEmpty()) "Plan : aucun arrêt prévu" else "Plan : arrêts à la fin des tours ${stopLaps.joinToString(", ")}",
                decision = reason ?: "plan calculé",
                details = listOfNotNull(
                    plan?.perLap?.let { "consommation_par_tour" to "%.2f".format(java.util.Locale.ROOT, it) },
                    plan?.fuelSource?.let { "source_consommation" to it.name },
                    plan?.totalLaps?.let { "tours_prevus" to it.toString() },
                ).toMap(),
            )
            while (planLog.size > MAX_PLANS) planLog.removeAt(0)
        }
        engineer.obligationCaveat = obligationCaveat().trim().takeIf { it.isNotEmpty() }
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
        // Dégradation : mesurée sur ce train de pneus d'abord, sinon apprise sur des relais comparables de la même gomme,
        // sinon sur l'historique de l'épreuve. Jamais déduite d'une déclaration.
        val learnedTyre = tyreModel.slopeMsPerLap?.takeIf { it.confidence != Confidence.INSUFFICIENT }
        val pace = StrategyOptions.Pace(
            lapMs = measuredAvg?.toDouble() ?: learnedPace?.mean,
            lapSpreadMs = learnedPace?.spread ?: 0.0,
            slopeMsPerLap = stint?.first ?: learnedTyre?.mean ?: model.paceSlopeMsPerLap?.mean,
            slopeSpread = stint?.second ?: learnedTyre?.spread ?: model.paceSlopeMsPerLap?.spread ?: 0.0,
            stopCosts = stopCosts(model, laneMs),
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
                stint != null -> add("chronos qui ${if (stint.first > 0) "se dégradent" else "s'améliorent"} de ${StrategyOptions.seconds(stint.first.toLong())} par tour avec ces pneus (mesuré)")
                learnedTyre != null -> add("dégradation apprise de ${StrategyOptions.seconds(learnedTyre.mean.toLong())} par tour d'âge des pneus (estimé, ${learnedTyre.confidence.label} : ${tyreModel.basis()})")
                model.paceSlopeMsPerLap != null -> add("chronos qui évoluent de ${StrategyOptions.seconds(model.paceSlopeMsPerLap.mean.toLong())} par tour de relais (mesuré, ${model.paceSlopeMsPerLap.sessions} courses)")
            }
            // Coûts des arrêts (passage, pneus, plein) : détaillés par la comparaison elle-même.
            if (model.pitLossMs == null && laneMs == null && model.pitLossByKind.isEmpty()) add("perte par arrêt inconnue")
            if (model.sessionsExcluded > 0) add("${model.sessionsExcluded} course(s) de cette épreuve écartée(s) car non comparables")
            if (model.quarantined.isNotEmpty()) add("${model.quarantined.size} course(s) au règlement contredit par GT7 : rythme et consommation seulement (${model.quarantined.first().substringAfter(" : ")})")
            if (model.relatedUsed > 0) add("dont ${model.relatedUsed} entraînement(s) ou session(s) au type non précisé : BoP et réglages non vérifiés, confiance limitée")
        }
        // Motricité : seulement une perte répétée, active et à surfaces connues (P0-A, P0-C).
        val degrading = engineer.grip.degrading || (stint != null && stint.first > 0) || driverCoach.traction.last.strategyEligible
        return StrategyOptions.compare(input, pace, basis, unverifiedRuleLabels(), TyreInputs(degrading = degrading))
    }

    /**
     * Coût d'un arrêt selon son contenu, sans compter deux fois une intervention : temps observés par type d'arrêt (ils
     * comprennent déjà ce qui y a été fait), sinon passage aux stands + changement de pneus (appris par différence entre
     * arrêts avec et sans pneus, sinon hypothèse provisoire de 10 s ± 10 s).
     */
    private fun stopCosts(model: HistoryModel, laneMs: Double?): StopCosts? {
        fun c(l: com.gt7pitwall.core.strategy.Learned, what: String) =
            Cost(l.mean, maxOf(l.spread, l.mean * 0.1), "$what observés (${l.samples} arrêt${if (l.samples > 1) "s" else ""})")
        val observed = model.pitLossByKind.mapValues { (k, l) -> c(l, k.label) }
        val lane = laneMs?.let { Cost(it, it * 0.5, "voie des stands seule (base GT7)") }
        val unknown = model.pitLossUnknownContent?.let { Cost(it.mean, maxOf(it.spread, it.mean * 0.2), "arrêts passés au contenu non précisé") }
        val (base, withRefuel) = when {
            observed[StopKind.NONE] != null -> observed[StopKind.NONE] to false
            lane != null -> lane to false
            observed[StopKind.FUEL_ONLY] != null -> observed[StopKind.FUEL_ONLY] to true
            unknown != null -> unknown to true
            else -> null to false
        }
        // Temps du changement de pneus appris par différence entre deux types d'arrêts observés, jamais supposé inclus ailleurs.
        val learnedTyre = listOfNotNull(
            observed[StopKind.TYRES_ONLY]?.let { t -> (observed[StopKind.NONE] ?: lane)?.let { t to it } },
            observed[StopKind.FUEL_AND_TYRES]?.let { t -> observed[StopKind.FUEL_ONLY]?.let { t to it } },
        ).firstOrNull()?.let { (with, without) ->
            Cost((with.ms - without.ms).coerceAtLeast(0.0), sqrt(with.spread * with.spread + without.spread * without.spread), "changement de pneus appris (${with.source} contre ${without.source})")
        }
        if (base == null && observed.isEmpty()) return null
        return StopCosts(base, withRefuel, learnedTyre ?: StopCosts.ASSUMED_TYRE_SERVICE, observed)
    }

    /**
     * Dérive des chronos dans le relais en cours (ms par tour) et son incertitude, mesurée sur au moins 4 tours propres ;
     * null si elle n'est pas nette (moins de deux fois son incertitude). Jamais déduite d'une déclaration.
     */
    private fun currentStintSlope(): Pair<Double, Double>? {
        // Relais pneus (pas depuis le dernier ravitaillement), tours d'arrêt et de sortie écartés. Âge incertain : seulement
        // les tours depuis le dernier arrêt, faute de mieux.
        val ctx = tyreStintContext()
        val lastStop = ledger.stopList.maxOfOrNull { it.lap } ?: 0
        val start = if (ctx.certain) ctx.startLap else maxOf(ctx.startLap, lastStop + 2)
        val laps = snapshot.laps.filter {
            it.lapNumber >= maxOf(2, start) && it.lapNumber !in ctx.excludedLaps && !it.refuelled && (it.timeMs ?: 0) > 0
        }
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

    /**
     * Continuer, tendres, mediums ou durs : temps total jusqu'à l'arrivée, arrêt compris, d'après les relais comparables
     * archivés et les chronos propres du relais en cours. Sans données suffisantes, aucun scénario n'est recommandé.
     */
    fun tyreScenarios(): TyreScenarioReport {
        val lap = (snapshot.packet?.currentLap ?: 1).coerceAtLeast(1)
        val total = plan?.totalLaps ?: rules.totalLaps ?: snapshot.packet?.totalLaps?.takeIf { it > 0 }
            ?: return TyreScenarioReport(emptyList(), null, "Nombre de tours inconnu : je ne compare pas les gommes.", emptyList())
        val ctx = tyreStintContext()
        val stint = ledger.tyreStint(lap, rules.startTyre)
        val live = snapshot.laps.filter {
            it.lapNumber >= maxOf(2, ctx.startLap + 1) && it.lapNumber !in ctx.excludedLaps && !it.refuelled && (it.timeMs ?: 0) > 0
        }.map { (it.lapNumber - stint.startLap) to it.timeMs!! }
        return TyreScenarios.compare(
            TyreScenarios.Input(
                currentLap = lap,
                totalLaps = total,
                current = stint.compound,
                tyreAge = stint.age,
                ageCertain = ctx.certain,
                liveLaps = live,
                book = compoundBook,
                costs = stopCosts(historyModel, course?.pitLaneDeltaS?.let { it * 1000.0 }),
                plannedStops = plan?.stops?.filter { it.endOfLap >= lap }?.map { it.endOfLap }.orEmpty(),
                allowed = rules.allowedTyres,
                requiredMissing = missingTyres,
                tyreLife = rules.tyreStintLaps,
            ),
        )
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
        val reason = reasonPrefix(ask, nowMs)
        val text = strategyText(ask, question, extra)
        val said = when {
            reason != null -> "$reason $text"
            intention -> "Noté comme intention, je n'enregistre rien tant que ce n'est pas fait. $text"
            else -> text
        }
        if (ask !is StrategyDialogue.Ask.Unclear) mood.onStrategy(nowMs)
        if (ask !is StrategyDialogue.Ask.Unclear) lastStrategyAnswerMs = nowMs
        if (ask !is StrategyDialogue.Ask.FollowUp && ask !is StrategyDialogue.Ask.Unclear) lastStrategyAsk = ask
        if (ask !is StrategyDialogue.Ask.Unclear && ask !is StrategyDialogue.Ask.Observe) {
            raceMemory.exchange(RaceMemory.Exchange(currentLap(), nowMs, question, said, RaceMemory.ENGINE, ConversationRouter.topicOf(ask), analysis = text))
        }
        val answer = EngineerMessage(said, Priority.HIGH, MessageCategory.ANSWER, expectsReply = ask is StrategyDialogue.Ask.Unclear || awaitingAnswer)
        return listOf(echo) + deliver(listOf(answer) + extra)
    }

    /** Réponse du moteur à une question stratégique, sans la dire (la conversation choisit qui la formule). */
    private fun strategyText(ask: StrategyDialogue.Ask, question: String, extra: MutableList<EngineerMessage>): String {
        val memoryAsk = ask == StrategyDialogue.Ask.Recall || ask == StrategyDialogue.Ask.SamePlan
        return if (!telemetryLive() && ask !is StrategyDialogue.Ask.Observe && ask !is StrategyDialogue.Ask.Unclear &&
            ask !is StrategyDialogue.Ask.TyresOnlyIntent && !memoryAsk
        ) {
            generalAdvice(ask)
        } else {
            when (ask) {
                StrategyDialogue.Ask.Best -> answerBest()
                is StrategyDialogue.Ask.WhyStop -> answerWhyStop(ask.lap)
                is StrategyDialogue.Ask.Extend -> answerExtend(ask.laps)
                StrategyDialogue.Ask.TyreStatus -> answerTyreStatus()
                StrategyDialogue.Ask.TyreLife -> answerTyreLife()
                StrategyDialogue.Ask.TyreChange -> answerTyreChange()
                StrategyDialogue.Ask.GainPitNow -> answerGainPitNow()
                StrategyDialogue.Ask.WhyPitWithFuel -> answerWhyPitWithFuel()
                is StrategyDialogue.Ask.GripQuestion -> answerGripQuestion(ask.kind, question)
                StrategyDialogue.Ask.TyresOnlyIntent -> {
                    noteIntention("« ${question.trim()} » (pneus seulement au prochain arrêt)")
                    "Noté : au prochain arrêt, pneus seulement. Je n'enregistre le changement que quand tu l'as fait."
                }
                StrategyDialogue.Ask.Early -> answerEarly()
                StrategyDialogue.Ask.OthersPitting -> answerOthersPitting()
                StrategyDialogue.Ask.FuelToFinish -> answerFuelToFinish()
                StrategyDialogue.Ask.WhatChanged -> answerWhatChanged()
                StrategyDialogue.Ask.FollowUp -> answerFollowUp()
                is StrategyDialogue.Ask.Observe -> answerObservation(ask, question, extra)
                StrategyDialogue.Ask.SamePlan -> answerSamePlan()
                StrategyDialogue.Ask.Recall -> answerRecall()
                StrategyDialogue.Ask.CompareStints -> answerCompareStints()
                StrategyDialogue.Ask.AttackRisk -> answerAttackRisk()
                StrategyDialogue.Ask.Unclear -> "Je n'ai pas bien compris. Tu me demandes la stratégie, le carburant ou l'arrêt ?"
            }
        }
    }

    /** Sans télémétrie : conseils de principe, annoncés comme tels, sans aucun chiffre de course. */
    private fun generalAdvice(ask: StrategyDialogue.Ask): String = "Conseil général, sans télémétrie : " + when (ask) {
        StrategyDialogue.Ask.Best -> "je n'ai pas de données de course. Je bâtirai le plan sur le règlement et ta consommation mesurée dès le premier tour."
        is StrategyDialogue.Ask.WhyStop -> "un arrêt se place quand l'autonomie ou le règlement l'imposent ; je n'ai pas de plan en cours."
        is StrategyDialogue.Ask.Extend -> "on ne prolonge un relais que si l'autonomie mesurée garde au moins un demi-tour de marge."
        StrategyDialogue.Ask.TyreStatus, StrategyDialogue.Ask.TyreLife, StrategyDialogue.Ask.TyreChange ->
            "sans télémétrie, je ne vois ni tes chronos ni la glisse. GT7 ne transmet pas l'usure : je juge sur tes chronos en course."
        StrategyDialogue.Ask.GainPitNow, StrategyDialogue.Ask.WhyPitWithFuel ->
            "un arrêt ne vaut que s'il fait gagner plus de temps qu'il n'en coûte ; je n'ai pas de course en cours pour le chiffrer."
        StrategyDialogue.Ask.Early -> "avancer un arrêt ne vaut que si le rythme chute nettement ou si le règlement l'impose."
        StrategyDialogue.Ask.OthersPitting -> "on suit notre propre plan tant que notre autonomie le permet ; je n'ai de données ni sur eux ni sur nous."
        StrategyDialogue.Ask.FuelToFinish -> "je ne connais pas ton carburant tant que GT7 ne m'envoie rien."
        StrategyDialogue.Ask.WhatChanged -> "pas de course en cours, donc aucun plan à comparer."
        else -> "je n'ai pas de données de course."
    }

    private val noPlan = "Je n'ai pas encore de plan : il me faut le nombre de tours et un tour mesuré."

    private fun firstSentence(s: String): String = s.substringBefore(". ").trimEnd('.') + "."

    /**
     * Pourquoi les obligations appliquées (arrêt obligatoire, gommes imposées) ne sont pas certaines, ou null si elles
     * le sont : deux sources concordantes, saisies par le pilote, ou règlement confirmé par lui. Course personnalisée :
     * règlement du pilote, jamais mis en doute.
     */
    fun regulationDoubt(): String? {
        // Course personnalisée : obligations venues d'un règlement mémorisé, jamais de la PS5, tant que le pilote ne
        // les a pas confirmées pour cette course.
        if (profile.id.mode == SessionMode.CUSTOM && localRulesAsked && !localRulesConfirmed && localObligations()) {
            return "règlement local mémorisé, non confirmé pour cette course"
        }
        if (!profile.id.sport || profile.confirmed) return null
        val reasons = mutableListOf<String>()
        // Épreuve choisie par le pilote (officiel, entraînement) : son choix vaut identification. Seule une épreuve
        // posée par la reconnaissance automatique peut être « seulement probable ».
        (sessionIdentity as? SessionMatcher.Result.Match)
            ?.takeIf { it.confidence == SessionMatcher.Confidence.PROBABLE && profile.id.mode == SessionMode.SPORT_IDENTIFIED }
            ?.let { reasons += "course ${it.series.letter} seulement probable" }
        val st = profile.ruleStatus
        fun singleSource(f: RuleField): Boolean = st[f] == com.gt7pitwall.core.sport.FieldStatus.PROBABLE
        val weak = buildList {
            if (rules.mandatoryStops > 0 && singleSource(RuleField.MANDATORY_STOPS)) add(edition?.mandatoryStops?.sources)
            if (rules.requiredTyres.isNotEmpty() && singleSource(RuleField.REQUIRED_TYRES)) add(edition?.requiredTyres?.sources)
        }
        if (weak.isNotEmpty()) {
            val names = weak.flatMap { it.orEmpty() }.map { it.name }.distinct()
            reasons += if (names.isEmpty()) "une seule source" else "seule la fiche ${names.joinToString(" et ")} la donne"
        }
        return reasons.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    /**
     * Obligations encore inconnues (fiche incomplète ou sources contradictoires). Le règlement enregistré vaut alors
     * « 0 arrêt, aucune gomme imposée » par défaut : jamais présenté comme une absence d'obligation.
     */
    private fun obligationCaveat(): String {
        val g = ruleGaps
        val parts = listOfNotNull(
            "l'arrêt obligatoire".takeIf { RuleField.MANDATORY_STOPS in g },
            "les gommes imposées".takeIf { RuleField.REQUIRED_TYRES in g },
        )
        if (parts.isEmpty()) return ""
        return " Attention : ${parts.joinToString(" et ")} ${if (parts.size > 1) "ne sont" else "n'est"} pas confirmé${if (parts.size > 1) "s" else ""} par le règlement."
    }

    /** Ce que la stratégie ne peut pas tenir pour vérifié : champs du règlement inconnus, épreuve seulement probable. */
    private fun unverifiedRuleLabels(): List<String> = ruleGaps.map { it.label } +
        listOfNotNull(
            (sessionIdentity as? SessionMatcher.Result.Match)?.takeIf { it.confidence == SessionMatcher.Confidence.PROBABLE }
                ?.let { "épreuve ${it.series.label} seulement probable" },
        )

    private fun rulesWarning(): String = strategy?.unverifiedRules?.takeIf { it.isNotEmpty() }
        ?.let { " Attention, règlement non vérifié : ${it.joinToString(", ")}." } ?: ""

    private fun answerBest(): String {
        val brief = strategyBrief ?: return noPlan
        return "${brief.recommendation} ${firstSentence(brief.reason)}${rulesWarning()}"
    }

    private fun answerWhyStop(asked: Int?): String {
        val p = plan ?: return noPlan
        val lap = (snapshot.packet?.currentLap ?: 1).coerceAtLeast(1)
        val next = p.nextStop(lap) ?: return "Aucun arrêt n'est prévu : le carburant suffit jusqu'à l'arrivée.${obligationCaveat()}"
        val cap = snapshot.packet?.fuelCapacity
        val why = buildList {
            if (next.forFuel) {
                add(
                    "le carburant" + (fuel?.lapsOfFuel?.let { ", il te reste ${FrenchFormat.decimal(it)} tours d'autonomie" } ?: "") +
                        (next.fuel?.let { ", il faudra ${FuelUnits.amount(it.toFloat(), cap)}" } ?: ""),
                )
            }
            val doubt = regulationDoubt()?.takeIf { next.mandatory || next.tyreToFit != null }?.let { " (à confirmer : $it)" } ?: ""
            if (next.mandatory) add("l'arrêt obligatoire du règlement$doubt")
            next.tyreToFit?.let { add("monter les ${it.spoken} imposés" + if (next.mandatory) "" else doubt) }
            if (next.changeTyres && next.tyreToFit == null) add("tes pneus atteignent la durée que tu m'as donnée")
        }
        return buildString {
            append("On rentre fin du tour ${next.endOfLap} pour ")
            append(if (why.isEmpty()) "suivre le plan" else why.joinToString(", et "))
            append(".")
            if (asked != null && asked != next.endOfLap) append(" Le plan prévoit le tour ${next.endOfLap}, pas le $asked.")
        }
    }

    private fun answerExtend(laps: Int = 1): String {
        val brief = strategyBrief ?: return noPlan
        val lap = (snapshot.packet?.currentLap ?: 1).coerceAtLeast(1)
        val next = plan?.nextStop(lap) ?: return "Rien à prolonger : aucun arrêt n'est prévu.${obligationCaveat()}"
        val tyres = if (engineer.grip.degrading) " Attention, tes pneus montrent des signes de dégradation : ce sont des tours plus lents." else ""
        if (laps <= 1) return "Prolonger d'un tour : ${brief.extendOneLap}$tyres"
        // Plus d'un tour : l'autonomie mesurée décide, avec la marge de sécurité du plan.
        if (!next.forFuel) return "Prolonger de $laps tours : l'arrêt du tour ${next.endOfLap} n'est pas pour l'essence, l'autonomie n'est pas en cause.$tyres"
        val autonomy = fuel?.lapsOfFuel ?: return "Prolonger de $laps tours : je n'ai pas encore mesuré ta consommation."
        val needed = next.endOfLap + laps - lap + 1 + RacePlanner.SAFETY_LAPS
        return if (autonomy >= needed) {
            "Prolonger de $laps tours : possible, ${FrenchFormat.decimal(autonomy)} tours d'autonomie, arrêt fin du tour ${next.endOfLap + laps}.$tyres"
        } else {
            "Prolonger de $laps tours : non, ${FrenchFormat.decimal(autonomy)} tours d'autonomie ne suffisent pas jusqu'au tour ${next.endOfLap + laps}."
        }
    }

    // ------------------------------------------------------------------ Pneus à la radio

    private fun tyreStintText(): String {
        val lap = (snapshot.packet?.currentLap ?: 1).coerceAtLeast(1)
        val st = ledger.tyreStint(lap, rules.startTyre)
        if (!st.certain) {
            val stop = ledger.stopList.lastOrNull { it.tyres == TyreService.UNKNOWN }
            val suspect = stop?.thermal == TyreChangeVerdict.SUSPECTED.name
            val whenStop = stop?.let { " au tour ${it.lap}" }.orEmpty()
            return "Depuis l'arrêt$whenStop, âge et gomme des pneus non confirmés" +
                if (suspect) " ; changement suspecté par les températures" else ""
        }
        val compound = st.compound?.spoken ?: "gomme non déclarée"
        return "Pneus de ${st.age} tour${if (st.age > 1) "s" else ""}, $compound"
    }

    /**
     * État des pneus en une phrase : gomme et âge, avant contre arrière et gauche contre droite (mesurés), écart avec la
     * température stabilisée du relais, et diagnostic d'adhérence. Aucune « bonne » température inventée : GT7 ne donne ni
     * l'usure ni la plage idéale de la gomme.
     */
    fun tyreSummary(): String {
        val t = snapshot.packet?.tyreTemps ?: return "Je n'ai pas encore de télémétrie."
        val front = (t.fl + t.fr) / 2
        val rear = (t.rl + t.rr) / 2
        val left = (t.fl + t.rl) / 2
        val right = (t.fr + t.rr) / 2
        val axle = when {
            front - rear >= AXLE_DIFF_C -> "l'avant est plus chaud de ${(front - rear).roundToInt()} degrés"
            rear - front >= AXLE_DIFF_C -> "l'arrière est plus chaud de ${(rear - front).roundToInt()} degrés"
            else -> "avant et arrière équilibrés"
        }
        val side = when {
            left - right >= SIDE_DIFF_C -> ", côté gauche plus chaud de ${(left - right).roundToInt()}"
            right - left >= SIDE_DIFF_C -> ", côté droit plus chaud de ${(right - left).roundToInt()}"
            else -> ""
        }
        val trend = engineer.tyreStableTemp?.let { ref ->
            val avg = (front + rear) / 2
            when {
                avg - ref >= TREND_DIFF_C -> " Ils sont ${(avg - ref).roundToInt()} degrés plus chauds qu'une fois stabilisés."
                ref - avg >= TREND_DIFF_C -> " Ils sont ${(ref - avg).roundToInt()} degrés plus froids qu'une fois stabilisés."
                else -> ""
            }
        } ?: ""
        val g = engineer.grip
        val grip = when (g.level) {
            com.gt7pitwall.core.tyres.GripLevel.INSUFFICIENT -> "Adhérence : pas encore assez de tours pour juger."
            com.gt7pitwall.core.tyres.GripLevel.STABLE -> "Adhérence : comportement stable."
            else -> "Adhérence : ${g.level.label}" + (g.evidence.firstOrNull()?.let { ", $it" } ?: "") + "."
        }
        return "${tyreStintText()}. Avant ${front.roundToInt()}, arrière ${rear.roundToInt()} degrés : $axle$side.$trend $grip${tyreOrDrivingSentence()}"
    }

    private fun answerTyreStatus(): String {
        val g = engineer.grip
        val declared = g.declared.takeIf { it.isNotEmpty() }?.let { " Tu m'as signalé : ${it.joinToString(", ")} (déclaré)." } ?: ""
        val body = when (g.level) {
            GripLevel.INSUFFICIENT -> "Pas encore assez de tours propres avec ces pneus pour juger (${g.cleanLaps} sur ${GripDiagnosis.MIN_LAPS})."
            GripLevel.STABLE -> g.isolatedLap?.let { "Comportement stable ; le tour $it était un tour isolé, pas une tendance." } ?: "Comportement stable : tes chronos tiennent."
            else -> "${g.level.label.replaceFirstChar { it.uppercase() }} : ${g.evidence.take(2).joinToString(", ")}. Pas forcément l'usure : ${g.alternatives.last()}."
        }
        val temps = snapshot.packet?.tyreTemps?.let { t ->
            val front = (t.fl + t.fr) / 2
            val rear = (t.rl + t.rr) / 2
            " Avant ${front.roundToInt()}, arrière ${rear.roundToInt()} degrés."
        } ?: ""
        return "${tyreStintText()}.$temps $body${tyreOrDrivingSentence()}${tractionSentence()}$declared"
    }

    private fun answerTyreLife(): String {
        val lap = (snapshot.packet?.currentLap ?: 1).coerceAtLeast(1)
        val age = ledger.tyreAge(lap)
        val unknown = ledger.stopList.lastOrNull { it.tyres == TyreService.UNKNOWN }
        if (unknown != null) {
            return "Je ne peux pas prévoir la durée restante : depuis l'arrêt au tour ${unknown.lap}, je ne sais pas si les pneus ont été changés. Confirme « pneus neufs » ou « mêmes pneus »."
        }
        rules.tyreStintLaps?.let { life ->
            return "Tu m'as dit qu'ils tiennent $life tours (déclaré) : ils en ont $age, il en reste ${(life - age).coerceAtLeast(0)}."
        }
        val life = tyreModel.performantLaps?.takeIf { it.confidence != Confidence.INSUFFICIENT }
            ?: return "Je ne peux pas le dire : pas assez de relais comparables avec ces pneus, et GT7 ne transmet pas l'usure. Ils ont $age tours."
        val at = life.mean.roundToInt()
        return "Estimation ${life.confidence.label} (${tyreModel.basis()}) : la perte devient nette vers $at tours d'âge. Tes pneus en ont $age."
    }

    private fun answerTyreChange(): String {
        val report = strategy
        val lap = (snapshot.packet?.currentLap ?: 1).coerceAtLeast(1)
        val planned = plan?.stops?.firstOrNull { it.changeTyres && it.endOfLap >= lap }
        if (planned != null) {
            val why = planned.reasons.joinToString(", ")
            return "Oui, changement prévu fin du tour ${planned.endOfLap} ($why)." + if (planned.tyresOnly) " Aucun ravitaillement nécessaire." else ""
        }
        if (report != null && report.recommended in StrategyOptions.TYRE_KINDS) return firstSentence(report.reason)
        if (engineer.grip.degrading) return "Les pneus montrent des signes de dégradation, mais je ne peux pas encore démontrer qu'un arrêt nous ferait gagner du temps."
        return "Non : rien ne montre qu'un changement de pneus nous ferait gagner du temps."
    }

    private fun answerGainPitNow(): String {
        val report = strategy ?: return noPlan
        val now = report.option(OptionKind.TYRES_NOW) ?: report.option(OptionKind.EARLY)
            ?: return "Je n'ai pas d'arrêt maintenant à comparer : la dégradation de tes pneus n'est pas encore connue."
        val u = report.uncertaintyMs
        val d = now.deltaMs
        val what = if (now.kind == OptionKind.TYRES_NOW) "Rentrer maintenant pour les pneus" else "Rentrer maintenant"
        return when {
            !now.feasible -> "$what : non, ${now.issues.firstOrNull() ?: "plan irréalisable"}."
            d == null || u == null -> "$what : je ne peux pas le chiffrer avec nos données."
            -d > u -> "$what : gain estimé ${StrategyOptions.seconds(-d)}, incertitude ${StrategyOptions.seconds(u)}." +
                if (report.recommended == now.kind) "" else " Pas assez net pour changer de stratégie."
            d > u -> "$what : on perdrait environ ${StrategyOptions.seconds(d)}."
            else -> "$what : pas de gain démontré, l'écart est sous notre incertitude."
        }
    }

    private fun answerWhyPitWithFuel(): String {
        val lap = (snapshot.packet?.currentLap ?: 1).coerceAtLeast(1)
        val next = plan?.nextStop(lap) ?: return "Je ne te fais pas rentrer : aucun arrêt n'est prévu.${obligationCaveat()}"
        return when {
            next.forFuel -> "Ton carburant ne suffit pas jusqu'à l'arrivée : arrêt fin du tour ${next.endOfLap}, ${next.fuel?.let { FuelUnits.amount(it.toFloat(), snapshot.packet?.fuelCapacity) } ?: "quantité à calculer"}."
            next.mandatory -> "Pour l'arrêt obligatoire du règlement, fin du tour ${next.endOfLap}, pas pour l'essence." +
                (regulationDoubt()?.let { " Obligation à confirmer : $it." } ?: "")
            next.tyreToFit != null -> "Pour monter les ${next.tyreToFit.spoken} imposés par le règlement, pas pour l'essence." +
                (regulationDoubt()?.let { " Obligation à confirmer : $it." } ?: "")
            next.changeTyres -> "Pas pour l'essence : pour les pneus. " + (strategy?.reason?.takeIf { "pneus" in it }?.let(::firstSentence)
                ?: "Tu m'as donné leur durée de vie et ils l'atteignent.")
            else -> "Arrêt prévu fin du tour ${next.endOfLap}."
        }
    }

    /** Constat et question du pilote (« je perds l'avant, tu vois quelque chose ? ») : noté comme déclaré, la mesure répond. */
    private fun answerGripQuestion(kind: StrategyDialogue.ObservationKind, question: String): String {
        val lap = snapshot.packet?.currentLap ?: 0
        observations.addLast(Observation(lap, kind, question.trim()))
        while (observations.size > MAX_OBSERVATIONS) observations.removeFirst()
        note(DriverNote(lap, "${kind.label} (déclaré)"))
        val g = engineer.grip
        val area = when (kind) {
            StrategyDialogue.ObservationKind.GRIP_FRONT -> com.gt7pitwall.core.tyres.GripArea.FRONT
            StrategyDialogue.ObservationKind.GRIP_REAR -> com.gt7pitwall.core.tyres.GripArea.REAR
            StrategyDialogue.ObservationKind.WHEELSPIN -> com.gt7pitwall.core.tyres.GripArea.TRACTION
            else -> null
        }
        val seen = when {
            g.level == GripLevel.INSUFFICIENT -> "Pas encore assez de tours propres avec ces pneus pour le confirmer."
            area != null && area in g.areas -> "Les mesures vont dans ton sens : ${g.evidence.take(2).joinToString(", ")}."
            g.degrading -> "Je mesure ${g.evidence.firstOrNull() ?: "une baisse"}, mais pas spécialement ${area?.label ?: "là"}."
            else -> "Je ne vois rien de net dans les mesures pour l'instant."
        }
        val causes = if (kind == StrategyDialogue.ObservationKind.WHEELSPIN) {
            " Causes possibles : accélérateur trop tôt ou trop brusque, pneus arrière froids ou fatigués, réglages."
        } else {
            " Ça peut venir des pneus, mais aussi du trafic, de la piste ou de ta trajectoire."
        }
        return "Noté, d'après toi. $seen$causes"
    }

    /** Détails sur demande (« pourquoi ? ») après une réponse sur les pneus. */
    private fun tyreDetails(): String {
        val g = engineer.grip
        val basis = listOfNotNull(
            g.evidence.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { "mesuré : $it" },
            g.declared.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { "déclaré : $it" },
            tyreModel.slopeMsPerLap?.takeIf { it.confidence != Confidence.INSUFFICIENT }?.let { "estimé : ${StrategyOptions.seconds(it.mean.toLong())} par tour d'âge (${it.confidence.label}, ${tyreModel.basis()})" },
        )
        val alt = g.alternatives.takeIf { it.isNotEmpty() }?.let { " Autres causes possibles : ${it.joinToString(", ")}." } ?: ""
        return (if (basis.isEmpty()) "Je n'ai pas encore de mesure nette sur ces pneus." else "Je me base sur : ${basis.joinToString(" ; ")}.") + alt
    }

    private fun answerEarly(): String {
        val report = strategy ?: return noPlan
        val o = report.option(OptionKind.EARLY) ?: return "Aucun arrêt n'est prévu : rien à avancer.${obligationCaveat()}"
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
            StrategyDialogue.Ask.TyreStatus, StrategyDialogue.Ask.TyreChange, StrategyDialogue.Ask.TyreLife, is StrategyDialogue.Ask.GripQuestion -> tyreDetails()
            StrategyDialogue.Ask.Best, is StrategyDialogue.Ask.Extend, StrategyDialogue.Ask.Early, StrategyDialogue.Ask.GainPitNow ->
                "${firstSentence(report.reason)} Je me base sur : $basis."
            else -> "Je me base sur : $basis.${rulesWarning()}"
        }
    }

    // ------------------------------------------------------------------ Mémoire de la course et conversation

    private fun currentLap(): Int = snapshot.packet?.currentLap ?: 0

    /** Arrêts encore à venir dans un plan (un arrêt déjà fait n'est pas un changement de plan). */
    private fun ahead(stops: List<Int>): List<Int> {
        val lap = currentLap().coerceAtLeast(1)
        return stops.filter { it >= lap }
    }

    /** « On garde la même stratégie ? » : le plan actuel comparé à celui décidé ensemble, sinon au plan annoncé avant. */
    private fun answerSamePlan(): String {
        val now = plan?.stops?.map { it.endOfLap } ?: return noPlan
        val agreed = raceMemory.agreedPlan
        val history = raceMemory.planHistory
        val reference = agreed ?: history.dropLast(1).lastOrNull() ?: history.firstOrNull()
        val label = if (agreed != null) "ce qu'on a décidé au tour ${agreed.lap}" else "le plan annoncé au tour ${reference?.lap ?: currentLap()}"
        if (reference == null || ahead(reference.stops) == ahead(now)) {
            val why = strategyBrief?.reason?.let { " ${firstSentence(it)}" } ?: ""
            return "Oui, on reste sur $label : ${RaceMemory.stopsText(ahead(now))}.$why"
        }
        val reason = lastPlanChange?.reason?.let { " Raison : ${it.trimEnd('.')}." } ?: ""
        return "Non, le plan a changé depuis $label : c'était ${RaceMemory.stopsText(ahead(reference.stops))}, maintenant ${RaceMemory.stopsText(ahead(now))}.$reason"
    }

    /** « On avait décidé quoi ? » : décisions validées, intentions, plan actuel. Rien d'autre que la mémoire. */
    private fun answerRecall(): String = buildString {
        val decisions = raceMemory.decisionList
        if (decisions.isEmpty()) {
            append("On n'a rien validé ensemble pour l'instant.")
        } else {
            append(decisions.takeLast(2).joinToString(" ") { "Au tour ${it.lap} : ${it.text.trimEnd('.')}." })
        }
        raceMemory.intentionList.lastOrNull()?.let { append(" Tu m'avais dit au tour ${it.lap} : ${it.text.trimEnd('.')}.") }
        plan?.let { append(" Plan actuel : ${RaceMemory.stopsText(ahead(it.stops.map { s -> s.endOfLap }))}.") }
    }

    private data class Stint(val number: Int, val firstLap: Int, val lastLap: Int, val compound: TyreCompound?, val cleanMs: List<Int>)

    /** Relais par train de pneus, avec leurs tours propres (ni départ, ni arrêt, ni sortie des stands, ni sortie de piste). */
    private fun stints(): List<Stint> {
        val laps = snapshot.laps
        if (laps.isEmpty()) return emptyList()
        val changes = ledger.changes
        val stopLaps = ledger.stopList.flatMap { listOf(it.lap, it.lap + 1) }.toSet()
        val bounds = listOf(0) + changes.map { it.lap }
        return bounds.mapIndexedNotNull { i, from ->
            val to = bounds.getOrNull(i + 1) ?: Int.MAX_VALUE
            val inStint = laps.filter { it.lapNumber in (from + 1)..to }
            if (inStint.isEmpty()) return@mapIndexedNotNull null
            val clean = inStint.filter { l ->
                (l.timeMs ?: 0) > 0 && !l.refuelled && !l.pitLane && (l.offTrackEvents ?: 0) == 0 && l.lapNumber != 1 && l.lapNumber !in stopLaps
            }.mapNotNull { it.timeMs }
            val compound = if (i == 0) rules.startTyre else changes[i - 1].compound
            Stint(i + 1, inStint.first().lapNumber, inStint.last().lapNumber, compound, clean)
        }
    }

    private fun stintLine(s: Stint): String {
        val tyres = s.compound?.spoken ?: "gomme non déclarée"
        val laps = if (s.firstLap == s.lastLap) "tour ${s.firstLap}" else "tours ${s.firstLap} à ${s.lastLap}"
        if (s.cleanMs.isEmpty()) return "relais ${s.number} ($tyres, $laps) : aucun tour propre"
        return "relais ${s.number} ($tyres, $laps) : moyenne ${FrenchFormat.lapTimeSpeech(s.cleanMs.average().roundToInt())} sur ${s.cleanMs.size} " +
            "tour${if (s.cleanMs.size > 1) "s" else ""} propre${if (s.cleanMs.size > 1) "s" else ""}, meilleur ${FrenchFormat.lapTimeSpeech(s.cleanMs.min())}"
    }

    /** « Je pilote mieux depuis le changement de pneus ? » : les deux derniers relais, sur les chronos mesurés. */
    private fun answerCompareStints(): String {
        val s = stints()
        if (s.size < 2) return "Pas encore de comparaison : tu n'as pas changé de pneus pendant cette course."
        val (a, b) = s[s.size - 2] to s.last()
        if (a.cleanMs.isEmpty() || b.cleanMs.isEmpty()) {
            return "Pas encore de comparaison fiable : il me faut au moins un tour propre sur chaque relais. ${stintLine(a).replaceFirstChar { it.uppercase() }} ; ${stintLine(b)}."
        }
        val diff = (a.cleanMs.average() - b.cleanMs.average()).roundToInt()
        val verdict = when {
            diff >= 300 -> "Oui, tu tournes ${StrategyOptions.seconds(diff.toLong())} plus vite en moyenne depuis le changement."
            diff <= -300 -> "Non, tu tournes ${StrategyOptions.seconds((-diff).toLong())} moins vite en moyenne depuis le changement."
            else -> "Ton rythme est quasiment le même qu'avant le changement."
        }
        return "$verdict ${stintLine(a).replaceFirstChar { it.uppercase() }} ; ${stintLine(b)}. Ça ne mesure pas que le pilotage : " +
            "les pneus neufs et le carburant plus léger comptent aussi."
    }

    /** « Qu'est-ce qu'on risque si j'attaque ? » : la marge de carburant mesurée, et ce qu'on ne sait pas des pneus. */
    private fun answerAttackRisk(): String {
        val fuelText = engineer.answer(DriverQuery.FUEL, snapshot, fuel).text
        val tyres = if (engineer.grip.degrading) " Tes pneus montrent déjà des signes de dégradation sur les chronos." else ""
        return "Attaquer fait consommer plus d'essence et use plus les pneus. Carburant : $fuelText Pneus : GT7 ne transmet pas " +
            "l'usure, je ne la juge que sur tes chronos et la glisse.$tyres"
    }

    /** Évolution du rythme et du carburant, pour la mémoire donnée à l'IA (mesures GT7 et calculs du moteur). */
    private fun raceTrend(): List<String> = buildList {
        stints().forEach { add("[mesure GT7] ${stintLine(it)}") }
        val cap = snapshot.packet?.fuelCapacity
        val samples = snapshot.fuelSamples
        if (samples.isNotEmpty()) {
            add(
                "[mesure GT7] consommation : ${samples.takeLast(3).joinToString(", ") { FuelUnits.amount(it, cap) }} sur les derniers tours" +
                    (fuel?.perLap?.let { ", moyenne ${FuelUnits.amount(it, cap)} par tour" } ?: ""),
            )
        }
        fuel?.lapsOfFuel?.let { add("[calcul du moteur] autonomie : ${FrenchFormat.decimal(it)} tours") }
    }

    /** Questions de l'ingénieur encore sans réponse. */
    private fun pendingQuestions(): List<String> = buildList {
        pendingConfirmation?.let { add("confirmation demandée au pilote (${commandName(it)})") }
        if (ledger.awaitingTyreDeclaration) add("Quels pneus as-tu montés ?")
        if (ledger.awaitingTyreChangeAnswer) add("As-tu changé les pneus ?")
        pendingTyreFamily?.let { (_, h) -> add("${h.spoken} : course, sport ou confort ?") }
        ledger.pending?.let { add("arrêt possible au tour ${it.lap}, à confirmer") }
    }

    /** Erreurs internes rattrapées (la course continue), par module : visibles dans le diagnostic et le journal. */
    val internalErrors = linkedMapOf<String, String>()

    /**
     * Un module a échoué : la course n'est pas interrompue et rien de trompeur n'est annoncé, mais l'échec est tracé
     * (journal, diagnostic) au lieu d'être masqué. Une seule entrée de journal par module et par session.
     */
    fun diagnostic(module: String, e: Throwable) {
        val what = "${e.javaClass.simpleName}${e.message?.let { ": " + it.take(120) } ?: ""}"
        if (internalErrors.put(module, what) == null) {
            runCatching { journal.record(JournalType.SESSION, JournalSource.SYSTEM, "Erreur interne ($module) : $what", decision = "module ignoré pour cette mesure, course non interrompue") }
        }
    }

    /** Bilan de pilotage de la dernière course terminée (rapport complet : écran et archive ; synthèse : radio). */
    var lastDrivingReport: com.gt7pitwall.core.engineer.DrivingReport? = null
        private set

    private fun drivingReportAtFinish(): List<EngineerMessage> {
        val r = runCatching {
            val total = snapshot.packet?.totalLaps?.takeIf { it > 0 } ?: rules.totalLaps
            val d = driverCoach.diagnostics()
            com.gt7pitwall.core.engineer.DrivingReport.of(
                laps = snapshot.laps.filter { total == null || it.lapNumber <= total },
                stopLaps = ledger.stopList.map { it.lap },
                tyreChanges = ledger.changes.map { it.lap to it.compound?.spoken },
                grid = startGrid(),
                finish = engineer.measured.finalPosition ?: finishPosition,
                cornerFindings = cornerFindings(),
                adviceGiven = d.adviceGiven,
                adviceImproved = d.adviceImproved,
            )
        }.getOrElse { diagnostic("bilan de pilotage", it); return emptyList() }
        lastDrivingReport = r
        r.lines.forEach { journal.record(JournalType.DECISION, JournalSource.ENGINE, "Bilan pilotage : $it") }
        return listOf(EngineerMessage(r.radio, Priority.HIGH, MessageCategory.RADIO, dedupKey = "bilan:$sessionId"))
    }

    /** « Qu'est-ce que Pitwall a appris sur mon pilotage ? » : uniquement les données mesurées et enregistrées. */
    fun learningReport(): List<String> = buildList {
        val d = driverCoach.diagnostics()
        addAll(d.lines())
        addAll(runCatching { driverCoach.facts(driverKey) }.getOrElse { emptyList() }.drop(1))
        cornerFindings().firstOrNull()?.let { add("Constat : $it") }
        if (d.adviceGiven == 0) add("Aucun conseil donné : ${d.lastNoAdviceReason ?: if (d.lapsUsable == 0) "aucun tour exploitable" else "pas de perte nette et répétée"}. Ce n'est pas la preuve d'un pilotage parfait.")
        internalErrors.forEach { (m, e) -> add("Erreur interne ($m) : $e.") }
    }

    /** Analyse du pilotage sur les tours propres de la session, avec les constats du profil par virage s'il en a. */
    fun drivingAnswer(query: DriverQuery): String {
        if (query == DriverQuery.DRIVING_LEARNED) return learningReport().take(LEARNED_RADIO_LINES).joinToString(" ")
        val pitLaps = ledger.stopList.flatMap { listOf(it.lap, it.lap + 1) }.toSet()
        // Virage où la perte se répète, avec une cause mesurée et une confiance au moins moyenne : en premier.
        val measured = cornerReport().firstOrNull {
            it.meanLossMs >= 100 && it.cause != null && it.confidence != com.gt7pitwall.core.analysis.CornerReport.Confidence.LOW
        }?.let { l ->
            "Au ${l.label} : ${String.format(java.util.Locale.FRANCE, "%.1f", l.meanLossMs / 1000.0)} s perdues en moyenne sur ton meilleur passage ; " +
                "cause probable : ${l.cause} (confiance ${l.confidence.label})."
        }
        return com.gt7pitwall.core.engineer.DrivingAnalysis.answer(query, snapshot.laps, pitLaps, listOfNotNull(measured) + cornerFindings())
    }

    /** Constats mesurés virage par virage (profil de pilotage), vides tant qu'il n'y en a pas. */
    fun cornerFindings(): List<String> = runCatching { driverCoach.findings().map(::relabelCorners) }.getOrElse { e ->
        diagnostic("constats par virage", e)
        emptyList()
    }

    /**
     * Libellés des virages détectés dans cette session : « T5 » d'après la numérotation communautaire du circuit quand
     * l'apex correspond, sinon « V3 » (estimé). Vide sans carte des virages.
     */
    fun cornerLabels(): Map<Int, com.gt7pitwall.core.analysis.CornerLabels.Label> = runCatching {
        val map = driverCoach.profile?.map ?: return emptyMap()
        val c = course
        com.gt7pitwall.core.analysis.CornerLabels.of(map, c?.let { database.numberedCorners[it.id] }, reverse = c?.reverse == true)
    }.getOrElse { diagnostic("numérotation des virages", it); emptyMap() }

    /** Apex des virages détectés (X, Z) avec leur libellé, pour la carte d'Analyse. */
    fun cornerMarks(): List<Triple<String, Float, Float>> = runCatching {
        val map = driverCoach.profile?.map ?: return emptyList()
        val labels = cornerLabels()
        map.corners.map { k ->
            val i = (k.apexM / com.gt7pitwall.core.analysis.CornerMap.STEP_M).roundToInt().coerceIn(0, map.xs.size - 1)
            Triple(labels[k.number]?.text ?: "V${k.number}", map.xs[i], map.zs[i])
        }
    }.getOrElse { emptyList() }

    /** Bilan par virage de la session (passages valides comparés au meilleur), le plus coûteux d'abord. */
    fun cornerReport(): List<com.gt7pitwall.core.analysis.CornerReport.Line> = runCatching {
        com.gt7pitwall.core.analysis.CornerReport.of(driverCoach.passes, cornerLabels())
    }.getOrElse { diagnostic("bilan par virage", it); emptyList() }

    /**
     * Baisse de rythme du relais en cours : pneus ou pilotage, lue virage par virage (issue #30-D). Les tours d'arrêt,
     * le tour suivant et les tours d'incident déclarés sont écartés ; aucun pourcentage d'usure.
     */
    fun tyreOrDriving(): com.gt7pitwall.core.tyres.TyreOrDriving.Report = runCatching {
        val ctx = tyreStintContext()
        val labels = cornerLabels()
        com.gt7pitwall.core.tyres.TyreOrDriving.crossCheck(
            com.gt7pitwall.core.tyres.TyreOrDriving.analyze(
                driverCoach.passes.filter { it.lap !in ctx.excludedLaps }, ctx.startLap,
            ) { labels[it]?.text ?: "V$it" },
            driverCoach.traction.last,
        )
    }.getOrElse { diagnostic("pneus ou pilotage", it); com.gt7pitwall.core.tyres.TyreOrDriving.Report(com.gt7pitwall.core.tyres.TyreOrDriving.Verdict.UNKNOWN) }

    private var tractionVersion = 0
    private var tractionLevel = com.gt7pitwall.core.tyres.TractionMonitor.Level.INSUFFICIENT

    /** Dernier constat de motricité, avec la numérotation affichée des virages (Analyse, réponses). */
    fun tractionReport(): String = driverCoach.traction.last.let { r ->
        val labels = cornerLabels()
        r.text { labels[it]?.text ?: "V$it" }
    }

    /**
     * Motricité à commande comparable (issue #35) : réévaluée à chaque tour mesuré, sur le relais en cours. La radio ne
     * parle qu'à un changement de niveau, jamais pour ordonner un arrêt : la perte entre dans la stratégie, qui seule
     * décide d'un « Box ».
     */
    private fun tractionCheck(): List<EngineerMessage> {
        val m = driverCoach.traction
        // Évaluée par le coach au moment de l'analyse du tour (avant son conseil) : ici, on lit seulement.
        if (m.evaluations == tractionVersion) return emptyList()
        tractionVersion = m.evaluations
        val st = tyreStintContext()
        val r = m.last
        val before = tractionLevel
        tractionLevel = r.level
        if (r.level == before) return emptyList()
        val lossMs = tractionLossMs(r)
        journal.record(
            JournalType.STRATEGY, JournalSource.ENGINE, "Motricité : ${tractionReport()}",
            details = listOfNotNull(
                "niveau" to r.level.name.lowercase(),
                "virages" to r.flaggedCorners.joinToString("/"),
                "tours_reference" to r.refLaps.joinToString("/"),
                "tours_recents" to r.recentLaps.joinToString("/"),
                // Temps complet des virages concernés (freinage et corde compris) : pas une perte due à la seule motricité.
                lossMs?.let { "ecart_virages_concernes_ms" to it.toString() },
                r.previous?.let { "dernier_constat_valide" to it.name.lowercase() },
                "relais_certain" to if (st.certain) "oui" else "non",
                "surface_connue" to if (r.surfaceKnown) "oui" else "non",
            ).toMap(),
        )
        // Relais incertain (arrêt sans réponse sur les pneus) : consigné, pas dit.
        if (!st.certain) return emptyList()
        // Pas de dixièmes à la radio : le temps des virages concernés n'est pas la seule perte de motricité (P1-D).
        val text = when {
            r.level == TractionLevel.DEGRADATION_SUSPECTED && before < TractionLevel.DEGRADATION_SUSPECTED ->
                if (r.surfaceKnown) "Les pneus semblent perdre de la motricité en sortie de virage. Je surveille."
                else "Possible perte de motricité en sortie de virage, à confirmer. Je surveille."
            r.level == TractionLevel.DEGRADATION_REPEATED && r.strategyEligible ->
                "Motricité en baisse confirmée sur ${r.flaggedCorners.size} virages. J'en tiens compte pour la stratégie."
            else -> null
        } ?: return emptyList()
        return listOf(
            EngineerMessage(text, Priority.NORMAL, MessageCategory.RADIO, topic = "traction", dedupKey = "motricite:$sessionId:${r.level}"),
        )
    }

    /** Temps perdu par tour dans les virages concernés : passages récents contre passages de référence (ms). */
    private fun tractionLossMs(r: com.gt7pitwall.core.tyres.TractionMonitor.Report): Int? {
        if (r.flaggedCorners.isEmpty()) return null
        val passes = driverCoach.passes
        val per = r.flaggedCorners.mapNotNull { c ->
            val ref = passes.filter { it.corner == c && it.lap in r.refLaps }.map { it.timeMs }
            val rec = passes.filter { it.corner == c && it.lap in r.recentLaps }.map { it.timeMs }
            if (ref.isEmpty() || rec.isEmpty()) null else rec.average() - ref.average()
        }
        return per.takeIf { it.isNotEmpty() }?.sum()?.roundToInt()
    }

    /**
     * Croisements n°2 à 4 (issue #36), relais en cours : freinage à pression égale, équilibre en virage, stress
     * thermique. Écran d'Analyse seulement : rien n'est dit à la radio tant que ce n'est pas validé sur PS5.
     */
    fun handlingReport(): List<String> = runCatching {
        val st = tyreStintContext()
        val labels = cornerLabels()
        listOf(
            "Freinage : " + driverCoach.braking().text { labels[it]?.text ?: "V$it" },
            "Équilibre : " + com.gt7pitwall.core.tyres.BalanceTrend.analyze(driverCoach.passes, st.startLap, st.excludedLaps).text,
            "Thermique : " + com.gt7pitwall.core.tyres.ThermalStress.analyze(snapshot.laps, st.startLap, st.excludedLaps).text,
        )
    }.getOrElse { diagnostic("freinage, équilibre, thermique", it); emptyList() }

    /** Version courte pour la voix : le niveau et le nombre de virages ; le détail chiffré reste dans Analyse. */
    private fun tractionSentence(): String {
        val r = driverCoach.traction.last
        if (r.level == com.gt7pitwall.core.tyres.TractionMonitor.Level.INSUFFICIENT) return ""
        return " ${r.level.label.replaceFirstChar { it.uppercase() }}" +
            (if (r.flaggedCorners.isNotEmpty()) " sur ${r.flaggedCorners.size} virages" else "") + " à accélérateur comparable."
    }

    /** Phrase radio du diagnostic pneus/pilotage, seulement quand il conclut quelque chose. */
    private fun tyreOrDrivingSentence(): String {
        val r = tyreOrDriving()
        return when (r.verdict) {
            com.gt7pitwall.core.tyres.TyreOrDriving.Verdict.TYRES,
            com.gt7pitwall.core.tyres.TyreOrDriving.Verdict.DRIVING,
            com.gt7pitwall.core.tyres.TyreOrDriving.Verdict.MIXED -> " Virage par virage : ${r.text.replaceFirstChar { it.lowercase() }}"
            else -> ""
        }
    }

    /**
     * « Virage 7 » (numéro de détection) devient « virage 5 » quand la numérotation du circuit est connue, pour que la
     * voix et la carte parlent du même virage.
     */
    private fun relabelCorners(text: String): String {
        val labels = cornerLabels().takeIf { it.isNotEmpty() } ?: return text
        return Regex("([Vv])irage (\\d+)").replace(text) { m ->
            val l = labels[m.groupValues[2].toInt()]
            if (l != null && l.community) "${m.groupValues[1]}irage ${l.text.removePrefix("T")}" else m.value
        }
    }

    /** Une phrase vient d'être échangée : la suivante peut la continuer (« pourquoi ? »). */
    fun inConversation(nowMs: Long = clock()): Boolean =
        inAiConversation(nowMs) || raceMemory.lastExchange?.let { nowMs - it.atMs < AI_CONVERSATION_MS } == true

    /** Sujet de l'échange en cours, s'il est récent. */
    fun conversationTopic(nowMs: Long = clock()): Topic? = raceMemory.currentTopic(nowMs, AI_CONVERSATION_MS)

    /** Calcul du moteur sur une intervention, sans le dire : il est donné à l'IA, et dit tel quel si elle ne répond pas. */
    fun engineAnalysis(turn: ConversationRouter.Turn): String? {
        turn.ask?.let { return strategyText(it, turn.question, mutableListOf()) }
        when (val q = turn.query) {
            DriverQuery.NEXT_TYRE -> return nextTyreSentence()
            DriverQuery.TYRE_CHANGE -> return tyreChangeSentence()
            DriverQuery.TYRES -> return if (snapshot.packet != null) tyreSummary() else null
            in com.gt7pitwall.core.engineer.DrivingAnalysis.QUERIES -> return drivingAnswer(q!!)
            null -> Unit
            else -> return engineer.answer(q, snapshot, fuel).text
        }
        // Relance (« pourquoi ? », « et alors ? ») : le calcul de l'échange précédent sur ce sujet reste la référence.
        if (turn.followUp) return raceMemory.lastExchange?.takeIf { it.topic == turn.topic }?.analysis
        return null
    }

    /** Contexte synthétique donné au modèle conversationnel : jamais le flux UDP. */
    fun conversationContext(turn: ConversationRouter.Turn, analysis: String?): EngineerPrompt.Context = EngineerPrompt.Context(
        telemetryLive = telemetryLive(),
        lap = snapshot.packet?.currentLap?.takeIf { it >= 0 },
        topic = turn.topic,
        facts = aiFacts(),
        analysis = analysis,
        memory = raceMemory.render(pendingQuestions(), raceTrend()) + pilotMemory.render(),
        recent = raceMemory.recent.takeLast(RECENT_EXCHANGES),
        mood = mood.mood(),
    )

    /**
     * Réponse d'une intervention conversationnelle : rédigée par le modèle [answeredBy] (vérifiée), ou le calcul du moteur
     * s'il n'a pas répondu. Rien n'est modifié dans la course.
     */
    fun conversationReply(
        turn: ConversationRouter.Turn,
        text: String,
        answeredBy: String,
        analysis: String?,
        fromAi: Boolean,
        nowMs: Long = clock(),
        notice: String? = null,
    ): List<EngineerMessage> {
        pendingConfirmation = null
        val echo = EngineerMessage("Toi : ${turn.question}", Priority.INFO, MessageCategory.DRIVER)
        raceMemory.exchange(RaceMemory.Exchange(currentLap(), nowMs, turn.question, text, answeredBy, turn.topic, analysis))
        if (fromAi) lastAiAnswerMs = nowMs
        if (turn.topic == Topic.PLAN || turn.topic == Topic.TYRES || turn.topic == Topic.FUEL) lastStrategyAnswerMs = nowMs
        turn.ask?.takeIf { it != StrategyDialogue.Ask.FollowUp }?.let { lastStrategyAsk = it }
        val shown = listOfNotNull(notice?.let { EngineerMessage(it, Priority.INFO, MessageCategory.DRIVER) })
        val answer = EngineerMessage(text, Priority.HIGH, MessageCategory.ANSWER, expectsReply = fromAi || awaitingAnswer, fromAi = fromAi)
        return listOf(echo) + deliver(shown + answer)
    }

    /**
     * « D'accord, on fait comme ça » juste après une discussion de stratégie : le plan en cours devient une décision
     * prise ensemble (mémoire et journal). Aucune donnée de course n'est modifiée ; aucun appel à l'IA.
     */
    // ------------------------------------------------------------------ Dialogue V4 (personnalité, désaccord, mémoire courte)

    /** Place gagnée mesurée : une réaction brève, au plus une fois toutes les deux minutes ; jamais une cause inventée. */
    private fun celebrate(messages: List<EngineerMessage>, nowMs: Long): List<EngineerMessage> = messages.map { m ->
        val pos = Regex("^P(\\d+)\\.$").matchEntire(m.text)?.groupValues?.get(1)?.toInt() ?: return@map m
        val before = lastSaidPosition
        lastSaidPosition = pos
        if (before != null && pos < before && nowMs - lastCelebrationMs >= CELEBRATION_GAP_MS) {
            lastCelebrationMs = nowMs
            m.copy(text = "Bien joué ! P$pos.")
        } else {
            m
        }
    }

    /** Le ton suit la course : places, meilleurs tours, incidents, décisions, progression du rythme. */
    private fun updateMood(events: List<SessionEvent>, changes: List<PositionTracker.Change>, p: TelemetryPacket) {
        mood.onLap(p.currentLap)
        if (p.currentLap >= 1) {
            for (c in changes) if (c is PositionTracker.Change.Moved && c.from != null) {
                if (c.to < c.from) mood.onPositionGained() else if (c.to > c.from) mood.onPositionLost(p.currentLap)
            }
        }
        if (pitWindowPackets > 0) mood.onStrategy()
        for (e in events) {
            if (e !is SessionEvent.LapCompleted) continue
            val t = e.lap.timeMs ?: continue
            if (e.lap.refuelled || e.lap.pitLane) continue
            val best = e.previousBestMs
            if (best != null && t < best) mood.onBestLap()
            if ((e.lap.offTrackEvents ?: 0) > 0 || (best != null && t > best * 1.05)) mood.onIncident(p.currentLap)
            val clean = snapshot.laps.filter { it.lapNumber > 1 && !it.refuelled && !it.pitLane && (it.timeMs ?: 0) > 0 }.mapNotNull { it.timeMs }
            if (clean.size >= 4) {
                val median = clean.sorted()[clean.size / 2]
                mood.onPace(clean.takeLast(2).all { it <= median })
            }
        }
    }

    /**
     * Ce que l'ingénieur pense de la course (« tu le sens comment ? »), en phrases courtes tirées des seuls faits : plan,
     * carburant, pneus, contexte sportif. Rien n'est inventé ; sans télémétrie, il le dit.
     */
    fun feelingText(nowMs: Long = clock()): String {
        if (!telemetryLive()) return "Pas de télémétrie en ce moment : je ne peux pas juger la course."
        val lap = currentLap()
        val parts = mutableListOf<String>()
        parts += when (mood.mood(nowMs)) {
            com.gt7pitwall.core.ai.RaceMood.CELEBRATE -> "Franchement, ça c'était bien."
            com.gt7pitwall.core.ai.RaceMood.ATTACK -> "Ça avance : tes derniers tours sont parmi tes meilleurs."
            com.gt7pitwall.core.ai.RaceMood.PRESSURE -> "C'est compliqué en ce moment. On se reconcentre, un virage après l'autre."
            com.gt7pitwall.core.ai.RaceMood.STRATEGY -> "On a une décision à prendre, je reste sur les chiffres."
            com.gt7pitwall.core.ai.RaceMood.CALM -> "On est bien."
        }
        val next = plan?.stops?.firstOrNull { it.endOfLap >= lap }
        parts += when {
            plan == null -> "Pas encore de plan : il me faut un tour mesuré."
            plan!!.warnings.isNotEmpty() -> "Mais je surveille un risque sur le plan."
            next == null -> "Plus d'arrêt prévu, le plan tient."
            else -> "Le plan tient : arrêt fin du tour ${next.endOfLap}."
        }
        val wear = engineer.tyreWearSuspectedLap
        when {
            wear != null -> parts += "Les pneus fatiguent depuis le tour $wear, je les surveille."
            rules.tyreMultiplier > 1 -> parts += "Je garde un œil sur les pneus."
        }
        return parts.joinToString(" ") + preferenceReminder()
    }

    private fun preferenceReminder(): String = raceMemory.preferenceList.lastOrNull()?.let { " Et je tiens compte de ta consigne : ${it.text}." } ?: ""

    /**
     * Actes de dialogue traités par l'ingénieur lui-même, avant toute IA : se rétracter, identité, préférences, désaccord
     * sur un arrêt immédiat, décision explicite, hypothèse. Renvoie null si la phrase suit son chemin habituel.
     * [cloudAvailable] : l'avis sur la course (« tu le sens comment ? ») est alors formulé par le modèle.
     */
    fun dialogue(text: String, nowMs: Long = clock(), cloudAvailable: Boolean = false): List<EngineerMessage>? {
        val lap = currentLap()
        // Réponse à une confirmation du dialogue.
        pendingPilotDecision?.let { (what, at) ->
            if (nowMs - at > DIALOGUE_CONFIRM_MS) {
                pendingPilotDecision = null
            } else if (com.gt7pitwall.core.ai.DialogueActs.yes(text)) {
                pendingPilotDecision = null
                if (what.contains("box à la fin de ce tour", ignoreCase = true)) driverRequestedPitLap = lap
                raceMemory.decision(lap, nowMs, "$what (décidé par le pilote)")
                noteIntention("décision du pilote : $what")
                journal.record(JournalType.DECISION, JournalSource.ENGINE, "Décision du pilote : $what", decision = "confirmée par le pilote")
                return localReply(text, "Compris, $what. C'est ta décision, je m'adapte et je recalcule tout après l'arrêt.")
            } else if (com.gt7pitwall.core.ai.DialogueActs.no(text)) {
                pendingPilotDecision = null
                return localReply(text, "D'accord, on reste sur le plan.")
            }
        }
        pendingLasting?.let { (what, at) ->
            if (nowMs - at > DIALOGUE_CONFIRM_MS) {
                pendingLasting = null
            } else if (com.gt7pitwall.core.ai.DialogueActs.yes(text)) {
                pendingLasting = null
                val saved = pilotMemory.remember(what, sessionId)
                return localReply(text, if (saved) "C'est retenu pour les prochaines courses." else "La mémoire durable est désactivée : je ne le garde que pour cette course.")
            } else if (com.gt7pitwall.core.ai.DialogueActs.no(text)) {
                pendingLasting = null
                return localReply(text, "D'accord, je ne retiens rien.")
            }
        }
        val act = com.gt7pitwall.core.ai.DialogueActs.classify(text) ?: return null
        return when (act) {
            com.gt7pitwall.core.ai.DialogueActs.Act.Retract -> {
                driverRequestedPitLap = null
                pendingConfirmation = null
                pendingPilotDecision = null
                val removed = raceMemory.retractLast(nowMs)
                localReply(text, if (removed != null) "OK, j'oublie." else "Rien à oublier, on continue.")
            }
            is com.gt7pitwall.core.ai.DialogueActs.Act.Correction -> null // traitée par la conversation (rétractation + suite)
            com.gt7pitwall.core.ai.DialogueActs.Act.Identity -> localReply(
                text,
                "Non, je suis l'ingénieur virtuel de Pitwall : un programme qui lit la télémétrie de GT7. Mais ta course, je la vis à fond.",
            )
            com.gt7pitwall.core.ai.DialogueActs.Act.Feeling -> if (cloudAvailable) null else localReply(text, feelingText(nowMs))
            is com.gt7pitwall.core.ai.DialogueActs.Act.RacePreference -> {
                raceMemory.preference(lap, nowMs, "« ${act.text} »")
                localReply(text, "Compris, c'est noté pour cette course. J'en tiens compte dans mes conseils.")
            }
            is com.gt7pitwall.core.ai.DialogueActs.Act.LastingPreference -> {
                pendingLasting = act.text to nowMs
                listOf(EngineerMessage("Toi : $text", Priority.INFO, MessageCategory.DRIVER)) + deliver(
                    listOf(EngineerMessage("Je le retiens pour les prochaines courses : « ${act.text} ». C'est bien ça ?", Priority.HIGH, MessageCategory.ANSWER, expectsReply = true)),
                )
            }
            com.gt7pitwall.core.ai.DialogueActs.Act.PitNowWish -> pitNowDebate(text, nowMs)
            com.gt7pitwall.core.ai.DialogueActs.Act.Decision -> {
                pendingPilotDecision = "box à la fin de ce tour" to nowMs
                mood.onStrategy(nowMs)
                listOf(EngineerMessage("Toi : $text", Priority.INFO, MessageCategory.DRIVER)) + deliver(
                    listOf(EngineerMessage("C'est ta décision : box à la fin de ce tour ? Dis oui pour confirmer.", Priority.HIGH, MessageCategory.ANSWER, expectsReply = true)),
                )
            }
            is com.gt7pitwall.core.ai.DialogueActs.Act.Hypothesis -> {
                raceMemory.hypothesis(lap, nowMs, "« ${act.text} »")
                // Avec l'IA, l'hypothèse suit le chemin de la conversation (calcul du moteur compris) ; sans elle, le moteur répond.
                if (act.tyres && telemetryLive() && !cloudAvailable) {
                    localReply(text, "On peut regarder. ${tyreScenarioSentence().trim()} Rien ne change tant que tu ne décides pas.")
                } else {
                    null
                }
            }
        }
    }

    /**
     * « Je veux rentrer maintenant » : l'ingénieur donne son avis chiffré par le moteur, le défend s'il n'est pas d'accord,
     * et demande la raison du pilote. Rien n'est modifié.
     */
    private fun pitNowDebate(text: String, nowMs: Long): List<EngineerMessage> {
        val lap = currentLap()
        mood.onStrategy(nowMs)
        noteIntention("« ${text.trim()} »")
        val next = plan?.stops?.firstOrNull { it.endOfLap >= lap }
        val report = strategy
        val early = report?.option(OptionKind.EARLY)
        val u = report?.uncertaintyMs
        val reply = when {
            next != null && next.endOfLap == lap -> "C'est prévu : arrêt à la fin de ce tour."
            report == null || early == null -> {
                awaitingReasonUntil = nowMs + DIALOGUE_CONFIRM_MS
                "Je n'ai pas de quoi chiffrer un arrêt maintenant. Qu'est-ce qui te pousse à rentrer ?"
            }
            early.feasible && early.deltaMs != null && u != null && -early.deltaMs > u -> "D'accord avec toi. ${answerEarly()}"
            else -> {
                awaitingReasonUntil = nowMs + DIALOGUE_CONFIRM_MS
                raceMemory.disagreement(lap, nowMs, "le pilote veut rentrer maintenant ; le moteur : ${answerEarly()}")
                val opener = if (early.feasible && early.deltaMs != null && u != null && early.deltaMs <= u) "Ça se discute." else "Je ne suis pas convaincu."
                "$opener ${answerEarly()} Qu'est-ce qui te pousse à rentrer ?"
            }
        }
        val out = localReply(text, reply)
        return out.map { if (it.category == MessageCategory.ANSWER) it.copy(expectsReply = true) else it }
    }

    /** Le pilote donne la raison de son désaccord : son ressenti est pris en compte, le plan réévalué. */
    private fun reasonPrefix(ask: StrategyDialogue.Ask, nowMs: Long): String? {
        if (nowMs > awaitingReasonUntil || ask !is StrategyDialogue.Ask.Observe || ask.negated) return null
        awaitingReasonUntil = 0L
        raceMemory.disagreement(currentLap(), nowMs, "raison du pilote : ${ask.kind.label}")
        return "OK, information importante. Je prends ton ressenti en compte."
    }

    fun agreement(text: String, nowMs: Long = clock()): List<EngineerMessage>? {
        if (awaitingAnswer) return null
        val last = raceMemory.lastExchange ?: return null
        if (nowMs - last.atMs > AGREEMENT_WINDOW_MS || last.topic !in AGREEMENT_TOPICS) return null
        val t = com.gt7pitwall.core.ai.IntentClassifier.clean(text)
        if (!AGREEMENT.matches(t) || Regex("(^|\\s)(pas|non|jamais)(\\s|$)").containsMatchIn(t)) return null
        val stops = plan?.stops?.map { it.endOfLap }?.let(::ahead) ?: emptyList()
        val what = "plan validé : ${RaceMemory.stopsText(stops)}, après « ${last.question.trim()} » → ${RaceMemory.firstSentence(last.answer)}"
        raceMemory.agree(currentLap(), nowMs, what)
        journal.record(
            JournalType.DECISION, JournalSource.ENGINE, "Décision prise ensemble : ${RaceMemory.stopsText(stops)}",
            decision = "validée par le pilote (« ${text.trim()} »)",
            details = mapOf("question" to last.question, "reponse" to last.answer, "repondu_par" to last.answeredBy),
        )
        val echo = EngineerMessage("Toi : $text", Priority.INFO, MessageCategory.DRIVER)
        return listOf(echo) + deliver(listOf(EngineerMessage("C'est noté, on part là-dessus : ${RaceMemory.stopsText(stops)}.", Priority.HIGH, MessageCategory.ANSWER)))
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
        val total = snapshot.packet?.totalLaps ?: 0
        // Après l'arrivée (retour au stand, tour 11/10, transition vers une autre session) : aucun arrêt, aucun pneu.
        if (sessionFinished || (total > 0 && v.lap > total)) {
            journal.record(JournalType.PIT, JournalSource.TELEMETRY, "Passage ignoré : course terminée (tour ${v.lap})", lap = v.lap)
            return emptyList()
        }
        val thermal = tyreHunter.conclude(v.lap, v.assessment)
        lastThermal = thermal.takeIf { v.assessment != PitAssessment.TRACK_STOP }
        journal.record(
            JournalType.DECISION, JournalSource.ENGINE, "Tyre Change Hunter, tour ${v.lap} : ${thermal.verdict.label}",
            decision = thermal.reason,
            details = mapOf(
                "hunter_verdict" to thermal.verdict.name,
                "hunter_saut_c" to (thermal.step?.deltas?.joinToString(",") { String.format(java.util.Locale.ROOT, "%.1f", it) } ?: "aucun"),
                "statut" to "indice expérimental : le pilote confirme, l'âge des pneus ne change pas sans lui",
            ),
            lap = v.lap,
        )
        val suspected = thermal.verdict == TyreChangeVerdict.SUSPECTED
        // Avec une durée de vie des pneus, un arrêt sans essence compte aussi : il peut s'agir d'un changement de pneus.
        // Un arrêt prévu par le plan (pneus seuls compris) rend aussi crédible un arrêt sans ravitaillement.
        val hasObligations = rules.mandatoryStops > 0 || rules.requiredTyres.isNotEmpty() || rules.tyreStintLaps != null ||
            plan?.stops?.any { v.lap - it.endOfLap in 0..1 } == true
        return when (v.assessment) {
            PitAssessment.REFUEL -> {
                val declaration = driverDeclaredDuringPitNumber
                driverDeclaredDuringPitNumber = null
                val rec = ledger.recordRefuel(v.lap, v.refuelAmount, declaration)
                ledger.setThermal(v.lap, thermal.verdict.name)
                if (declaration != null && rec.number == declaration && ledger.stopCount == rec.number) {
                    // Le pilote a déjà entendu « arrêt enregistré » et la question pneus : pas de second arrêt
                    // ni de nouvelle question qui masque la réponse en cours.
                    journal.record(
                        JournalType.PIT, JournalSource.ENGINE,
                        "Déclaration du pilote et ravitaillement GT7 rapprochés : arrêt ${rec.number} unique",
                        lap = v.lap,
                        details = mapOf("rapprochement" to "meme_visite", "carburant" to v.refuelAmount.toString()),
                    )
                    if (ledger.awaitingTyreChangeAnswer || ledger.awaitingTyreDeclaration ||
                        ledger.changeForPassage((snapshot.packet?.currentLap ?: v.lap).coerceAtLeast(1)) != null
                    ) emptyList()
                    else info(afterStopQuestions(), Priority.HIGH, expectsReply = awaitingAnswer)
                } else {
                    info(stopRecorded(rec) + afterStopQuestions(), Priority.HIGH, expectsReply = awaitingAnswer)
                }
            }
            // Arrêt sans ravitaillement : proposé s'il compte pour le règlement, ou si les quatre pneus ont changé de
            // température d'un coup (pneus seuls possibles). Rien n'est enregistré sans le « oui » du pilote.
            PitAssessment.PIT_LIKE_STOP -> if (hasObligations || suspected) {
                ledger.propose(v.lap, v.locationKnown)
                pendingStopTyreSuspect = suspected
                listOf(
                    EngineerMessage(
                        if (suspected) {
                            "Arrêt de ${v.stoppedSeconds.roundToInt()} secondes sans ravitaillement, et les quatre pneus ont changé de température d'un coup. Tu as changé les pneus ?"
                        } else {
                            "Je t'ai vu immobilisé ${v.stoppedSeconds.roundToInt()} secondes sans ravitaillement. Tu as bien effectué ton arrêt ?"
                        },
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
        raceMemory.fact(
            rec.lap, clock(), "arrêt ${rec.number} au tour ${rec.lap} ($proof)",
            if (rec.evidence == StopEvidence.REFUEL) Provenance.MEASURED else Provenance.DRIVER,
        )
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
        val recorded = ledger.declare(lap)
        if (tracker.pitVisitOpen) driverDeclaredDuringPitNumber = recorded.number
        return stopRecorded(recorded) to true
    }

    /**
     * Après un arrêt : si des gommes sont imposées, on demande lesquelles ont été montées ; si le pilote a donné
     * une durée de vie des pneus, on demande s'il les a changés. GT7 ne transmet pas les pneus montés.
     */
    private fun afterStopQuestions(): List<String> {
        // Pneus déjà déclarés pour ce passage (avant la détection du ravitaillement) : on ne redemande pas.
        val lap = (snapshot.packet?.currentLap ?: 0).coerceAtLeast(1)
        ledger.changeForPassage(lap)?.let { c ->
            if (c.compound != null || rules.requiredTyres.isEmpty()) {
                return listOf("Pneus déjà notés pour cet arrêt : ${c.compound?.spoken ?: "neufs, gomme non précisée"}.")
            }
        }
        if (rules.requiredTyres.isNotEmpty()) {
            ledger.awaitingTyreDeclaration = true
            return listOf("Quels pneus as-tu montés ?")
        }
        // Toujours demandé : un ravitaillement ne dit rien des pneus, et le suivi des pneus en dépend. Une signature
        // thermique ne fait que changer la formulation : l'âge des pneus ne bouge pas sans la réponse du pilote.
        ledger.awaitingTyreChangeAnswer = true
        return if (lastThermal?.verdict == TyreChangeVerdict.SUSPECTED) {
            listOf("Les quatre pneus ont changé de température d'un coup : pneus neufs ?")
        } else {
            listOf("As-tu changé les pneus ?")
        }
    }

    /** Pneus neufs sans gomme précisée : l'âge des pneus repart de zéro. */
    private fun tyresChanged(lap: Int): List<String> {
        // Changement déjà déclaré pour ce passage (« pneus tendres » au stand, puis « oui » à « As-tu changé les pneus ? ») :
        // la confirmation n'en crée pas un second et n'efface pas la gomme.
        ledger.changeForPassage(lap.coerceAtLeast(1))?.let { c ->
            ledger.fitTyres(lap.coerceAtLeast(1), null)
            labelThermal(changed = true)
            return listOf("C'est noté : ${c.compound?.spoken ?: "pneus neufs, gomme non précisée,"} à cet arrêt.")
        }
        ledger.fitTyres(lap.coerceAtLeast(1), null)
        labelThermal(changed = true)
        performanceTyreLaps = emptyList()
        engineer.onTyresChanged(lap.coerceAtLeast(1))
        val out = mutableListOf("Pneus neufs confirmés : je repars sur un nouveau relais.")
        // GT7 ne transmet pas la gomme : on la demande une fois, on ne la devine jamais.
        ledger.awaitingTyreDeclaration = true
        out += "Tu as monté quelle gomme ?"
        return out
    }

    private var tyreQuestionPackets = 0

    /**
     * Le pilote a dit ce qu'il a fait des pneus à ce passage : son étiquette accompagne le diagnostic thermique dans le
     * journal (exemple pour la calibration). Une seule étiquette par passage ; un diagnostic ancien n'est jamais étiqueté.
     */
    private fun labelThermal(changed: Boolean) {
        val t = lastThermal ?: return
        lastThermal = null
        val lap = (snapshot.packet?.currentLap ?: t.lap)
        if (lap - t.lap !in 0..2) return
        val example = TyreHunterCalibration.Example(
            suspected = t.verdict == TyreChangeVerdict.SUSPECTED, changed = changed, stepC = t.step?.deltas.orEmpty(),
        )
        sessionExamples += example
        journal.record(
            JournalType.PIT, JournalSource.ENGINE,
            "Tyre Change Hunter, étiquette du pilote au tour ${t.lap} : ${if (changed) "pneus changés" else "pneus conservés"} " +
                "(diagnostic : ${t.verdict.label})",
            details = mapOf(
                "hunter_etiquette" to if (changed) "pneus_changes" else "pneus_conserves",
                "origine_etiquette" to "réponse du pilote",
                "hunter_verdict" to t.verdict.name,
                "hunter_saut_c" to (t.step?.deltas?.joinToString(",") { String.format(java.util.Locale.ROOT, "%.1f", it) } ?: "aucun"),
            ),
            lap = t.lap,
        )
    }

    /** Séries complètes d'un passage (sortie comprise), pour l'export du Tyre Change Hunter. */
    private fun journalThermal(c: ThermalCapture) {
        journal.record(
            JournalType.PIT, JournalSource.TELEMETRY,
            "Tyre Change Hunter, séries du tour ${c.lap} : ${c.samples.size} s mesurées, ${c.verdict.label}",
            details = TyreChangeHunter.exportDetails(c),
            lap = c.lap,
        )
    }

    /**
     * Fiabilité du diagnostic sur tous les arrêts étiquetés par le pilote (journaux archivés et session en cours). Tant
     * qu'elle n'est pas démontrée, le pilote confirme toujours ; même démontrée, cette version ne confirme rien seule.
     */
    fun tyreHunterCalibration(): TyreHunterCalibration.Status {
        val archived = calibrationCache ?: runCatching {
            val s = storage ?: return@runCatching emptyList()
            s.journalSessionIds().filter { it != sessionId }.flatMap { id -> SessionJournal.load(s, id) }
                .filter { it.details["hunter_etiquette"] != null && it.details["hunter_verdict"] != null }
                .map {
                    TyreHunterCalibration.Example(
                        suspected = it.details["hunter_verdict"] == TyreChangeVerdict.SUSPECTED.name,
                        changed = it.details["hunter_etiquette"] == "pneus_changes",
                        stepC = TyreHunterCalibration.parseStep(it.details["hunter_saut_c"]),
                    )
                }
        }.getOrDefault(emptyList()).also { calibrationCache = it }
        return TyreHunterCalibration.evaluate(archived + sessionExamples)
    }

    /** Relais pneus en cours pour le diagnostic d'adhérence : indépendant des ravitaillements. */
    private fun tyreStintContext(): com.gt7pitwall.core.tyres.StintContext {
        val lap = (snapshot.packet?.currentLap ?: 1).coerceAtLeast(1)
        val st = ledger.tyreStint(lap, rules.startTyre)
        val excluded = ledger.stopList.flatMap { listOf(it.lap, it.lap + 1) }.toSet() +
            driverNotes.filter { INCIDENT_NOTE.containsMatchIn(it.text) }.map { it.lap }
        return com.gt7pitwall.core.tyres.StintContext(st.startLap, st.certain, excluded, activeObservations.map { it.kind }, engineer.raining)
    }

    private companion object {
        /** Voiture immobile depuis 0,5 s (à 60 paquets/s), à moins de [GRID_STILL_M] près : position de grille examinée. */
        const val GRID_STILL_PACKETS = 30
        const val GRID_STILL_M = 2f

        /** Nouvelle position à l'arrêt examinée si elle est à plus de [GRID_REPROBE_M] de la précédente ; au plus [GRID_PROBES] par session. */
        const val GRID_REPROBE_M = 50f
        const val GRID_PROBES = 4

        /** Acquis d'une qualification repris par la course qui suit pendant au plus 30 min. */
        const val HANDOFF_MAX_MS = 30L * 60 * 1000
        val INCIDENT_NOTE = Regex("(?i)accident|accroch|t[eê]te.?[aà].?queue|sortie de piste|touch[eé]|crash|abîm|abim")
        const val TYRE_QUESTION_PACKETS = 60 * 60

        /** Un tour plus court n'est pas un circuit (tour incomplet, chargement). */
        const val MIN_LEARN_LAP_M = 1_000f

        /** Écart toléré entre la distance mesurée d'un tour et la longueur officielle (trajectoire, coupes). */
        const val TRACK_LENGTH_TOLERANCE = 0.15f

        /** Une moyenne supérieure à la vitesse de pointe (+5 %) est impossible : circuit erroné. */
        const val TRACK_SPEED_TOLERANCE = 1.05

        /** Réponse radio à « qu'as-tu appris ? » : l'essentiel ; tout le diagnostic est à l'écran. */
        const val LEARNED_RADIO_LINES = 4
        const val POSITION_CALM_MS = 30_000L

        /** Réponses gardées entières : détail demandé, secteurs, règlement. */
        val DETAILED_QUERIES = setOf(DriverQuery.DETAILS, DriverQuery.SECTORS, DriverQuery.OBLIGATIONS) +
            com.gt7pitwall.core.engineer.DrivingAnalysis.QUERIES

        /** Réponse à « quelle gomme ? » : quelques mots (« course mediums », « les moyens »). */
        const val COMPOUND_ANSWER_MAX_WORDS = 4
        const val AXLE_DIFF_C = 5f
        const val SIDE_DIFF_C = 5f
        const val TREND_DIFF_C = 8f
        const val AI_LAPS = 5
        const val AI_HISTORY = 3
        const val AI_CONVERSATION_MS = 45_000L
        const val TELEMETRY_LIVE_MS = 3_000L
        const val MAX_INTENTIONS = 3
        /** Fenêtre d'écho : la phrase est dite (quelques secondes), passe par Discord et peut revenir par le casque. */
        const val ECHO_WINDOW_MS = 10_000L
        const val MAX_SPOKEN = 20
        const val MAX_NOTES = 30
        const val MAX_OBSERVATIONS = 6
        const val OBSERVATION_LAPS = 5
        const val STRATEGY_FOLLOW_UP_MS = 45_000L
        const val MAX_PLANS = 40
        const val RECENT_EXCHANGES = 4
        const val AGREEMENT_WINDOW_MS = 120_000L
        val AGREEMENT_TOPICS = setOf(Topic.PLAN, Topic.TYRES, Topic.FUEL)
        val AGREEMENT = Regex(
            "^(bon |alors |ok |okay |ouais |oui )*(ok|okay|d'accord|ca marche|ca me va|ca roule|je valide|valide|banco|c'est parti|" +
                "on fait comme ca|on fait ca|on part la dessus|on part sur ca|on y va|on garde ca|parfait on fait ca)( (alors|on y va|on fait comme ca|on fait ca|c'est parti))?$",
        )

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
            DriverQuery.STOP_STATUS to "Arrêt prévu",
            DriverQuery.TYRE_CHANGE to "Changement de pneus",
            DriverQuery.NEXT_TYRE to "Prochaine gomme",
        )

        /** 45 s après la ligne d'un tour d'arrêt prévu, le temps d'atteindre son stand. */
        const val PIT_WINDOW_PACKETS = 45 * 60

        /** Délai laissé au pilote pour répondre à une question sur l'arrêt ou les pneus avant de recalculer. */
        const val QUESTION_WINDOW_PACKETS = 30 * 60

        private val FAMILY_RANK = listOf(TyreCompound.Family.RACING, TyreCompound.Family.SPORT, TyreCompound.Family.COMFORT)
        private val HARDNESS_RANK = listOf(TyreCompound.Hardness.SOFT, TyreCompound.Hardness.MEDIUM, TyreCompound.Hardness.HARD)

        /** Délai pour répondre « course », « sport » ou « confort » à la question sur la gomme. */
        const val TYRE_FAMILY_WINDOW_MS = 30_000L

        /** « Tu confirmes … ? » : le « oui » est attendu 45 s au plus. */
        const val CONFIRMATION_WINDOW_MS = 45_000L

        /** Question sur le règlement local mémorisé : réponse attendue 60 s (grille, début du premier tour). */
        const val LOCAL_RULES_QUESTION_MS = 60_000L

        /** « B ou C ? » : la réponse est attendue une minute (grille, premier tour). */
        const val SERIES_QUESTION_WINDOW_MS = 60_000L

        /** Paquets identiques (voiture « en mouvement » qui ne bouge pas) avant de considérer la télémétrie figée : 0,5 s. */
        const val FROZEN_PACKETS = 30

        /** Une confirmation du dialogue (décision, préférence durable) attend au plus 30 s. */
        const val DIALOGUE_CONFIRM_MS = 30_000L
        const val CELEBRATION_GAP_MS = 120_000L
    }

    /**
     * Réponse attendue qui change le plan (la question sur les pneus ne compte que si leur durée de vie est connue ; celle
     * sur la gomme montée, que si des gommes sont imposées).
     */
    private val awaitingPlanAnswer: Boolean
        get() = pendingConfirmation != null || (ledger.awaitingTyreDeclaration && rules.requiredTyres.isNotEmpty()) || ledger.pending != null ||
            (ledger.awaitingTyreChangeAnswer && (rules.tyreStintLaps != null || performanceTyreLaps.isNotEmpty() || plan?.stops?.any { it.forTyres } == true))

    private val awaitingAnswer: Boolean
        get() = pendingConfirmation != null || ledger.awaitingTyreDeclaration || ledger.awaitingTyreChangeAnswer || ledger.pending != null

    private fun tyres(c: DriverCommand.Tyres, lap: Int): List<String> {
        // Issue #30 : gommes imposées par une fiche Sport que deux sources confirment (« tendres » quand RM + RS sont
        // imposées) : la famille se déduit sans question de plus. La gomme reste déclarée par le pilote, jamais mesurée.
        val sheetConfirmed = profile.id.sport && profile.ruleStatus[RuleField.REQUIRED_TYRES] == com.gt7pitwall.core.sport.FieldStatus.CONFIRMED
        val compound = TyreCompound.fromSpeech(c.text, rules.allowedTyres, rules.requiredTyres, rulesConfirmed = profile.confirmed || sheetConfirmed)
            ?: familyFromContext(c, lap)
            ?: return ambiguousTyres(c)
        pendingTyreFamily = null
        val out = mutableListOf<String>()
        if (rules.allowedTyres.isNotEmpty() && compound !in rules.allowedTyres) {
            out += if (profile.confirmed) {
                "Attention : les ${compound.spoken} ne sont pas dans les gommes autorisées."
            } else {
                "À vérifier : les ${compound.spoken} ne sont pas dans le règlement enregistré, qui n'est pas confirmé."
            }
        }
        // « Je suis en medium » après une signature thermique n'est PAS une correction du pneu de départ.
        // La déclaration explicite d'une nouvelle gomme par le pilote confirme le candidat observé
        // (et lui seul). Le train commence au premier tour APRÈS le passage aux stands.
        val recentCandidate = ledger.pending?.takeIf {
            c.current && !c.atStart && lap - it.lap in 0..2 &&
                ledger.currentTyre(rules.startTyre) != compound
        }
        val atStart = c.atStart || lap < 1 ||
            (c.current && ledger.stopCount == 0 && ledger.changes.isEmpty() && recentCandidate == null && ledger.pending == null)
        if (atStart) {
            applyRules(rules.copy(startTyre = compound))
            raceMemory.fact(lap.coerceAtLeast(0), clock(), "départ en ${compound.spoken}", Provenance.DRIVER)
            out += "Noté : départ en ${compound.spoken}."
        } else {
            val confirmedStop = recentCandidate?.let { candidate ->
                ledger.confirmPending()?.also { rec ->
                    pendingStopTyreSuspect = false
                    ledger.setThermal(rec.lap, lastThermal?.verdict?.name ?: TyreChangeVerdict.SUSPECTED.name)
                    out += "Arrêt au tour ${rec.lap} confirmé par ta déclaration de pneus."
                }
            }
            val effectiveLap = confirmedStop?.lap?.plus(1) ?: lap
            val passage = ledger.changeForPassage(effectiveLap)
            val replaced = ledger.fitTyres(effectiveLap, compound)
            labelThermal(changed = true)
            performanceTyreLaps = emptyList()
            if (passage == null) engineer.onTyresChanged(effectiveLap)
            out += when {
                replaced != null -> {
                    journal.record(
                        JournalType.DECISION, JournalSource.ENGINE, "Gomme corrigée : ${replaced.spoken} → ${compound.spoken}",
                        decision = "déclaration précédente remplacée", details = mapOf("avant" to replaced.name, "apres" to compound.name, "tour" to passage!!.lap.toString()),
                    )
                    raceMemory.fact(lap, clock(), "gomme corrigée : ${compound.spoken} au lieu de ${replaced.spoken}", Provenance.DRIVER)
                    "Corrigé : ${compound.spoken} au lieu de ${replaced.spoken}."
                }
                passage != null -> "Noté : ${compound.spoken} montés à cet arrêt.".also { raceMemory.fact(lap, clock(), "${compound.spoken} montés", Provenance.DRIVER) }
                else -> "Noté : ${compound.spoken} montés au tour $effectiveLap.".also { raceMemory.fact(effectiveLap, clock(), "${compound.spoken} montés", Provenance.DRIVER) }
            }
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
     * « J'ai mis des mediums » en réponse à la question sur la gomme, ou « non, finalement des tendres » juste après une
     * déclaration (course du 26/10) : la famille est celle des pneus actuels, sans seconde question. Hors de ce contexte,
     * ou si le règlement l'exclut, la famille est demandée.
     */
    private fun familyFromContext(c: DriverCommand.Tyres, lap: Int): TyreCompound? {
        if (c.atStart) return null
        val t = DriverCommandParser.normalize(c.text)
        val hardness = TyreCompound.hardnessIn(t) ?: return null
        if (TyreCompound.familyIn(t) != null) return null
        val answering = ledger.awaitingTyreDeclaration || ledger.changeForPassage(lap.coerceAtLeast(1)) != null
        if (!answering) return null
        val family = (ledger.currentTyre(rules.startTyre) ?: rules.startTyre)?.family ?: return null
        return TyreCompound.of(family, hardness)?.takeIf { rules.allowedTyres.isEmpty() || it in rules.allowedTyres }
    }

    /** Dureté dite sans famille, en attente de précision : « tendres » → « course, sport ou confort ? ». */
    private var pendingTyreFamily: Pair<DriverCommand.Tyres, TyreCompound.Hardness>? = null
    private var pendingTyreFamilyMs = 0L

    /**
     * Gomme non identifiable. Si seule la famille manque, on la demande, avec en hypothèse la famille des pneus actuels
     * (« oui » la retient) ; jamais une famille tirée d'un règlement non confirmé.
     */
    private fun ambiguousTyres(c: DriverCommand.Tyres): List<String> {
        val t = DriverCommandParser.normalize(c.text)
        val hardness = TyreCompound.hardnessIn(t)
        if (hardness == null || TyreCompound.familyIn(t) != null) {
            return listOf("Précise la gomme : course, sport ou confort, et dure, medium ou tendre.")
        }
        pendingTyreFamily = c to hardness
        pendingTyreFamilyMs = clock()
        val current = (ledger.currentTyre(rules.startTyre) ?: rules.startTyre)?.family
        val guess = current?.let { TyreCompound.of(it, hardness) }
        if (guess != null) {
            pendingConfirmation = c.copy(text = "${current.spoken} ${hardness.spoken}")
            return listOf("Tu confirmes des pneus ${guess.spoken} actuellement ?")
        }
        return listOf("${hardness.spoken.replaceFirstChar { it.uppercase() }} : course, sport ou confort ?")
    }

    /** Réponse d'un seul mot de famille (« course ») à la question sur la gomme. */
    /**
     * Gomme dite seule en réponse à « Tu as monté quelle gomme ? » (course du 25/10 : « moyens », puis « medium », restés
     * sans effet faute de mot « pneus » dans la phrase). Seulement une réponse courte, pendant que la question est posée.
     */
    private fun compoundAnswer(text: String): DriverCommand.Tyres? {
        if (!ledger.awaitingTyreDeclaration || pendingTyreFamily != null) return null
        val t = DriverCommandParser.normalize(text).replace(Regex("[.,!?;:]"), " ").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty() || t.split(' ').size > COMPOUND_ANSWER_MAX_WORDS) return null
        val named = TyreCompound.hardnessIn(t) != null || TyreCompound.entries.any { Regex("(^|\\s)${it.name.lowercase()}($|\\s)").containsMatchIn(t) } ||
            Regex("intermediaire|(^|\\s)(pluie|wet)($|\\s)").containsMatchIn(t)
        if (!named) return null
        if (Regex("(^|\\s)(pluie|wet)($|\\s)").containsMatchIn(t)) return DriverCommand.Tyres("pneus $t", atStart = false)
        // Course du 26/10 : « medium » seul, réponse à la question sur la gomme. Sans famille dite, celle des pneus
        // actuels (RS au départ → course) : la réponse ne relance pas une seconde question ; le pilote peut corriger
        // (« non, sport mediums »). Famille inconnue ou contraire au règlement : la question de famille reste posée.
        val hardness = TyreCompound.hardnessIn(t)
        if (hardness != null && TyreCompound.familyIn(t) == null) {
            val family = (ledger.currentTyre(rules.startTyre) ?: rules.startTyre)?.family
            val guess = family?.let { TyreCompound.of(it, hardness) }
            if (guess != null && (rules.allowedTyres.isEmpty() || guess in rules.allowedTyres)) {
                return DriverCommand.Tyres("${family.spoken} ${hardness.spoken}", atStart = false)
            }
        }
        return DriverCommand.Tyres(t, atStart = false)
    }

    /**
     * La phrase répond-elle à une question que l'ingénieur vient de poser (gomme montée, famille de gomme) ? Elle va
     * alors directement au moteur, jamais écartée comme bout de phrase par la conversation (course du 26/10).
     */
    fun answersPendingQuestion(text: String): Boolean =
        compoundAnswer(text) != null || tyreFamilyAnswer(text) != null || seriesAnswer(text) != null

    private fun tyreFamilyAnswer(text: String): DriverCommand.Tyres? {
        val (c, hardness) = pendingTyreFamily ?: return null
        // Question expirée : un mot isolé n'est plus relu comme une gomme.
        if (clock() - pendingTyreFamilyMs > TYRE_FAMILY_WINDOW_MS) {
            pendingTyreFamily = null
            return null
        }
        val t = DriverCommandParser.normalize(text)
        if (TyreCompound.hardnessIn(t) != null) return null
        val family = TyreCompound.familyIn(t) ?: return null
        return c.copy(text = "${family.spoken} ${hardness.spoken}")
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
            if (!ledger.tyreStint(lap, rules.startTyre).certain) append(" Âge incertain : un arrêt est resté sans réponse sur les pneus.")
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

    /**
     * Quelle gomme monter au prochain arrêt. S'appuie sur les gommes imposées, autorisées (en disant si le règlement est
     * confirmé), les pneus montés, les tours restants et la durée de vie donnée par le pilote. GT7 ne transmet pas
     * l'usure : sans durée de vie, l'ingénieur le dit et pose une seule question courte.
     */
    fun nextTyreSentence(): String = nextTyreFacts() + tyreScenarioSentence()

    /** Chiffres du moteur seulement (historique comparable) : jamais une gomme recommandée sans eux. */
    private fun tyreScenarioSentence(): String {
        val sc = tyreScenarios()
        return when {
            sc.recommended != null || sc.changeAdvised -> " D'après tes courses comparables : ${sc.reason}"
            sc.scenarios.count { it.estimable } >= 2 -> " ${sc.reason}"
            else -> " Pas assez d'historique comparable pour chiffrer les gommes."
        }
    }

    private fun nextTyreFacts(): String {
        val lap = (snapshot.packet?.currentLap ?: 1).coerceAtLeast(1)
        val total = plan?.totalLaps ?: rules.totalLaps ?: snapshot.packet?.totalLaps?.takeIf { it > 0 }
        val nextStop = plan?.stops?.firstOrNull { it.endOfLap >= lap }
        val after = if (total != null && nextStop != null) (total - nextStop.endOfLap).coerceAtLeast(0) else null
        val current = ledger.currentTyre(rules.startTyre)
        val allowed = rules.allowedTyres.filter { it.hardness != null }
        val missing = missingTyres
        return buildString {
            if (missing.isNotEmpty()) {
                append("Le règlement impose encore les ${missing.joinToString(" et ") { it.spoken }} : monte-les à ton prochain arrêt.")
                return@buildString
            }
            when {
                nextStop != null -> append("Prochain arrêt prévu fin du tour ${nextStop.endOfLap}" + (after?.let { ", il restera $it tours." } ?: "."))
                total != null && lap <= total -> append("Aucun arrêt prévu d'ici l'arrivée.${obligationCaveat()}")
            }
            current?.let { append(" Tu es en ${it.spoken}.") }
            if (allowed.isEmpty()) {
                append(" Je ne connais pas les gommes autorisées : indique-les dans la carte Course.")
                return@buildString
            }
            append(if (profile.confirmed) " Gommes autorisées : " else " D'après le règlement non confirmé, gommes autorisées : ")
            append(allowed.joinToString(", ") { it.spoken } + ".")
            if (current != null && current !in rules.allowedTyres) {
                append(" Tes ${current.spoken} n'y figurent pas : vérifie le règlement.")
            }
            // En principe : course plus rapide que sport, sport que confort ; dans une famille, tendre plus rapide que dur.
            val fastest = allowed.minWith(compareBy({ FAMILY_RANK.indexOf(it.family) }, { HARDNESS_RANK.indexOf(it.hardness) }))
            append(" En principe, les plus rapides sont les ${fastest.spoken}.")
            val life = rules.tyreStintLaps
            when {
                life != null && after != null && life >= after -> append(" Ils tiennent $life tours d'après toi, assez pour les $after restants.")
                life != null && after != null -> append(" Ils tiennent $life tours d'après toi, moins que les $after restants : prends plus dur ou prévois un autre changement.")
                after != null -> append(" GT7 ne transmet pas l'usure : combien de tours tiennent-ils, d'après toi ?")
                else -> append(" GT7 ne transmet pas l'usure : je ne peux pas te dire s'ils tiendront.")
            }
        }
    }

    /** État des obligations avec leurs preuves. N'affirme jamais « règlement respecté ». */
    /**
     * Le pilote renonce à l'arrêt PRÉVU (« oublie l'arrêt, tu n'en as pas besoin »). Selon la raison de cet arrêt :
     * - arrêt obligatoire d'un règlement non vérifié (course personnalisée mémorisée, fiche d'une seule source, épreuve
     *   reconnue automatiquement) : l'obligation est retirée, notée « saisie par le pilote », et le plan recalculé ;
     * - obligation vérifiée (deux sources concordantes, règlement confirmé ou saisi par le pilote) en course Sport :
     *   jamais retirée en silence ; l'ingénieur l'explique et garde l'arrêt ;
     * - arrêt pour le carburant : impossible à supprimer sans risque de panne ; l'ingénieur donne la cible de
     *   consommation qui permettrait de finir sans ;
     * - changement de pneus pour la durée de vie donnée : on garde les pneus jusqu'à l'arrivée.
     * Toujours une confirmation claire de ce qui a réellement changé.
     */
    private fun cancelPlannedStop(lap: Int): List<EngineerMessage> {
        val next = plan?.nextStop(lap.coerceAtLeast(1))
        if (next == null) {
            val last = ledger.stopList.lastOrNull()
            return answer(listOf(
                if (last != null) "Aucun arrêt n'est prévu. Le dernier arrêt enregistré est au tour ${last.lap} : dis « retire le dernier arrêt » s'il est faux."
                else "Aucun arrêt n'est prévu : tu restes en piste jusqu'à l'arrivée.${obligationCaveat()}",
            ))
        }
        val changes = mutableListOf<String>()
        var newRules = rules
        val fields = mutableSetOf<RuleField>()
        fun verified(f: RuleField): Boolean {
            val st = profile.ruleStatus[f]
            return profile.id.sport && (profile.confirmed || st == com.gt7pitwall.core.sport.FieldStatus.CONFIRMED || st == com.gt7pitwall.core.sport.FieldStatus.PILOT)
        }
        if (next.mandatory || next.mandatoryOnly) {
            if (verified(RuleField.MANDATORY_STOPS)) {
                journal.record(JournalType.DECISION, JournalSource.ENGINE, "Annulation d'arrêt refusée : obligation vérifiée",
                    decision = "arrêt obligatoire conservé", details = mapOf("epreuve" to profile.id.label))
                return answer(listOf(
                    "Je garde l'arrêt fin du tour ${next.endOfLap} : il est obligatoire dans le règlement vérifié de ${profile.id.label}. " +
                        "Si le règlement a changé, dis « pas d'arrêt obligatoire ».",
                ))
            }
            newRules = newRules.copy(mandatoryStops = 0)
            fields += RuleField.MANDATORY_STOPS
            changes += "plus d'arrêt obligatoire"
        }
        if (next.tyreToFit != null && rules.requiredTyres.isNotEmpty()) {
            if (verified(RuleField.REQUIRED_TYRES)) {
                return answer(listOf(
                    "Je garde l'arrêt fin du tour ${next.endOfLap} : les ${next.tyreToFit.spoken} sont imposés par le règlement vérifié de ${profile.id.label}.",
                ))
            }
            newRules = newRules.copy(requiredTyres = emptySet())
            fields += RuleField.REQUIRED_TYRES
            changes += "plus de gomme imposée"
        }
        if (next.changeTyres && next.tyreToFit == null && rules.tyreStintLaps != null) {
            newRules = newRules.copy(tyreStintLaps = null)
            changes += "on garde ces pneus jusqu'à l'arrivée"
        }
        if (next.forFuel) {
            // Le carburant ne se négocie pas : sans arrêt, panne sèche. Cible de consommation pour tenter sans.
            val f = fuel
            val total = plan?.totalLaps ?: snapshot.packet?.totalLaps?.takeIf { it > 0 }
            val remaining = total?.let { (it - lap + 1).coerceAtLeast(1) }
            val target = f?.fuelLevel?.let { lvl -> remaining?.let { lvl / it } }
            val cap = snapshot.packet?.fuelCapacity
            journal.record(JournalType.DECISION, JournalSource.ENGINE, "Annulation d'arrêt refusée : carburant insuffisant",
                decision = "arrêt carburant conservé")
            return answer(listOf(
                "Je ne peux pas l'enlever : sans arrêt, le carburant ne suffit pas jusqu'à l'arrivée." +
                    (target?.let { " Pour finir sans t'arrêter, il faudrait consommer au plus ${FuelUnits.amount(it, cap)} par tour." } ?: ""),
            ))
        }
        if (changes.isEmpty()) {
            return answer(listOf("Je n'ai pas pu retirer l'arrêt fin du tour ${next.endOfLap} : je ne sais pas ce qui l'impose."))
        }
        // Le pilote corrige lui-même le règlement local : provenance « saisi par le pilote », jamais « confirmé par la PS5 ».
        val was = profile.confirmed
        rules = if (fields.isNotEmpty()) profiles.declare(newRules, fields).rules else newRules.also { applyRules(it) }
        noteConfirmationDrop(was, "arrêt obligatoire retiré par le pilote")
        storage?.saveRules(rules)
        storage?.saveProfiles(profiles)
        pitApproachReminder.reset()
        pacer.drop { it.topic == "pit-approach" || it.text.startsWith("Box") || it.text.startsWith("Stand au prochain tour") }
        journal.record(
            JournalType.DECISION, JournalSource.TRANSCRIPT, "Arrêt prévu annulé par le pilote (fin du tour ${next.endOfLap})",
            decision = changes.joinToString(", "), details = mapOf("provenance" to "déclaré par le pilote", "epreuve" to profile.id.label),
        )
        val replanned = replan()
        val still = plan?.nextStop(lap.coerceAtLeast(1))
        val confirmation = if (still == null) {
            "C'est noté : ${changes.joinToString(", ")}. Plus d'arrêt prévu, tu restes en piste jusqu'à l'arrivée."
        } else {
            "C'est noté : ${changes.joinToString(", ")}. Il reste un arrêt fin du tour ${still.endOfLap}" +
                (if (still.forFuel) ", pour le carburant." else ".")
        }
        return answer(listOf(confirmation)) + replanned.filter { it.category != MessageCategory.ANSWER }
    }

    /** « As-tu enlevé l'arrêt ? » : état du plan, sans rien modifier. */
    fun stopStatusSentence(): String {
        val lap = (snapshot.packet?.currentLap ?: 1).coerceAtLeast(1)
        val next = plan?.nextStop(lap)
        return if (next == null) {
            "Oui : aucun arrêt n'est prévu, tu restes en piste jusqu'à l'arrivée.${obligationCaveat()}"
        } else {
            val why = listOfNotNull(
                "obligatoire".takeIf { next.mandatory || next.mandatoryOnly },
                "pour le carburant".takeIf { next.forFuel },
                next.tyreToFit?.let { "pour monter les ${it.spoken}" },
                "pour les pneus".takeIf { next.changeTyres && next.tyreToFit == null },
            )
            "Non : un arrêt est encore prévu fin du tour ${next.endOfLap}" + (if (why.isEmpty()) "." else ", ${why.joinToString(", ")}.")
        }
    }

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

    /** Nouveau circuit appris, enregistré sur le téléphone et retenu pour l'épreuve en cours. */
    private fun learnTrack(name: String, distM: Float): List<EngineerMessage> {
        val c = database.learn(name, distM.roundToInt())
        storage?.saveLearnedTracks(Gt7Database.learnedJson(database.learned))
        applyRules(rules.copy(trackId = c.id))
        journal.record(
            JournalType.DECISION, JournalSource.ENGINE, "Circuit appris : ${c.name}",
            decision = "absent de la base communautaire ; longueur mesurée sur un tour complet",
            details = mapOf("longueur_m" to c.lengthM.toString(), "identifiant" to c.id.toString()),
        )
        return listOf(EngineerMessage("Nouveau circuit enregistré : ${c.name}.", Priority.INFO, MessageCategory.RADIO))
    }

    /** Reconnaissance géométrique (créée avec les signatures de la base, recréée si elles changent). */
    private var geometry: com.gt7pitwall.core.knowledge.TrackGeometryRecognizer? = null
    private var geometrySignatures: List<com.gt7pitwall.core.knowledge.TrackSignature>? = null
    /** Dernier verdict géométrique déjà traité dans cette session (journal et décision une seule fois). */
    private val geometryHandled = mutableSetOf<String>()
    /** Première position échantillonnée de la session (diagnostic du repère X/Z au journal). */
    private var firstGeometryPosition = "aucune"

    /** Ce que la géométrie a conclu dans cette session (diagnostic). */
    val trackGeometryVerdict: com.gt7pitwall.core.knowledge.TrackGeometryRecognizer.Verdict?
        get() = geometry?.verdict

    /**
     * Circuit reconnu par sa géométrie (positions X/Z) bien avant la fin du premier tour, pour que la stratégie parte
     * du bon circuit (mémoire, historique, règlement Sport). Seul un verdict unique agit : il fixe le circuit, remplace
     * un circuit mémorisé contradictoire et sert de preuve pour reconnaître la course Sport. Ambigu ou inconnu : noté au
     * journal seulement (circuit absent des signatures, configurations voisines, repère à confirmer en course).
     */
    private fun trackGeometry(p: TelemetryPacket, raceActive: Boolean, out: MutableList<EngineerMessage>) {
        val sigs = database.geometrySignatures
        if (sigs.isEmpty()) return
        if (geometrySignatures !== sigs) {
            geometrySignatures = sigs
            geometry = com.gt7pitwall.core.knowledge.TrackGeometryRecognizer(sigs)
            geometryHandled.clear()
        }
        val g = geometry ?: return
        // Fuji, 28/10 (#47) : circuit reconnu à 709 m, puis « aucune signature » au tour 6 après 26,8 km cumulés (voie des
        // stands, dérive). Une fois reconnu pour la session, le circuit n'est plus remis en cause par l'accumulation.
        if (geometryLocked) return
        // Revue #62 : qualification ou essais (GT7 n'annonce pas de tours), tour de sortie compté 0 : le tracé y sert déjà.
        val qualifyingOutLap = p.totalLaps <= 0 && p.currentLap == 0 && !p.isPaused && !p.isLoading
        val moving = (raceActive || qualifyingOutLap) && !tracker.pitVisitOpen && p.speedKmh > 10f
        if (moving && firstGeometryPosition == "aucune") {
            firstGeometryPosition = String.format(java.util.Locale.ROOT, "x=%.0f z=%.0f", p.positionX, p.positionZ)
        }
        val v = g.onPosition(p.positionX, p.positionZ, moving)
        // Chaque conclusion n'est traitée qu'une fois par session (pas de journal à chaque échantillon).
        val key = when (v) {
            is com.gt7pitwall.core.knowledge.TrackGeometryRecognizer.Verdict.Identified -> "circuit ${v.course}"
            is com.gt7pitwall.core.knowledge.TrackGeometryRecognizer.Verdict.Ambiguous -> "ambigu ${v.courses}"
            is com.gt7pitwall.core.knowledge.TrackGeometryRecognizer.Verdict.Unknown -> "inconnu"
            com.gt7pitwall.core.knowledge.TrackGeometryRecognizer.Verdict.Pending -> {
                // Diagnostic : toujours rien après un tour complet (positions non échantillonnées, voiture aux stands…).
                if (p.currentLap >= 2 && geometryHandled.add("sans conclusion")) {
                    journal.record(
                        JournalType.DECISION, JournalSource.ENGINE, "Géométrie : pas de conclusion après un tour",
                        decision = "reconnaissance par la longueur",
                        details = mapOf(
                            "distance_m" to g.distanceM.roundToInt().toString(),
                            "echantillons" to g.sampleCount.toString(),
                            "premiere_position" to firstGeometryPosition,
                        ),
                    )
                }
                return
            }
        }
        if (!geometryHandled.add(key)) return
        when (v) {
            is com.gt7pitwall.core.knowledge.TrackGeometryRecognizer.Verdict.Identified -> {
                val c = database.course(v.course) ?: return
                val previous = rules.trackId
                journal.record(
                    JournalType.DECISION, JournalSource.ENGINE, "Circuit reconnu par la géométrie : ${c.label}",
                    decision = when {
                        previous == v.course -> "circuit enregistré confirmé"
                        previous == null -> "circuit appliqué"
                        else -> "circuit enregistré (${database.course(previous)?.label ?: previous}) remplacé"
                    },
                    details = mapOf(
                        "distance_m" to v.distanceM.roundToInt().toString(),
                        "ecart_moyen_m" to String.format(java.util.Locale.ROOT, "%.1f", v.meanDevM),
                        "sens" to if (v.reverse) "inverse" else "normal",
                        "signature" to v.name,
                        "premiere_position" to firstGeometryPosition,
                        "source" to (v.source ?: "gt7-datalogger-track-data (CC0 / MIT)"),
                    ),
                )
                sessionTrackEvidence = v.course
                sessionTrackSource = TrackEvidence.GEOMETRY_MEASURED
                geometryLocked = true
                milestone(Milestone.TRACK_MEASURED, "télémétrie (tracé X/Z)", "mesurée", "${c.label} à ${v.distanceM.roundToInt()} m")
                trackCandidates = emptyList()
                if (previous != v.course) {
                    applyRules(rules.copy(trackId = v.course))
                    out += EngineerMessage("Circuit reconnu : ${c.label}.", Priority.NORMAL, MessageCategory.RADIO, dedupKey = "geometrie:$sessionId:${v.course}")
                    out += replan()
                }
            }
            is com.gt7pitwall.core.knowledge.TrackGeometryRecognizer.Verdict.Ambiguous -> journal.record(
                JournalType.DECISION, JournalSource.ENGINE,
                "Géométrie : ${v.courses.joinToString(" ou ") { database.course(it)?.label ?: it.toString() }}",
                decision = "configurations voisines, aucune choisie",
                details = mapOf("distance_m" to v.distanceM.roundToInt().toString()),
            )
            is com.gt7pitwall.core.knowledge.TrackGeometryRecognizer.Verdict.Unknown -> journal.record(
                JournalType.DECISION, JournalSource.ENGINE, "Géométrie : aucune signature ne correspond",
                decision = "circuit absent des signatures ou repère à vérifier ; reconnaissance par la longueur",
                details = listOfNotNull(
                    "distance_m" to v.distanceM.roundToInt().toString(),
                    "echantillons" to g.sampleCount.toString(),
                    "circuit_enregistre" to (rules.trackId?.toString() ?: "aucun"),
                    g.closest().takeIf { it.isNotEmpty() }?.let { list ->
                        "plus_proche" to list.joinToString(" ; ") { c -> "${c.frame} : ${c.name} à ${String.format(java.util.Locale.ROOT, "%.0f", c.meanDevM)} m" }
                    },
                    "premiere_position" to firstGeometryPosition,
                ).toMap(),
            )
            com.gt7pitwall.core.knowledge.TrackGeometryRecognizer.Verdict.Pending -> Unit
        }
    }

    /**
     * Circuit retenu d'après la longueur du tour (avec ou sans fiche) : circuit de travail PROBABLE, jamais une preuve
     * indépendante pour reconnaître l'épreuve (issue #51). La géométrie mesurée, si elle conclut, le remplace.
     */
    private fun adoptProbableTrack(c: Course, dist: Float, why: String, out: MutableList<EngineerMessage>) {
        if (sessionTrackSource != TrackEvidence.GEOMETRY_MEASURED) {
            sessionTrackEvidence = c.id
            sessionTrackSource = TrackEvidence.LAP_LENGTH_COMPATIBLE
        }
        applyRules(rules.copy(trackId = c.id))
        journal.record(
            JournalType.DECISION, JournalSource.ENGINE, "Circuit probable : ${c.label}",
            decision = "circuit de travail, non prouvé par la géométrie",
            details = mapOf("preuve" to why, "source" to TrackEvidence.LAP_LENGTH_COMPATIBLE.name, "longueur_mesuree_m" to dist.roundToInt().toString()),
        )
        out += EngineerMessage("Circuit probable : ${c.label}.", Priority.NORMAL, MessageCategory.RADIO)
    }

    /** Circuit reconnu par la géométrie pour cette session : la reconnaissance s'arrête là (issue #47). */
    private var geometryLocked = false

    // Issue #58 : position à l'arrêt (grille, voie des stands) examinée une fois par session.
    private var gridProbeDone = false
    private var gridProbes = 0
    private var gridProbedX = Float.NaN
    private var gridProbedZ = Float.NaN
    private var gridStillPackets = 0
    private var gridLastX = Float.NaN
    private var gridLastZ = Float.NaN

    /**
     * Issue #58 (zéro touch circuits) : dès que la voiture est immobile avec une position valide (grille, stands), sa
     * position est comparée à tous les tracés connus. Un seul circuit compatible (et un seul sens possible) en fait le
     * circuit de travail PROBABLE dès la grille ; jamais une reconnaissance : un point seul ne prouve pas le circuit
     * (circuit absent des signatures, tracés qui se croisent). Le tracé mesuré en roulant confirme ou remplace.
     */
    private fun gridPositionProbe(p: TelemetryPacket, out: MutableList<EngineerMessage>) {
        if (gridProbeDone || geometryLocked || sessionTrackSource == TrackEvidence.GEOMETRY_MEASURED) return
        if (p.isLoading || p.isPaused || p.speedKmh > 1f || (p.positionX == 0f && p.positionZ == 0f) ||
            p.positionX.isNaN() || p.positionZ.isNaN()
        ) {
            gridStillPackets = 0
            return
        }
        if (gridLastX.isNaN() || kotlin.math.hypot(p.positionX - gridLastX, p.positionZ - gridLastZ) > GRID_STILL_M) {
            gridLastX = p.positionX
            gridLastZ = p.positionZ
            gridStillPackets = 0
        }
        if (++gridStillPackets < GRID_STILL_PACKETS) return
        // Revue #62 : une position ambiguë n'arrête pas tout ; une nouvelle position à l'arrêt (sortie des stands,
        // prégrille) est examinée à son tour, au plus [GRID_PROBES] fois par session.
        if (!gridProbedX.isNaN() && kotlin.math.hypot(p.positionX - gridProbedX, p.positionZ - gridProbedZ) < GRID_REPROBE_M) return
        gridProbedX = p.positionX
        gridProbedZ = p.positionZ
        if (++gridProbes >= GRID_PROBES) gridProbeDone = true
        val sigs = database.geometrySignatures
        if (sigs.isEmpty()) return
        val matches = com.gt7pitwall.core.knowledge.TrackGeometryRecognizer.positionCandidates(sigs, p.positionX, p.positionZ)
        val courses = matches.flatMap { listOfNotNull(it.sig.course, it.sig.reverseCourse) }.distinct()
        val where = String.format(java.util.Locale.ROOT, "x=%.0f z=%.0f", p.positionX, p.positionZ)
        val unique = matches.singleOrNull()?.takeIf { courses.size == 1 }
        journal.record(
            JournalType.DECISION, JournalSource.ENGINE,
            when {
                matches.isEmpty() -> "Position à l'arrêt : aucun tracé connu à moins de ${com.gt7pitwall.core.knowledge.TrackGeometryRecognizer.GRID_TOLERANCE_M.roundToInt()} m"
                else -> "Position à l'arrêt compatible avec : " + matches.joinToString(", ") { it.sig.name }
            },
            decision = when {
                unique != null -> "circuit probable, à confirmer par le tracé en roulant"
                matches.isEmpty() -> "aucune conclusion (circuit absent des signatures, ou voiture hors piste)"
                else -> "plusieurs tracés ou sens possibles : aucune conclusion avant de rouler"
            },
            details = listOfNotNull(
                "position" to where,
                "candidats" to matches.size.toString(),
                matches.firstOrNull()?.let { "ecart_m" to String.format(java.util.Locale.ROOT, "%.1f", it.devM) },
                "circuit_enregistre" to (rules.trackId?.toString() ?: "aucun"),
                "source" to (matches.firstOrNull()?.sig?.sourceLabel ?: "signatures gt7-datalogger-track-data (CC0 ; captures MIT)"),
            ).toMap(),
        )
        val id = unique?.let { courses.single() } ?: return
        gridProbeDone = true
        val c = database.course(id) ?: return
        milestone(Milestone.TRACK_CANDIDATE, "télémétrie (position à l'arrêt)", "probable", "${c.label}, écart ${unique.devM.roundToInt()} m")
        sessionTrackEvidence = id
        sessionTrackSource = TrackEvidence.POSITION_COMPATIBLE
        val previous = rules.trackId
        // Circuit enregistré (fiche, mémoire) sans tracé connu à cet endroit : la position le contredit.
        val contradicted = previous != null && previous != id && sigs.any { it.course == previous || it.reverseCourse == previous }
        if (previous == null || contradicted) {
            applyRules(rules.copy(trackId = id))
            out += EngineerMessage("Circuit probable d'après la position sur la grille : ${c.label}.", Priority.NORMAL, MessageCategory.RADIO, dedupKey = "grille:$sessionId:$id")
            out += replan()
        }
    }

    /** Circuit déjà signalé incohérent dans cette session (un seul message). */
    private var trackInconsistencyNoted = false

    /**
     * Le circuit retenu est-il compatible avec ce tour ? Course du 26/10 : Le Mans (13,6 km), mémorisé d'une course
     * précédente, pour un tour en 1:20,9 avec 240 km/h de pointe (il aurait fallu 607 km/h de moyenne). Une incohérence
     * est tracée et le circuit n'est plus utilisé pour cette session (ni mémoire, ni profil de pilotage, ni historique) ;
     * il n'est jamais remplacé d'office par un autre : la reconnaissance par la distance reprend, ou le pilote choisit.
     * Renvoie faux si le circuit vient d'être écarté.
     */
    private fun checkTrackConsistency(lap: com.gt7pitwall.core.session.LapRecord, trackId: Int, out: MutableList<EngineerMessage>): Boolean {
        val c = database.course(trackId) ?: return true
        if (c.lengthM <= 0) return true
        val dist = lap.distanceM
        val time = lap.timeMs?.takeIf { it > 0 }
        val vmax = lap.maxSpeedKmh
        val avgKmh = time?.let { c.lengthM / 1000.0 / (it / 3_600_000.0) }
        val reason = when {
            dist != null && dist >= MIN_LEARN_LAP_M && kotlin.math.abs(dist - c.lengthM) > c.lengthM * TRACK_LENGTH_TOLERANCE ->
                "distance mesurée ${km(dist)} pour une piste de ${km(c.lengthM.toFloat())}"
            avgKmh != null && vmax != null && vmax > 50f && avgKmh > vmax * TRACK_SPEED_TOLERANCE ->
                "tour en ${lapTime(time)} avec ${vmax.roundToInt()} km/h de pointe : il faudrait ${avgKmh.roundToInt()} km/h de moyenne sur ${km(c.lengthM.toFloat())}"
            else -> null
        } ?: return true
        if (!trackInconsistencyNoted) {
            trackInconsistencyNoted = true
            journal.record(
                JournalType.DECISION, JournalSource.ENGINE, "Circuit incohérent : ${c.label} ($reason)",
                decision = "circuit écarté pour cette session (mémoire, profil, historique) ; reconnaissance par la distance ou choix du pilote",
                details = mapOf("circuit" to c.id.toString(), "tour" to lap.lapNumber.toString()),
            )
            out += EngineerMessage(
                "Le circuit enregistré, ${c.label}, ne correspond pas à ce tour : je ne l'utilise plus. Choisis le circuit dans la carte Course si besoin.",
                Priority.INFO, MessageCategory.RADIO,
            )
        }
        applyRules(rules.copy(trackId = null))
        return false
    }

    private fun km(m: Float) = String.format(java.util.Locale.FRANCE, "%.1f km", m / 1000f)
    private fun lapTime(ms: Int) = "${ms / 60_000}:${String.format(java.util.Locale.FRANCE, "%04.1f", (ms % 60_000) / 1000.0)}"

    private fun learn(events: List<SessionEvent>): List<EngineerMessage> {
        val out = mutableListOf<EngineerMessage>()
        val p = tracker.snapshot.packet ?: return out
        for (e in events) {
            if (e !is SessionEvent.LapCompleted) continue
            val trackId = rules.trackId?.takeIf { checkTrackConsistency(e.lap, it, out) }
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
            if (dist < MIN_LEARN_LAP_M) continue
            // Épreuve Sport dont le circuit publié manque à la base (SUGO à sa sortie, le 15/10) : la longueur seule
            // désignerait à tort un circuit voisin (Laguna Seca, 3 602 m, pour SUGO, 3 586 m). Le circuit est appris sous
            // son nom publié, avec la longueur mesurée.
            // Issue #51 : le nom publié par la fiche est une ATTENTE (SCHEDULE_EXPECTED), jamais une observation. Il ne
            // devient le circuit de travail que si la longueur mesurée du tour le permet, et seulement comme circuit
            // probable (LAP_LENGTH_COMPATIBLE) : seule la géométrie mesurée peut le reconnaître.
            val published = edition?.takeIf { profile.id.series != Series.OTHER }?.trackName?.value
            val byLength = database.coursesMatching(dist)
            if (published != null) {
                val named = com.gt7pitwall.core.sport.ScheduleBuilder.matchTrack(published, database)
                val pick = named.filter { n -> byLength.any { it.id == n.id } }.singleOrNull()
                if (pick != null) {
                    adoptProbableTrack(pick, dist, "longueur du tour compatible avec la fiche ($published)", out)
                    continue
                }
                if (named.isEmpty()) {
                    out += learnTrack(published, dist)
                    continue
                }
                journal.record(
                    JournalType.DECISION, JournalSource.ENGINE, "Circuit publié $published non retenu : longueur du tour incompatible",
                    decision = "aucun circuit appliqué d'après la fiche",
                    details = mapOf("longueur_mesuree_m" to dist.roundToInt().toString(), "source" to TrackEvidence.SCHEDULE_EXPECTED.name),
                )
            }
            val found = byLength
            when {
                found.size == 1 -> adoptProbableTrack(found[0], dist, "longueur du tour", out)
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
        return radio.apply(messages).also { out ->
            // Ce que l'ingénieur va dire : son écho éventuel dans le micro du pilote sera reconnu (issue #45).
            out.filter { it.category != MessageCategory.DRIVER && it.text.isNotBlank() }.forEach { noteSpoken(it.text) }
        }
    }

    /**
     * La voix de l'ingénieur devient audible (téléphone ou Discord) : la fenêtre d'écho repart de ce moment, une phrase
     * mise en attente par la radio pouvant sortir bien après sa remise.
     */
    fun noteSpoken(text: String) {
        if (text.isBlank()) return
        synchronized(spoken) {
            spoken.addLast(clock() to text)
            while (spoken.size > MAX_SPOKEN) spoken.removeFirst()
        }
    }

    /** Phrases de l'ingénieur récentes (heure de remise à la voix, texte). */
    private val spoken = ArrayDeque<Pair<Long, String>>()

    /** Ce que l'ingénieur a dit depuis moins de [ECHO_WINDOW_MS] : synthèse, envoi Discord et retour par le casque compris. */
    fun recentEngineer(nowMs: Long = clock()): List<String> = synchronized(spoken) {
        // Durée de la phrase (~350 ms par mot) en plus : une longue réponse peut encore résonner à la fin.
        spoken.filter { (at, text) -> nowMs - at <= ECHO_WINDOW_MS + text.split(' ').size * 350L }.map { it.second }.distinct()
    }

    /**
     * Phrase du pilote qui n'est que l'écho de l'ingénieur (course du 28/10 : « dix second » après « Tu es P10 sur 14 »).
     * Écartée sans réponse parlée, pour ne pas relancer la boucle ; une réponse attendue (« oui », « medium », « la C »)
     * n'est jamais écartée. Null : la phrase est traitée normalement.
     */
    fun echoOfEngineer(text: String): List<EngineerMessage>? {
        if (!echoSensitive || awaitingAnswer || pendingConfirmation != null) return null
        val recent = recentEngineer()
        if (recent.isEmpty() || !EchoFilter.isEcho(text, recent)) return null
        // Commande de course à moitié reprise de l'ingénieur (« arrêt fait » après « arrêt prévu… ») : elle n'est pas
        // écartée en silence, le garde demandera « Tu confirmes… ? ». Un écho complet, lui, est écarté.
        val cmd = DriverCommandParser.parse(text)
        if (cmd != null && CommandGate.sensitive(cmd) && EchoFilter.echoShare(text, recent) < 1.0) return null
        echoIgnored++
        journal.record(
            JournalType.DECISION, JournalSource.ENGINE, "« ${text.trim()} » écarté : écho probable de l'ingénieur",
            decision = "rien modifié, pas de réponse parlée",
            details = mapOf("part_ecoute" to "%.0f %%".format(java.util.Locale.FRANCE, EchoFilter.echoShare(text, recent) * 100)),
        )
        return listOf(EngineerMessage("🎙 Écho de l'ingénieur ignoré : « ${text.trim()} »", Priority.INFO, MessageCategory.DRIVER))
    }

    /**
     * La phrase en cours de traitement a commencé pendant la voix de l'ingénieur ou juste après : seule une telle phrase
     * est comparée à ce qu'il vient de dire. Une réponse dite après la fin de sa phrase n'est jamais prise pour un écho.
     */
    @Volatile
    var echoSensitive: Boolean = false

    /** Phrases écartées comme écho depuis le lancement (diagnostic). */
    var echoIgnored = 0
        private set
}
