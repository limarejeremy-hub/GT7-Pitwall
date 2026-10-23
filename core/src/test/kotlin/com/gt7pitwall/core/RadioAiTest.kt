package com.gt7pitwall.core

import com.gt7pitwall.core.ai.AiFacts
import com.gt7pitwall.core.ai.AiReply
import com.gt7pitwall.core.ai.RadioAi
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.radio.DriverCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** L'IA reformule ou traduit en commande ; elle ne doit jamais introduire une donnée que GT7 n'a pas transmise. */
class RadioAiTest {
    private val facts = AiFacts(
        listOf(
            "Carburant : Carburant pour 6,5 tours. Arrêt prévu à la fin du tour 7, environ 62 litres.",
            "Meilleur tour : Meilleur tour : 1 minute 27 secondes 4.",
            "Tour 3 : 1 minute 28 secondes 1, 2 blocages de roue.",
        ),
    )

    @Test
    fun answersMadeOfFactsPass() {
        val r = RadioAi.parse("Tu as de l'essence pour 6,5 tours, on s'arrête à la fin du tour 7 pour 62 litres.", "essence ?", facts)
        assertEquals(AiReply.Answer("Tu as de l'essence pour 6,5 tours, on s'arrête à la fin du tour 7 pour 62 litres."), r)
        // 6.5 et 6,5 sont le même nombre.
        assertIs<AiReply.Answer>(RadioAi.parse("Autonomie : 6.5 tours.", "essence ?", facts))
    }

    @Test
    fun anInventedNumberIsRejected() {
        // Écart avec un adversaire : GT7 ne le transmet pas.
        assertEquals(
            AiReply.Rejected(AiReply.Reason.INVENTED_NUMBER),
            RadioAi.parse("Tu as 3 secondes d'avance sur la voiture derrière.", "j'ai combien d'avance ?", facts),
        )
        // « 7 » existe dans les faits, mais pas « 7 secondes » : l'unité doit correspondre.
        assertEquals(
            AiReply.Rejected(AiReply.Reason.INVENTED_NUMBER),
            RadioAi.parse("Tu perds 7 secondes au freinage.", "je perds où ?", facts),
        )
        // Pas de chiffre, mais un sujet que GT7 ne transmet pas.
        assertEquals(
            AiReply.Rejected(AiReply.Reason.NOT_TRANSMITTED),
            RadioAi.parse("La voiture derrière toi revient fort.", "ça revient derrière ?", facts),
        )
        // Un rang de secteur n'est pas une position.
        assertIs<AiReply.Answer>(RadioAi.parse("Le deuxième secteur est ton point faible.", "où je perds ?", facts))
        // En toutes lettres aussi.
        assertEquals(
            AiReply.Rejected(AiReply.Reason.INVENTED_NUMBER),
            RadioAi.parse("Tu es quatrième.", "je suis où ?", facts),
        )
        // Un chiffre de la question peut être repris.
        assertIs<AiReply.Answer>(RadioAi.parse("Tour 12, compris.", "on est au tour 12 ?", facts))
    }

    @Test
    fun unknownAndEmptyAreSaidHonestly() {
        assertEquals(AiReply.Rejected(AiReply.Reason.UNKNOWN), RadioAi.parse("INCONNU", "quel temps fera-t-il ?", facts))
        assertEquals(AiReply.Rejected(AiReply.Reason.EMPTY), RadioAi.parse("  ", "?", facts))
        assertEquals(AiReply.Rejected(AiReply.Reason.EMPTY), RadioAi.parse(null, "?", facts))
    }

    @Test
    fun commandsGoThroughTheUsualParser() {
        val r = RadioAi.parse("COMMANDE: les pneus tiennent 8 tours", "mes gommes font huit tours environ", facts)
        assertEquals(AiReply.Command("les pneus tiennent 8 tours", DriverCommand.SetTyreStint(8)), r)
        assertEquals(
            DriverCommand.DeclareStop,
            (RadioAi.parse("COMMANDE: « j'ai fait un arrêt aux stands ».", "voilà je ressors du garage", facts) as AiReply.Command).command,
        )
        // Le chiffre d'une commande doit venir du pilote, pas de l'IA.
        assertEquals(
            AiReply.Rejected(AiReply.Reason.INVENTED_NUMBER),
            RadioAi.parse("COMMANDE: course de 20 tours", "règle la course", facts),
        )
        assertEquals(AiReply.Rejected(AiReply.Reason.BAD_COMMAND), RadioAi.parse("COMMANDE: fais-moi un café", "café", facts))
    }

