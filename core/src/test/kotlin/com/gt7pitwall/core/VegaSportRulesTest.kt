package com.gt7pitwall.core

import com.gt7pitwall.core.ai.AiBackend
import com.gt7pitwall.core.ai.AiFacts
import com.gt7pitwall.core.ai.AiMode
import com.gt7pitwall.core.ai.AiProvider
import com.gt7pitwall.core.ai.AiResult
import com.gt7pitwall.core.ai.AiRouter
import com.gt7pitwall.core.ai.AiUsage
import com.gt7pitwall.core.ai.FactTopic
import com.gt7pitwall.core.ai.RadioConversation
import com.gt7pitwall.core.ai.ToolCapable
import com.gt7pitwall.core.ai.chatgpt.ResponsesStream
import com.gt7pitwall.core.ai.tools.AnalysisFacts
import com.gt7pitwall.core.ai.tools.LatencyStats
import com.gt7pitwall.core.ai.tools.SportSemantics
import com.gt7pitwall.core.ai.tools.ToolLoop
import com.gt7pitwall.core.ai.tools.ToolSnapshot
import com.gt7pitwall.core.ai.tools.VegaCheck
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.sport.Edition
import com.gt7pitwall.core.sport.FieldStatus
import com.gt7pitwall.core.sport.ScheduleState
import com.gt7pitwall.core.sport.SourceRef
import com.gt7pitwall.core.sport.Sourced
import com.gt7pitwall.core.strategy.RaceRules
import com.gt7pitwall.core.strategy.TyreCompound
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Revue #72 (essai Fold du 06/11, 01h50) : Véga a dit « C … RH et RM autorisés » alors que les deux sont OBLIGATOIRES
 * (GTPlanet, DG EDGE). Fixtures reprenant la semaine du 02/11/2026 ; données publiques, aucune donnée du compte.
 */
class VegaSportRulesTest {
    private val paris = ZoneId.of("Europe/Paris")
    private fun ms(d: LocalDate, h: Int = 12) = d.atTime(h, 0).atZone(paris).toInstant().toEpochMilli()
    private val monday = LocalDate.of(2026, 11, 2)
    private val gtp = SourceRef("GTPlanet", null, ms(monday))
    private val dge = SourceRef("DG EDGE", null, ms(monday))

    private fun c(required: Sourced<Set<TyreCompound>>, track: Sourced<String> = Sourced.unknown()) = Edition(
        Series.DAILY_C, monday, monday.plusDays(7), trackName = track, trackId = null,
        laps = Sourced(11, FieldStatus.CONFIRMED, listOf(gtp, dge)), cars = Sourced.unknown(), bop = Sourced.unknown(),
        settings = Sourced.unknown(), startType = Sourced.unknown(),
        allowedTyres = Sourced(setOf(TyreCompound.RH, TyreCompound.RM), FieldStatus.CONFIRMED, listOf(gtp, dge)),
        requiredTyres = required, mandatoryStops = Sourced.unknown(), fuelMultiplier = Sourced.unknown(), tyreMultiplier = Sourced.unknown(),
        sources = listOf(gtp, dge),
    )

    private val required = Sourced(setOf(TyreCompound.RH, TyreCompound.RM), FieldStatus.CONFIRMED, listOf(gtp, dge))
    private fun state(vararg e: Edition, stale: Boolean = false) = ScheduleState(e.associateBy { it.series }, emptyList(), ms(monday, 9), null, stale)
    private val wednesday = ms(monday.plusDays(2))

    @Test fun mandatoryTyresAreStatedWithTheirConsequenceAndAnUnknownTrackIsSaidUnknown() {
        val sec = AnalysisFacts.sport(state(c(required)), wednesday)
        val sheet = sec.facts.single { it.text.startsWith("Course Sport C (") }.text
        assertTrue("pneus OBLIGATOIRES RH, RM [confirmé]" in sheet || "pneus OBLIGATOIRES RM, RH [confirmé]" in sheet, sheet)
        assertTrue("circuit inconnu" in sheet, "circuit absent du cache : dit inconnu, jamais deviné ($sheet)")
        val rule = sec.facts.single { it.text.startsWith("Course Sport C : les gommes") }.text
        assertTrue("OBLIGATOIRES" in rule && "passage aux stands" in rule && "même si aucun arrêt obligatoire n'est écrit" in rule, rule)
        assertTrue(sec.sportRules.single().tyreChangeNeeded)
        assertTrue(sec.missing.any { "inconnu pour circuit" in it })
        assertTrue(sec.facts.first().text.contains("actualisé par le téléphone") && sec.facts.first().text.contains("pas une recherche web"))
    }

    @Test fun anUnverifiedObligationIsSaidAndNeverConcluded() {
        val sec = AnalysisFacts.sport(state(c(Sourced(null, FieldStatus.CONFLICT, listOf(gtp, dge), "GTPlanet : RH|RM / DG EDGE : RM"))), wednesday)
        assertTrue(sec.missing.any { "obligation de gommes NON VÉRIFIÉE" in it })
        assertTrue(sec.facts.none { "OBLIGATOIRES (il faut" in it.text })
        assertTrue(sec.conflicts.any { "pneus obligatoires" in it })
        val rules = sec.sportRules
        assertNotNull(SportSemantics.problem("En C, RH obligatoire.", rules), "obligation non vérifiée affirmée")
        assertNull(SportSemantics.problem("En C, 11 tours, RH et RM autorisés ; l'obligation de gommes n'est pas vérifiée.", rules))
    }

