package com.gt7pitwall.core

import com.gt7pitwall.core.ai.AiBackend
import com.gt7pitwall.core.ai.AiErrors
import com.gt7pitwall.core.ai.AiMode
import com.gt7pitwall.core.ai.AiProvider
import com.gt7pitwall.core.ai.AiResult
import com.gt7pitwall.core.ai.AiRouter
import com.gt7pitwall.core.ai.AiUsage
import com.gt7pitwall.core.ai.ConversationRouter
import com.gt7pitwall.core.ai.FailureKind
import com.gt7pitwall.core.ai.RadioConversation
import com.gt7pitwall.core.ai.Topic
import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.race.JournalType
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.SessionJournal
import com.gt7pitwall.core.race.StrategyDialogue
import com.gt7pitwall.core.radio.DriverQuery
import com.gt7pitwall.core.strategy.RaceRules
import com.gt7pitwall.core.strategy.TyreCompound
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Conversation avec un modèle cloud SIMULÉ (aucun appel réseau, aucun quota consommé), sur les phrases réelles de la
 * course du 21/10/2026 : départ en course durs, arrêt au tour 6, course tendres, 12 tours, carburant ×7.
 * Les réponses simulées ne servent qu'à vérifier le chemin (contexte envoyé, garde, mémoire, replis), jamais présentées
 * au pilote comme de vraies réponses de Gemini.
 */
class AiConversationTest {

    /** Fournisseur simulé : réponses scriptées, prompts enregistrés. */
    private class FakeCloud(
        override val backend: AiBackend,
        override var ready: Boolean = true,
        val script: suspend (prompt: String) -> AiResult = { AiResult.Text("Compris.") },
    ) : AiProvider {
        val prompts = mutableListOf<String>()
        override val modelId: String = backend.defaultModelId!!
        override val status: String get() = if (ready) "simulé (test)" else "non configuré"
        override suspend fun complete(systemInstruction: String, prompt: String): AiResult {
            prompts += prompt
            return script(prompt)
        }
    }

    private val rules = RaceRules(
        totalLaps = 12, fuelMultiplier = 7, mandatoryStops = 1,
        allowedTyres = setOf(TyreCompound.RH, TyreCompound.RM, TyreCompound.RS), startTyre = TyreCompound.RH,
    )

    private class Setup(val store: FakeStorage, val sim: RaceSim, val conv: RadioConversation, val lite: FakeCloud, val flash: FakeCloud, val router: AiRouter, val mutex: Mutex)

    private fun setup(
        lite: FakeCloud = FakeCloud(AiBackend.FLASH_LITE),
        flash: FakeCloud = FakeCloud(AiBackend.FLASH),
        mode: AiMode = AiMode.AUTO,
        timeoutMs: Long = 12_000L,
    ): Setup {
        val store = FakeStorage(rules = rules)
        val sim = RaceSim(RaceController(storage = store), totalLaps = 12, perLap = 14f)
        val mutex = Mutex()
        val router = AiRouter(mapOf(AiBackend.FLASH_LITE to lite, AiBackend.FLASH to flash), AiUsage(), mode, totalTimeoutMs = timeoutMs)
        val conv = RadioConversation(sim.controller, mutex).also { it.cloud = router }
        return Setup(store, sim, conv, lite, flash, router, mutex)
    }

    private suspend fun Setup.say(text: String, free: String? = text): List<EngineerMessage> =
        conv.onUtterance(text, free, model = null).also { sim.said += it }

    private fun List<EngineerMessage>.answer(): EngineerMessage = last { it.category == MessageCategory.ANSWER }

    private val calls: Setup.() -> Int = { lite.prompts.size + flash.prompts.size }

