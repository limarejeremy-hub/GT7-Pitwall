package com.gt7pitwall.core.live

/**
 * Ce que la radio locale entend : sert UNIQUEMENT au serveur simulé, qui ne comprend pas l'audio. Le vrai GPT-Live
 * écoute lui-même le micro ([LiveClientEvent.InputAudio]) et n'a pas besoin de ces indices.
 */
interface LiveHints {
    fun hintSpeechStart()
    fun hintUtterance(text: String)
}

/**
 * Serveur GPT-Live SIMULÉ, entièrement local (aucun réseau, aucune clé, aucun coût). Il reproduit le déroulé d'une
 * session en délégation client : `session.started`, transcription de ta phrase, `session.delegation.created`, puis la
 * réponse de Pitwall dite en flux (`session.output_audio.delta` par 20 ms) et interrompue dès que tu reprends la parole.
 *
 * Ce qu'il ne simule PAS : la voix et l'intelligence de GPT-Live. Le son vient de [voice] (dans l'app : la synthèse
 * Android, la même que A) et le texte de Pitwall. Le délai mesuré est celui de la simulation, pas celui de GPT-Live.
 */
class SimulatedLiveServer(
    private val clock: () -> Long,
    /** Texte → PCM16 mono 24 kHz, ou null si la synthèse échoue. */
    private val voice: (String) -> ShortArray?,
    /** Morceaux d'audio émis à chaque [pump] (20 ms chacun) : 2 = deux fois le temps réel, comme un service qui devance la lecture. */
    private val chunksPerPump: Int = 2,
) : LiveTransport, LiveHints {
    override val label = "simulation locale (aucun réseau, aucune clé)"
    override val real = false

    private var sink: ((LiveServerEvent) -> Unit)? = null
    private var startMs = 0L
    private var started = false
    private var delegations = 0
    private var pendingDelegation: String? = null
    private val out = ArrayDeque<ShortArray>()

    /** Échantillons de micro reçus (preuve que l'audio est bien envoyé ; le simulateur ne le comprend pas). */
    var inputSamples = 0L
        private set

    val streaming: Boolean get() = out.isNotEmpty()

    override fun open(onEvent: (LiveServerEvent) -> Unit) {
        sink = onEvent
    }

    private fun emit(e: LiveServerEvent) = sink?.invoke(e)

    private fun offset() = clock() - startMs

    override fun send(event: LiveClientEvent) {
        when (event) {
            is LiveClientEvent.Start -> {
                started = true
                startMs = clock()
                emit(LiveServerEvent.Started("sim_${startMs}"))
            }
            is LiveClientEvent.InputAudio -> if (started) inputSamples += event.pcm.size
            is LiveClientEvent.ThinkingAppend -> Unit
            is LiveClientEvent.CommentaryAppend -> {
                // Une réponse arrivée après une nouvelle question est périmée : jamais dite.
                if (!started || event.delegationId != pendingDelegation) return
                pendingDelegation = null
                emit(LiveServerEvent.OutputTranscriptDelta(event.content))
                val pcm = voice(event.content)
                if (pcm == null) {
                    emit(LiveServerEvent.Error("synthèse impossible"))
                    return
                }
                out.addAll(LiveAudio.chunks(pcm))
            }
            is LiveClientEvent.Close -> close()
        }
    }

    /** Envoie les prochains morceaux d'audio prêts (appelé toutes les 20 ms par la session). */
    fun pump() {
        repeat(chunksPerPump) { out.removeFirstOrNull()?.let { emit(LiveServerEvent.OutputAudioDelta(it)) } ?: return }
    }

    override fun hintSpeechStart() {
        if (!started) return
        // Full duplex : tu parles, la génération de la réponse en cours s'arrête aussitôt.
        out.clear()
        emit(LiveServerEvent.InputTranscriptDelta("", offset(), offset()))
    }

    override fun hintUtterance(text: String) {
        if (!started || text.isBlank()) return
        out.clear()
        val o = offset()
        emit(LiveServerEvent.InputTranscriptDelta(text, o, o))
        val id = "deleg_sim_${++delegations}"
        pendingDelegation = id
        emit(LiveServerEvent.DelegationCreated(id, o))
    }

    override fun close() {
        if (!started) return
        started = false
        out.clear()
        emit(LiveServerEvent.UsageUpdated(offset() / 1000.0))
        emit(LiveServerEvent.Closed("fermée par Pitwall"))
    }
}

