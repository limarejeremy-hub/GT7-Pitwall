package com.gt7pitwall.app.engine

import com.gt7pitwall.core.analysis.LapAnalyzer
import com.gt7pitwall.core.analysis.LapStats
import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.engineer.Priority
import com.gt7pitwall.core.engineer.RaceEngineer
import com.gt7pitwall.core.radio.DriverQuery
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
)

/**
 * Chaîne complète : réception UDP -> suivi de session -> stratégie carburant -> ingénieur -> voix.
 * Indépendant de l'interface : le service de premier plan le maintient en vie écran éteint.
 */
class PitwallEngine(private val voice: VoiceOutput) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = TelemetryClient()
    private val tracker = SessionTracker()
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

    private var collectJob: Job? = null
    private var lastPublishMs = 0L

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

    /** Question du pilote (boutons de l'écran aujourd'hui, reconnaissance vocale demain). */
    fun ask(query: DriverQuery) {
        scope.launch {
            val message = mutex.withLock {
                val snap = tracker.snapshot
                engineer.answer(query, snap, FuelStrategy.compute(snap))
            }
            deliver(message)
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
            val snap = tracker.snapshot
            val fuel = FuelStrategy.compute(snap)
            val messages = engineer.onUpdate(events, snap, fuel)
            val now = System.currentTimeMillis()
            // ~20 rafraîchissements d'écran par seconde suffisent (GT7 envoie ~60 paquets/s).
            if (events.isNotEmpty() || now - lastPublishMs >= UI_REFRESH_MS) {
                _state.value = PitwallUiState(snap, fuel, LapAnalyzer.analyze(snap.laps))
                lastPublishMs = now
            }
            messages.forEach(::deliver)
        }
    }

    private fun deliver(message: EngineerMessage) {
        _radio.update { (listOf(message) + it).take(MAX_RADIO_LOG) }
        if (voiceEnabled) voice.speak(message)
    }

    private companion object {
        const val UI_REFRESH_MS = 50L
        const val MAX_RADIO_LOG = 50
    }
}