    @Test
    fun everyCommandExampleIsUnderstoodByTheParser() {
        for (e in RadioAi.COMMAND_EXAMPLES) {
            assertTrue(com.gt7pitwall.core.radio.DriverCommandParser.parse(e) != null, e)
        }
    }

    @Test
    fun longAnswersAreCutForTheRadio() {
        val r = RadioAi.parse("Bien. Continue. Garde ton repère de freinage. Quatrième phrase.", "?", facts) as AiReply.Answer
        assertEquals("Bien. Continue. Garde ton repère de freinage.", r.text)
    }

    @Test
    fun factsComeFromTheRaceAndRepliesGoThroughTheController() {
        val c = RaceController()
        val s = RaceSim(c, totalLaps = 10, perLap = 5f)
        s.start()
        s.driveLaps(3)
        val f = c.aiFacts()
        assertTrue(f.lines.any { it.startsWith("Carburant :") && "tours" in it }, f.text)
        assertTrue(f.lines.any { it.startsWith("Tour 2 :") }, f.text)
        assertTrue(f.lines.first().startsWith("GT7 ne transmet pas"))

        // Commande comprise par l'IA : Gemini ne fait que reformuler, rien ne change sans le « oui » du pilote.
        val cmd = RadioAi.parse("COMMANDE: les pneus tiennent 6 tours", "mes pneus durent 6 tours", f)
        s.said += c.aiReply("mes pneus durent 6 tours", cmd)
        assertEquals(null, c.rules.tyreStintLaps, "pas appliqué sans confirmation")
        assertEquals("Tu confirmes : les pneus tiennent 6 tours ?", s.lastAnswer)
        assertTrue(s.texts.contains("Toi : mes pneus durent 6 tours"))
        s.said += c.utterance("oui")
        assertEquals(6, c.rules.tyreStintLaps, "confirmé par le pilote : même effet qu'à la voix")

        s.said += c.aiReply("il fait beau ?", AiReply.Rejected(AiReply.Reason.UNKNOWN))
        assertTrue("GT7 ne me la transmet pas" in s.lastAnswer, s.lastAnswer)
        s.said += c.aiReply("ça va ?", AiReply.Answer("Tout va bien, rythme régulier."))
        assertEquals("Tout va bien, rythme régulier.", s.lastAnswer)
        assertEquals(MessageCategory.ANSWER, s.said.last().category)
        assertFalse(s.texts.any { "Je n'ai pas compris" in it })
    }

    @Test
    fun openQuestionsGoToTheAiAndCommandsStayWithTheParser() {
        fun route(text: String, free: String? = null) = RadioAi.route(text, free)
        val ai = RadioAi.Route.Ai::class
        // Questions ouvertes : elles étaient captées par des mots-clés (« course », « temps », « tour »).
        assertIs<RadioAi.Route.Ai>(route("résume ma course"))
        assertIs<RadioAi.Route.Ai>(route("comment je peux être plus rapide"))
        assertIs<RadioAi.Route.Ai>(route("analyse mon dernier tour"))
        assertIs<RadioAi.Route.Ai>(route("pourquoi je perds du temps"))
        // Reconnaissance radio dépassée (mots hors vocabulaire) : la version libre part à l'IA.
        val r = route("[unk] je [unk] mes pneus [unk]", "est-ce que je surchauffe mes pneus arrière")
        assertEquals(RadioAi.Route.Ai("est ce que je surchauffe mes pneus arriere"), r)
        // « question : » force l'IA.
        assertEquals(RadioAi.Route.Ai("je suis bien"), route("question je suis bien"))
        // Commandes et questions simples : analyseur, immédiat.
        for (s in listOf(
            "arrêt fait", "j'ai fait un arrêt aux stands", "je suis cinquième", "les pneus tiennent huit tours",
            "course de 12 tours", "oui", "non", "silence", "combien d'essence", "mes secteurs",
        )) {
            assertIs<RadioAi.Route.Parser>(route(s, s), s)
        }
        // Commande dite avec des mots inconnus autour : reste une commande.
        assertIs<RadioAi.Route.Parser>(route("[unk] arrêt fait [unk]", "bon arrêt fait là"))
        assertTrue(ai.isInstance(route("[unk] [unk]", "je fais quoi maintenant")))
    }

