package com.gt7pitwall.core

import com.gt7pitwall.core.ai.AiBackend
import com.gt7pitwall.core.ai.AiMode
import com.gt7pitwall.core.ai.AiProvider
import com.gt7pitwall.core.ai.AiResult
import com.gt7pitwall.core.ai.AiRouter
import com.gt7pitwall.core.ai.AiUsage
import com.gt7pitwall.core.ai.DialogueActs
import com.gt7pitwall.core.ai.MoodTracker
import com.gt7pitwall.core.ai.Persona
import com.gt7pitwall.core.ai.PilotMemory
import com.gt7pitwall.core.ai.RaceMood
import com.gt7pitwall.core.ai.RadioConversation
import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.engineer.Priority
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.radio.ConversationWindow
import com.gt7pitwall.core.radio.DuplexMode
import com.gt7pitwall.core.radio.DuplexPolicy
import com.gt7pitwall.core.radio.InterruptDetector
import com.gt7pitwall.core.radio.LatencyTrace
import com.gt7pitwall.core.radio.RadioPacer
import com.gt7pitwall.core.radio.RemoteVoice
import com.gt7pitwall.core.radio.RoutedVoiceOutput
import com.gt7pitwall.core.radio.SpeechStyle
import com.gt7pitwall.core.radio.VoiceActivity
import com.gt7pitwall.core.radio.VoiceOutput
import com.gt7pitwall.core.radio.VoiceRoute
import com.gt7pitwall.core.strategy.RaceRules
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Human Radio V4 : conversation naturelle, interruption, mémoire, personnalité. Les interactions audio sont simulées par
 * des événements et des blocs PCM synthétiques : ces tests ne remplacent pas un essai avec le casque PS5 et le Fold.
 */
class HumanRadioV4Test {

    private class FakeCloud(override val backend: AiBackend, val script: suspend (String) -> AiResult) : AiProvider {
        val prompts = mutableListOf<String>()
        override val modelId: String = backend.defaultModelId!!
        override val ready: Boolean = true
        override val status: String = "simulé (test)"
        override suspend fun complete(systemInstruction: String, prompt: String): AiResult {
            prompts += prompt
            return script(prompt)
        }
    }

    private fun router(flash: FakeCloud, lite: FakeCloud = FakeCloud(AiBackend.FLASH_LITE) { AiResult.Text("Compris.") }) =
        AiRouter(mapOf(AiBackend.FLASH_LITE to lite, AiBackend.FLASH to flash), AiUsage(), AiMode.AUTO, totalTimeoutMs = 12_000L)

    private fun live(rules: RaceRules = RaceRules(totalLaps = 12, mandatoryStops = 1)): Pair<RaceController, RaceSim> {
        val c = RaceController(storage = FakeStorage(rules = rules))
        val s = RaceSim(c, totalLaps = 12, perLap = 6f)
        s.start()
        s.driveLaps(3)
        return c to s
    }

    private suspend fun RadioConversation.say(text: String) = onUtterance(text, text, model = null)
    private fun List<EngineerMessage>.answer() = lastOrNull { it.category == MessageCategory.ANSWER }?.text.orEmpty()

    // 1, 2, 3 : conversation continue, bip d'ouverture une seule fois, double bip à la fermeture.
    @Test
    fun aConversationStaysOpenAfterRadioWithOneReadyBeepAndADoubleBeepAtTheEnd() {
        val w = ConversationWindow()
        w.wake(0)
        assertEquals(ConversationWindow.Effect.READY_BEEP, w.micReady())
        assertNull(w.micReady(), "un seul bip d'ouverture")
        assertTrue(w.addressedByWake)
        assertNull(w.utterance("tu le sens comment", 1_000))
        assertNull(w.utterance("et si je mets les tendres", 4_000), "relance sans redire « Radio »")
        assertFalse(w.addressedByWake)
        assertNull(w.micReady(), "pas de bip à chaque phrase")
        assertNull(w.tick(4_000 + ConversationWindow.DEFAULT_IDLE_MS - 1))
        assertEquals(ConversationWindow.Effect.CLOSED_BEEP, w.tick(4_000 + ConversationWindow.DEFAULT_IDLE_MS))
        assertFalse(w.open)
        w.wake(20_000)
        assertEquals(ConversationWindow.Effect.READY_BEEP, w.micReady(), "nouvelle conversation, nouveau bip")
        assertEquals(ConversationWindow.Effect.CLOSED_BEEP, w.utterance("merci terminé", 21_000))
    }

