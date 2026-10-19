package com.gt7pitwall.core

import com.gt7pitwall.core.ai.DrivingPrinciples
import com.gt7pitwall.core.ai.Intent
import com.gt7pitwall.core.ai.IntentClassifier
import com.gt7pitwall.core.ai.LanguageModel
import com.gt7pitwall.core.ai.LocalReplies
import com.gt7pitwall.core.ai.RadioAi
import com.gt7pitwall.core.ai.RadioConversation
import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.radio.DriverCommand
import com.gt7pitwall.core.radio.DriverQuery
import com.gt7pitwall.core.radio.RemoteVoice
import com.gt7pitwall.core.radio.RoutedVoiceOutput
import com.gt7pitwall.core.radio.VoiceOutput
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Comprendre le pilote avant de le conseiller. Retour d'essai réel : à « Est-ce que tu comprends ce que je dis ? »,
 * Gemini répondait par un conseil de freinage, parce que le prompt lui fournissait toujours les principes de freinage,
 * de point de corde et de sortie, quel que soit le sujet.
 */
class ConversationIntentTest {

    private class ScriptedModel(vararg replies: String) : LanguageModel {
        private val queue = ArrayDeque(replies.toList())
        val prompts = mutableListOf<String>()
        override val ready = true
        override val status = "prêt"
        override suspend fun generate(systemInstruction: String, prompt: String): String? {
            prompts += prompt
            return queue.removeFirstOrNull()
        }
    }

    private class Clock(var now: Long = 1_000_000L)

    private fun say(conv: RadioConversation, model: LanguageModel?, text: String): List<EngineerMessage> =
        runBlocking { conv.onUtterance(text, text, model) }

    private fun answers(out: List<EngineerMessage>) = out.filter { it.category != MessageCategory.DRIVER }

    private val brakingAdvice = Regex("(?i)frein|corde|accél|trajectoire")

    // ------------------------------------------------------------------ Communication radio

    @Test
    fun understandingCheckIsARadioConfirmationNotBrakingAdvice() {
        val c = RaceController()
        val conv = RadioConversation(c)
        val model = ScriptedModel("Je comprends. Pour être plus rapide, concentre-toi sur l'application du freinage.")
        val out = answers(say(conv, model, "Est-ce que tu comprends ce que je dis ?"))
        assertEquals(1, out.size, out.toString())
        val reply = out.single().text
        assertEquals("Affirmatif, je te reçois cinq sur cinq ! Pas de télémétrie pour le moment, mais je suis prêt.", reply)
        assertFalse(brakingAdvice.containsMatchIn(reply), reply)
        assertTrue(model.prompts.isEmpty(), "réponse locale immédiate, sans Gemini")
    }

    @Test
    fun radioChecksAreAnsweredLocallyEvenWithoutGemini() {
        val conv = RadioConversation(RaceController())
        for (s in listOf("tu m'entends ?", "test radio", "tu me reçois bien ?", "est-ce que ça marche ?", "un deux un deux test micro")) {
            val out = answers(say(conv, null, s))
            assertTrue(out.single().text.startsWith("Affirmatif, je te reçois cinq sur cinq"), "$s → $out")
        }
    }

    @Test
    fun areYouThereGetsAShortAnswer() {
        val model = ScriptedModel()
        val conv = RadioConversation(RaceController())
        val reply = answers(say(conv, model, "Tu es là ?")).single().text
        assertTrue(reply.startsWith("Toujours là"), reply)
        assertTrue(reply.split(' ').size <= 12, "réponse courte : $reply")
        assertTrue(model.prompts.isEmpty())
    }

    @Test
    fun simpleConversationIsAnsweredNaturally() {
        val model = ScriptedModel()
        val conv = RadioConversation(RaceController())
        assertTrue(answers(say(conv, model, "salut")).single().text.startsWith("Salut"))
        assertEquals("Avec plaisir.", answers(say(conv, model, "merci")).single().text)
        assertEquals("Parfait, on continue.", answers(say(conv, model, "reçu")).single().text)
        val caps = answers(say(conv, model, "qu'est-ce que tu sais faire ?")).single().text
        assertTrue("essence" in caps && "Pas de télémétrie" in caps, caps)
        assertTrue(model.prompts.isEmpty(), "aucun appel inutile à Gemini")
    }

