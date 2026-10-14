package com.gt7pitwall.core

import com.gt7pitwall.core.ai.AdviceCheck
import com.gt7pitwall.core.ai.AiFacts
import com.gt7pitwall.core.ai.AiReply
import com.gt7pitwall.core.ai.CoachingObservations
import com.gt7pitwall.core.ai.DrivingPrinciples
import com.gt7pitwall.core.ai.Evidence
import com.gt7pitwall.core.ai.LanguageModel
import com.gt7pitwall.core.ai.RadioAi
import com.gt7pitwall.core.ai.RadioConversation
import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.session.LapRecord
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ingénieur IA : fragments ignorés, conseils de pilotage vérifiés (absurdités, banalités, défauts non prouvés),
 * constats tirés de la télémétrie, et dialogues sur plusieurs échanges avec un modèle scripté.
 */
class AiCoachingTest {

    /** Modèle scripté : renvoie les réponses dans l'ordre et garde les invites (prompts) reçues. */
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

    /** Horloge manuelle : la conversation dure 45 s après une réponse. */
    private class Clock(var now: Long = 1_000_000L)

    private fun say(conv: RadioConversation, model: LanguageModel, limited: String, free: String? = limited): List<EngineerMessage> =
        runBlocking { conv.onUtterance(limited, free, model) }

    private fun lap(n: Int, ms: Int, lockups: Int = 0, spins: Int = 0, tcs: Float = 0f, sectors: List<Int>? = null) =
        LapRecord(n, ms, 2f, false, sectors, 200f, tcs, 80f, 3000f, lockups, spins)

    // ------------------------------------------------------------------ 1. Fragments et relances

    @Test
    fun fragmentsAreIgnoredButShortUsefulAnswersAreKept() {
        // Essai réel : « et » seul, capté par le micro resté ouvert, était parti à Gemini.
        assertEquals(RadioAi.Route.Ignore, RadioAi.route("et", "et", inConversation = true))
        assertEquals(RadioAi.Route.Ignore, RadioAi.route("euh", "euh", inConversation = true))
        assertEquals(RadioAi.Route.Ignore, RadioAi.route("[unk] et", "et euh", inConversation = true))
        assertEquals(RadioAi.Route.Ignore, RadioAi.route("[unk]", "bah donc", inConversation = true))
        // Phrase coupée : transcription libre et transcription limitée.
        assertEquals(RadioAi.Route.Ignore, RadioAi.route("je [unk] comment dans les", "je fais comment dans les", inConversation = true))
        assertEquals(RadioAi.Route.Ignore, RadioAi.route("comment [unk] dans les", null, inConversation = true))
        // Hors conversation, un mot seul n'est pas une question.
        assertEquals(RadioAi.Route.Ignore, RadioAi.route("pourquoi", "pourquoi", inConversation = false))
        assertEquals(RadioAi.Route.Ignore, RadioAi.route("[unk] [unk]", "et", inConversation = false))

        // Réponses courtes utiles pendant la conversation.
        assertEquals(RadioAi.Route.Ai("oui"), RadioAi.route("oui", "oui", inConversation = true))
        assertEquals(RadioAi.Route.Ai("non"), RadioAi.route("non", "non", inConversation = true))
        assertEquals(RadioAi.Route.Ai("pourquoi"), RadioAi.route("pourquoi", "pourquoi", inConversation = true))
        assertEquals(RadioAi.Route.Ai("en sortie"), RadioAi.route("en sortie", "en sortie", inConversation = true))
        // Transcription limitée seule (pas de libre) : la relance passe quand même.
        assertIs<RadioAi.Route.Ai>(RadioAi.route("et au freinage", null, inConversation = true))

        // L'ingénieur attend un oui / non (arrêt à confirmer) : la réponse est pour lui, pas pour Gemini.
        assertIs<RadioAi.Route.Parser>(RadioAi.route("oui", "oui", inConversation = true, engineerAwaitsAnswer = true))
        // Hors conversation, oui / non vont à l'analyseur comme avant.
        assertIs<RadioAi.Route.Parser>(RadioAi.route("oui", "oui", inConversation = false))
        // Les commandes ne passent jamais par l'IA.
        assertIs<RadioAi.Route.Parser>(RadioAi.route("arrêt fait", "arrêt fait", inConversation = true))
        assertIs<RadioAi.Route.Parser>(RadioAi.route("je suis cinquième", "je suis cinquième", inConversation = true))
    }

    @Test
    fun isFragmentSeparatesNoiseFromSentences() {
        for (f in listOf("", "et", "euh", "bon ben", "et au", "je fais comment dans les", "est-ce que tu peux m'aider à")) {
            assertTrue(RadioAi.isFragment(f), "« $f » devrait être un fragment")
        }
        for (ok in listOf("oui", "non", "pourquoi", "et au freinage", "l'avant glisse en sortie", "comment je freine mieux")) {
            assertFalse(RadioAi.isFragment(ok), "« $ok » est une vraie phrase")
        }
    }