    // 4 : jamais de fermeture pendant que l'ingénieur parle ou qu'une réponse se prépare.
    @Test
    fun silenceNeverClosesTheConversationWhileTheEngineerSpeaksOrThinks() {
        val w = ConversationWindow()
        w.wake(0)
        w.utterance("pourquoi tu veux me faire rentrer", 1_000)
        w.thinking(true, 1_000)
        assertNull(w.tick(30_000), "Gemini réfléchit : pas de double bip")
        w.thinking(false, 30_000)
        w.engineerSpeaking(true, 30_000)
        assertNull(w.tick(50_000), "l'ingénieur parle")
        w.engineerSpeaking(false, 50_000)
        assertNull(w.tick(50_000 + ConversationWindow.DEFAULT_IDLE_MS - 1), "le délai repart à la fin de sa phrase")
        assertEquals(ConversationWindow.Effect.CLOSED_BEEP, w.tick(50_000 + ConversationWindow.DEFAULT_IDLE_MS))
        assertTrue(ConversationWindow.DEFAULT_IDLE_MS in 6_000L..10_000L, "réévalué : ni 5 s, ni une écoute interminable")
    }

    private fun tone(hz: Double, amp: Double, n: Int = 4_000) = ShortArray(n) { (sin(2 * PI * hz * it / 16_000) * amp).toInt().toShort() }
    private fun noise(amp: Int, n: Int = 4_000, seed: Int = 1) = Random(seed).let { r -> ShortArray(n) { r.nextInt(-amp, amp).toShort() } }

    // 5 : interruption pendant une réponse vocale.
    @Test
    fun theDriverCanInterruptTheEngineer() {
        val d = InterruptDetector()
        d.engineer(true, "Je te conseille de rentrer à la fin du prochain tour.", 0)
        val vad = VoiceActivity()
        repeat(4) { vad.accept(noise(300, seed = it), 4_000) } // bruit de fond : plancher appris
        assertTrue(vad.accept(tone(220.0, 6_000.0), 4_000), "voix du pilote nettement au-dessus du bruit")
        d.voice(1_000)
        assertTrue(d.words("non attends", 1_200))
        // La réponse en préparation devient périmée.
        val (c, _) = live()
        val conv = RadioConversation(c)
        val before = conv.turn
        conv.interrupt()
        assertTrue(conv.turn > before)
    }

    // 6 : la voix de l'ingénieur ne l'interrompt pas.
    @Test
    fun theEngineerNeverInterruptsHimself() {
        val d = InterruptDetector()
        d.engineer(true, "Je te conseille de rentrer à la fin du prochain tour.", 0)
        d.voice(500)
        assertFalse(d.words("rentrer à la fin du prochain tour", 700), "écho de sa propre phrase")
        // Sur haut-parleur, la fin de sa phrase résonne encore : garde anti-écho.
        val gaps = InterruptDetector(echoGuardMs = 150)
        gaps.engineer(true, "Arrêt fin du tour sept.", 0)
        gaps.engineer(false, "Arrêt fin du tour sept.", 1_000)
        gaps.voice(1_050)
        assertFalse(gaps.words("attends", 1_100), "pendant la garde")
        gaps.voice(1_300)
        assertTrue(gaps.words("attends", 1_350))
        // Une alerte critique ne se coupe jamais ; le mode suit le matériel.
        assertFalse(DuplexPolicy.interruptible(EngineerMessage("Box, box !", Priority.CRITICAL, MessageCategory.ALERT)))
        assertEquals(DuplexMode.FULL, DuplexPolicy.mode(VoiceRoute.REMOTE, phoneSpeaker = true), "Discord : flux du pilote séparé")
        assertEquals(DuplexMode.GAPS, DuplexPolicy.mode(VoiceRoute.PHONE, phoneSpeaker = true), "haut-parleur : entre les phrases")
        assertEquals(DuplexMode.FULL, DuplexPolicy.mode(VoiceRoute.PHONE, phoneSpeaker = false), "écouteurs")
    }

