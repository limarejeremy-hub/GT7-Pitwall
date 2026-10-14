package com.gt7pitwall.app.engine

import com.gt7pitwall.core.ai.AiReply
import com.gt7pitwall.core.ai.LanguageModel
import com.gt7pitwall.core.ai.RadioAi
import com.gt7pitwall.core.analysis.GForce
import com.gt7pitwall.core.analysis.LapAnalyzer
import com.gt7pitwall.core.analysis.LapStats
import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.engineer.Priority
import com.gt7pitwall.core.engineer.RadioLevel
import com.gt7pitwall.core.knowledge.CarTrackRecord
import com.gt7pitwall.core.knowledge.Course
import com.gt7pitwall.core.knowledge.Gt7Database
import com.gt7pitwall.core.race.RaceController
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withLock

data class PitwallUiState(
    val snapshot: SessionSnapshot = SessionSnapshot.EMPTY,
    val fuel: FuelReport? = null,
    val stats: LapStats = LapAnalyzer.analyze(emptyList()),
    /** Position annoncée (grille GT7 puis déclarations du pilote). */
    val driverPosition: Int? = null,
    val quiet: Boolean = false,
    val radioLevel: RadioLevel = RadioLevel.FULL,
    val gForce: GForce? = null,
    /** Pluie signalée par le pilote (GT7 ne transmet pas la météo). */
    val raining: Boolean = false,
    /** Nom de la voiture d'après le code GT7 (base communautaire). */
    val carName: String? = null,
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
    /** Tours bouclés avec les pneus actuels (d'après les changements déclarés par le pilote). */
    val tyreAgeLaps: Int = 0,
    /** L'ingénieur attend « oui / non » à « As-tu changé les pneus ? ». */
    val askTyreChange: Boolean = false,
)

/** Dernière question envoyée à l'IA, sa réponse brute et ce qu'on en a fait. */
data class AiTrace(val question: String, val raw: String?, val verdict: String, val latencyMs: Long)

/**
 * Chaîne Android : réception UDP, sérialisation et voix. Toute la logique de course est dans
 * [RaceController] (module core, testé) : boutons, voix et écran passent par le même chemin.
 */