    // ------------------------------------------------------------------ 2. Conseils incohérents

    @Test
    fun nonsenseAdviceIsDetected() {
        val nonsense = listOf(
            "Pour les virages, l'essentiel est de rester en contact avec le rail.", // essai réel
            "Appuie-toi contre le mur en sortie.",
            "Freine fort au point de corde pour faire tourner la voiture.",
            "Accélère à fond en entrée de virage.",
            "Coupe le virage par l'herbe pour gagner du temps.",
            "Tire le frein à main dans l'épingle.",
            "Freine et accélère en même temps pour stabiliser.",
        )
        for (s in nonsense) assertEquals(AdviceCheck.Problem.NONSENSE, AdviceCheck.check(s, emptySet(), ""), s)
    }

    @Test
    fun banalitiesWithoutAConcreteGestureAreDetected() {
        val banal = listOf(
            "Concentre-toi sur la fluidité de tes mouvements.", // essai réel
            "Sois fluide.",
            "Cherche toujours cet équilibre parfait.",
            "Reste concentré et donne tout.",
        )
        for (s in banal) assertEquals(AdviceCheck.Problem.BANALITY, AdviceCheck.check(s, emptySet(), ""), s)
        // Le même mot avec un geste concret est un vrai conseil.
        assertNull(AdviceCheck.check("Reste fluide en relâchant la pédale de frein progressivement jusqu'au point de corde.", emptySet(), ""))
    }

    @Test
    fun aPersonalDefectNeedsProof() {
        val claim = "Tu bloques tes roues au freinage, relâche la pédale plus tôt quand tu tournes le volant."
        assertEquals(AdviceCheck.Problem.UNPROVEN_DEFECT, AdviceCheck.check(claim, emptySet(), ""))
        // Prouvé par la télémétrie…
        assertNull(AdviceCheck.check(claim, setOf(Evidence.LOCKUPS), ""))
        // … ou dit par le pilote lui-même.
        assertNull(AdviceCheck.check(claim, emptySet(), "je bloque mes roues dans l'épingle"))
        // Une question n'affirme rien : c'est ainsi qu'on approfondit.
        assertNull(AdviceCheck.check("Tu bloques tes roues au freinage ?", emptySet(), ""))
        assertEquals(AdviceCheck.Problem.UNPROVEN_DEFECT, AdviceCheck.check("Tu sous-vires en entrée.", emptySet(), ""))
        assertNull(AdviceCheck.check("Tu sous-vires en entrée.", emptySet(), "l'avant glisse en entrée"))
        assertEquals(AdviceCheck.Problem.UNPROVEN_DEFECT, AdviceCheck.check("Tu rates le point de corde.", emptySet(), ""))
        assertEquals(AdviceCheck.Problem.UNPROVEN_DEFECT, AdviceCheck.check("Tes pneus surchauffent.", emptySet(), ""))
        assertNull(AdviceCheck.check("Tes pneus surchauffent.", setOf(Evidence.TYRES_HOT), ""))
        // Essai réel (v39) : « Tu as dit que tu es régulier, c'est bien » avec un seul tour chronométré.
        assertEquals(AdviceCheck.Problem.UNPROVEN_DEFECT, AdviceCheck.check("Tu as dit que tu es régulier, c'est bien.", emptySet(), "tu es regulier"))
        assertNull(AdviceCheck.check("Tu es régulier, garde tes repères.", setOf(Evidence.CONSISTENT), ""))
        // La question du pilote ne vaut pas aveu.
        val r = RadioAi.parse(claim, "est-ce que je bloque mes roues", AiFacts(listOf("Course : tour 3.")))
        assertEquals(AiReply.Rejected(AiReply.Reason.UNPROVEN_DEFECT), r)
    }

    @Test
    fun theDrivingPrinciplesPassTheirOwnChecks() {
        // Les principes donnés à l'IA doivent être cohérents avec le contrôle : aucun ne doit être rejeté.
        for (t in DrivingPrinciples.Topic.entries) {
            for (sentence in t.text.split(Regex("(?<=[.!?])\\s+"))) {
                assertNull(AdviceCheck.check(sentence, emptySet(), ""), "${t.name} : « $sentence »")
            }
            assertFalse(Regex("\\d").containsMatchIn(t.text), "${t.name} : un principe ne contient aucun chiffre")
        }
    }