    // 7 : bruits du jeu et fausses reconnaissances.
    @Test
    fun gameNoiseAndFalseRecognitionsDoNotInterruptOrKeepListening() {
        val vad = VoiceActivity()
        // Bruit moteur constant, fort : le plancher s'adapte, ce n'est pas une voix.
        repeat(20) { assertFalse(vad.accept(noise(900, seed = it), 4_000) && it > 3) }
        val d = InterruptDetector()
        d.engineer(true, "Tour cinq, une minute quarante.", 0)
        assertFalse(d.words("attends", 500), "mot reconnu sans voix établie")
        d.voice(1_000)
        assertFalse(d.words("bon", 1_100), "mot isolé")
        assertFalse(d.words("[unk] [unk]", 1_100))
        assertFalse(d.words("attends", 3_000), "voix trop ancienne")
        // Écoute involontaire : trois fragments inintelligibles de suite referment la conversation.
        val w = ConversationWindow()
        w.wake(0)
        assertNull(w.utterance("euh", 1_000))
        assertNull(w.utterance("[unk]", 2_000))
        assertEquals(ConversationWindow.Effect.CLOSED_BEEP, w.utterance("hum", 3_000))
    }

    // 8 et 15 : une ancienne réponse est annulée après une nouvelle question ou un changement de session.
    @Test
    fun aStaleAnswerIsNeverSpoken() = runBlocking<Unit> {
        val (c, s) = live()
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val flash = FakeCloud(AiBackend.FLASH) { calls++; if (calls == 1) gate.await(); AiResult.Text("Réponse à la question $calls.") }
        val conv = RadioConversation(c).also { it.cloud = router(flash, FakeCloud(AiBackend.FLASH_LITE) { calls++; if (calls == 1) gate.await(); AiResult.Text("Réponse à la question $calls.") }) }
        val slow = async { conv.say("tu me conseilles quels pneus") }
        while (calls == 0) yield()
        val fresh = conv.say("et le carburant pour finir on est bon")
        gate.complete(Unit)
        assertTrue(slow.await().none { it.category == MessageCategory.ANSWER }, "réponse périmée non dite")
        assertTrue(fresh.answer().isNotBlank())

        // Changement de session pendant la génération.
        val gate2 = CompletableDeferred<Unit>()
        val conv2 = RadioConversation(c).also { it.cloud = router(FakeCloud(AiBackend.FLASH) { gate2.await(); AiResult.Text("Trop tard.") }, FakeCloud(AiBackend.FLASH_LITE) { gate2.await(); AiResult.Text("Trop tard.") }) }
        val pending = async { conv2.say("tu me conseilles quels pneus") }
        yield()
        RaceSim(c, totalLaps = 5).start() // nouvelle course : nouvelle session
        gate2.complete(Unit)
        assertTrue(pending.await().none { it.category == MessageCategory.ANSWER && "Trop tard" in it.text })
    }

    // 9 et 10 : décision ambiguë confirmée ; désaccord sans modification automatique.
    @Test
    fun aDisagreementIsDebatedAndADecisionIsConfirmedBeforeAnything() = runBlocking<Unit> {
        val (c, s) = live()
        val conv = RadioConversation(c)
        val stops = c.plan?.stops?.map { it.endOfLap }
        val r = conv.say("je veux rentrer maintenant").answer()
        assertTrue(r.startsWith("Je ne suis pas convaincu.") || r.startsWith("Ça se discute.") || r.startsWith("Je n'ai pas de quoi") || r.startsWith("D'accord avec toi") || r.startsWith("C'est prévu"), r)
        assertTrue(r.endsWith("Qu'est-ce qui te pousse à rentrer ?") || r.startsWith("D'accord avec toi") || r.startsWith("C'est prévu"), r)
        assertEquals(0, c.ledger.stopCount, "rien n'est enregistré")
        assertEquals(stops, c.plan?.stops?.map { it.endOfLap }, "le plan ne change pas tout seul")
        // La raison du pilote : son ressenti est pris en compte, le plan réévalué par le moteur.
        val why = conv.say("je perds l'avant").answer()
        assertTrue(why.startsWith("OK, information importante. Je prends ton ressenti en compte."), why)
        // Décision explicite : confirmée avant d'être notée.
        val d = conv.say("je rentre quand même").answer()
        assertEquals("C'est ta décision : box à la fin de ce tour ? Dis oui pour confirmer.", d)
        assertTrue(c.raceMemory.decisionList.none { "décidé par le pilote" in it.text })
        val ok = conv.say("oui").answer()
        assertTrue(ok.startsWith("Compris, box à la fin de ce tour."), ok)
        assertTrue(c.raceMemory.decisionList.any { "décidé par le pilote" in it.text })
        assertEquals(0, c.ledger.stopCount, "une décision n'est pas un arrêt effectué")
        s.driveLaps(1)
    }