    // ------------------------------------------------------------------ Coaching

    @Test
    fun brakingQuestionGetsRelevantAdvice() {
        val c = RaceController()
        val conv = RadioConversation(c)
        val model = ScriptedModel(
            "Freine fort roues droites, puis relâche la pédale progressivement en tournant jusqu'au point de corde : " +
                "l'avant reste chargé et la voiture tourne mieux.",
        )
        val out = answers(say(conv, model, "Comment améliorer mon freinage ?"))
        assertTrue(out.single().fromAi && "relâche la pédale" in out.single().text, out.toString())
        val prompt = model.prompts.single()
        assertTrue("SUJET : pilotage" in prompt, prompt)
        assertTrue("Freinage :" in prompt, "principe du freinage fourni")
        assertTrue("aucune télémétrie GT7" in prompt, "contexte réel : pas de course suivie")
    }

    @Test
    fun understeerProblemIsADrivingQuestionNotARadioCheck() {
        assertEquals(Intent.COACHING, IntentClassifier.classify("Tu comprends mon problème de sous-virage ?"))
        val model = ScriptedModel(
            "Oui. Le sous-virage vient souvent d'une entrée trop rapide : freine un peu plus tôt et garde un filet de frein " +
                "en entrée pour charger l'avant. C'est dans quel virage ?",
        )
        val conv = RadioConversation(RaceController())
        val out = answers(say(conv, model, "Tu comprends mon problème de sous-virage ?"))
        assertTrue(out.single().fromAi && "sous-virage" in out.single().text, out.toString())
        assertFalse("cinq sur cinq" in out.single().text)
        val prompt = model.prompts.single()
        assertTrue("Sous-virage" in prompt && "SUJET : pilotage" in prompt, prompt)
    }

    // ------------------------------------------------------------------ Sans télémétrie : rien d'inventé

    @Test
    fun withoutTelemetryNothingIsInventedAboutTheRace() {
        val c = RaceController()
        val model = ScriptedModel("Tu es P3 et tu perds 2 secondes par tour.")
        val conv = RadioConversation(c)
        assertFalse(c.telemetryLive())

        val r1 = answers(say(conv, model, "Que sais-tu de ma course ?"))
        assertFalse(r1.any { Regex("\\d").containsMatchIn(it.text) }, "aucun chiffre inventé : $r1")
        assertTrue(r1.single().text.contains("télémétrie"), r1.toString())

        // Demande connue de l'analyseur : sa réponse déterministe, sans Gemini.
        val r2 = answers(say(conv, model, "c'est quoi la meilleure stratégie pour cette course ?"))
        assertTrue(r2.single().text.startsWith("Je n'ai pas de plan"), r2.toString())
        // Question de stratégie libre : la réponse honnête « pas de données ».
        val r3 = answers(say(conv, model, "tu penses qu'il y aura beaucoup de trafic ?"))
        assertEquals(LocalReplies.NO_RACE_DATA, r3.single().text)
        assertTrue(model.prompts.isEmpty(), "sans données, Gemini n'a rien à calculer")
        assertFalse((r1 + r2 + r3).any { "P3" in it.text })
    }

    @Test
    fun telemetryContextComesFromRealPacketsNotFromAnOpenApp() {
        var now = 0L
        val c = RaceController(clock = { now })
        assertFalse(c.telemetryLive(), "application ouverte, aucun paquet")
        RaceSim(c, totalLaps = 5, perLap = 2f).start()
        assertTrue(c.telemetryLive(), "paquets GT7 reçus, voiture en piste")
        now += 10_000
        assertFalse(c.telemetryLive(), "plus de paquets : l'ancien paquet en mémoire ne compte pas")
        val reply = answers(say(RadioConversation(c), null, "tu m'entends ?")).single().text
        assertTrue("Pas de télémétrie" in reply, reply)
    }

