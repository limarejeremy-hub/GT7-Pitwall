package com.gt7pitwall.core

import com.gt7pitwall.core.live.LiveAudio
import com.gt7pitwall.core.live.LiveClientEvent
import com.gt7pitwall.core.live.LiveConfig
import com.gt7pitwall.core.live.LiveServerEvent
import com.gt7pitwall.core.live.LiveSession
import com.gt7pitwall.core.live.LiveTransport
import com.gt7pitwall.core.live.SimulatedLiveServer
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** V510 : prototype B GPT-Live, en simulation locale (aucun réseau, aucune clé). */
class LiveSessionTest {
    private var now = 0L

    /** 1 s de « voix » par tranche de 20 caractères, à 24 kHz : assez long pour être coupée. */
    private val fakeVoice: (String) -> ShortArray? = { t -> ShortArray(LiveConfig.SAMPLE_RATE * (1 + t.length / 20)) { 1000 } }

    private class Rig(val server: SimulatedLiveServer, val session: LiveSession) {
        val played = mutableListOf<ShortArray>()
        var flushes = 0
        val asked = mutableListOf<String>()
    }

    private fun rig(answers: Map<String, String> = emptyMap(), maxMs: Long = LiveSession.FIRST_TRIAL_MAX_MS): Rig {
        val server = SimulatedLiveServer({ now }, fakeVoice)
        lateinit var r: Rig
        val session = LiveSession(
            server, { now },
            delegate = { q, reply -> r.asked += q; reply(answers.entries.firstOrNull { q.contains(it.key) }?.value ?: "Je n'ai pas compris.") },
            play = { r.played += it },
            flush = { r.flushes++ },
            maxMs = maxMs,
        )
        r = Rig(server, session)
        return r
    }

    private fun advance(r: Rig, ms: Long) {
        val end = now + ms
        while (now < end) {
            now += 20
            r.session.tick()
        }
    }

    private val race = mapOf(
        "carburant" to "Oui, tu finis avec une marge d'un tour.",
        "rentre maintenant" to "Si tu rentres maintenant, tu perds environ vingt secondes, sans gain à l'arrivée.",
        "combien de tours" to "Il reste onze tours. Tu es onzième sur quinze.",
    )

    @Test
    fun manualStartOnlyAndRealServiceStaysDisabled() {
        val r = rig()
        assertEquals(LiveSession.State.OFF, r.session.state)
        r.session.onDriverUtterance("je finis avec ce carburant")
        assertTrue(r.asked.isEmpty(), "rien avant le démarrage manuel")
        val real = object : LiveTransport {
            override val label = "GPT-Live"
            override val real = true
            override fun open(onEvent: (LiveServerEvent) -> Unit) = Unit
            override fun send(event: LiveClientEvent) = error("aucun envoi vers un service réel")
            override fun close() = Unit
        }
        val s = LiveSession(real, { now }, { _, _ -> }, {}, {})
        assertFalse(s.start(), "GPT-Live réel désactivé")
        assertEquals(LiveSession.State.OFF, s.state)
    }

    @Test
    fun fiveMinuteCapClosesTheSession() {
        val r = rig()
        assertTrue(r.session.start())
        advance(r, 299_000)
        assertEquals(LiveSession.State.OPEN, r.session.state)
        assertEquals(1_000L, r.session.remainingMs())
        advance(r, 1_000)
        assertEquals(LiveSession.State.CLOSED, r.session.state)
        assertEquals("limite de 5 minutes atteinte", r.session.closeReason)
        assertEquals(300.0, r.session.usageSeconds, 0.05)
        r.session.onDriverUtterance("je finis avec ce carburant")
        assertTrue(r.asked.isEmpty(), "plus rien après la limite")
    }