    // 11 : mémoire conservant une préférence explicite ; 12 : une hypothèse n'est jamais un fait.
    @Test
    fun preferencesAreRememberedAndHypothesesAreNotFacts() = runBlocking<Unit> {
        val storage = FakeStorage(rules = RaceRules(totalLaps = 12))
        val c = RaceController(storage = storage)
        RaceSim(c, totalLaps = 12, perLap = 6f).also { it.start(); it.driveLaps(3) }
        val conv = RadioConversation(c)
        assertTrue(conv.say("je veux préserver les pneus").answer().startsWith("Compris, c'est noté pour cette course."))
        assertTrue("PRÉFÉRENCES DU PILOTE POUR CETTE COURSE" in c.raceMemory.render(emptyList(), emptyList()))
        assertTrue("ta consigne" in conv.say("tu le sens comment").answer(), "l'avis tient compte de la préférence")
        // Hypothèse : notée comme telle, rien n'est enregistré.
        conv.say("et si je mets les tendres")
        assertTrue(c.raceMemory.hypothesisList.any { "tendres" in it.text })
        assertTrue(c.raceMemory.factList.none { "tendres" in it.text })
        assertTrue(c.ledger.changes.isEmpty())
        // « Oublie ce que je viens de dire » retire la dernière déclaration.
        assertEquals("OK, j'oublie.", conv.say("oublie ce que je viens de dire").answer())
        assertTrue(c.raceMemory.hypothesisList.isEmpty())
        // Préférence durable : demandée, confirmée, gardée d'une session à l'autre.
        assertTrue(conv.say("retiens que je préfère des réponses courtes").answer().endsWith("C'est bien ça ?"))
        assertEquals("C'est retenu pour les prochaines courses.", conv.say("oui").answer())
        val again = RaceController(storage = storage)
        assertTrue(again.pilotMemory.all.single().text.contains("réponses courtes"))
        assertTrue("MÉMOIRE DURABLE DU PILOTE" in again.pilotMemory.render())
        // Désactivée : rien n'est lu ni écrit ; effacée : plus rien.
        again.pilotMemory.setEnabled(false)
        assertEquals("", again.pilotMemory.render())
        assertFalse(again.pilotMemory.remember("autre chose", null))
        again.pilotMemory.clear()
        assertTrue(RaceController(storage = storage).pilotMemory.all.isEmpty())
    }

    // 13 : reconnexion Discord.
    @Test
    fun aDiscordDropFallsBackToThePhoneAndChangesTheInterruptionMode() {
        val phoneSaid = mutableListOf<String>()
        val phone = object : VoiceOutput {
            override fun speak(message: EngineerMessage) { phoneSaid += message.text }
            override fun stop() {}
        }
        var live = true
        val remote = object : RemoteVoice {
            override val live: Boolean get() = live
            override fun speak(message: EngineerMessage, onFailed: (EngineerMessage) -> Unit) { if (!live) onFailed(message) }
            override fun stop() {}
        }
        val out = RoutedVoiceOutput(phone, remote) { 0L }
        assertEquals(DuplexMode.FULL, DuplexPolicy.mode(out.route, phoneSpeaker = true))
        live = false
        out.speak(EngineerMessage("Carburant critique.", Priority.CRITICAL, MessageCategory.ALERT, timestampMs = 0))
        assertEquals(listOf("Carburant critique."), phoneSaid, "rien n'est perdu pendant la reconnexion")
        assertEquals(DuplexMode.GAPS, DuplexPolicy.mode(out.route, phoneSpeaker = true), "sur le Fold : interruption entre les phrases")
        live = true
        assertEquals(VoiceRoute.REMOTE, out.route)
    }