    // 1. Choix des pneus : la question réelle du tour 5 atteint le modèle, avec le calcul du moteur sur les pneus.
    @Test
    fun tyreChoiceReachesTheModelWithTheEngineTyreAnalysis() = runBlocking<Unit> {
        val reply = "Pour le dernier relais, je partirais sur des course tendres : ce sont les plus rapides du règlement. " +
            "GT7 ne me donne pas l'usure, alors combien de tours tiennent-ils d'après toi ?"
        val s = setup(flash = FakeCloud(AiBackend.FLASH) { AiResult.Text(reply) })
        s.sim.start()
        s.sim.driveLaps(4)
        val analysis = s.sim.controller.engineAnalysis(ConversationRouter.Turn("x", Topic.TYRES, true, query = DriverQuery.NEXT_TYRE))!!
        val out = s.say("tu conseil deux mettre quoi gommes pneus prochain stands", "tu me conseille de mettre quoi comme pneus au prochain stand")
        assertEquals(1, s.flash.prompts.size, "question complexe : Flash 3.8 en mode automatique")
        val prompt = s.flash.prompts.single()
        assertTrue("SUJET EN COURS : pneus" in prompt)
        assertTrue(analysis in prompt, "le calcul du moteur accompagne la question")
        assertTrue("CONTEXTE : course suivie en direct" in prompt)
        val a = out.answer()
        assertEquals(reply, a.text)
        assertTrue(a.fromAi)
        assertTrue(s.sim.controller.ledger.changes.isEmpty(), "un conseil ne modifie rien")
        assertEquals(AiBackend.FLASH, s.conv.lastTrace!!.backend)
        assertEquals("gemini-3.8-flash", s.conv.lastTrace!!.modelId)
    }

    // 2 et 3. « Pourquoi ? », « et si je garde les durs deux tours de plus ? », « bon, on fait quoi alors ? » : le sujet,
    // le calcul et les échanges précédents suivent ; « d'accord, on fait comme ça » devient une décision commune.
    @Test
    fun followUpsKeepTheSubjectThenAgreementBecomesADecision() = runBlocking<Unit> {
        val answers = ArrayDeque(
            listOf(
                "Je partirais sur des course tendres au prochain arrêt, ce sont les plus rapides du règlement.",
                "Parce que tu n'as pas de contrainte de gomme imposée et que les tendres sont les plus rapides du règlement.",
                "À mon avis non : le moteur dit que l'autonomie ne suffit pas.",
                "On garde le plan du moteur et on monte les course tendres.",
            ),
        )
        val s = setup(flash = FakeCloud(AiBackend.FLASH) { AiResult.Text(answers.removeFirst()) })
        s.sim.start()
        s.sim.driveLaps(2)
        s.say("tu me conseilles quels pneus")
        s.say("pourquoi")
        val second = s.flash.prompts[1]
        assertTrue("SUJET EN COURS : pneus" in second, second)
        assertTrue("pilote : tu me conseilles quels pneus" in second, "l'échange précédent est fourni")
        assertTrue("CALCUL DU MOTEUR SUR CETTE QUESTION" in second, "la relance garde le calcul du moteur")

        s.say("et si je garde les durs deux tours de plus")
        val third = s.flash.prompts[2]
        assertTrue("Prolonger de 2 tours" in third, "le scénario est calculé par le moteur, pas par l'IA")

        val out = s.say("bon on fait quoi alors")
        val fourth = s.flash.prompts[3]
        assertTrue("pilote : pourquoi" in fourth && "pilote : et si je garde les durs deux tours de plus" in fourth)
        assertEquals("On garde le plan du moteur et on monte les course tendres.", out.answer().text)

        val before = s.calls()
        val ok = s.say("d'accord on fait comme ca")
        assertEquals(before, s.calls(), "une validation ne consomme aucune requête")
        assertTrue(ok.answer().text.startsWith("C'est noté, on part là-dessus"))
        val m = s.sim.controller.raceMemory
        assertEquals(1, m.decisionList.size)
        assertNotNull(m.agreedPlan)
        val id = s.store.journals.keys.single()
        assertTrue(SessionJournal.load(s.store, id).any { it.type == JournalType.DECISION && it.text.startsWith("Décision prise ensemble") })

        // « On garde la même stratégie ? » (phrase réelle du tour 6) : le modèle reçoit la décision prise ensemble.
        s.say("est ce combien la memes strategie", "est ce qu'on garde la meme strategie")
        val fifth = s.flash.prompts[4]
        assertTrue("DÉCISIONS PRISES ENSEMBLE" in fifth && "plan validé" in fifth, fifth)
    }

