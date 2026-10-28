package com.gt7pitwall.app.engine

import com.gt7pitwall.core.race.JournalEvent
import com.gt7pitwall.core.race.JournalExport
import com.gt7pitwall.core.race.JournalSessionInfo
import com.gt7pitwall.core.race.JournalSource
import com.gt7pitwall.core.race.JournalType
import com.gt7pitwall.core.race.SessionJournal
import kotlinx.coroutines.withContext
import com.gt7pitwall.core.ai.AiTrace
import com.gt7pitwall.core.ai.IntentClassifier
import com.gt7pitwall.core.ai.RadioConversation
import com.gt7pitwall.core.ai.LanguageModel
import com.gt7pitwall.core.analysis.GForce
import com.gt7pitwall.core.analysis.LapAnalyzer
import com.gt7pitwall.core.analysis.LapStats
import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.engineer.Priority
import com.gt7pitwall.core.engineer.RadioLevel
import com.gt7pitwall.core.knowledge.CarTrackRecord
import com.gt7pitwall.core.knowledge.DriverGarage
import com.gt7pitwall.core.knowledge.GarageCar
import com.gt7pitwall.core.profile.PitwallLocalCareer
import com.gt7pitwall.core.knowledge.Course
import com.gt7pitwall.core.knowledge.Gt7Database
import com.gt7pitwall.core.race.EventProfile
import com.gt7pitwall.core.race.HistorySummary
import com.gt7pitwall.core.race.ProfileId
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.sport.Edition
import com.gt7pitwall.core.sport.RuleField
import com.gt7pitwall.core.sport.ScheduleState
import com.gt7pitwall.core.race.RaceStorage
import com.gt7pitwall.core.radio.DriverCommand
import com.gt7pitwall.core.radio.DriverCommandParser
import com.gt7pitwall.core.radio.DriverQuery
import com.gt7pitwall.core.radio.VoiceInput
import com.gt7pitwall.core.radio.VoiceInputEvent
import com.gt7pitwall.core.radio.VoiceOutput
import com.gt7pitwall.core.session.SessionSnapshot
import com.gt7pitwall.core.strategy.FuelReport
import com.gt7pitwall.core.strategy.PitStopRecord
import com.gt7pitwall.core.strategy.RacePlan
import com.gt7pitwall.core.strategy.RaceRules
import com.gt7pitwall.core.strategy.StopCandidate
import com.gt7pitwall.core.strategy.TyreCompound
import com.gt7pitwall.core.telemetry.ConnectionStatus
import com.gt7pitwall.core.telemetry.TelemetryClient
import com.gt7pitwall.core.telemetry.TelemetryPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import com.gt7pitwall.core.telemetry.PositionProbe
import com.gt7pitwall.core.telemetry.GapHunter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PitwallUiState(
    val snapshot: SessionSnapshot = SessionSnapshot.EMPTY,
    val fuel: FuelReport? = null,
    val stats: LapStats = LapAnalyzer.analyze(emptyList()),
    /** Position annoncée (grille GT7 puis déclarations du pilote). */
    val driverPosition: Int? = null,
    /** La position vient de GT7 (0x84 filtré), sinon des annonces du pilote. */
    val positionMeasured: Boolean = false,
    val positionCars: Int? = null,
    val quiet: Boolean = false,
    val radioLevel: RadioLevel = RadioLevel.FULL,
    val gForce: GForce? = null,
    /** Pluie signalée par le pilote (GT7 ne transmet pas la météo). */
    val raining: Boolean = false,
    /** Nom de la voiture d'après le code GT7 (base communautaire). */
    val carName: String? = null,
    /** Voiture confirmée par la PS5 ou dernière connue avec provenance, sans hypothèse déguisée. */
    val garageCar: GarageCar = GarageCar(null, null, com.gt7pitwall.core.knowledge.CarEvidence.UNKNOWN),
    val course: Course? = null,
    /** Circuits possibles d'après la distance mesurée d'un tour, quand il y a ambiguïté. */
    val trackCandidates: List<Course> = emptyList(),
    val plan: RacePlan? = null,
    val memory: CarTrackRecord? = null,
    /** Arrêts enregistrés avec leur preuve. */
    val stops: List<PitStopRecord> = emptyList(),
    /** Arrêt sans ravitaillement en attente de confirmation du pilote. */
    val pendingStop: StopCandidate? = null,
    val tyresUsed: Set<TyreCompound> = emptySet(),
    val missingTyres: Set<TyreCompound> = emptySet(),
    /** Diagnostic du profil de pilotage : ce qui est mesuré, enregistré, et pourquoi aucun conseil (données vérifiables). */
    val learning: List<String> = emptyList(),
    /** Bilan de pilotage de la dernière course terminée (rapport complet). */
    val drivingReport: List<String> = emptyList(),
    /** Préparation intégrale au départ, affichée sans multiplier les annonces vocales. */
    val preRaceBrief: List<String> = emptyList(),
    /** Virages de la session (libellé, X, Z) pour la carte d'Analyse. */
    val cornerMarks: List<Triple<String, Float, Float>> = emptyList(),
    /** Bilan par virage, le plus coûteux d'abord. */
    val cornerReport: List<String> = emptyList(),
    /** Origine de la numérotation des virages affichée. */
    val cornerNumbering: String = "",
    /** Baisse de rythme du relais : pneus ou pilotage, lue virage par virage (jamais d'usure en %). */
    val tyreOrDriving: String = "",
    /** Arrivée franchie (la télémétrie continue mais la course est finie : « Course terminée — 5/5 »). */
    val raceFinished: Boolean = false,
    /** Épreuve reconnue par la télémétrie pour la session en cours (null : rien à dire). */
    val sessionIdentity: String? = null,
    /** Tours bouclés avec les pneus actuels (d'après les changements déclarés par le pilote). */
    val tyreAgeLaps: Int = 0,
    /** L'ingénieur attend « oui / non » à « As-tu changé les pneus ? ». */
    val askTyreChange: Boolean = false,
    /** Épreuve active (Sport A/B/C, qualification ou course) et ce que le pilote en a déclaré. */
    val profile: EventProfile = EventProfile(),
    /** Sessions archivées comparables à celle-ci. */
    val history: HistorySummary = HistorySummary.EMPTY,
    /** Sessions archivées, toutes épreuves confondues. */
    val archivedSessions: Int = 0,
    /** Totals measured from all saved local GT7 sessions, independent of PSN access. */
    val localCareer: PitwallLocalCareer = PitwallLocalCareer.EMPTY,
    /** Édition de la semaine pour l'épreuve active (programme Sport public). */
    val edition: Edition? = null,
    /** Informations du règlement utiles à la stratégie qui restent inconnues. */
    val ruleGaps: List<RuleField> = emptyList(),
    /** Programme Sport connu : fraîcheur, erreur de lecture, péremption. */
    val schedule: ScheduleState? = null,
    /** Stratégies comparées (carburant et pneus) et recommandation. */
    val strategy: com.gt7pitwall.core.strategy.StrategyReport? = null,
    /** Diagnostic d'adhérence du train de pneus en cours (mesuré / déclaré / autres causes). */
    val grip: com.gt7pitwall.core.tyres.GripReport = com.gt7pitwall.core.tyres.GripReport.NONE,
    /** Relais pneus en cours, indépendant du carburant. */
    val tyreStint: com.gt7pitwall.core.strategy.TyreStint? = null,
    /** Conclusion des scénarios pneus (moteur, historique comparable). */
    val tyreScenario: String? = null,
    /** Verdict du Tyre Change Hunter au dernier arrêt (indice expérimental, jamais une confirmation). */
    val lastThermal: com.gt7pitwall.core.tyres.TyreChangeVerdict? = null,
    /** Relais pneus comparables appris (estimations). */
    val tyreModel: com.gt7pitwall.core.tyres.TyreModel = com.gt7pitwall.core.tyres.TyreModel.EMPTY,
    /** Réponses stratégiques prêtes (recommandation, prolonger, autres qui rentrent, carburant pour finir). */
    val brief: com.gt7pitwall.core.strategy.StrategyBrief? = null,
)