    // 14 : Gemini indisponible.
    @Test
    fun withoutGeminiTheEngineerStillTalksNaturally() = runBlocking<Unit> {
        val (c, _) = live()
        val conv = RadioConversation(c) // aucun modèle configuré
        val feel = conv.say("tu le sens comment").answer()
        assertTrue(feel.isNotBlank() && "Gemini" !in feel, feel)
        assertTrue(Regex("^(On est bien\\.|Ça avance|C'est compliqué|On a une décision|Franchement)").containsMatchIn(feel), feel)
        assertEquals(
            "Non, je suis l'ingénieur virtuel de Pitwall : un programme qui lit la télémétrie de GT7. Mais ta course, je la vis à fond.",
            conv.say("t'es humain").answer(),
        )
        // Panne réseau : le moteur répond, aucune attente.
        val offline = RadioConversation(c).also {
            it.cloud = router(FakeCloud(AiBackend.FLASH) { throw java.net.UnknownHostException("hors ligne") }, FakeCloud(AiBackend.FLASH_LITE) { throw java.net.UnknownHostException("hors ligne") })
        }
        assertTrue(offline.say("au prochain arrêt je mets quoi comme pneus").answer().isNotBlank())
    }

    // 16 : alerte critique pendant une conversation ; 17 : message non urgent différé puis périmé.
    @Test
    fun criticalAlertsAlwaysPassAndDeferredMessagesExpire() {
        val pacer = RadioPacer()
        val coach = EngineerMessage("Virage 3 : remets les gaz plus tôt.", Priority.NORMAL, MessageCategory.RADIO, validUntilLap = 4)
        val alert = EngineerMessage("Carburant critique : moins d'un tour !", Priority.CRITICAL, MessageCategory.ALERT)
        assertEquals(emptyList(), pacer.pace(listOf(coach), busy = true, nowMs = 0, lap = 4))
        assertEquals(listOf(alert), pacer.pace(listOf(alert), busy = true, nowMs = 1_000, lap = 4), "jamais retenue")
        assertEquals(emptyList(), pacer.pace(emptyList(), busy = true, nowMs = 20_000, lap = 5))
        assertEquals(emptyList(), pacer.pace(emptyList(), busy = false, nowMs = 25_000, lap = 5), "périmée au tour 5 : annulée")
        assertTrue(pacer.pending.isEmpty())
    }

    // 18 : aucun chiffre inventé ; la personnalité n'ajoute ni chiffre ni fait.
    @Test
    fun noInventedNumbersAndNoHollowPhrases() = runBlocking<Unit> {
        val (c, _) = live()
        val invented = FakeCloud(AiBackend.FLASH) { AiResult.Text("Tu as 3,2 secondes d'avance sur le deuxième, garde ce rythme.") }
        val conv = RadioConversation(c).also { it.cloud = router(invented, FakeCloud(AiBackend.FLASH_LITE) { AiResult.Text("Tu as 3,2 secondes d'avance sur le deuxième.") }) }
        val said = conv.say("tu me conseilles quoi pour la suite").answer()
        assertFalse("3,2 secondes d'avance" in said, said)
        val polished = Persona.polish("Excellent choix ! Euh, je comprends parfaitement ta frustration. On garde le plan : arrêt fin du tour 7.")
        assertEquals("On garde le plan : arrêt fin du tour 7.", polished)
        assertEquals(Regex("\\d+").findAll(polished).toList().map { it.value }, listOf("7"))
        assertEquals("Le plan tient.", Persona.polish("OK. Le plan tient.", listOf("OK. On reste dehors.")), "ouverture répétée retirée")
        assertTrue("PERSONNALITÉ" in com.gt7pitwall.core.ai.EngineerPrompt.system && "Excellent choix" in Persona.prompt)
    }

    // 19 : synthèse et écoute après interruption ; latence mesurée étape par étape.
    @Test
    fun afterAnInterruptionTheNextQuestionIsAnsweredAndLatencyIsMeasured() = runBlocking<Unit> {
        val (c, _) = live()
        val conv = RadioConversation(c)
        conv.interrupt()
        assertTrue(conv.say("bon on fait quoi maintenant").answer().isNotBlank(), "la conversation reprend normalement")
        // Diffusion en phrases (haut-parleur), alertes plus directes.
        assertEquals(listOf("Je préfère prolonger d'un tour.", "La perte de rythme ne justifie pas encore l'arrêt."), SpeechStyle.sentences("Je préfère prolonger d'un tour. La perte de rythme ne justifie pas encore l'arrêt."))
        assertEquals(listOf("OK. Le plan tient : 1,5 tour de marge."), SpeechStyle.sentences("OK. Le plan tient : 1,5 tour de marge."))
        assertTrue(SpeechStyle.of(EngineerMessage("Box !", Priority.CRITICAL, MessageCategory.ALERT)).rate > 1f)
        assertTrue(SpeechStyle.of(EngineerMessage("Bien joué ! P5.", Priority.NORMAL, MessageCategory.RADIO)).pitch > 1f)
        val t = LatencyTrace(1)
        t.mark(LatencyTrace.Step.SPEECH_START, 0)
        t.mark(LatencyTrace.Step.SPEECH_END, 1_200)
        t.mark(LatencyTrace.Step.TRANSCRIBED, 1_500)
        t.mark(LatencyTrace.Step.AI, 2_700)
        t.mark(LatencyTrace.Step.SYNTHESIS, 2_710)
        t.mark(LatencyTrace.Step.AUDIBLE, 3_100)
        assertEquals(1_900L, t.totalMs)
        assertEquals(LatencyTrace.Step.AI, t.slowest!!.first)
        assertTrue("réponse de l'IA 1200 ms" in t.text(), t.text())
    }