    @Test
    fun fuelThenFollowUpWithoutVegaThenBargeIn() {
        val r = rig(race)
        r.session.start()
        r.session.onDriverUtterance("véga je finis avec ce carburant")
        advance(r, 200)
        assertEquals(listOf("véga je finis avec ce carburant"), r.asked)
        assertTrue(r.played.isNotEmpty(), "réponse jouée en flux")
        val firstDelay = r.session.turnsSoFar[0].delayMs
        assertEquals(20L, firstDelay, "premier morceau au premier tick (délai de simulation)")
        advance(r, 3_000) // la réponse se termine

        // Relance sans « Véga » : prise telle quelle par la session ouverte.
        r.session.onDriverUtterance("et si je rentre maintenant")
        advance(r, 500)
        assertTrue(r.session.speaking(), "Véga est en train de répondre")
        val playedBefore = r.played.size
        // Coupure : « Attends, il reste combien de tours ? » pendant la réponse.
        r.session.onDriverSpeechStart()
        assertEquals(1, r.flushes, "lecture locale vidée dès le début de ta phrase")
        assertFalse(r.session.speaking())
        advance(r, 300)
        assertEquals(playedBefore, r.played.size, "plus aucun morceau de l'ancienne réponse")
        r.session.onDriverUtterance("attends il reste combien de tours")
        advance(r, 200)
        assertTrue(r.played.size > playedBefore, "nouvelle réponse jouée")

        val t = r.session.turnsSoFar
        assertEquals(3, t.size)
        assertFalse(t[0].withoutWakeWord)
        assertTrue(t[1].withoutWakeWord && t[1].interrupted)
        assertTrue(t[2].withoutWakeWord && !t[2].interrupted)
        assertEquals(1, r.session.bargeIns)
        assertEquals(listOf(race["carburant"], race["rentre maintenant"], race["combien de tours"]).size, r.asked.size)
        val lines = r.session.lines()
        assertTrue(lines.any { "« et si je rentre maintenant » — réponse en" in it && "sans « Véga »" in it && "coupée" in it }, lines.toString())
    }

    @Test
    fun aStaleAnswerIsNeverSpoken() {
        // La réponse à la première question arrive après la deuxième : elle est jetée.
        val server = SimulatedLiveServer({ now }, fakeVoice)
        val pending = mutableListOf<(String) -> Unit>()
        val played = mutableListOf<ShortArray>()
        val s = LiveSession(server, { now }, { _, reply -> pending += reply }, { played += it }, {})
        s.start()
        s.onDriverUtterance("je finis avec ce carburant")
        s.onDriverUtterance("il reste combien de tours")
        pending[0]("Réponse périmée.")
        repeat(10) { now += 20; s.tick() }
        assertTrue(played.isEmpty())
        pending[1]("Il reste onze tours.")
        repeat(10) { now += 20; s.tick() }
        assertTrue(played.isNotEmpty())
    }

    @Test
    fun micAudioIsSentAndEventsUseTheGptLiveShape() {
        val r = rig()
        r.session.start()
        r.session.onMicAudio(ShortArray(480))
        assertEquals(480L, r.server.inputSamples)

        val start = LiveClientEvent.Start("e1", LiveConfig()).toJson()
        assertEquals("session.start", start.getString("type"))
        assertEquals("gpt-live-1", start.getJSONObject("session").getString("model"))
        assertEquals("brise", start.getJSONObject("session").getJSONObject("audio").getJSONObject("output").getString("voice"))
        assertTrue(start.getJSONObject("session").isNull("delegation"), "délégation client : delegation null")
        val s = start.toString()
        assertFalse(s.contains("key", ignoreCase = true) || s.contains("Bearer"), "aucune clé dans la configuration : $s")
        assertEquals("session.commentary.append", LiveClientEvent.CommentaryAppend("e2", "d1", "ok").toJson().getString("type"))
        assertEquals("session.thinking.append", LiveClientEvent.ThinkingAppend("e3", "d1").toJson().getString("type"))
        assertEquals("session.input_audio.append", LiveClientEvent.InputAudio("e4", shortArrayOf(1, -2)).toJson().getString("type"))

        val created = LiveServerEvent.fromJson(JSONObject("""{"type":"session.delegation.created","offset_ms":1000,"delegation":{"id":"item_9","type":"delegation","target":"client"}}"""))
        assertEquals(LiveServerEvent.DelegationCreated("item_9", 1000), created)
        val pcm = shortArrayOf(0, 1, -1, 32767, -32768)
        val audio = LiveServerEvent.fromJson(JSONObject().put("type", "session.output_audio.delta").put("delta", LiveAudio.toBase64(pcm))) as LiveServerEvent.OutputAudioDelta
        assertArrayEquals(pcm, audio.pcm)
        assertNull(LiveServerEvent.fromJson(JSONObject().put("type", "session.unknown")))
    }
}