    // 3 bis. Sans modèle, « on garde la même stratégie ? » répond quand même à partir de la mémoire.
    @Test
    fun samePlanIsAnsweredFromMemoryWithoutAnyModel() = runBlocking<Unit> {
        val s = setup(lite = FakeCloud(AiBackend.FLASH_LITE, ready = false), flash = FakeCloud(AiBackend.FLASH, ready = false))
        s.sim.start()
        s.sim.driveLaps(2)
        s.say("tu me conseilles quels pneus")
        s.say("d'accord on fait comme ca")
        val out = s.say("est ce combien la memes strategie", "est ce qu'on garde la meme strategie")
        assertTrue(out.answer().text.startsWith("Oui, on reste sur ce qu'on a décidé au tour"), out.answer().text)
        assertEquals(0, s.calls())
        assertEquals(StrategyDialogue.Ask.SamePlan, StrategyDialogue.classify("est ce qu'on garde la meme strategie"))
        assertEquals(StrategyDialogue.Ask.Recall, StrategyDialogue.classify("on avait decide quoi tout a l'heure"))
    }

    // 4. Déclaration ambiguë : la commande proposée par le modèle passe par le garde, rien n'est modifié sans « oui ».
    @Test
    fun aCommandFromTheModelIsVettedAndNeverAppliedSilently() = runBlocking<Unit> {
        val s = setup(lite = FakeCloud(AiBackend.FLASH_LITE) { AiResult.Text("COMMANDE: j'ai mis les tendres") })
        s.sim.start()
        s.sim.driveLaps(5)
        val out = s.say("bon voila c'est reparti tout va bien de mon cote")
        assertTrue(s.sim.controller.ledger.changes.isEmpty(), "aucune gomme enregistrée sans confirmation")
        // Le modèle ne fait que proposer : le garde demande une précision ou une confirmation au pilote.
        assertTrue(out.answer().expectsReply, out.map { it.text }.toString())
        // Et une proposition à propos des autres voitures n'est jamais enregistrée non plus.
        s.say("je crois que c'est bon on est reparti avec les autres")
        assertTrue(s.sim.controller.ledger.changes.isEmpty())
    }

    // 5. Modèle cloud non configuré : aucun appel, le moteur répond, la raison est visible.
    @Test
    fun withoutFirebaseTheEngineAnswersAndNothingIsSent() = runBlocking<Unit> {
        val s = setup(lite = FakeCloud(AiBackend.FLASH_LITE, ready = false), flash = FakeCloud(AiBackend.FLASH, ready = false))
        s.sim.start()
        s.sim.driveLaps(3)
        val out = s.say("au prochain arret je mets quoi comme type de pneus")
        assertEquals(0, s.calls())
        assertEquals(s.sim.controller.nextTyreSentence(), out.answer().text)
        val trace = s.conv.lastTrace!!
        assertTrue(trace.fallback && trace.backend == AiBackend.ENGINE && "non configuré" in trace.verdict, trace.verdict)
    }

    // 6. Quota (429) : Flash 3.8 refuse, Flash-Lite répond ; Flash est mis en pause ; tout refusé → calcul du moteur.
    @Test
    fun quotaErrorsFallBackCleanly() = runBlocking<Unit> {
        var liteQuota = false
        val s = setup(
            flash = FakeCloud(AiBackend.FLASH) {
                AiErrors.classify("com.google.firebase.ai.type.QuotaExceededException", "Quota exceeded for metric: generate_content_free_tier_requests, limit: 20 per day")
            },
            lite = FakeCloud(AiBackend.FLASH_LITE) {
                if (liteQuota) AiErrors.classify("ServerException", "429 RESOURCE_EXHAUSTED") else AiResult.Text("On reste sur le plan du moteur.")
            },
        )
        s.sim.start()
        s.sim.driveLaps(3)
        val first = s.say("quelle est notre meilleure strategie")
        assertEquals("On reste sur le plan du moteur.", first.answer().text)
        assertEquals(1, s.router.usage.stats(AiBackend.FLASH).requestsToday)
        assertEquals(1, s.router.usage.stats(AiBackend.FLASH_LITE).requestsToday)
        assertFalse(s.router.usage.canSend(AiBackend.FLASH), "quota journalier : Flash en pause jusqu'au lendemain (heure du Pacifique)")
        assertTrue(s.router.usage.stats(AiBackend.FLASH).pausedUntilMs!! >= AiUsage.nextPacificMidnight(System.currentTimeMillis()) - 1)

        s.say("pourquoi")
        assertEquals(1, s.flash.prompts.size, "Flash en pause : plus aucune requête envoyée")

        liteQuota = true
        val out = s.say("et si on decale l'arret de deux tours")
        assertTrue(out.answer().text.startsWith("Prolonger de 2 tours"), "calcul du moteur dit tel quel : ${out.answer().text}")
        assertFalse(out.answer().fromAi)
        assertTrue(out.any { it.category == MessageCategory.DRIVER && "quota atteint" in it.text })
        assertTrue(s.conv.lastTrace!!.fallback)
    }