    @Test
    fun principlesFollowTheSubject() {
        val e = emptySet<Evidence>()
        assertTrue(DrivingPrinciples.Topic.BRAKING in DrivingPrinciples.select("comment je freine mieux", emptyList(), e))
        assertTrue(DrivingPrinciples.Topic.UNDERSTEER in DrivingPrinciples.select("l'avant glisse en sortie", emptyList(), e))
        assertTrue(DrivingPrinciples.Topic.EXIT in DrivingPrinciples.select("l'avant glisse en sortie", emptyList(), e))
        assertTrue(DrivingPrinciples.Topic.LOCKUP in DrivingPrinciples.select("comment être plus rapide", emptyList(), setOf(Evidence.LOCKUPS)))
        // Question vague, sans constat : les fondamentaux.
        assertEquals(
            listOf(DrivingPrinciples.Topic.BRAKING, DrivingPrinciples.Topic.APEX, DrivingPrinciples.Topic.EXIT),
            DrivingPrinciples.select("comment être plus rapide", emptyList(), e),
        )
    }

    // ------------------------------------------------------------------ 3. Constats prouvés par la télémétrie

    @Test
    fun observationsComeOnlyFromMeasuredLaps() {
        val none = CoachingObservations.from(emptyList(), null)
        assertEquals(listOf(Evidence.NO_TELEMETRY), none.map { it.evidence })

        val clean = listOf(lap(1, 90_000), lap(2, 90_100), lap(3, 90_050))
        val ev = CoachingObservations.from(clean, null).map { it.evidence }
        assertFalse(Evidence.LOCKUPS in ev)
        assertFalse(Evidence.WHEELSPIN in ev)
        assertTrue(Evidence.CONSISTENT in ev)

        val messy = listOf(
            lap(1, 90_000, lockups = 1, spins = 2, tcs = 0.12f, sectors = listOf(30_000, 30_000, 30_000)),
            lap(2, 92_500, lockups = 2, spins = 1, tcs = 0.10f, sectors = listOf(30_100, 31_000, 30_100)),
            lap(3, 90_200, lockups = 1, spins = 0, tcs = 0.09f, sectors = listOf(30_000, 30_800, 30_000)),
        )
        val obs = CoachingObservations.from(messy, null)
        val byEvidence = obs.associateBy { it.evidence }
        assertEquals("4 blocages de roue au freinage sur les 3 derniers tours (tour 1 : 1, tour 2 : 2, tour 3 : 1).", byEvidence[Evidence.LOCKUPS]?.text)
        assertTrue(Evidence.WHEELSPIN in byEvidence)
        assertTrue(Evidence.TCS in byEvidence)
        assertTrue(Evidence.INCONSISTENT in byEvidence)
        assertTrue(byEvidence[Evidence.SECTOR_LOSS]!!.text.contains("secteur 2"), byEvidence[Evidence.SECTOR_LOSS]!!.text)

        // Un seul blocage isolé ne fait pas un défaut.
        assertFalse(Evidence.LOCKUPS in CoachingObservations.from(listOf(lap(1, 90_000, lockups = 1), lap(2, 90_000)), null).map { it.evidence })
    }

    @Test
    fun aProvenDefectCanBeSaidWithItsNumbers() {
        val laps = listOf(lap(1, 90_000, lockups = 2), lap(2, 90_100, lockups = 1), lap(3, 90_050, lockups = 2))
        val facts = AiFacts(listOf("Course : tour 4 sur 10."), CoachingObservations.from(laps, null))
        val r = RadioAi.parse(
            "Tu as 5 blocages de roue au freinage sur les 3 derniers tours. Relâche la pédale plus tôt quand tu tournes le volant.",
            "comment je peux être plus rapide",
            facts,
        )
        assertIs<AiReply.Answer>(r)
        // Le même reproche sans les constats est retiré, le conseil général reste.
        val r2 = RadioAi.parse(
            "Tu bloques tes roues au freinage. Pour freiner fort sans bloquer, appuie en ligne droite puis relâche progressivement en tournant.",
            "comment je peux être plus rapide",
            AiFacts(listOf("Course : tour 4 sur 10.")),
        )
        assertEquals(AiReply.Answer("Pour freiner fort sans bloquer, appuie en ligne droite puis relâche progressivement en tournant."), r2)
    }

    // ------------------------------------------------------------------ 4. Dialogues sur plusieurs échanges