    // ------------------------------------------------------------------ Stratégie avec de vraies données

    @Test
    fun strategyQuestionWithRealDataUsesTheExistingCalculations() {
        val c = RaceController()
        val sim = RaceSim(c, totalLaps = 10, perLap = 2f)
        sim.start()
        sim.driveLaps(3)
        assertTrue(c.telemetryLive())
        val model = ScriptedModel()
        val conv = RadioConversation(c)
        val fuel = answers(say(conv, model, "combien d'essence"))
        val expected = c.engineer.answer(DriverQuery.FUEL, c.snapshot, c.fuel).text
        assertEquals(expected, fuel.single().text)
        assertTrue(model.prompts.isEmpty(), "le calcul reste au moteur de Pitwall")

        // Question de stratégie libre : Gemini reçoit les calculs dans les FAITS, sans principes de pilotage.
        val free = ScriptedModel("Avec ta consommation, l'arrêt prévu tient toujours.")
        say(RadioConversation(c), free, "tu penses que ma stratégie d'arrêt tient la route ?")
        val prompt = free.prompts.single()
        assertTrue("SUJET : stratégie" in prompt && "Carburant" in prompt, prompt)
        assertFalse("PRINCIPES DE PILOTAGE" in prompt, "pas de conseil de pilotage pour une question de stratégie")
        assertTrue("télémétrie GT7 active" in prompt)
    }

    // ------------------------------------------------------------------ Continuité de la conversation

    @Test
    fun aFollowUpKeepsTheSubject() {
        val clock = Clock()
        val c = RaceController()
        val conv = RadioConversation(c, clock = { clock.now })
        val model = ScriptedModel(
            "Freine fort roues droites, puis relâche progressivement en tournant jusqu'au point de corde.",
            "En sortie, attends que la voiture ait tourné et ouvre le volant avant de remettre les gaz progressivement.",
            "Parce que l'arrière est allégé tant que tu braques : trop de gaz trop tôt et il décroche.",
        )
        say(conv, model, "comment je peux mieux freiner")
        clock.now += 5_000
        say(conv, model, "et en sortie de virage ?")
        assertTrue("ÉCHANGES PRÉCÉDENTS" in model.prompts[1] && "mieux freiner" in model.prompts[1], model.prompts[1])
        clock.now += 5_000
        val r3 = answers(say(conv, model, "pourquoi ?"))
        assertTrue(r3.single().fromAi, r3.toString())
        val p3 = model.prompts[2]
        assertTrue("SUJET : pilotage" in p3 && "PRINCIPES DE PILOTAGE" in p3, "la relance garde le sujet pilotage")
        assertTrue("en sortie de virage" in p3, "et le fil de la conversation")
    }

    @Test
    fun unrelatedQuestionsDoNotRepeatAnOldAdvice() {
        val clock = Clock()
        val c = RaceController()
        val conv = RadioConversation(c, clock = { clock.now })
        val braking = "Freine fort roues droites, puis relâche la pédale progressivement en tournant jusqu'au point de corde."
        val model = ScriptedModel(
            braking,
            // Gemini ressort l'ancien conseil à une question sans rapport : il est écarté.
            braking,
            "Une GT3 se pilote avec beaucoup d'appui : elle aime les gestes précis. Tu en as une préférée ?",
        )
        say(conv, model, "comment améliorer mon freinage ?")

        clock.now += 5_000
        val r2 = answers(say(conv, model, "tu as un avis sur les voitures de la catégorie GT3 ?"))
        val p2 = model.prompts[1]
        assertFalse("ÉCHANGES PRÉCÉDENTS" in p2, "nouveau sujet : l'ancien échange n'oriente pas la réponse")
        assertFalse("PRINCIPES DE PILOTAGE" in p2 || "Freinage :" in p2, p2)
        assertFalse(r2.any { "relâche la pédale" in it.text }, "le conseil n'est pas répété : $r2")
        assertEquals("refusée : conseil déjà donné", conv.lastTrace?.verdict)
        assertTrue(r2.single().expectsReply, "il demande ce que le pilote veut vraiment")

        clock.now += 5_000
        val r3 = answers(say(conv, model, "tu as un avis sur les voitures de la catégorie GT3 ?"))
        assertTrue(r3.single().fromAi && "GT3" in r3.single().text, r3.toString())
    }

