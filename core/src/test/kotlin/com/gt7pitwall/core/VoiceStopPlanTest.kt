package com.gt7pitwall.core

import com.gt7pitwall.core.ai.AiBackend
import com.gt7pitwall.core.ai.AiMode
import com.gt7pitwall.core.ai.AiProvider
import com.gt7pitwall.core.ai.AiResult
import com.gt7pitwall.core.ai.AiRouter
import com.gt7pitwall.core.ai.AiUsage
import com.gt7pitwall.core.ai.RadioConversation
import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.radio.DriverCommand
import com.gt7pitwall.core.radio.DriverCommandParser
import com.gt7pitwall.core.radio.DriverQuery
import com.gt7pitwall.core.sport.FieldStatus
import com.gt7pitwall.core.sport.RuleField
import com.gt7pitwall.core.strategy.RaceRules
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Course réelle du 27/10 (Suzuka, course personnalisée de 5 tours, règlement local mémorisé : 1 arrêt obligatoire,
 * carburant ×2, pneus ×8). Phrases reprises du journal (issue #29) :
 * - 18:54:22 « oublie la arret au stand tu n'as pas besoin » → 16 s avant la réponse, via le cloud ;
 * - 18:55:01 « as tu enlever les arret aux stands » → pris pour une nouvelle annulation ;
 * - 18:55:19 « oui » → « Aucun arrêt à retirer » (ledger.cancelLast), puis les « box » continuaient.
 */
class VoiceStopPlanTest {
    /** Cloud qui répondrait au bout de 15 s (comme le 27/10) : il ne doit jamais être appelé pour une commande simple. */
    private class SlowCloud(override val backend: AiBackend) : AiProvider {
        var calls = 0
        override val modelId: String = backend.defaultModelId!!
        override val ready: Boolean = true
        override val status: String = "simulé (test)"
        override suspend fun complete(systemInstruction: String, prompt: String): AiResult {
            calls++
            delay(15_000)
            return AiResult.Text("Compris.")
        }
    }

    private fun suzuka27(): Pair<RaceController, RaceSim> {
        val c = RaceController(storage = FakeStorage(rules = RaceRules(totalLaps = 5, fuelMultiplier = 2, tyreMultiplier = 8, mandatoryStops = 1)))
        val s = RaceSim(c, totalLaps = 5, perLap = 3f)
        s.start()
        s.driveLaps(1)
        return c to s
    }

    private fun List<EngineerMessage>.answer() = lastOrNull { it.category == MessageCategory.ANSWER }?.text.orEmpty()

    @Test
    fun theFourStopIntentionsAreToldApart() {
        assertEquals(DriverCommand.CancelPlannedStop, DriverCommandParser.parse("oublie la arret au stand tu n'as pas besoin"))
        assertEquals(DriverCommand.CancelPlannedStop, DriverCommandParser.parse("pas besoin de rentrer"))
        assertEquals(DriverCommand.CancelPlannedStop, DriverCommandParser.parse("on ne rentre pas"))
        assertEquals(DriverCommand.Ask(DriverQuery.STOP_STATUS), DriverCommandParser.parse("as tu enlever les arret aux stands"))
        assertEquals(DriverCommand.Ask(DriverQuery.STOP_STATUS), DriverCommandParser.parse("tu as retiré l'arrêt ?"))
        assertEquals(DriverCommand.CancelStop, DriverCommandParser.parse("retire le dernier arrêt"))
        assertEquals(DriverCommand.CancelStop, DriverCommandParser.parse("j'ai pas fait d'arrêt"))
        assertEquals(DriverCommand.SetMandatoryStops(0), DriverCommandParser.parse("pas d'arrêt obligatoire"))
    }

    @Test
    fun the27OctoberSequenceCancelsThePlannedStopLocallyAndQuickly() = runBlocking {
        val (c, s) = suzuka27()
        assertTrue(c.plan!!.stops.isNotEmpty(), "un arrêt obligatoire est prévu au départ")
        val lite = SlowCloud(AiBackend.FLASH_LITE)
        val flash = SlowCloud(AiBackend.FLASH)
        val conv = RadioConversation(c).also {
            it.cloud = AiRouter(mapOf(AiBackend.FLASH_LITE to lite, AiBackend.FLASH to flash), AiUsage(), AiMode.AUTO, totalTimeoutMs = 16_000L)
        }
        // Phrase de conversation (sans « Radio ») : modification à confirmer, mais tout de suite et sans le cloud.
        val t0 = System.nanoTime()
        val first = conv.onUtterance("oublie la arret au stand tu n'as pas besoin", "oublie la arret au stand tu n'as pas besoin", model = null, addressed = false)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(ms < 2_000, "réponse locale en $ms ms")
        assertEquals("Tu confirmes : on supprime l'arrêt prévu ?", first.answer())
        // La question d'état ne modifie rien et ne passe pas pour une nouvelle annulation.
        val status = conv.onUtterance("as tu enlever les arret aux stands", "as tu enlever les arret aux stands", model = null, addressed = false)
        assertTrue(status.answer().startsWith("Non : un arrêt est encore prévu fin du tour"), status.answer())
        assertEquals(1, c.rules.mandatoryStops)
        // « Oui » : la bonne intention, une seule fois.
        val yes = conv.onUtterance("oui", "oui", model = null, addressed = false)
        assertEquals("C'est noté : plus d'arrêt obligatoire. Plus d'arrêt prévu, tu restes en piste jusqu'à l'arrivée.", yes.answer())
        assertEquals(0, c.rules.mandatoryStops)
        assertEquals(FieldStatus.PILOT, c.profile.ruleStatus[RuleField.MANDATORY_STOPS], "provenance : déclaré par le pilote")
        assertTrue(c.plan?.stops.isNullOrEmpty())
        assertEquals(0, lite.calls + flash.calls, "aucun appel au cloud pour une commande simple")
        // Plus aucun appel au stand ensuite.
        val before = s.texts.size
        s.driveLaps(5)
        assertTrue(s.texts.drop(before).none { it.startsWith("Box") || "box à la fin" in it.lowercase() }, s.texts.drop(before).toString())
        val again = conv.onUtterance("as-tu enlevé l'arrêt ?", "as-tu enlevé l'arrêt ?", model = null, addressed = false)
        assertTrue(again.answer().startsWith("Oui : aucun arrêt n'est prévu"), again.answer())
    }

    @Test
    fun aRecordedStopIsStillRemovedRightAfterItWasDeclared() {
        val (c, s) = suzuka27()
        s.say("j'ai fait mon arrêt")
        assertEquals(1, c.ledger.stopCount)
        s.say("annule l'arrêt")
        assertEquals(0, c.ledger.stopCount)
        assertTrue(s.lastAnswer.startsWith("Arrêt 1 retiré"), s.lastAnswer)
    }

    @Test
    fun aFuelStopIsNeverRemovedButTheTargetIsGiven() {
        val c = RaceController(storage = FakeStorage(rules = RaceRules(totalLaps = 12)))
        val s = RaceSim(c, totalLaps = 12, perLap = 12f)
        s.start()
        s.driveLaps(2)
        val next = c.plan!!.stops.first()
        assertTrue(next.forFuel)
        s.say("radio oublie l'arrêt au stand")
        assertTrue(s.lastAnswer.startsWith("Je ne peux pas l'enlever : sans arrêt, le carburant ne suffit pas"), s.lastAnswer)
        assertTrue("au plus" in s.lastAnswer && "par tour" in s.lastAnswer, s.lastAnswer)
        assertEquals(next.endOfLap, c.plan!!.stops.first().endOfLap)
    }

    @Test
    fun nothingPlannedSaysSoWithoutTouchingTheRecordedStops() {
        val c = RaceController(storage = FakeStorage(rules = RaceRules(totalLaps = 5)))
        val s = RaceSim(c, totalLaps = 5, perLap = 3f)
        s.start()
        s.driveLaps(1)
        assertNull(c.plan?.stops?.firstOrNull())
        s.say("oublie l'arrêt")
        assertTrue(s.lastAnswer.startsWith("Aucun arrêt n'est prévu"), s.lastAnswer)
    }

    @Test
    fun aVerifiedSportObligationIsNeverRemovedSilently() {
        val c = RaceController(storage = FakeStorage())
        c.selectProfile(com.gt7pitwall.core.race.ProfileId.of(com.gt7pitwall.core.race.SessionMode.SPORT_OFFICIAL, com.gt7pitwall.core.race.Series.DAILY_B))
        c.setRules(RaceRules(totalLaps = 5, mandatoryStops = 1))
        c.confirmRules("2026-10-27")
        val s = RaceSim(c, totalLaps = 5, perLap = 3f)
        s.start()
        s.driveLaps(1)
        s.say("radio oublie l'arrêt au stand")
        assertTrue(s.lastAnswer.startsWith("Je garde l'arrêt fin du tour"), s.lastAnswer)
        assertTrue("règlement vérifié" in s.lastAnswer, s.lastAnswer)
        assertEquals(1, c.rules.mandatoryStops)
        assertTrue(c.plan!!.stops.isNotEmpty())
    }
}