    @Test fun mandatoryTyresPresentedAsMerelyAllowedAreRejected() {
        val rules = AnalysisFacts.sport(state(c(required)), wednesday).sportRules
        assertNotNull(SportSemantics.problem("C : 11 tours, RH et RM autorisés.", rules), "le cas de l'essai du 06/11")
        assertNotNull(SportSemantics.problem("A : Dragon Trail Gardens, 5 tours. B : 6 tours en RM. C : 11 tours, RH et RM autorisés.", rules))
        assertNull(SportSemantics.problem("C : 11 tours, RH et RM obligatoires : il faudra passer aux stands pour changer de gomme.", rules))
        assertNull(SportSemantics.problem("En C, les deux gommes RH et RM sont imposées.", rules))
        assertNull(SportSemantics.problem("A : 5 tours en CS.", rules), "la réponse ne parle pas de la C")
    }

    @Test fun theToolTestRejectsTheAllowedOnlyAnswerEndToEnd() = runBlocking<Unit> {
        val now = wednesday
        val snap = ToolSnapshot.from(AiFacts(emptyList()), false, null, now, mapOf(FactTopic.SPORT to AnalysisFacts.sport(state(c(required)), now)))
        var n = 0
        val out = ToolLoop({ _ ->
            n++
            if (n == 1) ResponsesStream.Turn("", listOf(ResponsesStream.FunctionCall("c", "get_current_sport_events", "{}", "pitwall")), null)
            else ResponsesStream.Turn("Cette semaine, C : 11 tours, RH et RM autorisés.", emptyList(), null)
        }, clock = { now }).run("m", "i", VegaCheck.sportPrompt, snap)
        val v = VegaCheck.withTools(VegaCheck.SPORT_QUESTION, out.result, VegaCheck.sportLocalAnswer(snap)) { SportSemantics.problem(it, snap.sportRules) }
        assertFalse(v.fromChatGpt, v.verdict)
        assertTrue("sens" in v.verdict)
        assertTrue("OBLIGATOIRES" in v.answer, "la réponse locale dit l'obligation : ${v.answer}")
    }

    @Test fun onNineNovemberTheWeekOfTheSecondIsOldAndNeverTodaysRace() {
        val sec = AnalysisFacts.sport(state(c(required)), ms(LocalDate.of(2026, 11, 9), 10))
        val sheet = sec.facts.single { "Course Sport C" in it.text && "11 tours" in it.text }.text
        assertTrue(sheet.startsWith("SEMAINE ANCIENNE"), sheet)
        assertFalse(sec.sportRules.single().current)
        val snap = ToolSnapshot.from(AiFacts(emptyList()), false, null, ms(LocalDate.of(2026, 11, 9), 10), mapOf(FactTopic.SPORT to sec))
        assertFalse(VegaCheck.sportLocalAnswer(snap).contains("11 tours"), "une semaine ancienne n'est pas la course du jour")
        val stale = AnalysisFacts.sport(state(c(required), stale = true), wednesday)
        assertTrue(stale.missing.any { "PROGRAMME PÉRIMÉ" in it })
        assertTrue(stale.facts.filter { "11 tours" in it.text }.all { it.text.startsWith("SEMAINE ANCIENNE") })
    }

    @Test fun aSingleLatencySampleIsNotPresentedAsAReliableP95() {
        val l = LatencyStats().also { it.record(7_997) }
        assertTrue(l.summary()!!.contains("trop peu de mesures pour un p95 fiable"), l.summary())
        repeat(9) { l.record(3_000) }
        assertFalse(l.summary()!!.contains("trop peu"))
    }

    // ---------------------------------------------------------------- Radio : même contrôle de sens

    private class FakeToolChat(val answer: String) : AiProvider, ToolCapable {
        var calls = 0
        override val backend = AiBackend.CHATGPT
        override val modelId = "gpt-6.1-sol"
        override val ready = true
        override val status = "simulé"
        override val toolsEnabled = true
        override suspend fun complete(systemInstruction: String, prompt: String) = AiResult.Text(answer)
        override suspend fun completeWithTools(systemInstruction: String, prompt: String, snapshot: ToolSnapshot): AiResult {
            calls++
            return AiResult.Text(answer, snapshot.result(com.gt7pitwall.core.ai.tools.PitwallDataTools.Tool.SPORT).evidenceText)
        }
    }

    @Test fun theRadioNeverSaysMandatoryTyresAreMerelyAllowed() = runBlocking<Unit> {
        val rules = RaceRules(totalLaps = 12, fuelMultiplier = 7, mandatoryStops = 1, allowedTyres = setOf(TyreCompound.RH, TyreCompound.RM), startTyre = TyreCompound.RH)
        val controller = RaceController(storage = FakeStorage(rules = rules))
        val today = java.time.Instant.now().atZone(paris).toLocalDate()
        val thisMonday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        controller.applySchedule(state(c(required).copy(validFrom = thisMonday, validTo = thisMonday.plusDays(7))))
        val chat = FakeToolChat("Cette semaine, C : 11 tours, RH et RM autorisés.")
        val conv = RadioConversation(controller, Mutex()).also {
            it.cloud = AiRouter(mapOf(AiBackend.CHATGPT to chat), AiUsage(), AiMode.CHATGPT_PLAN, totalTimeoutMs = 12_000L)
        }
        RaceSim(controller, totalLaps = 12, perLap = 14f).also { it.start(); it.driveLaps(2) }
        val out = conv.onUtterance("pourquoi tu veux me faire rentrer maintenant", "pourquoi tu veux me faire rentrer maintenant", model = null)
        val said = out.filter { it.category == MessageCategory.ANSWER }.joinToString(" ") { it.text }
        assertEquals(1, chat.calls, "la question est bien passée par Véga (outils)")
        assertFalse("RH et RM autorisés" in said, said)
    }
}