/**
 * Session B côté Pitwall (prototype V510) : activation MANUELLE uniquement, durée bornée à [maxMs] (5 min pour le
 * premier essai au casque), délégation des questions de course à Pitwall, interruption à tout moment.
 *
 * - Relances : pendant la session, chaque phrase est prise sans « Véga » (la session reste ouverte).
 * - Coupure : dès qu'une transcription de ta voix arrive pendant que Véga parle, la lecture locale est vidée
 *   ([flush]) ; le service arrête lui-même de générer.
 * - GPT-Live réel désactivé : une session refuse de démarrer sur un transport réel ([LiveTransport.real]).
 */
class LiveSession(
    private val transport: LiveTransport,
    private val clock: () -> Long,
    /** Question entendue → réponse de Pitwall (état de course), rendue par le rappel (le calcul peut être asynchrone). */
    private val delegate: (String, (String) -> Unit) -> Unit,
    /** PCM16 24 kHz à jouer dans le casque, dans l'ordre. */
    private val play: (ShortArray) -> Unit,
    /** Vide la lecture en attente (coupure). */
    private val flush: () -> Unit,
    val maxMs: Long = FIRST_TRIAL_MAX_MS,
    private val config: LiveConfig = LiveConfig(),
) {
    enum class State { OFF, OPEN, CLOSED }

    data class Turn(
        val heard: String,
        /** Fin de ta phrase → premier morceau d'audio reçu ; null si aucune réponse n'a été jouée. */
        val delayMs: Long?,
        /** Faux si la phrase commençait par « Véga » (inutile pendant la session). */
        val withoutWakeWord: Boolean,
        val interrupted: Boolean = false,
    )

    var state: State = State.OFF
        private set
    var closeReason: String? = null
        private set
    var usageSeconds: Double = 0.0
        private set
    var bargeIns: Int = 0
        private set

    private var startMs = 0L
    private var ids = 0
    private val turns = mutableListOf<Turn>()
    private var turnEndMs: Long? = null
    private var awaitingAudio = false
    private var playUntilMs = 0L
    private var lastHeard = ""

    val turnsSoFar: List<Turn> get() = turns.toList()

    fun remainingMs(now: Long = clock()): Long = if (state == State.OPEN) (maxMs - (now - startMs)).coerceAtLeast(0) else 0

    /** Démarrage manuel. Refusé sur un service réel : GPT-Live reste désactivé dans ce prototype. */
    fun start(): Boolean {
        if (state == State.OPEN || transport.real) return false
        state = State.OPEN
        closeReason = null
        startMs = clock()
        turns.clear()
        bargeIns = 0
        transport.open(::onEvent)
        transport.send(LiveClientEvent.Start(nextId(), config))
        return true
    }

    fun stop(reason: String = "arrêtée par le pilote") {
        if (state != State.OPEN) return
        closeReason = reason
        state = State.CLOSED
        transport.send(LiveClientEvent.Close(nextId()))
        flush()
        playUntilMs = 0
    }

    /** Toutes les 20 ms : limite de durée, puis audio simulé suivant. */
    fun tick() {
        if (state != State.OPEN) return
        if (clock() - startMs >= maxMs) return stop("limite de ${maxMs / 60_000} minutes atteinte")
        (transport as? SimulatedLiveServer)?.pump()
    }

    /** Micro du casque (24 kHz) vers le service. */
    fun onMicAudio(pcm24k: ShortArray) {
        if (state == State.OPEN && pcm24k.isNotEmpty()) transport.send(LiveClientEvent.InputAudio(nextId(), pcm24k))
    }

    /** La radio locale entend le début d'une phrase (simulation seulement). */
    fun onDriverSpeechStart() {
        if (state == State.OPEN) (transport as? LiveHints)?.hintSpeechStart()
    }

    /** La radio locale a transcrit une phrase complète (simulation seulement). */
    fun onDriverUtterance(text: String) {
        if (state != State.OPEN) return
        // Phrase d'interruption sans début de parole détecté avant : la coupure passe quand même.
        (transport as? LiveHints)?.let {
            if (speaking()) it.hintSpeechStart()
            it.hintUtterance(text)
        }
    }

    /** Véga parle encore dans le casque (audio reçu dont la lecture n'est pas finie). */
    fun speaking(now: Long = clock()): Boolean = now < playUntilMs

    private fun onEvent(e: LiveServerEvent) {
        val now = clock()
        when (e) {
            is LiveServerEvent.Started -> Unit
            is LiveServerEvent.InputTranscriptDelta -> {
                if (speaking(now)) {
                    // Coupure : la lecture locale est vidée tout de suite.
                    flush()
                    playUntilMs = now
                    bargeIns++
                    turns.lastOrNull()?.let { turns[turns.lastIndex] = it.copy(interrupted = true) }
                    awaitingAudio = false
                }
                if (e.delta.isNotBlank()) {
                    lastHeard = e.delta
                    turnEndMs = now
                }
            }
            is LiveServerEvent.DelegationCreated -> {
                val heard = lastHeard
                val id = e.delegationId
                transport.send(LiveClientEvent.ThinkingAppend(nextId(), id))
                turns += Turn(heard, null, !heard.trim().lowercase().let { it.startsWith("véga") || it.startsWith("vega") })
                awaitingAudio = true
                delegate(heard) { answer -> if (state == State.OPEN) transport.send(LiveClientEvent.CommentaryAppend(nextId(), id, answer)) }
            }
            is LiveServerEvent.OutputTranscriptDelta -> Unit
            is LiveServerEvent.OutputAudioDelta -> {
                if (awaitingAudio) {
                    awaitingAudio = false
                    val d = turnEndMs?.let { now - it }
                    turns.lastOrNull()?.let { turns[turns.lastIndex] = it.copy(delayMs = d) }
                }
                playUntilMs = maxOf(playUntilMs, now) + e.pcm.size * 1000L / config.sampleRate
                play(e.pcm)
            }
            is LiveServerEvent.UsageUpdated -> usageSeconds = e.seconds
            is LiveServerEvent.Closed -> if (state == State.OPEN) {
                state = State.CLOSED
                closeReason = e.reason
            }
            is LiveServerEvent.Error -> closeReason = e.message
        }
    }

    private fun nextId() = "evt_${++ids}"

    fun lines(now: Long = clock()): List<String> = buildList {
        add(
            "B — ${transport.label} : " + when (state) {
                State.OFF -> "pas démarrée"
                State.OPEN -> "ouverte, reste ${remainingMs(now) / 1000} s"
                State.CLOSED -> "fermée (${closeReason ?: "?"})" + if (usageSeconds > 0) ", ${"%.0f".format(usageSeconds)} s" else ""
            },
        )
        turns.forEachIndexed { i, t ->
            add(
                "${i + 1}. « ${t.heard} » — " + listOfNotNull(
                    t.delayMs?.let { "réponse en $it ms (simulation)" } ?: "pas de réponse jouée",
                    if (t.withoutWakeWord) "sans « Véga »" else "avec « Véga »",
                    if (t.interrupted) "coupée" else null,
                ).joinToString(", "),
            )
        }
        if (bargeIns > 0) add("Coupures : $bargeIns")
    }

    companion object {
        /** Premier essai au casque : cinq minutes au plus. */
        const val FIRST_TRIAL_MAX_MS = 5 * 60_000L
    }
}