class PitwallEngine(
    private val voice: VoiceOutput,
    private val voiceInput: VoiceInput? = null,
    val database: Gt7Database = Gt7Database.EMPTY,
    storage: RaceStorage? = null,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = TelemetryClient()
    private val race = RaceController(database, storage)
    private val mutex = Mutex()

    private val _rules = MutableStateFlow(race.rules)
    val rules: StateFlow<RaceRules> = _rules.asStateFlow()

    val connection: StateFlow<ConnectionStatus> = client.status

    private val _state = MutableStateFlow(PitwallUiState())
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

    private val _aiTrace = MutableStateFlow<AiTrace?>(null)
    /** Dernier échange avec l'IA (diagnostic affiché dans la carte « Ingénieur IA »). */
    val aiTrace: StateFlow<AiTrace?> = _aiTrace.asStateFlow()

    /** Demander la position à chaque tour (si la radio vocale écoute). */
    @Volatile
    var askPositionEachLap: () -> Boolean = { false }

    private var collectJob: Job? = null
    private var replanJob: Job? = null
    private var lastPublishMs = 0L

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
        client.stop()
        collectJob?.cancel()
        collectJob = null
        _running.value = false
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

    /** Règlement saisi à l'écran : affiché tout de suite, appliqué (enregistré + replanifié) quand la saisie s'arrête. */
    fun setRules(rules: RaceRules) {
        _rules.value = rules
        replanJob?.cancel()
        replanJob = scope.launch {
            delay(RULES_DEBOUNCE_MS)
            val messages = mutex.withLock { race.setRules(rules).also { publish(force = true) } }
            messages.forEach(::deliver)
        }
    }

    private suspend fun onVoice(event: VoiceInputEvent) {
        when (event) {
            VoiceInputEvent.Wake -> deliver(EngineerMessage("Je t'écoute.", Priority.HIGH, MessageCategory.ANSWER))
            is VoiceInputEvent.Utterance -> {
                val lm = languageModel
                // Commandes et questions simples : analyseur à mots-clés (immédiat, testé). Questions ouvertes : IA.
                val route = RadioAi.route(event.text, event.freeText)
                val messages = when {
                    route is RadioAi.Route.Ai && lm != null && lm.ready -> askAi(lm, route.question)
                    route is RadioAi.Route.Ai && lm != null && DriverCommandParser.parse(event.text) == null -> listOf(
                        EngineerMessage("Toi : ${route.question}", Priority.INFO, MessageCategory.DRIVER),
                        EngineerMessage("Gemini n'est pas prêt : ${lm.status}", Priority.HIGH, MessageCategory.ANSWER),
                    )
                    else -> mutex.withLock { race.utterance(event.text).also { publish(force = true) } }
                }
                messages.forEach(::deliver)
            }
        }
    }

    /** Question libre : l'IA ne voit que les faits de la course ; sa réponse est vérifiée avant d'être dite. */
    private suspend fun askAi(ai: LanguageModel, question: String): List<EngineerMessage> {
        deliver(EngineerMessage("Gemini réfléchit : « $question »", Priority.INFO, MessageCategory.DRIVER))
        val facts = mutex.withLock { race.aiFacts() }
        val started = System.currentTimeMillis()
        val raw = withTimeoutOrNull(AI_TIMEOUT_MS) { ai.generate(RadioAi.systemInstruction, RadioAi.prompt(question, facts)) }
        val reply = RadioAi.parse(raw, question, facts)
        _aiTrace.value = AiTrace(
            question = question,
            raw = raw,
            verdict = when (reply) {
                is AiReply.Answer -> "réponse dite"
                is AiReply.Command -> "commande : ${reply.phrase}"
                is AiReply.Rejected -> when (reply.reason) {
                    AiReply.Reason.UNKNOWN -> "Gemini ne sait pas"
                    AiReply.Reason.INVENTED_NUMBER -> "refusée : chiffre absent des données"
                    AiReply.Reason.NOT_TRANSMITTED -> "refusée : parle des autres voitures"
                    AiReply.Reason.BAD_COMMAND -> "refusée : commande inconnue"
                    AiReply.Reason.EMPTY -> if (raw == null) "pas de réponse (délai ou erreur)" else "réponse vide"
                }
            },
            latencyMs = System.currentTimeMillis() - started,
        )
        return mutex.withLock { race.aiReply(question, reply).also { publish(force = true) } }
    }

    fun testVoice() {
        voice.speak(EngineerMessage("Radio check. Ici ton ingénieur, je te reçois cinq sur cinq.", Priority.HIGH, MessageCategory.RADIO))
    }

    private suspend fun onPacket(p: TelemetryPacket) {
        mutex.withLock {
            race.engineer.askPositionEachLap = askPositionEachLap()
            val messages = race.onPacket(p)
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
        _rules.value = race.rules
        _state.value = PitwallUiState(
            snapshot = snap,
            fuel = race.fuel,
            stats = LapAnalyzer.analyze(snap.laps),
            driverPosition = race.engineer.driverPosition,
            quiet = race.engineer.quiet,
            radioLevel = race.engineer.radioLevel,
            gForce = race.gForce,
            raining = race.engineer.raining,
            carName = race.carName,
            course = race.course,
            trackCandidates = race.trackCandidates,
            plan = race.plan,
            memory = race.memoryRecord,
            stops = race.ledger.stopList,
            pendingStop = race.ledger.pending,
            tyresUsed = race.tyresUsed,
            missingTyres = race.missingTyres,
            tyreAgeLaps = race.ledger.tyreAge((race.snapshot.packet?.currentLap ?: 1).coerceAtLeast(1)),
            askTyreChange = race.ledger.awaitingTyreChangeAnswer,
        )
    }

    private fun deliver(message: EngineerMessage) {
        _radio.update { (listOf(message) + it).take(MAX_RADIO_LOG) }
        // Les paroles du pilote sont seulement affichées.
        if (voiceEnabled && message.category != MessageCategory.DRIVER) voice.speak(message)
        if (message.expectsReply) voiceInput?.expectReply()
    }

    private companion object {
        const val UI_REFRESH_MS = 50L
        const val AI_TIMEOUT_MS = 8_000L
        const val MAX_RADIO_LOG = 50
        const val RULES_DEBOUNCE_MS = 1_500L
    }
}
