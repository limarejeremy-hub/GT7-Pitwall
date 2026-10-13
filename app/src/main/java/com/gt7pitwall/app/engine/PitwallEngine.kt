package com.gt7pitwall.app.engine

import com.gt7pitwall.core.analysis.GForce
import com.gt7pitwall.core.analysis.GForceMeter
import com.gt7pitwall.core.analysis.LapAnalyzer
import com.gt7pitwall.core.analysis.LapStats
import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.engineer.Priority
import com.gt7pitwall.core.engineer.RaceEngineer
import com.gt7pitwall.core.knowledge.CarTrackMemory
import com.gt7pitwall.core.knowledge.CarTrackRecord
import com.gt7pitwall.core.knowledge.Course
import com.gt7pitwall.core.knowledge.Gt7Database
import com.gt7pitwall.core.radio.DriverCommand
import com.gt7pitwall.core.radio.DriverCommandParser
import com.gt7pitwall.core.radio.DriverQuery
import com.gt7pitwall.core.radio.VoiceInput
import com.gt7pitwall.core.radio.VoiceInputEvent
import com.gt7pitwall.core.radio.VoiceOutput
import com.gt7pitwall.core.session.SessionSnapshot
import com.gt7pitwall.core.session.SessionTracker
import com.gt7pitwall.core.strategy.FuelReport
import com.gt7pitwall.core.session.SessionEvent
import com.gt7pitwall.core.strategy.FuelStrategy
import com.gt7pitwall.core.strategy.RacePlan
import com.gt7pitwall.core.strategy.RacePlanner
import com.gt7pitwall.core.strategy.RaceRules
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
import kotlinx.coroutines.sync.withLock

data class PitwallUiState(
    val snapshot: SessionSnapshot = SessionSnapshot.EMPTY,
    val fuel: FuelReport? = null,
    val stats: LapStats = LapAnalyzer.analyze(emptyList()),
    /** Position annoncée (grille GT7 puis déclarations du pilote). */
    val driverPosition: Int? = null,
    val quiet: Boolean = false,
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
)

/** Stockage du règlement et de la mémoire voiture × circuit (implémenté côté Android). */
interface RaceStorage {
    fun loadRules(): RaceRules
    fun saveRules(r: RaceRules)
    fun loadMemory(): Map<String, CarTrackRecord>
    fun saveMemory(records: Collection<CarTrackRecord>)
}

/**
 * Chaîne complète : réception UDP -> suivi de session -> stratégie carburant -> ingénieur -> voix.
 * Indépendant de l'interface : le service de premier plan le maintient en vie écran éteint.
 */
