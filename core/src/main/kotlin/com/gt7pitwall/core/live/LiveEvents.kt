package com.gt7pitwall.core.live

import org.json.JSONObject
import java.util.Base64

/**
 * V510 — prototype B « GPT-Live » : sous-ensemble des événements de la session GPT-Live (WebSocket, délégation client),
 * d'après developers.openai.com (guides GPT-Live, lus le 19/11/2026). Les noms d'événements et la forme JSON sont
 * ceux de la documentation, pour que le passage au vrai service ne change que le transport.
 *
 * Pitwall joue le rôle du « backend » en délégation client : GPT-Live ouvre une délégation, Pitwall calcule la réponse
 * à partir de l'état de course et la rend par `session.commentary.append`.
 *
 * Aucune clé ici : la configuration ne contient ni clé ni secret. La future connexion réelle passera par un serveur
 * relais du pilote qui garde la clé OpenAI (voir docs/VOICE_LIVE_B.md).
 */
data class LiveConfig(
    val model: String = MODEL,
    /** Voix française féminine de GPT-Live. */
    val voice: String = VOICE_FR_FEMININE,
    val instructions: String = DEFAULT_INSTRUCTIONS,
    /** Délégation client : Pitwall répond aux délégations avec l'état de course. */
    val delegation: String = "client",
    /** PCM16 mono 24 kHz, le format par défaut de la WebSocket GPT-Live. */
    val sampleRate: Int = SAMPLE_RATE,
) {
    companion object {
        const val MODEL = "gpt-live-1"
        const val VOICE_FR_FEMININE = "brise"
        const val SAMPLE_RATE = 24_000
        const val DEFAULT_INSTRUCTIONS =
            "Tu es Véga, l'ingénieure de course du pilote, à la radio pendant une course Gran Turismo 7. Parle français, " +
                "phrases courtes, ton calme et naturel. Pour toute donnée de course (carburant, tours, position, arrêts), " +
                "délègue à Pitwall et dis seulement ce qu'il répond, sans rien inventer. Si le pilote te coupe, arrête-toi " +
                "et réponds à sa nouvelle question. Il n'a pas besoin de redire « Véga » pendant la session."
    }
}

/** Événements envoyés par Pitwall au service. */
sealed interface LiveClientEvent {
    val eventId: String

    data class Start(override val eventId: String, val config: LiveConfig) : LiveClientEvent
    data class InputAudio(override val eventId: String, val pcm: ShortArray) : LiveClientEvent
    data class ThinkingAppend(override val eventId: String, val delegationId: String) : LiveClientEvent
    data class CommentaryAppend(override val eventId: String, val delegationId: String, val content: String) : LiveClientEvent
    data class Close(override val eventId: String) : LiveClientEvent

    /** JSON au format GPT-Live (pour le futur relais). */
    fun toJson(): JSONObject = when (this) {
        is Start -> JSONObject().put("type", "session.start").put("event_id", eventId).put(
            "session",
            JSONObject()
                .put("model", config.model)
                .put("instructions", config.instructions)
                .put("audio", JSONObject().put("output", JSONObject().put("voice", config.voice)))
                .put("delegation", if (config.delegation == "client") JSONObject.NULL else JSONObject()),
        )
        is InputAudio -> JSONObject().put("type", "session.input_audio.append").put("event_id", eventId).put("audio", LiveAudio.toBase64(pcm))
        is ThinkingAppend -> JSONObject().put("type", "session.thinking.append").put("event_id", eventId)
            .put("delegation_id", delegationId).put("content", "")
        is CommentaryAppend -> JSONObject().put("type", "session.commentary.append").put("event_id", eventId)
            .put("delegation_id", delegationId).put("content", content)
        is Close -> JSONObject().put("type", "session.close").put("event_id", eventId)
    }
}

/** Événements reçus du service (réel ou simulé). */
sealed interface LiveServerEvent {
    data class Started(val sessionId: String) : LiveServerEvent
    data class InputTranscriptDelta(val delta: String, val startMs: Long, val endMs: Long) : LiveServerEvent
    data class OutputTranscriptDelta(val delta: String) : LiveServerEvent
    data class OutputAudioDelta(val pcm: ShortArray) : LiveServerEvent
    data class DelegationCreated(val delegationId: String, val offsetMs: Long) : LiveServerEvent
    data class UsageUpdated(val seconds: Double) : LiveServerEvent
    data class Closed(val reason: String) : LiveServerEvent
    data class Error(val message: String) : LiveServerEvent

    companion object {
        /** Lecture du JSON GPT-Live ; null pour un événement que le prototype n'utilise pas. */
        fun fromJson(o: JSONObject): LiveServerEvent? = when (o.optString("type")) {
            "session.started" -> Started(o.optJSONObject("session")?.optString("id") ?: o.optString("session_id"))
            "session.input_transcript.delta" -> InputTranscriptDelta(o.optString("delta"), o.optLong("start_ms"), o.optLong("end_ms"))
            "session.output_transcript.delta" -> OutputTranscriptDelta(o.optString("delta"))
            "session.output_audio.delta" -> OutputAudioDelta(LiveAudio.fromBase64(o.optString("delta")))
            "session.delegation.created" -> DelegationCreated(o.getJSONObject("delegation").getString("id"), o.optLong("offset_ms"))
            "session.usage.updated" -> UsageUpdated(o.optJSONObject("usage")?.optDouble("seconds") ?: 0.0)
            "session.closed" -> Closed(o.optString("reason", "fermée"))
            "error" -> Error(o.optJSONObject("error")?.optString("message") ?: o.toString())
            else -> null
        }
    }
}

/** Transport d'une session : simulé ([SimulatedLiveServer]) ou, plus tard, relais serveur. */
interface LiveTransport {
    /** Nom affiché (« simulation locale »). */
    val label: String

    /** Vrai seulement pour un service réel : le prototype n'en a aucun. */
    val real: Boolean

    fun open(onEvent: (LiveServerEvent) -> Unit)
    fun send(event: LiveClientEvent)
    fun close()
}

object LiveAudio {
    fun toBase64(pcm: ShortArray): String {
        val b = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            b[2 * i] = (pcm[i].toInt() and 0xFF).toByte()
            b[2 * i + 1] = (pcm[i].toInt() shr 8 and 0xFF).toByte()
        }
        return Base64.getEncoder().encodeToString(b)
    }

    fun fromBase64(s: String): ShortArray {
        val b = Base64.getDecoder().decode(s)
        return ShortArray(b.size / 2) { i -> ((b[2 * i].toInt() and 0xFF) or (b[2 * i + 1].toInt() shl 8)).toShort() }
    }

    /** Morceaux de 20 ms au débit [rate]. */
    fun chunks(pcm: ShortArray, rate: Int = LiveConfig.SAMPLE_RATE, ms: Int = 20): List<ShortArray> {
        val n = rate * ms / 1000
        return (pcm.indices step n).map { pcm.copyOfRange(it, minOf(pcm.size, it + n)) }
    }
}
