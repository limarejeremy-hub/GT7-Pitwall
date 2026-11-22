package com.gt7pitwall.core

import com.gt7pitwall.core.analysis.CornerPass
import com.gt7pitwall.core.driver.CornerCoach
import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.engineer.Priority
import com.gt7pitwall.core.live.LiveConfig
import com.gt7pitwall.core.live.LiveSession
import com.gt7pitwall.core.live.SimulatedLiveServer
import com.gt7pitwall.core.radio.RoutedVoiceOutput
import com.gt7pitwall.core.radio.SpeechQueue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** V511 — non-régression des correctifs de fiabilisation (radio, prototype B, coaching). */
class V511ReliabilityTest {

    // ------------------------------------------------------------------ Radio : alertes critiques concurrentes

    @Test
    fun aSecondCriticalAlertNeverErasesTheFirstOne() {
        val q = SpeechQueue<EngineerMessage> { it.priority }
        val info = EngineerMessage("Tour 4 bouclé.", Priority.NORMAL, MessageCategory.LAP)
        val fuel = EngineerMessage("Box, box ! Carburant critique.", Priority.CRITICAL, MessageCategory.FUEL)
        val pit = EngineerMessage("Arrêt obligatoire ce tour-ci.", Priority.CRITICAL, MessageCategory.RADIO)
        q.offer(info)
        assertEquals(listOf(info), q.offer(fuel), "l'annonce non critique est écartée")
        assertEquals(emptyList<EngineerMessage>(), q.offer(pit), "la première alerte critique n'est pas écartée")
        assertEquals(fuel, q.poll())
        assertEquals(pit, q.poll(), "les deux alertes critiques passent, dans l'ordre")
        assertNull(q.poll())
    }

    // ------------------------------------------------------------------ Radio : données périmées avant la synthèse

    @Test
    fun aMessageThatWaitedTooLongIsNotSpokenLate() {
        val t0 = 1_000_000L
        val fuel = EngineerMessage("Carburant pour 3 tours.", Priority.NORMAL, MessageCategory.FUEL, timestampMs = t0)
        assertFalse(RoutedVoiceOutput.stale(fuel, t0 + 9_000))
        assertTrue(RoutedVoiceOutput.stale(fuel, t0 + 10_500), "« carburant pour 3 tours » dit 10 s plus tard est faux")
        val place = EngineerMessage("P6.", Priority.NORMAL, MessageCategory.RADIO, timestampMs = t0, ttlMs = 4_000)
        assertTrue(RoutedVoiceOutput.stale(place, t0 + 4_500), "une place a sa propre validité, plus courte")
        val sample = EngineerMessage("Onzième sur quinze.", Priority.NORMAL, MessageCategory.RADIO, timestampMs = t0, ttlMs = 120_000)
        assertFalse(RoutedVoiceOutput.stale(sample, t0 + 30_000), "phrases d'écoute : jamais jugées périmées dans la file")
    }

    // ------------------------------------------------------------------ Prototype B : synthèse non bloquante, file bornée

    private var now = 0L
    private val pcm: (String) -> ShortArray = { ShortArray(LiveConfig.SAMPLE_RATE * 5) { 1000 } } // 5 s de « voix »

    @Test
    fun simulatedSynthesisNeverBlocksTheSessionAndAnInterruptedAnswerIsDropped() {
        val pending = mutableListOf<Pair<String, (ShortArray?) -> Unit>>()
        val server = SimulatedLiveServer({ now }, voice = { text, done -> pending += text to done })
        val played = mutableListOf<ShortArray>()
        var flushes = 0
        val s = LiveSession(server, { now }, { _, reply -> reply("Il reste onze tours.") }, { played += it }, { flushes++ }, maxMs = 10_000)
        s.start()
        s.onDriverUtterance("il reste combien de tours")
        assertEquals(1, pending.size, "synthèse demandée, pas attendue")
        // Synthèse lente : la session continue de tourner (limite de durée appliquée, rien de joué).
        repeat(100) { now += 20; s.tick() }
        assertTrue(played.isEmpty())
        // Le pilote reparle avant la fin de la synthèse : l'ancienne réponse ne sera jamais jouée.
        s.onDriverUtterance("et le carburant")
        pending[0].second(pcm(pending[0].first))
        repeat(10) { now += 20; s.tick() }
        assertTrue(played.isEmpty(), "réponse périmée jetée à l'arrivée de son audio")
        pending[1].second(pcm(pending[1].first))
        repeat(10) { now += 20; s.tick() }
        assertTrue(played.isNotEmpty())
        // Limite de durée appliquée même pendant une synthèse en attente.
        s.onDriverUtterance("encore une")
        repeat(500) { now += 20; s.tick() }
        assertEquals(LiveSession.State.CLOSED, s.state)
    }

