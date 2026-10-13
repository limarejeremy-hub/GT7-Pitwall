package com.gt7pitwall.app.engine

import com.gt7pitwall.core.analysis.GForce
import com.gt7pitwall.core.analysis.GForceMeter
import com.gt7pitwall.core.analysis.LapAnalyzer
import com.gt7pitwall.core.analysis.LapStats
import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.engineer.Priority
import com.gt7pitwall.core.engineer.RaceEngineer
import com.gt7pitwall.core.radio.DriverCommand
import com.gt7pitwall.core.radio.DriverCommandParser
import com.gt7pitwall.core.radio.DriverQuery
import com.gt7pitwall.core.radio.VoiceInput
import com.gt7pitwall.core.radio.VoiceInputEvent
import com.gt7pitwall.core.radio.VoiceOutput
import com.gt7pitwall.core.session.SessionSnapshot
import com.gt7pitwall.core.session.SessionTracker
import com.gt7pitwall.core.strategy.FuelReport
import com.gt7pitwall.core.strategy.FuelStrategy
import com.gt7pitwall.core.telemetry.ConnectionStatus
import com.gt7pitwall.core.telemetry.TelemetryClient
import com.gt7pitwall.core.telemetry.TelemetryPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
)

/**
 * Chaîne complète : réception UDP -> suivi de session -> stratégie carburant -> ingénieur -> voix.
 * Indépendant de l'interface : le service de premier plan le maintient en vie écran éteint.
 */
class PitwallEngine(private val voice: VoiceOutput, private val voiceInput: VoiceInput? = null) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = TelemetryClient()
    private val tracker = SessionTracker()
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
            val message = mutex.withLock {
                val snap = tracker.snapshot
                engineer.handle(command, snap, FuelStrategy.compute(snap)).also { publishDriverState() }
            }
            deliver(message)
        }
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
        _state.update { it.copy(driverPosition = engineer.driverPosition, quiet = engineer.quiet, raining = engineer.raining) }
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
            val fuel = FuelStrategy.compute(snap)
            val messages = engineer.onUpdate(events, snap, fuel)
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
    }
}