class PitwallEngine(
    private val voice: VoiceOutput,
    private val voiceInput: VoiceInput? = null,
    val database: Gt7Database = Gt7Database.EMPTY,
    private val storage: RaceStorage? = null,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = TelemetryClient()
    private val tracker = SessionTracker()

    private val _rules = MutableStateFlow(storage?.loadRules() ?: RaceRules())
    val rules: StateFlow<RaceRules> = _rules.asStateFlow()
    private val memory = (storage?.loadMemory() ?: emptyMap()).toMutableMap()
    private var plan: RacePlan? = null
    private var trackCandidates: List<Course> = emptyList()
    private var candidatesAnnouncedLap = -1
    private val gMeter = GForceMeter()
    private var lastG: GForce? = null
    private val engineer = RaceEngineer()
    private val mutex = Mutex()

    val connection: StateFlow<ConnectionStatus> = client.status

    private val _state = MutableStateFlow(PitwallUiState())
    val state: StateFlow<PitwallUiState> = _state.asStateFlow()

    private val _radio = MutableStateFlow<List<EngineerMessage>>(emptyList())
    val radio: StateFlow<List<EngineerMessage>> = _radio.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    @Volatile
    var voiceEnabled: Boolean = true

    /** Demander la position à chaque tour (si la radio vocale écoute). */
    @Volatile
    var askPositionEachLap: () -> Boolean = { false }

    private var collectJob: Job? = null
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

    fun command(command: DriverCommand) {
        scope.launch {
            val messages = mutex.withLock { handleLocked(command) }
            messages.forEach(::deliver)
        }
    }

    private var replanJob: Job? = null

    /** Règlement saisi à l'écran : appliqué tout de suite, plan recalculé quand la saisie s'arrête. */
    fun setRules(rules: RaceRules) {
        _rules.value = rules
        replanJob?.cancel()
        replanJob = scope.launch {
            delay(RULES_DEBOUNCE_MS)
            val messages = mutex.withLock {
                applyRules(rules)
                listOfNotNull(replan())
            }
            messages.forEach(::deliver)
        }
    }

    private fun applyRules(rules: RaceRules) {
        if (rules.trackId != _rules.value.trackId) trackCandidates = emptyList()
        _rules.value = rules
        storage?.saveRules(rules)
        publishDriverState()
    }

    private fun handleLocked(command: DriverCommand): List<EngineerMessage> {
        val r = _rules.value
        when (command) {
            is DriverCommand.SetLaps -> applyRules(r.copy(totalLaps = command.laps, durationMin = null))
            is DriverCommand.SetDuration -> applyRules(r.copy(durationMin = command.minutes, totalLaps = null))
            is DriverCommand.SetMandatoryStops -> applyRules(r.copy(mandatoryStops = command.stops))
            is DriverCommand.SetFuelMultiplier -> applyRules(r.copy(fuelMultiplier = command.factor.coerceAtLeast(1)))
            is DriverCommand.SetTyreMultiplier -> applyRules(r.copy(tyreMultiplier = command.factor.coerceAtLeast(1)))
            else -> Unit
        }
        val snap = tracker.snapshot
        val reply = engineer.handle(command, snap, FuelStrategy.compute(snap, plan?.totalLaps))
        publishDriverState()
        return listOfNotNull(reply, if (command is DriverCommand.Rule) replan() else null)
    }

    /**
     * Recalcule le plan de course : consommation mesurée en direct, sinon mémoire voiture × circuit
     * (ramenée au multiplicateur du règlement) ; rythme réel ou mémorisé.
     */
    private fun replan(): EngineerMessage? {
        val snap = tracker.snapshot
        val p = snap.packet ?: return null
        val rules = _rules.value
        val rec = rules.trackId?.let { memory[CarTrackRecord.key(p.carCode, it)] }
        val livePerLap = FuelStrategy.compute(snap)?.perLap
        val perLap = livePerLap ?: rec?.fuelPerLapX1?.times(rules.fuelMultiplier)
        val avg = LapAnalyzer.analyze(snap.laps).recentAverageMs
            ?: snap.laps.lastOrNull { (it.timeMs ?: 0) > 0 && !it.refuelled }?.timeMs
            ?: rec?.typicalLapMs
        val course = rules.trackId?.let(database::course)
        plan = if (p.fuelCapacity > 0f || rules.mandatoryStops > 0) {
            RacePlanner.plan(
                rules, p.totalLaps, p.currentLap, p.fuelLevel, p.fuelCapacity, perLap, avg,
                snap.raceElapsedMs, snap.stops, course?.pitLaneDeltaS,
            )
        } else {
            null
        }
        engineer.mandatoryStops = rules.mandatoryStops
        return engineer.onPlan(plan)
    }

    /** Mémoire voiture × circuit et reconnaissance du circuit d'après la distance du tour. */
    private fun learn(events: List<SessionEvent>): List<EngineerMessage> {
        val out = mutableListOf<EngineerMessage>()
        val p = tracker.snapshot.packet ?: return out
        for (e in events) {
            if (e !is SessionEvent.LapCompleted) continue
            val trackId = _rules.value.trackId
            if (trackId != null) {
                val key = CarTrackRecord.key(p.carCode, trackId)
                memory[key] = CarTrackMemory.update(memory[key], p.carCode, trackId, listOf(e.lap), _rules.value.fuelMultiplier)
                storage?.saveMemory(memory.values.toList())
            } else {
                val dist = e.lap.distanceM ?: continue
                val found = database.coursesMatching(dist)
                val names = found.map { it.name }.distinct()
                when {
                    found.size == 1 -> {
                        applyRules(_rules.value.copy(trackId = found[0].id))
                        out += EngineerMessage("Circuit reconnu : ${found[0].label}.", Priority.NORMAL, MessageCategory.RADIO)
                    }
                    found.isNotEmpty() -> {
                        trackCandidates = found
                        if (candidatesAnnouncedLap < 0) {
                            candidatesAnnouncedLap = e.lap.lapNumber
                            out += EngineerMessage(
                                "Circuit probable : ${names.take(3).joinToString(" ou ")}. Choisis-le dans la carte Course.",
                                Priority.NORMAL,
                                MessageCategory.RADIO,
                            )
                        }
                    }
                }
            }
        }
        return out
    }

    private suspend fun onVoice(event: VoiceInputEvent) {
        when (event) {
            VoiceInputEvent.Wake -> deliver(EngineerMessage("Je t'écoute.", Priority.HIGH, MessageCategory.ANSWER))
            is VoiceInputEvent.Utterance -> {
                deliver(EngineerMessage("Toi : ${event.text}", Priority.INFO, MessageCategory.DRIVER))
                val message = mutex.withLock {
                    val snap = tracker.snapshot
                    val cmd = DriverCommandParser.parse(event.text)
                    (if (cmd == null) engineer.notUnderstood(event.text) else engineer.handle(cmd, snap, FuelStrategy.compute(snap)))
                        .also { publishDriverState() }
                }
                deliver(message)
            }
        }
    }

    private fun publishDriverState() {
        _state.update {
            it.copy(
                driverPosition = engineer.driverPosition,
                quiet = engineer.quiet,
                raining = engineer.raining,
                course = _rules.value.trackId?.let(database::course),
                trackCandidates = trackCandidates,
                plan = plan,
            )
        }
    }

    fun testVoice() {
        voice.speak(
            EngineerMessage(
                "Radio check. Ici ton ingénieur, je te reçois cinq sur cinq.",
                Priority.HIGH,
                MessageCategory.RADIO,
            ),
        )
    }

    private suspend fun onPacket(p: TelemetryPacket) {
        mutex.withLock {
            val events = tracker.update(p)
            engineer.askPositionEachLap = askPositionEachLap()
            gMeter.update(p)?.let { lastG = it }
            val snap = tracker.snapshot
            val extra = mutableListOf<EngineerMessage>()
            if (events.isNotEmpty()) {
                if (events.any { it is SessionEvent.SessionReset }) {
                    trackCandidates = emptyList()
                    candidatesAnnouncedLap = -1
                }
                extra += learn(events)
                replan()?.let { extra += it }
            }
            val fuel = FuelStrategy.compute(snap, plan?.totalLaps)
            val messages = engineer.onUpdate(events, snap, fuel) + extra
            val now = System.currentTimeMillis()
            // ~20 rafraîchissements d'écran par seconde suffisent (GT7 envoie ~60 paquets/s).
            if (events.isNotEmpty() || now - lastPublishMs >= UI_REFRESH_MS) {
                _state.value = PitwallUiState(
                    snap,
                    fuel,
                    LapAnalyzer.analyze(snap.laps),
                    engineer.driverPosition,
                    engineer.quiet,
                    lastG,
                    engineer.raining,
                    database.car(p.carCode)?.fullName,
                    _rules.value.trackId?.let(database::course),
                    trackCandidates,
                    plan,
                    _rules.value.trackId?.let { memory[CarTrackRecord.key(p.carCode, it)] },
                )
                lastPublishMs = now
            }
            messages.forEach(::deliver)
        }
    }

    private fun deliver(message: EngineerMessage) {
        _radio.update { (listOf(message) + it).take(MAX_RADIO_LOG) }
        // Les paroles du pilote sont seulement affichées.
        if (voiceEnabled && message.category != MessageCategory.DRIVER) voice.speak(message)
        if (message.expectsReply) voiceInput?.expectReply()
    }

    private companion object {
        const val UI_REFRESH_MS = 50L
        const val MAX_RADIO_LOG = 50
        const val RULES_DEBOUNCE_MS = 1_500L
    }
}