/**
 * Chaîne Android : réception UDP, sérialisation et voix. Toute la logique de course est dans
 * [RaceController] (module core, testé) : boutons, voix et écran passent par le même chemin.
 */
class PitwallEngine(
    private val voice: VoiceOutput,
    private val voiceInput: VoiceInput? = null,
    val database: Gt7Database = Gt7Database.EMPTY,
    private val storage: RaceStorage? = null,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = TelemetryClient()
    private val race = RaceController(database, storage)
    private val mutex = Mutex()
    private val conversation = RadioConversation(race, mutex)

    init {
        // Course : silence par défaut, seuls les tours qui comptent sont annoncés (essai PS5).
        race.engineer.quietRace = true
    }

    /** Voiture transmise par GT7 mais absente de la base : mise à jour de la base demandée (voir DatabaseRepository). */
    var onUnknownCar: () -> Unit = {}

    /** L'ingénieur parle déjà (téléphone ou Discord) : une place mise en file serait périmée à son tour. */
    var voiceBusy: () -> Boolean = { false }

    @Volatile private var lastCarPacketAtMs = 0L
    private var lastGarageComputeMs = 0L
    private var lastGarageCode: Int? = null
    private var lastGarageEditionId: String? = null
    private var lastGarageProfile: com.gt7pitwall.core.race.ProfileId? = null
    private var lastGarageTrack: Int? = null
    private val _rules = MutableStateFlow(race.rules)
    val rules: StateFlow<RaceRules> = _rules.asStateFlow()

    val connection: StateFlow<ConnectionStatus> = client.status

    private val _state = MutableStateFlow(PitwallUiState(localCareer = PitwallLocalCareer.fromSessions(race.archivedSessions)))
    val state: StateFlow<PitwallUiState> = _state.asStateFlow()

    private val _radio = MutableStateFlow<List<EngineerMessage>>(emptyList())
    val radio: StateFlow<List<EngineerMessage>> = _radio.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    @Volatile
    var voiceEnabled: Boolean = true

    /** IA de la radio (Gemini Nano sur le téléphone), null si désactivée. */
    @Volatile
    var languageModel: LanguageModel? = null

    /** Modèles cloud (Gemini Flash-Lite, Flash 3.8) ; null ou non configurés : la radio fonctionne sans cloud. */
    var cloudRouter: com.gt7pitwall.core.ai.AiRouter?
        get() = conversation.cloud
        set(value) {
            conversation.cloud = value
        }

    /** Chaque intervention traitée par l'IA (ou son repli) : compteurs et état affichés dans les réglages. */
    @Volatile
    var onAiTrace: (AiTrace) -> Unit = {}

    /** Une réponse est en préparation (la conversation ne se referme pas, signal discret si elle tarde). */
    val thinking: Boolean get() = conversation.thinking

    private val _latency = MutableStateFlow<String?>(null)
    /** Dernière mesure de latence d'un tour de parole, étape par étape (diagnostic). */
    val latency: StateFlow<String?> = _latency.asStateFlow()

    /** Tour de parole dont on attend le premier son, et les réponses qui le concluent. */
    @Volatile
    private var pendingLatency: Pair<com.gt7pitwall.core.radio.LatencyTrace, Set<EngineerMessage>>? = null

    /** Premier son réellement émis pour un message (synthèse du Fold ou salon Discord). */
    fun onAudible(message: EngineerMessage, atMs: Long) {
        val (trace, answers) = pendingLatency ?: return
        if (answers.none { it === message }) return
        pendingLatency = null
        trace.mark(com.gt7pitwall.core.radio.LatencyTrace.Step.AUDIBLE, atMs)
        val text = trace.text()
        _latency.value = text
        race.journal.record(
            JournalType.RADIO, JournalSource.SYSTEM, "Latence : $text",
            details = listOfNotNull("total_ms" to (trace.totalMs?.toString() ?: "?"), trace.slowest?.let { "etape_la_plus_lente" to it.first.label }).toMap(),
        )
    }

    private var turnSeq = 0L

    private val _memory = MutableStateFlow<List<com.gt7pitwall.core.ai.PilotMemory.Entry>>(emptyList())
    /** Mémoire durable de l'ingénieur (déclarations confirmées du pilote), consultable et effaçable. */
    val pilotMemory: StateFlow<List<com.gt7pitwall.core.ai.PilotMemory.Entry>> = _memory.asStateFlow()

    private val _memoryOn = MutableStateFlow(true)
    val pilotMemoryEnabled: StateFlow<Boolean> = _memoryOn.asStateFlow()

    fun refreshPilotMemory() {
        scope.launch {
            _memory.value = race.pilotMemory.all
            _memoryOn.value = race.pilotMemory.enabled
        }
    }

    fun forgetPilotMemory(id: String) = scope.launch { race.pilotMemory.forget(id); refreshPilotMemory() }

    fun clearPilotMemory() = scope.launch { race.pilotMemory.clear(); refreshPilotMemory() }

    fun setPilotMemoryEnabled(on: Boolean) = scope.launch { race.pilotMemory.setEnabled(on); refreshPilotMemory() }

    private val _aiTrace = MutableStateFlow<AiTrace?>(null)
    /** Dernier échange avec l'IA (diagnostic affiché dans la carte « Ingénieur IA »). */
    val aiTrace: StateFlow<AiTrace?> = _aiTrace.asStateFlow()

    /**
     * Diagnostic des champs 0x84 / 0x86 (outils de développement) : observe seulement, rien n'est transmis à la
     * stratégie ni annoncé à la radio.
     */
    val positionProbe = PositionProbe()
    private val _probe = MutableStateFlow(positionProbe.summary())
    val probe: StateFlow<PositionProbe.Summary> = _probe.asStateFlow()
    private var lastProbeMs = 0L

    fun refreshProbe() {
        _probe.value = positionProbe.summary()
    }

    /**
     * Gap Hunter (outils de développement) : exploration silencieuse des octets de la télémétrie. Désactivé par défaut ;
     * ne transmet rien à la stratégie ni à la radio.
     */
    val gapHunter = GapHunter()
    private val _gap = MutableStateFlow(gapHunter.summary())
    val gap: StateFlow<GapHunter.Summary> = _gap.asStateFlow()
    private var lastGapMs = 0L

    /** Sonde des octets de heartbeat (diagnostic, à lancer dans les menus de GT7). */
    val formatScan: StateFlow<TelemetryClient.FormatScan> = client.scan

    fun setGapHunter(on: Boolean) {
        gapHunter.setEnabled(on)
        updateRawTap()
        refreshGap()
    }

    /** Un seul branchement sur les paquets déchiffrés : Gap Hunter et trace télémétrique, chacun s'il est actif. */
    private fun updateRawTap() {
        client.rawTap = if (!gapHunter.enabled && !recorder.running) null else TelemetryClient.RawTap { plain, size, nowMs ->
            if (gapHunter.enabled) gapHunter.onFrame(plain, size, nowMs)
            if (recorder.running) recorder.onFrame(plain, nowMs)
        }
    }

    // ------------------------------------------------------------------ Trace télémétrique réelle (#41 P1)

    /** Enregistrement sur demande du pilote, coupé à chaque lancement de Pitwall. Paquets GT7 seulement, aucun son. */
    val recorder = com.gt7pitwall.core.telemetry.TelemetryRecorder()
    private val _trace = MutableStateFlow<List<String>>(emptyList())
    val trace: StateFlow<List<String>> = _trace.asStateFlow()
    private var traceTicker: kotlinx.coroutines.Job? = null

    fun traceFiles(dir: java.io.File): List<java.io.File> =
        dir.listFiles { f -> f.name.endsWith(TRACE_EXT) }?.sortedByDescending { it.lastModified() }.orEmpty()

    fun startTrace(dir: java.io.File, hz: Int) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                dir.mkdirs()
                // Au plus [MAX_TRACES] traces sur le téléphone : les plus anciennes partent.
                traceFiles(dir).drop(MAX_TRACES - 1).forEach { it.delete() }
                val name = "gt7-pitwall-trace-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date()) + TRACE_EXT
                recorder.start(java.io.FileOutputStream(java.io.File(dir, name)).buffered(64 * 1024), hz)
                updateRawTap()
                traceTicker?.cancel()
                traceTicker = scope.launch {
                    while (true) {
                        _trace.value = recorder.stats().lines
                        if (!recorder.running) {
                            updateRawTap()
                            break
                        }
                        kotlinx.coroutines.delay(1_000)
                    }
                }
            }.onFailure { _trace.value = listOf("Enregistrement impossible : ${it.javaClass.simpleName}") }
        }
    }

    fun stopTrace() {
        scope.launch(Dispatchers.IO) {
            recorder.stop()
            updateRawTap()
            _trace.value = recorder.stats().lines
        }
    }

    /**
     * Rejoue une trace hors course dans un contrôleur neuf (sans archive ni journal persistant) : jamais pendant que
     * GT7 envoie des données, pour ne pas disputer le processeur à la course.
     */
    fun replayTrace(file: java.io.File) {
        if (System.currentTimeMillis() - lastCarPacketAtMs < 10_000L) {
            _trace.value = listOf("Relecture refusée : GT7 envoie des données. Rejoue la trace hors course.")
            return
        }
        scope.launch(Dispatchers.Default) {
            _trace.value = listOf("Relecture de ${file.name}…")
            val lines = runCatching {
                // Revue PR #44 : relecture en flux (un échantillon en mémoire à la fois) et horloge virtuelle = heure
                // enregistrée de chaque paquet, partie de l'heure de début inscrite dans le nom du fichier.
                val start = runCatching {
                    java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT)
                        .parse(file.name.removePrefix("gt7-pitwall-trace-").removeSuffix(TRACE_EXT))!!.time
                }.getOrDefault(file.lastModified())
                val clock = com.gt7pitwall.core.telemetry.TelemetryTrace.VirtualClock(start)
                val offline = RaceController(database, clock = clock::now)
                offline.engineer.quietRace = true
                val r = file.inputStream().buffered(64 * 1024).use {
                    com.gt7pitwall.core.telemetry.TelemetryTrace.replay(it, clock) { p -> offline.onPacket(p) }
                }
                val laps = offline.snapshot.laps
                r.lines + listOf(
                    "Tours rejoués : ${laps.size}" + (laps.mapNotNull { it.timeMs }.minOrNull()?.let { ", meilleur ${com.gt7pitwall.core.engineer.FrenchFormat.lapTime(it)}" } ?: "") + ".",
                    "Fichier : ${file.length() / 1024} Ko. Rien n'a été archivé ni annoncé pendant la relecture.",
                )
            }.getOrElse { listOf("Relecture impossible : ${it.message ?: it.javaClass.simpleName}") }
            _trace.value = lines
        }
    }

    fun refreshGap() {
        _gap.value = gapHunter.summary()
    }

    fun startFormatScan() = client.startFormatScan()

    fun cancelFormatScan() = client.cancelFormatScan()

    /** Messages de fin de tour détaillés ou courts (temps, écart avec ton meilleur, position). */
    var detailedLapReports: Boolean
        get() = !race.engineer.shortLapReports
        set(value) {
            race.engineer.shortLapReports = !value
        }

    /** Demander la position à chaque tour (si la radio vocale écoute). */
    @Volatile
    var askPositionEachLap: () -> Boolean = { false }

    private var collectJob: Job? = null
    private var replanJob: Job? = null
    private var lastPublishMs = 0L
    private var lastHistoryMs = 0L
    private var lastCareerMs = 0L
    private var lastCareerArchiveCount = -1

    init {
        voiceInput?.let { input -> scope.launch { input.events.collect { onVoice(it) } } }
    }

    @Synchronized
    fun start(ps5Host: String?) {
        stop()
        collectJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.packets.collect { onPacket(it) }
        }
        client.start(scope, ps5Host)
        _running.value = true
    }

    @Synchronized
    fun stop() {
        if (recorder.running) stopTrace()
        client.stop()
        collectJob?.cancel()
        collectJob = null
        _running.value = false
        // La session en cours est archivée (interrompue, sauf si la course est finie).
        scope.launch { mutex.withLock {
            race.endSession()
            publish(force = true) // Show the completed archive even after PS5 telemetry is stopped.
        } }
    }

    /** Question du pilote via les boutons de l'écran. */
    fun ask(query: DriverQuery) = command(DriverCommand.Ask(query))

    /** Boutons de l'écran : même traitement que la voix. */
    fun command(command: DriverCommand) {
        scope.launch {
            val messages = mutex.withLock { race.command(command).also { publish(force = true) } }
            messages.forEach(::deliver)
        }
    }

    /** Règlement en cours de saisie, pas encore appliqué. */
    @Volatile
    private var pendingRules: RaceRules? = null

    /** Règlement saisi à l'écran : affiché tout de suite, appliqué (enregistré + replanifié) quand la saisie s'arrête. */
    fun setRules(rules: RaceRules) {
        _rules.value = rules
        pendingRules = rules
        replanJob?.cancel()
        replanJob = scope.launch {
            delay(RULES_DEBOUNCE_MS)
            val messages = mutex.withLock {
                pendingRules = null
                race.setRules(rules).also { publish(force = true) }
            }
            messages.forEach(::deliver)
        }
    }

    /** Une saisie en cours est appliquée à l'épreuve où elle a été faite, avant tout changement d'épreuve. */
    private fun flushRules(): List<EngineerMessage> {
        replanJob?.cancel()
        val r = pendingRules ?: return emptyList()
        pendingRules = null
        return race.setRules(r)
    }

    /** Épreuve choisie à l'écran (Sport A/B/C, qualification ou course) : son propre règlement est repris. */
    fun selectProfile(id: ProfileId) {
        scope.launch {
            val messages = mutex.withLock { (flushRules() + race.selectProfile(id)).also { publish(force = true) } }
            messages.forEach(::deliver)
        }
    }

    /** Version de GT7 et contexte de réglages / BoP de l'épreuve active. */
    fun updateProfile(profile: EventProfile) {
        scope.launch { mutex.withLock { race.updateProfile(profile).also { publish(force = true) } } }
    }

    /** Programme Sport lu (cache ou réseau) : appliqué aux épreuves A, B, C (pas en pleine course). */
    fun applySchedule(state: ScheduleState) {
        scope.launch {
            val messages = mutex.withLock { race.applySchedule(state).also { publish(force = true) } }
            messages.forEach(::deliver)
        }
    }

    /** Le pilote donne lui-même des champs du règlement (même inchangés). */
    fun declareRules(rules: RaceRules, fields: Set<RuleField>) {
        scope.launch {
            val messages = mutex.withLock { (flushRules() + race.declareRules(rules, fields)).also { publish(force = true) } }
            messages.forEach(::deliver)
        }
    }

    /** Le pilote confirme le règlement de l'épreuve active. */
    fun confirmRules() {
        val today = java.time.LocalDate.now().toString()
        scope.launch {
            val messages = mutex.withLock { flushRules().also { race.confirmRules(today); publish(force = true) } }
            messages.forEach(::deliver)
        }
    }

    private suspend fun onVoice(event: VoiceInputEvent) {
        when (event) {
            // Statuts affichés seulement, jamais dits : un bip signale l'écoute, un double bip la fin de la conversation.
            VoiceInputEvent.Wake -> deliver(EngineerMessage("🎙 Conversation ouverte", Priority.INFO, MessageCategory.DRIVER))
            VoiceInputEvent.Listening -> deliver(EngineerMessage("🎙 À l'écoute", Priority.INFO, MessageCategory.DRIVER))
            is VoiceInputEvent.ConversationClosed -> deliver(
                EngineerMessage(if (event.byDriver) "🎙 Conversation terminée" else "🎙 Conversation refermée (silence)", Priority.INFO, MessageCategory.DRIVER),
            )
            is VoiceInputEvent.Notice -> deliver(EngineerMessage(event.text, Priority.HIGH, MessageCategory.ANSWER))
            // Surveillance de l'écoute : consigné et affiché, jamais dit (aucun son enregistré).
            is VoiceInputEvent.Diagnostic -> deliver(EngineerMessage("🎙 ${event.text}", Priority.INFO, MessageCategory.DRIVER))
            is VoiceInputEvent.BargeIn -> {
                // Le pilote coupe l'ingénieur : la voix s'arrête, la réponse en préparation ne sera pas dite.
                voice.stop()
                conversation.interrupt()
                pendingLatency = null
                race.journal.record(
                    JournalType.RADIO, JournalSource.TRANSCRIPT, "Interruption du pilote : ${event.heard}",
                    details = mapOf("mode" to event.mode.label),
                )
                deliver(EngineerMessage("🎙 Interrompu : « ${event.heard} »", Priority.INFO, MessageCategory.DRIVER))
            }
            is VoiceInputEvent.Utterance -> {
                journalUtterance(event)
                val heardAt = android.os.SystemClock.elapsedRealtime()
                val trace = com.gt7pitwall.core.radio.LatencyTrace(++turnSeq).apply {
                    val (start, end) = voiceInput?.speechTiming ?: (null to null)
                    start?.takeIf { heardAt - it < 30_000 }?.let { mark(com.gt7pitwall.core.radio.LatencyTrace.Step.SPEECH_START, it) }
                    end?.takeIf { heardAt - it < 30_000 }?.let { mark(com.gt7pitwall.core.radio.LatencyTrace.Step.SPEECH_END, it) }
                    mark(com.gt7pitwall.core.radio.LatencyTrace.Step.TRANSCRIBED, heardAt)
                }
                // Chaque phrase a son propre tour : une nouvelle question n'attend pas la réponse lente de la précédente,
                // qui devient périmée (jamais dite).
                scope.launch {
                    val thinkingTone = scope.launch {
                        kotlinx.coroutines.delay(THINKING_TONE_MS)
                        if (conversation.thinking) voiceInput?.thinking()
                    }
                    try {
                        // Commandes et questions simples : analyseur (immédiat, testé). Questions ouvertes : IA. Fragments : ignorés.
                        // Phrase de la conversation continue sans « radio » devant : modifications de course à confirmer.
                        val before = conversation.lastTrace
                        val messages = conversation.onUtterance(event.text, event.freeText, languageModel, ::deliver, addressed = event.addressed)
                        val answeredAt = android.os.SystemClock.elapsedRealtime()
                        mutex.withLock { publish(force = true) }
                        val aiTrace = conversation.lastTrace?.takeIf { it !== before }
                        aiTrace?.let { _aiTrace.value = it; journalAi(it); onAiTrace(it) }
                        trace.mark(
                            if (aiTrace?.modelId != null && !aiTrace.fallback) com.gt7pitwall.core.radio.LatencyTrace.Step.AI else com.gt7pitwall.core.radio.LatencyTrace.Step.ENGINE,
                            answeredAt,
                        )
                        val spoken = messages.filter { it.category != MessageCategory.DRIVER }.toSet()
                        if (spoken.isNotEmpty()) {
                            trace.mark(com.gt7pitwall.core.radio.LatencyTrace.Step.SYNTHESIS, android.os.SystemClock.elapsedRealtime())
                            pendingLatency = trace to spoken
                        }
                        messages.forEach(::deliver)
                    } finally {
                        thinkingTone.cancel()
                    }
                }
            }
        }
    }

    /** Question libre : l'IA ne voit que les faits de la course ; sa réponse est vérifiée avant d'être dite. */
    private suspend fun askAi(ai: LanguageModel, question: String): List<EngineerMessage> {
        val before = conversation.lastTrace
        val messages = conversation.ask(ai, question, ::deliver)
        mutex.withLock { publish(force = true) }
        conversation.lastTrace?.takeIf { it !== before }?.let { _aiTrace.value = it; journalAi(it); onAiTrace(it) }
        return messages
    }

    /** Réponse du test Gemini : dite à la radio et inscrite au journal, comme une réponse de l'ingénieur. */
    fun sayAiTest(text: String) {
        val m = EngineerMessage(text, Priority.HIGH, MessageCategory.ANSWER, fromAi = true)
        _radio.update { (listOf(m) + it).take(MAX_RADIO_LOG) }
        voice.speak(m)
    }

    /** Question posée à Gemini depuis l'écran (sans micro). */
    fun askAiFromScreen(question: String) {
        race.journal.record(JournalType.DRIVER, JournalSource.SCREEN, question, details = mapOf("canal" to "question tapée à l'écran"))
        val lm = languageModel
        scope.launch {
            val messages = if (conversation.cloud?.available(complex = true) == true || conversation.cloud?.available() == true) {
                // Même chemin que la radio : modèle cloud avec le calcul du moteur et la mémoire, replis compris.
                val before = conversation.lastTrace
                conversation.onUtterance(question, question, lm, ::deliver).also {
                    mutex.withLock { publish(force = true) }
                    conversation.lastTrace?.takeIf { t -> t !== before }?.let { t -> _aiTrace.value = t; journalAi(t); onAiTrace(t) }
                }
            } else if (lm != null && lm.ready) {
                askAi(lm, question)
            } else if (IntentClassifier.classify(question) in IntentClassifier.LOCAL || DriverCommandParser.parse(question) != null) {
                // Sans Gemini, l'ingénieur répond quand même aux messages simples et aux questions qu'il calcule.
                conversation.onUtterance(question, question, lm, ::deliver).also { mutex.withLock { publish(force = true) } }
            } else {
                listOf(EngineerMessage("Gemini n'est pas prêt : ${lm?.status ?: "désactivé"}", Priority.HIGH, MessageCategory.ANSWER))
            }
            messages.forEach(::deliver)
        }
    }

    fun testVoice() {
        voice.speak(EngineerMessage("Radio check. Ici ton ingénieur, je te reçois cinq sur cinq.", Priority.HIGH, MessageCategory.RADIO))
    }

    /** Mesures préalables à la Black Box (Atelier) : rien n'est enregistré, seulement mesuré. */
    val blackBox = com.gt7pitwall.core.telemetry.BlackBoxProbe()
    private val _blackBoxReport = MutableStateFlow(listOf("Mesure arrêtée."))
    val blackBoxReport: StateFlow<List<String>> = _blackBoxReport.asStateFlow()
    private var lastBlackBoxReportMs = 0L
    private var lastCornerMs = 0L
    private var lastBlackBoxJournalMs = 0L

    fun setBlackBoxMeasure(enabled: Boolean) {
        if (enabled) {
            blackBoxRuns.clear()
            blackBox.start()
        } else {
            blackBox.stop()
        }
        _blackBoxReport.value = if (enabled) listOf("Mesure en cours : lance une course.") else blackBox.report()
        if (!enabled) journalBlackBox("Mesure Black Box terminée")
    }

    /** Courses du journal vues pendant la mesure, dans l'ordre : le bilan appartient à la dernière, même close. */
    private val blackBoxRuns = com.gt7pitwall.core.telemetry.BlackBoxRuns()

    /**
     * Compte rendu de la mesure dans le journal de la course mesurée (exportable) : preuve réelle, pas une simulation.
     * Mesure arrêtée depuis le menu après l'arrivée : rattaché à cette course, jamais à la suivante. Aucune course vue
     * pendant la mesure : rien n'est écrit (rien n'a été mesuré en course).
     */
    private fun journalBlackBox(title: String) {
        val target = blackBoxRuns.last() ?: return
        val lines = blackBox.report()
        runCatching {
            race.journal.recordFor(
                target, com.gt7pitwall.core.race.JournalType.DECISION, com.gt7pitwall.core.race.JournalSource.SYSTEM, title,
                decision = "mesure seulement, rien n'est enregistré",
                details = lines.mapIndexed { i, l -> "mesure_${i + 1}" to l }.toMap() +
                    mapOf(
                        "appareil" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                        "android" to android.os.Build.VERSION.RELEASE,
                        "courses_mesurees" to blackBoxRuns.all().joinToString(","),
                    ),
            )
        }
    }

    private suspend fun onPacket(p: TelemetryPacket) {
        val now = System.currentTimeMillis()
        if (p.carCode > 0) lastCarPacketAtMs = now
        runCatching {
            positionProbe.onPacket(p, now)
            if (now - lastProbeMs >= PROBE_REFRESH_MS) {
                lastProbeMs = now
                refreshProbe()
            }
            if (gapHunter.enabled && now - lastGapMs >= GAP_REFRESH_MS) {
                lastGapMs = now
                refreshGap()
            }
        }
        mutex.withLock {
            race.engineer.askPositionEachLap = askPositionEachLap()
            val t0 = System.nanoTime()
            val messages = race.onPacket(p)
            if (blackBox.running) {
                blackBox.onPacket(p, System.nanoTime() - t0)
                blackBoxRuns.seen(race.journal.sessionId)
                if (now - lastBlackBoxReportMs >= 5_000L) {
                    lastBlackBoxReportMs = now
                    _blackBoxReport.value = blackBox.report()
                }
                // Point de mesure au journal toutes les 10 minutes : chaque course mesurée garde ses chiffres.
                if (now - lastBlackBoxJournalMs >= 600_000L) {
                    if (lastBlackBoxJournalMs > 0) journalBlackBox("Mesure Black Box (point d'étape)")
                    lastBlackBoxJournalMs = now
                }
            }
            publish(force = messages.isNotEmpty())
            messages.forEach(::deliver)
        }
    }

    /** ~20 rafraîchissements d'écran par seconde suffisent (GT7 envoie ~60 paquets/s). */
    private fun publish(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPublishMs < UI_REFRESH_MS) return
        lastPublishMs = now
        val snap = race.snapshot
        // Voiture absente de la base (nouvelle voiture du jeu) : la base est relue sans attendre.
        snap.packet?.carCode?.takeIf { it > 0 && race.carName == null }?.let { onUnknownCar() }
        _rules.value = race.rules
        val currentCarCode = snap.packet?.carCode?.takeIf { it > 0 }
        val editionId = race.edition?.id
        val profileId = race.profile.id
        val trackId = race.rules.trackId
        val garageChanged = currentCarCode != lastGarageCode ||
            editionId != lastGarageEditionId || profileId != lastGarageProfile || trackId != lastGarageTrack
        val garage = if (force || garageChanged || now - lastGarageComputeMs >= 2_000L) {
            lastGarageComputeMs = now
            lastGarageCode = currentCarCode
            lastGarageEditionId = editionId
            lastGarageProfile = profileId
            lastGarageTrack = trackId
            DriverGarage.resolve(
                telemetryCarCode = currentCarCode,
                // A valid car packet may already arrive in the lobby/loading screen,
                // before the on-track flag required by the driving coach becomes true.
                telemetryConnected = currentCarCode != null && now - lastCarPacketAtMs <= 2_000L,
                profile = race.profile,
                trackId = trackId,
                edition = race.edition,
                sessions = race.archivedSessions,
                db = database,
            )
        } else _state.value.garageCar
        // Virages : recalculés au plus toutes les 2 s (passages ajoutés à la fin de chaque tour).
        val cornersDue = force || now - lastCornerMs >= 2_000L
        val tyreOrDriving = if (cornersDue) race.tyreOrDriving().text else _state.value.tyreOrDriving
        val corners = if (cornersDue) {
            lastCornerMs = now
            val labels = race.cornerLabels()
            Triple(
                race.cornerMarks(),
                race.cornerReport().map { it.text },
                when {
                    labels.isEmpty() -> ""
                    labels.values.all { it.community } -> race.course?.let { c -> database.numberedCorners[c.id] }
                        ?.let { if (it.matchesOfficial) "Numérotation du circuit (source communautaire CC0)." else "Numérotation communautaire, différente du nombre officiel de virages : à vérifier." }
                        ?: ""
                    labels.values.any { it.community } -> "T : numérotation du circuit (source communautaire CC0) ; V : virage détecté, numéro estimé."
                    else -> "Numéros estimés (virages détectés par Pitwall) : pas de relevé pour ce circuit."
                },
            )
        } else {
            Triple(_state.value.cornerMarks, _state.value.cornerReport, _state.value.cornerNumbering)
        }
        val archiveCount = race.archivedCount
        val career = if (force || archiveCount != lastCareerArchiveCount ||
            now - lastCareerMs >= 2_500L) {
            lastCareerMs = now
            lastCareerArchiveCount = archiveCount
            PitwallLocalCareer.fromSessions(race.archivedSessions)
        } else _state.value.localCareer
        _state.value = PitwallUiState(
            snapshot = snap,
            fuel = race.fuel,
            stats = LapAnalyzer.analyze(snap.laps),
            driverPosition = race.engineer.currentPosition,
            positionMeasured = race.engineer.positionMeasured,
            positionCars = race.engineer.measured.let { it.finalCars ?: it.cars },
            quiet = race.engineer.quiet,
            radioLevel = race.engineer.radioLevel,
            gForce = race.gForce,
            raining = race.engineer.raining,
            carName = race.carName,
            garageCar = garage,
            course = race.course,
            trackCandidates = race.trackCandidates,
            plan = race.plan,
            memory = race.memoryRecord,
            stops = race.ledger.stopList,
            pendingStop = race.ledger.pending,
            tyresUsed = race.tyresUsed,
            missingTyres = race.missingTyres,
            learning = runCatching { race.learningReport() }.getOrElse { listOf("Diagnostic indisponible : ${it.javaClass.simpleName}") },
            drivingReport = race.lastDrivingReport?.lines.orEmpty(),
            preRaceBrief = if (force || snap.packet?.currentLap == 0) race.victoryBriefing() else _state.value.preRaceBrief,
            sessionIdentity = race.sessionIdentityText,
            raceFinished = race.raceFinished,
            cornerMarks = corners.first,
            cornerReport = corners.second,
            cornerNumbering = corners.third,
            tyreOrDriving = tyreOrDriving,
            tyreAgeLaps = race.ledger.tyreAge((race.snapshot.packet?.currentLap ?: 1).coerceAtLeast(1)),
            askTyreChange = race.ledger.awaitingTyreChangeAnswer,
            profile = race.profile,
            history = if (force || now - lastHistoryMs > HISTORY_REFRESH_MS) race.history.also { lastHistoryMs = now } else _state.value.history,
            archivedSessions = archiveCount,
            localCareer = career,
            edition = race.edition,
            ruleGaps = race.ruleGaps,
            schedule = race.schedule,
            strategy = race.strategy,
            grip = race.engineer.grip,
            tyreStint = race.ledger.tyreStint((snap.packet?.currentLap ?: 1).coerceAtLeast(1), race.rules.startTyre),
            tyreModel = race.tyreModel,
            tyreScenario = race.tyreScenarios().reason,
            lastThermal = race.ledger.stopList.lastOrNull()?.thermal?.let { v -> com.gt7pitwall.core.tyres.TyreChangeVerdict.entries.firstOrNull { it.name == v } },
            brief = if (force) race.strategyBrief else _state.value.brief ?: race.strategyBrief,
        )
    }

    private fun deliver(message: EngineerMessage) {
        journalMessage(message)
        _radio.update { (listOf(message) + it).take(MAX_RADIO_LOG) }
        // Les paroles du pilote sont seulement affichées.
        if (voiceEnabled && message.category != MessageCategory.DRIVER &&
            !com.gt7pitwall.core.radio.RadioPacer.dropBeforeSpeech(message, voiceBusy())
        ) {
            voice.speak(message)
        }
        if (message.expectsReply) voiceInput?.expectReply()
    }

    // ------------------------------------------------------------------ Journal de course

    /** Phrase du pilote : seule sa transcription est connue (aucun son n'est enregistré). */
    private fun journalUtterance(event: VoiceInputEvent.Utterance) {
        val free = event.freeText?.takeIf { it.isNotBlank() }
        race.journal.record(
            JournalType.DRIVER, JournalSource.TRANSCRIPT, free ?: event.text,
            details = listOfNotNull(
                "transcription_vocabulaire_radio" to event.text,
                free?.let { "transcription_libre" to it },
                "reconnaissance" to event.recognizer,
                "adressee" to if (event.addressed) "oui (après « radio » ou réponse attendue)" else "non (conversation continue, modifications à confirmer)",
                "phrase_prononcee" to "inconnue : aucun son enregistré",
            ).toMap(),
        )
    }

    private var lastJournaledTrace: AiTrace? = null

    /** Appel à Gemini : question, réponse brute, verdict du contrôle et délai. */
    private fun journalAi(trace: AiTrace) {
        if (trace === lastJournaledTrace) return
        lastJournaledTrace = trace
        val who = trace.modelId ?: trace.backend.label
        race.journal.record(
            JournalType.DECISION,
            if (trace.fallback) JournalSource.ENGINE else JournalSource.GEMINI,
            if (trace.fallback) "IA non utilisée, réponse du moteur local : « ${trace.question} »" else "IA interrogée ($who) : « ${trace.question} »",
            decision = trace.verdict,
            details = listOfNotNull(
                "modele" to who,
                "source" to when {
                    trace.fallback -> "moteur local (repli)"
                    trace.backend.cloud -> "cloud (Firebase AI Logic)"
                    else -> "téléphone (Gemini Nano)"
                },
                "delai_ms" to trace.latencyMs.toString(),
                trace.attempts.takeIf { it.isNotEmpty() }?.let { a ->
                    "essais" to a.joinToString(" ; ") { "${it.modelId} : ${it.failure?.kind?.label ?: "réponse"} en ${it.latencyMs} ms" }
                },
                trace.raw?.let { "reponse_brute" to it },
            ).toMap(),
        )
    }

    /** Message de la radio : dit ou affiché, calculé par le moteur ou rédigé par Gemini. L'écho « Toi : » est ignoré. */
    private fun journalMessage(m: EngineerMessage) {
        if (m.category == MessageCategory.DRIVER && m.text.startsWith("Toi :")) return
        val type = when {
            m.category == MessageCategory.DRIVER -> JournalType.RADIO
            m.category == MessageCategory.ALERT || m.priority == Priority.CRITICAL -> JournalType.ALERT
            else -> JournalType.ENGINEER
        }
        val source = when {
            m.fromAi -> JournalSource.GEMINI
            m.category == MessageCategory.DRIVER -> JournalSource.SYSTEM
            else -> JournalSource.ENGINE
        }
        race.journal.record(
            type, source, m.text,
            details = listOfNotNull(
                "priorite" to m.priority.name.lowercase(),
                "categorie" to m.category.name.lowercase(),
                "dit_a_la_radio" to if (voiceEnabled && m.category != MessageCategory.DRIVER) "oui" else "non (affiché)",
                if (m.expectsReply) "attend_une_reponse" to "oui" else null,
            ).toMap(),
        )
    }

    /** Sessions du journal (archive + radio), la plus récente d'abord. */
    suspend fun historySessions(): List<JournalSessionInfo> = withContext(Dispatchers.IO) {
        storage?.let { runCatching { SessionJournal.sessions(it) }.getOrNull() } ?: emptyList()
    }

    suspend fun historyEvents(id: String): List<JournalEvent> = withContext(Dispatchers.IO) {
        storage?.let { runCatching { SessionJournal.load(it, id) }.getOrNull() } ?: emptyList()
    }

    /** Identifiant de la session en cours d'enregistrement, null hors course. */
    val liveSessionId: String? get() = race.journal.sessionId

    suspend fun exportJson(id: String): String = withContext(Dispatchers.IO) {
        val s = storage ?: return@withContext "{}"
        JournalExport.json(id, s.loadSessions().firstOrNull { it.id == id }, SessionJournal.load(s, id), System.currentTimeMillis())
    }

    suspend fun exportText(id: String): String = withContext(Dispatchers.IO) {
        val s = storage ?: return@withContext ""
        JournalExport.text(id, s.loadSessions().firstOrNull { it.id == id }, SessionJournal.load(s, id))
    }

    private companion object {
        const val TRACE_EXT = ".gt7trace"
        const val MAX_TRACES = 5
        /** Au-delà, un petit signal discret dit que la réponse arrive (jamais une phrase « je réfléchis »). */
        const val THINKING_TONE_MS = 1_500L
        const val UI_REFRESH_MS = 50L
        const val PROBE_REFRESH_MS = 500L
        const val GAP_REFRESH_MS = 1_000L
        const val MAX_RADIO_LOG = 50
        const val RULES_DEBOUNCE_MS = 1_500L
        const val HISTORY_REFRESH_MS = 2_000L
    }
}