    // ------------------------------------------------------------------ Cause : principes ajoutés par défaut

    @Test
    fun drivingPrinciplesAreOnlyGivenForDrivingQuestions() {
        val facts = RaceController().aiFacts()
        for (intent in listOf(Intent.OTHER, Intent.STRATEGY)) {
            val p = RadioAi.prompt("est-ce que tu comprends ce que je dis", facts, emptyList(), intent, telemetryLive = false)
            assertFalse("PRINCIPES DE PILOTAGE" in p || "Freinage :" in p || "Point de corde :" in p, "$intent : $p")
        }
        val coaching = RadioAi.prompt("comment être plus rapide", facts, emptyList(), Intent.COACHING, telemetryLive = false)
        assertTrue("Freinage :" in coaching, "question de pilotage vague : les fondamentaux restent utiles")
        assertTrue(DrivingPrinciples.Topic.UNDERSTEER in DrivingPrinciples.select("l'avant glisse", emptyList(), emptySet()))
    }

    @Test
    fun intentsAreRecognisedWithoutKeywordsHijackingSpecificQuestions() {
        val expected = mapOf(
            "Est-ce que tu comprends ce que je dis ?" to Intent.RADIO_CHECK,
            "tu m'entends bien" to Intent.RADIO_CHECK,
            "radio check" to Intent.RADIO_CHECK,
            "t'es toujours là ?" to Intent.PRESENCE,
            "bonjour" to Intent.GREETING,
            "merci beaucoup" to Intent.THANKS,
            "comment tu fonctionnes ?" to Intent.CAPABILITIES,
            "comment améliorer mon freinage" to Intent.COACHING,
            "tu comprends mon problème de sous-virage" to Intent.COACHING,
            "tu comprends pourquoi je perds du temps" to Intent.COACHING,
            "tu m'entends quand je freine trop tard ?" to Intent.COACHING,
            "il me reste combien de tours d'essence" to Intent.STRATEGY,
            "est-ce que je dois changer mes pneus" to Intent.STRATEGY,
            "que sais-tu de ma course" to Intent.STRATEGY,
            "tu préfères les voitures japonaises ?" to Intent.OTHER,
        )
        for ((s, intent) in expected) assertEquals(intent, IntentClassifier.classify(s), s)
    }

    // ------------------------------------------------------------------ Questions sur l'aide de l'ingénieur

    @Test
    fun askingHowTheEngineerWillHelpIsNotAnAskForTheCurrentValue() {
        // Essai réel : « si je suis en galère d'essence tu vas m'aider » recevait « Je n'ai pas encore de télémétrie. »
        val model = ScriptedModel()
        val conv = RadioConversation(RaceController())
        val fuel = answers(say(conv, model, "si je suis en galère d'essence tu vas m'aider"))
        assertTrue(fuel.single().text.startsWith("Oui, compte sur moi") && "consommation" in fuel.single().text, fuel.toString())
        assertTrue(fuel.single().text.endsWith(LocalReplies.NO_TELEMETRY))
        assertFalse(Regex("\\d").containsMatchIn(fuel.single().text), "aucune donnée inventée")

        val race = answers(say(conv, model, "pendant les courses tu vas m'aider comment"))
        assertTrue(race.single().text.startsWith("Pendant la course"), race.toString())
        val tyres = answers(say(conv, model, "tu pourras me prévenir pour les pneus ?"))
        assertTrue("pneus" in tyres.single().text && "changer" in tyres.single().text, tyres.toString())
        assertTrue(model.prompts.isEmpty())
    }