    @Test
    fun dialogueWithoutTelemetryAsksThenDeepens() {
        val c = RaceController()
        val clock = Clock()
        val conv = RadioConversation(c, clock = { clock.now })
        val model = ScriptedModel(
            // 1. Pas de télémétrie : il ne suppose rien, il demande.
            "Sans télémétrie, je ne peux pas te dire où tu perds. Dans quel virage tu as le plus de mal, et c'est à l'entrée, au point de corde ou en sortie ?",
            // 2. Le pilote décrit : diagnostic + pourquoi + geste + question.
            "C'est du sous-virage en sortie : l'avant s'allège quand tu remets les gaz alors que le volant est encore braqué. " +
                "Attends d'avoir passé le point de corde et ouvre le volant avant d'accélérer progressivement. Ça glisse dès que tu touches l'accélérateur ?",
            // 3. « oui » : il approfondit le même sujet.
            "Alors prends un point de corde plus tardif : entre un peu plus large pour que la voiture pivote avant, tu pourras accélérer volant plus droit.",
        )

        val r1 = say(conv, model, "comment je [unk] [unk] plus rapide", "comment je peux être plus rapide")
        assertTrue(r1.last().fromAi && r1.last().expectsReply, r1.toString())
        assertTrue("Aucune télémétrie de course" in model.prompts[0], model.prompts[0])
        assertTrue("Freinage :" in model.prompts[0] && "Point de corde :" in model.prompts[0], "fondamentaux donnés")

        clock.now += 10_000
        val r2 = say(conv, model, "dans [unk] avant [unk] en sortie", "dans l'épingle l'avant glisse en sortie")
        assertTrue(r2.last().fromAi && "sous-virage" in r2.last().text, r2.toString())
        assertTrue("Sous-virage" in model.prompts[1] && "Réaccélération" in model.prompts[1], "principes du sujet")
        assertTrue("ÉCHANGES PRÉCÉDENTS" in model.prompts[1] && "Dans quel virage" in model.prompts[1], "la relance connaît la question")

        clock.now += 10_000
        val r3 = say(conv, model, "oui", "oui")
        assertTrue(r3.last().fromAi && "point de corde plus tardif" in r3.last().text, r3.toString())
        assertTrue("l'avant glisse en sortie" in model.prompts[2], "le fil de la conversation est gardé")

        // Fragment parasite : rien n'est envoyé, rien n'est dit.
        clock.now += 5_000
        assertTrue(say(conv, model, "et", "et").isEmpty())
        assertEquals(3, model.prompts.size)
        assertEquals(3, c.aiHistory.size)

        // Fin de conversation : 45 s plus tard, « oui » redevient une réponse pour l'ingénieur.
        clock.now += 60_000
        assertIs<RadioAi.Route.Parser>(RadioAi.route("oui", "oui", c.inAiConversation(clock.now), c.awaitingDriverAnswer))
    }

    @Test
    fun dialogueWithTelemetryUsesProofAndRejectsInventedDefects() {
        val c = RaceController()
        val sim = RaceSim(c, totalLaps = 10, perLap = 2f)
        sim.start()
        sim.driveLaps(4)
        val clock = Clock()
        val conv = RadioConversation(c, clock = { clock.now })
        val model = ScriptedModel(
            // Invente un défaut que la télémétrie ne montre pas, plus une absurdité : tout est écarté.
            "Tu bloques tes roues au freinage. Reste en contact avec le rail.",
            // Bonne réponse : s'appuie sur le constat réel (chronos réguliers) et approfondit.
            "La télémétrie ne montre aucun blocage et tes chronos sont réguliers. Pour gagner, travaille un virage à la fois : lequel te pose problème ?",
            // Le pilote précise : conseil concret.
            "Dans une chicane, sacrifie la première partie pour bien sortir de la seconde : freine en ligne droite et vise un point de corde tardif sur le dernier virage.",
        )

        val r1 = say(conv, model, "[unk] je bloque mes roues", "est-ce que je bloque mes roues")
        val prompt1 = model.prompts[0]
        assertTrue("Chronos réguliers" in prompt1, prompt1)
        assertTrue("Tour 3 :" in prompt1, "tours mesurés dans les faits")
        // Réponse entièrement écartée : l'ingénieur ne dit pas de bêtise, il cherche le vrai problème.
        assertFalse(r1.any { "rail" in it.text || "Tu bloques" in it.text }, r1.toString())
        assertTrue(r1.last().expectsReply && "à l'entrée, au point de corde ou en sortie" in r1.last().text, r1.toString())
        assertEquals("refusée : défaut non prouvé par la télémétrie", conv.lastTrace?.verdict)

        clock.now += 5_000
        val r2 = say(conv, model, "comment je [unk] gagner du temps", "comment je peux gagner du temps alors")
        assertTrue(r2.last().fromAi && "réguliers" in r2.last().text, r2.toString())

        clock.now += 10_000
        val r3 = say(conv, model, "la chicane", "la chicane après la ligne droite")
        assertTrue(r3.last().fromAi && "point de corde tardif" in r3.last().text, r3.toString())
        assertTrue("Trajectoire" in model.prompts[2], "principe de trajectoire pour une chicane")

        // Une commande en pleine conversation reste une commande, sans passer par Gemini.
        clock.now += 5_000
        val before = model.prompts.size
        say(conv, model, "je suis cinquième", "je suis cinquième")
        assertEquals(before, model.prompts.size)
        assertEquals(5, c.engineer.driverPosition)
    }
}
