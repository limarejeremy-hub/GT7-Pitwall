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
        val r = RadioAi.parse("Première phrase. Deuxième phrase. Troisième phrase.", "?", facts) as AiReply.Answer
        assertEquals("Première phrase. Deuxième phrase.", r.text)
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

        // Commande comprise par l'IA : même effet qu'à la voix.
        val cmd = RadioAi.parse("COMMANDE: les pneus tiennent 6 tours", "mes pneus durent 6 tours", f)
        s.said += c.aiReply("mes pneus durent 6 tours", cmd)
        assertEquals(6, c.rules.tyreStintLaps)
        assertTrue(s.texts.contains("Toi : mes pneus durent 6 tours"))

        s.said += c.aiReply("il fait beau ?", AiReply.Rejected(AiReply.Reason.UNKNOWN))
        assertTrue("GT7 ne me la transmet pas" in s.lastAnswer, s.lastAnswer)
        s.said += c.aiReply("ça va ?", AiReply.Answer("Tout va bien, rythme régulier."))
        assertEquals("Tout va bien, rythme régulier.", s.lastAnswer)
        assertEquals(MessageCategory.ANSWER, s.said.last().category)
        assertFalse(s.texts.any { "Je n'ai pas compris" in it })
    }
}