    // Personnalité : le ton suit la course, jamais l'humeur supposée du pilote.
    @Test
    fun theMoodFollowsMeasuredRaceEvents() {
        var now = 0L
        val m = MoodTracker { now }
        m.onLap(3)
        assertEquals(RaceMood.CALM, m.mood())
        m.onPace(improving = true)
        assertEquals(RaceMood.ATTACK, m.mood())
        m.onStrategy()
        assertEquals(RaceMood.STRATEGY, m.mood())
        m.onBestLap()
        assertEquals(RaceMood.CELEBRATE, m.mood())
        now += MoodTracker.CELEBRATE_MS + 1
        m.onIncident(3)
        assertEquals(RaceMood.PRESSURE, m.mood())
        m.onLap(6)
        now += MoodTracker.STRATEGY_MS
        assertEquals(RaceMood.CALM, m.mood(), "deux tours plus tard, plus de pression ; plus de rythme en progression après l'incident")
        assertTrue("pas l'humeur du pilote" in Persona.moodLine(RaceMood.PRESSURE))
        // Actes de dialogue reconnus.
        assertEquals(DialogueActs.Act.Correction("on reste en piste"), DialogueActs.classify("Non, attends, finalement on reste en piste."))
        assertEquals(DialogueActs.Act.Feeling, DialogueActs.classify("Bon, tu le sens comment ?"))
        assertEquals(DialogueActs.Act.Retract, DialogueActs.classify("Oublie ce que je viens de dire."))
        assertEquals(DialogueActs.Act.PitNowWish, DialogueActs.classify("Je veux rentrer maintenant."))
        assertTrue(DialogueActs.classify("Et si je mets les tendres ?") is DialogueActs.Act.Hypothesis)
        assertNull(DialogueActs.classify("Combien d'essence il me reste ?"), "une question de chiffre suit son chemin habituel")
    }

    // 20 : non-régression des pneus, carburant, positions et plans d'arrêt après une conversation.
    @Test
    fun conversationNeverChangesTyresFuelOrPlanByItself() = runBlocking<Unit> {
        val (c, s) = live()
        val conv = RadioConversation(c)
        val plan = c.plan?.stops?.map { it.endOfLap }
        val perLap = c.fuel?.perLap
        for (q in listOf("tu le sens comment", "et si je mets les tendres", "je pense qu'on peut tenter un tour de plus", "pourquoi tu veux me faire rentrer", "oublie ce que je viens de dire", "non attends finalement on reste en piste")) {
            conv.say(q)
        }
        assertEquals(plan, c.plan?.stops?.map { it.endOfLap })
        assertEquals(perLap, c.fuel?.perLap)
        assertTrue(c.ledger.changes.isEmpty() && c.ledger.stopCount == 0)
        s.driveLaps(1)
        assertTrue(c.snapshot.laps.size >= 4)
    }

    // « Oublie » retire la dernière déclaration même si deux phrases tombent dans la même milliseconde (CI rapide).
    @Test
    fun retractRemovesTheLastStatementEvenAtTheSameMillisecond() {
        val m = com.gt7pitwall.core.ai.RaceMemory()
        m.preference(3, 1_000, "préserver les pneus")
        m.hypothesis(3, 1_000, "et si je mets les tendres")
        assertEquals("et si je mets les tendres", m.retractLast(1_000))
        assertEquals(1, m.preferenceList.size, "la préférence, plus ancienne, reste")
        assertEquals("préserver les pneus", m.retractLast(1_000))
        assertNull(m.retractLast(1_000))
    }
}