    // 7. Connexion perdue : erreur réseau ou délai dépassé → moteur local ; la télémétrie n'attend jamais le modèle.
    @Test
    fun aLostConnectionNeverBlocksTelemetry() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val s = setup(lite = FakeCloud(AiBackend.FLASH_LITE) { gate.await(); AiResult.Text("trop tard") }, timeoutMs = 3_000L)
        s.sim.start()
        s.sim.driveLaps(2)
        val lap = s.sim.lap
        val pending = async { s.conv.onUtterance("explique moi comment je peux progresser", "explique moi comment je peux progresser", model = null) }
        yield()
        // Pendant que le modèle « réfléchit », les paquets continuent d'être traités sous le même verrou.
        s.mutex.withLock { s.sim.driveLaps(1) }
        assertEquals(lap + 1, s.sim.lap)
        val out = pending.await()
        assertEquals(FailureKind.TIMEOUT, s.conv.lastTrace!!.attempts.single().failure!!.kind)
        assertTrue(out.isNotEmpty())

        val offline = setup(
            lite = FakeCloud(AiBackend.FLASH_LITE) { throw java.net.UnknownHostException("Unable to resolve host \"firebasevertexai.googleapis.com\"") },
            flash = FakeCloud(AiBackend.FLASH) { throw java.net.UnknownHostException("Unable to resolve host") },
        )
        offline.sim.start()
        offline.sim.driveLaps(3)
        val r = offline.say("tu me conseilles quels pneus")
        assertEquals(offline.sim.controller.nextTyreSentence(), r.answer().text)
        assertEquals(1, offline.calls(), "échec réseau (lent) : pas de second essai qui ferait attendre le pilote")
        assertEquals(FailureKind.NETWORK, offline.conv.lastTrace!!.attempts.single().failure!!.kind)
    }

    // 8. Pas de requête gaspillée : paquets UDP, questions de chiffre simples, test radio → moteur local, aucune requête.
    // Une réponse qui invente un écart avec les autres est écartée au profit du calcul du moteur.
    @Test
    fun noRequestIsWastedAndInventedDataIsRejected() = runBlocking<Unit> {
        val s = setup(flash = FakeCloud(AiBackend.FLASH) { AiResult.Text("Tu as 3 secondes d'avance sur le deuxième, garde ce rythme.") })
        s.sim.start()
        s.sim.driveLaps(4)
        s.say("radio check")
        s.say("combien d'essence")
        s.say("comment sont pneus", "comment sont les pneus")
        s.say("quel est mon dernier tour")
        assertEquals(0, s.calls(), "aucune requête cloud pour les paquets et les questions simples")

        val out = s.say("quelle est notre meilleure strategie")
        assertEquals(1, s.flash.prompts.size)
        assertFalse(out.answer().fromAi, "écart inventé : réponse écartée")
        assertFalse(Regex("(^|\\W)avance(\\W|$)").containsMatchIn(out.answer().text), out.answer().text)
    }

    // Aiguillage des phrases du pilote (sans aucun appel).
    @Test
    fun routingOfTheDriversRealAndExamplePhrases() {
        fun d(t: String, conv: Boolean = false, topic: Topic? = null) = ConversationRouter.decide(t, t, conv, topic, engineAwaitsAnswer = false)
        assertEquals(Topic.TYRES, d("tu me conseilles quels pneus pour le dernier relais")!!.topic)
        assertTrue(d("pourquoi tu veux me faire rentrer maintenant")!!.complex)
        assertEquals(StrategyDialogue.Ask.Extend(2), d("et si on decale l'arret de deux tours")!!.ask)
        assertEquals(StrategyDialogue.Ask.CompareStints, d("tu trouves que je pilote mieux depuis le changement de pneus")!!.ask)
        assertEquals(StrategyDialogue.Ask.Recall, d("on avait decide quoi tout a l'heure")!!.ask)
        assertEquals(StrategyDialogue.Ask.AttackRisk, d("explique moi ce qu'on risque si j'attaque davantage")!!.ask)
        assertEquals(StrategyDialogue.Ask.SamePlan, d("on reste sur notre plan")!!.ask)
        val why = d("pourquoi", conv = true, topic = Topic.TYRES)!!
        assertTrue(why.followUp && why.topic == Topic.TYRES)
        // Commandes, réponses attendues, questions simples, radio : le moteur.
        assertNull(d("j'ai fait mon arret"))
        assertNull(d("combien d'essence"))
        assertNull(d("radio check"))
        assertNull(ConversationRouter.decide("oui", "oui", true, Topic.PLAN, engineAwaitsAnswer = true))
    }

    @Test
    fun errorsAreClassifiedAndCountersFollowThePacificDay() {
        assertEquals(FailureKind.QUOTA, AiErrors.classify("QuotaExceededException", "Resource has been exhausted (e.g. check quota).").kind)
        assertTrue(AiErrors.classify("ServerException", "429: limit 20 per day").daily)
        assertEquals(FailureKind.OVERLOADED, AiErrors.classify("ServerException", "503 The model is overloaded. Please try again later.").kind)
        assertEquals(FailureKind.NETWORK, AiErrors.classify("java.net.UnknownHostException", "Unable to resolve host").kind)
        assertEquals(FailureKind.AUTH, AiErrors.classify("FirebaseAIException", "Firebase App Check token is invalid").kind)
        assertEquals(FailureKind.MODEL_UNAVAILABLE, AiErrors.classify("ServerException", "404 models/gemini-x is not found").kind)
        assertFalse("AIzaSy" in AiErrors.classify("X", "API key AIzaSyA1234567890abcdef not valid").detail!!)

        var now = ZonedDateTime.of(2026, 10, 21, 23, 0, 0, 0, AiUsage.PACIFIC).toInstant().toEpochMilli()
        val u = AiUsage { now }
        u.onSent(AiBackend.FLASH)
        u.dailyCaps[AiBackend.FLASH] = 1
        assertFalse(u.canSend(AiBackend.FLASH), "plafond saisi par le pilote atteint")
        u.onFailure(AiBackend.FLASH_LITE, AiResult.Failure(FailureKind.OVERLOADED), 100)
        assertFalse(u.canSend(AiBackend.FLASH_LITE))
        now += 31_000
        assertTrue(u.canSend(AiBackend.FLASH_LITE), "503 : courte pause seulement")
        now += 3_600_000 // minuit passé, heure du Pacifique
        assertEquals(0, u.stats(AiBackend.FLASH).requestsToday)
        assertTrue(u.canSend(AiBackend.FLASH))
        val copy = AiUsage { now }.also { it.restore(u.save()) }
        assertEquals(u.stats(AiBackend.FLASH_LITE).failuresToday, copy.stats(AiBackend.FLASH_LITE).failuresToday)
    }

    @Test
    fun theDriversModeChoiceDecidesTheModels() {
        assertEquals(listOf(AiBackend.FLASH, AiBackend.FLASH_LITE), AiMode.AUTO.cloudOrder(complex = true))
        assertEquals(listOf(AiBackend.FLASH_LITE), AiMode.AUTO.cloudOrder(complex = false))
        assertEquals(listOf(AiBackend.FLASH), AiMode.FLASH.cloudOrder(complex = false))
        assertTrue(AiMode.NANO.cloudOrder(true).isEmpty() && AiMode.LOCAL.cloudOrder(true).isEmpty())
    }
}