    @Test
    fun simulatedAudioIsRequestedWithBackPressure() {
        val server = SimulatedLiveServer({ now }, voice = { t, done -> done(pcm(t)) })
        val s = LiveSession(server, { now }, { _, reply -> reply("Réponse longue.") }, {}, {})
        s.start()
        s.onDriverUtterance("question")
        var maxLead = 0L
        repeat(250) {
            now += 20
            s.tick()
            maxLead = maxOf(maxLead, if (s.speaking()) (playUntil(s) - now) else 0)
        }
        assertTrue(maxLead <= LiveSession.MAX_LEAD_MS + 40, "au plus ${LiveSession.MAX_LEAD_MS} ms d'avance (mesuré : $maxLead ms)")
        assertTrue(s.speaking(), "la réponse de 5 s est toujours en cours après 5 s de flux régulé")
    }

    /** Fin prévue de la lecture, lue par dichotomie sur speaking(t) (l'état interne reste privé). */
    private fun playUntil(s: LiveSession): Long {
        var lo = now
        var hi = now + 10_000
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (s.speaking(mid)) lo = mid else hi = mid
        }
        return hi
    }

    // ------------------------------------------------------------------ Coaching : « virage 5, 41 dixièmes »

    private fun pass(corner: Int, lap: Int, ms: Int) = CornerPass(
        corner = corner, lap = lap, timeMs = ms, brakeStartM = 100f, brakePeak = 0.9f, brakeRampMs = 120,
        entryKmh = 180f, minKmh = 90f, minAtM = 150f, throttleOnM = 170f, exitKmh = 140f, lockup = false, wheelspin = false,
        tcsShare = 0f, apexLateralM = 0f, rotationRatio = null, offTrack = null,
    )

    @Test
    fun anImplausibleCornerLossGivesNoAdviceAndNoFigure() {
        // Référence ~6 s ; trois derniers passages ~4,1 s plus lents (incident répété ou zone mal découpée).
        val history = mapOf(5 to listOf(pass(5, 1, 6_000), pass(5, 2, 6_050), pass(5, 3, 6_020)))
        val recent = mapOf(5 to listOf(pass(5, 4, 10_100), pass(5, 5, 10_150), pass(5, 6, 10_080)))
        assertTrue(CornerCoach.losses(history, recent).isEmpty(), "perte non plausible : virage écarté")
        assertNull(CornerCoach.worst(history, recent, CornerCoach.MIN_LOSS_RACE_MS))
        // Une perte plausible reste détectée (0,4 s).
        val plausible = mapOf(5 to listOf(pass(5, 4, 6_420), pass(5, 5, 6_430), pass(5, 6, 6_410)))
        assertTrue(CornerCoach.losses(history, plausible)[5]!! in 380..420)
    }

    @Test
    fun durationsOfOneSecondOrMoreAreSaidInSeconds() {
        assertEquals("un dixième", CornerCoach.tenths(90))
        assertEquals("4 dixièmes", CornerCoach.tenths(400))
        assertEquals("1 seconde", CornerCoach.tenths(1_000))
        assertEquals("1,5 seconde", CornerCoach.tenths(1_500))
        assertEquals("4,1 secondes", CornerCoach.tenths(4_100), "jamais « 41 dixièmes »")
    }
}