    @Test
    fun inARaceTheHelpAnswerIsFollowedByTheRealCalculation() {
        val c = RaceController()
        RaceSim(c, totalLaps = 10, perLap = 2f).apply { start(); driveLaps(3) }
        val out = answers(say(RadioConversation(c), null, "si je suis en galère d'essence tu vas m'aider"))
        assertEquals(2, out.size, out.toString())
        assertFalse(LocalReplies.NO_TELEMETRY in out[0].text)
        assertEquals(c.engineer.answer(DriverQuery.FUEL, c.snapshot, c.fuel).text, out[1].text)
    }

    @Test
    fun helpQuestionsDoNotHijackRealRequests() {
        // Demande immédiate : le calcul.
        assertIs<RadioAi.Route.Parser>(RadioAi.route("combien d'essence", "tu peux me dire combien d'essence il reste"))
        // Demande de conseil : du coaching, pour Gemini.
        assertEquals(null, IntentClassifier.helpTopic("tu peux m'aider à mieux freiner ?"))
        assertIs<RadioAi.Route.Ai>(RadioAi.route("tu peux m'aider à mieux freiner", "tu peux m'aider à mieux freiner"))
        // Déclaration : une commande, même avec une demande d'aide.
        assertIs<RadioAi.Route.Parser>(RadioAi.route("je suis cinquième", "je suis cinquième"))
        assertEquals(com.gt7pitwall.core.ai.HelpTopic.STOPS, IntentClassifier.helpTopic("tu vas m'aider pour les arrêts au stand ?"))
    }

    // ------------------------------------------------------------------ Non-régression : commandes et radio Discord

    @Test
    fun voiceCommandsStillGoToTheParser() {
        val commands = listOf(
            "je suis cinquième", "arrêt fait", "il pleut", "silence", "répète", "course de 12 tours",
            "combien d'essence", "j'ai changé les pneus", "annule l'arrêt", "tu peux parler",
        )
        for (s in commands) {
            assertIs<RadioAi.Route.Parser>(RadioAi.route(s, s, telemetryLive = false), s)
            assertIs<RadioAi.Route.Parser>(RadioAi.route(s, s, inConversation = true), s)
        }
        // « oui » répond à l'ingénieur quand il attend une réponse, même en pleine conversation.
        assertIs<RadioAi.Route.Parser>(RadioAi.route("oui", "oui", inConversation = true, engineerAwaitsAnswer = true))
        // Un fragment reste ignoré ; « tu es là » n'en est plus un.
        assertEquals(RadioAi.Route.Ignore, RadioAi.route("et", "et", inConversation = true))
        assertIs<RadioAi.Route.Local>(RadioAi.route("tu es là", "tu es là"))

        val c = RaceController()
        val conv = RadioConversation(c)
        say(conv, ScriptedModel(), "je suis cinquième")
        assertEquals(5, c.engineer.driverPosition)
        assertEquals(DriverCommand.ReportPosition(5), com.gt7pitwall.core.radio.DriverCommandParser.parse("je suis cinquième"))
    }

    @Test
    fun localRepliesTakeTheDiscordRadioLikeAnyEngineerMessage() {
        val phone = object : VoiceOutput {
            val said = mutableListOf<String>()
            override fun speak(message: EngineerMessage) { said += message.text }
            override fun stop() = Unit
        }
        val discord = object : RemoteVoice {
            val said = mutableListOf<String>()
            override val live = true
            override fun speak(message: EngineerMessage, onFailed: (EngineerMessage) -> Unit) { said += message.text }
            override fun stop() = Unit
        }
        val out = RoutedVoiceOutput(phone, discord)
        val conv = RadioConversation(RaceController())
        answers(say(conv, null, "tu m'entends ?")).forEach(out::speak)
        assertEquals(1, discord.said.size, "réponse dite dans le casque PS5")
        assertTrue(phone.said.isEmpty(), "pas de double voix")
    }
}