    @Test
    fun gainingTimeIsNotAnOvertake() {
        val p = com.gt7pitwall.core.radio.DriverCommandParser::parse
        assertTrue(p("comment je peux gagner du temps") !is DriverCommand.Overtook)
        assertTrue(p("j'ai perdu du temps au freinage") !is DriverCommand.Overtaken)
        assertEquals(DriverCommand.Overtook, p("j'ai gagné une place"))
        assertEquals(DriverCommand.Overtaken, p("j'ai perdu une place"))
    }

    @Test
    fun onlyTheSentenceWithAnInventedNumberIsRemoved() {
        // Retour réel : un résumé entier refusé pour un seul chiffre. Seule la phrase fautive saute.
        val r = RadioAi.parse(
            "Ton meilleur tour est 1 minute 27 secondes 4. Tu as gagné 4 places. Tu as bloqué tes roues 2 fois au tour 3, freine plus progressivement.",
            "résume ma course",
            facts,
        )
        assertEquals(
            AiReply.Answer("Ton meilleur tour est 1 minute 27 secondes 4. Tu as bloqué tes roues 2 fois au tour 3, freine plus progressivement."),
            r,
        )
        // Un conseil sans chiffre passe.
        assertIs<AiReply.Answer>(RadioAi.parse("Freine un peu plus tôt et en ligne droite, puis relâche progressivement.", "comment aller plus vite", facts))
    }

    @Test
    fun followUpsGoToTheAiDuringAConversation() {
        // Hors conversation, « et au freinage » ne veut rien dire pour les mots-clés ; pendant, c'est une relance.
        assertIs<RadioAi.Route.Ai>(RadioAi.route("et au freinage", "et au freinage", inConversation = true))
        // Bout de phrase capté par le micro resté ouvert (essai réel : « et ») : ignoré ; « pourquoi » seul est une relance.
        assertEquals(RadioAi.Route.Ignore, RadioAi.route("et", "et", inConversation = true))
        assertIs<RadioAi.Route.Ai>(RadioAi.route("pourquoi", "pourquoi", inConversation = true))
        // Une commande reste une commande, même en pleine conversation.
        assertIs<RadioAi.Route.Parser>(RadioAi.route("arrêt fait", "arrêt fait", inConversation = true))
        assertIs<RadioAi.Route.Parser>(RadioAi.route("combien d'essence", "combien d'essence", inConversation = false))
    }

    @Test
    fun theControllerKeepsTheConversation() {
        val c = RaceController()
        val s = RaceSim(c, totalLaps = 10, perLap = 5f)
        s.start()
        s.driveLaps(2)
        assertFalse(c.inAiConversation())
        s.said += c.aiReply("comment je roule", AiReply.Answer("Tu es régulier, continue comme ça."), nowMs = 1_000)
        // La réponse rouvre le micro sans mot de réveil, et l'échange est gardé pour la relance.
        assertTrue(s.said.last().expectsReply && s.said.last().fromAi)
        assertTrue(c.inAiConversation(nowMs = 30_000))
        assertFalse(c.inAiConversation(nowMs = 60_000))
        assertEquals(listOf(RadioAi.Exchange("comment je roule", "Tu es régulier, continue comme ça.")), c.aiHistory)
        val prompt = RadioAi.prompt("et au freinage ?", c.aiFacts(), c.aiHistory)
        assertTrue("ÉCHANGES PRÉCÉDENTS" in prompt && "Tu es régulier" in prompt, prompt)
        // Les chiffres d'une réponse déjà vérifiée peuvent être repris dans la relance.
        val h = listOf(RadioAi.Exchange("q", "Tu as 2 blocages de roue."))
        assertIs<AiReply.Answer>(RadioAi.parse("Ces 2 blocages de roue te coûtent du temps.", "et alors ?", AiFacts(emptyList()), h))
    }
}
