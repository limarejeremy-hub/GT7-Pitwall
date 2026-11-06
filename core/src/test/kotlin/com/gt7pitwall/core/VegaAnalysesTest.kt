package com.gt7pitwall.core

import com.gt7pitwall.core.ai.AiFacts
import com.gt7pitwall.core.ai.AiReply
import com.gt7pitwall.core.ai.AiResult
import com.gt7pitwall.core.ai.EngineerPrompt
import com.gt7pitwall.core.ai.FactProvenance
import com.gt7pitwall.core.ai.FactTopic
import com.gt7pitwall.core.ai.RadioAi
import com.gt7pitwall.core.ai.Topic
import com.gt7pitwall.core.ai.chatgpt.ResponsesStream
import com.gt7pitwall.core.ai.tools.AnalysisFacts
import com.gt7pitwall.core.ai.tools.LatencyStats
import com.gt7pitwall.core.ai.tools.PitwallDataTools
import com.gt7pitwall.core.ai.tools.ToolLoop
import com.gt7pitwall.core.ai.tools.ToolSnapshot
import com.gt7pitwall.core.analysis.CornerReport
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.sport.Edition
import com.gt7pitwall.core.sport.FieldStatus
import com.gt7pitwall.core.sport.ScheduleState
import com.gt7pitwall.core.sport.SourceRef
import com.gt7pitwall.core.sport.Sourced
import com.gt7pitwall.core.strategy.RaceReview
import com.gt7pitwall.core.strategy.RaceRules
import com.gt7pitwall.core.strategy.TyreCompound
import com.gt7pitwall.core.tyres.StartTyreAdvisor
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Issue #70 / #68 (V492) : quatre exemples reproductibles demandés par le pilote — question virage, gomme de départ,
 * fiche Sport de la semaine, débrief — plus la latence. Données SIMULÉES (fixtures), transport simulé : aucune course
 * réelle, aucun appel réseau, rien de validé sur le Fold ni la PS5.
 */
class VegaAnalysesTest {
    private val now = System.currentTimeMillis()

    private fun snapshot(sections: Map<FactTopic, AnalysisFacts.Section>) = ToolSnapshot.from(AiFacts(emptyList()), true, 6, now, sections)

    /** Le modèle appelle [tool], puis répond [answer] ; renvoie l'issue de la boucle. */
    private fun ask(snap: ToolSnapshot, tool: String, answer: String): ToolLoop.Outcome = runBlocking {
        var n = 0
        ToolLoop({ _ ->
            n++
            if (n == 1) ResponsesStream.Turn("", listOf(ResponsesStream.FunctionCall("c1", tool, "{}", "pitwall")), null, ttfbMs = 400)
            else ResponsesStream.Turn(answer, emptyList(), null, ttfbMs = 300)
        }).run("gpt-6.1-sol", "Tu es Véga.", "question", snap)
    }

    /** Contrôle radio de la réponse : seuls les faits rendus par les outils autorisent ses chiffres. */
    private fun verify(question: String, out: ToolLoop.Outcome): AiReply {
        val t = out.result as AiResult.Text
        return RadioAi.parse(t.text, question, AiFacts(t.evidence.lines().filter { it.isNotBlank() }), extraSource = t.evidence)
    }

    // ---------------------------------------------------------------- 1. « Pourquoi je perds du temps au virage 5 ? »

    private val corners = listOf(
        CornerReport.Line(5, "T5", passes = 6, bestMs = 4_200, meanLossMs = 600, entryKmh = 182f, minKmh = 96f, bestMinKmh = 104f, lockups = 2, wheelspins = 0, cause = "freinage trop tardif", confidence = CornerReport.Confidence.HIGH),
        CornerReport.Line(2, "T2", passes = 6, bestMs = 3_100, meanLossMs = 20, entryKmh = 150f, minKmh = 88f, bestMinKmh = 89f, lockups = 0, wheelspins = 0, cause = null, confidence = CornerReport.Confidence.MEDIUM),
        CornerReport.Line(9, "V9", passes = 1, bestMs = 2_800, meanLossMs = 900, entryKmh = 120f, minKmh = 70f, bestMinKmh = 70f, lockups = 0, wheelspins = 1, cause = null, confidence = CornerReport.Confidence.LOW),
    )

    @Test fun example1CornerQuestionUsesOnlyComparablePasses() {
        val sec = AnalysisFacts.corners(corners, tyreOrDriving = null, traction = null)
        assertEquals(2, sec.facts.size, "V9 (1 passage) n'est pas chiffré")
        assertTrue(sec.missing.any { "V9" in it && "comparables" in it })
        assertTrue(sec.facts.all { it.provenance == FactProvenance.CALCULATED && it.reliability?.contains("confiance") == true })
        val out = ask(snapshot(mapOf(FactTopic.CORNERS to sec)), "get_corner_comparison", "Au T5 tu perds 0,60 s en moyenne : freinage trop tardif, 2 blocages.")
        val r1 = verify("pourquoi je perds du temps au virage 5", out)
        assertIs<AiReply.Answer>(r1, "$r1 / ${(out.result as AiResult.Text).evidence}")
        val invented = ask(snapshot(mapOf(FactTopic.CORNERS to sec)), "get_corner_comparison", "Au V9 tu perds 0,9 s.")
        assertIs<AiReply.Rejected>(verify("et au virage 9", invented), "virage non comparable : son chiffre n'est pas rendu, donc rejeté")
    }

    // ---------------------------------------------------------------- 2. « Quelle gomme au départ ? »

    @Test fun example2StartTyreAdviceComesFromTheEngineOrIsMissing() {
        val none = AnalysisFacts.startTyre(null)
        assertTrue(none.facts.isEmpty() && none.missing.single().contains("aucun conseil"))
        val advice = StartTyreAdvisor.Advice(
            StartTyreAdvisor.Status.RECOMMENDED, TyreCompound.RS, null, emptyMap(), setOf(TyreCompound.RS, TyreCompound.RM),
            "P4 sur 16 : je conseille de partir en course tendres, mediums fin du tour 7 : environ 3,2 s de mieux.",
            listOf("règlement : GTPlanet", "3 courses comparables"),
        )
        val sec = AnalysisFacts.startTyre(advice)
        assertTrue(sec.facts.first().text.startsWith("P4 sur 16"))
        assertTrue(sec.facts.any { "RS, RM" in it.text || "RM, RS" in it.text })
        val out = ask(snapshot(mapOf(FactTopic.START_TYRE to sec)), "get_start_tyre_advice", "Pars en tendres, mediums fin du tour 7 : environ 3,2 s de mieux.")
        assertIs<AiReply.Answer>(verify("quelle gomme au départ", out))
        val close = AnalysisFacts.startTyre(advice.copy(status = StartTyreAdvisor.Status.CLOSE, recommended = null))
        assertTrue(close.missing.any { "options proches" in it }, "pas de recommandation ferme : dit")
    }

    // ---------------------------------------------------------------- 3. « Quelle course Sport cette semaine ? »

    private val paris = ZoneId.of("Europe/Paris")
    private val today = java.time.Instant.ofEpochMilli(now).atZone(paris).toLocalDate()
    private val gtp = SourceRef("GTPlanet", "https://www.gtplanet.net", now - 3_600_000)

    private fun edition(series: Series, from: LocalDate, laps: Sourced<Int>, required: Set<TyreCompound>?, stops: Int?) = Edition(
        series, from, from.plusDays(6),
        trackName = Sourced("Suzuka Circuit", FieldStatus.CONFIRMED, listOf(gtp)), trackId = null,
        laps = laps, cars = Sourced("Gr.3", FieldStatus.PROBABLE, listOf(gtp)), bop = Sourced.unknown(), settings = Sourced.unknown(),
        startType = Sourced.unknown(), allowedTyres = Sourced(setOf(TyreCompound.RH, TyreCompound.RM), FieldStatus.PROBABLE, listOf(gtp)),
        requiredTyres = required?.let { Sourced(it, FieldStatus.PROBABLE, listOf(gtp)) } ?: Sourced.unknown(),
        mandatoryStops = stops?.let { Sourced(it, FieldStatus.PROBABLE, listOf(gtp)) } ?: Sourced.unknown(),
        fuelMultiplier = Sourced(3, FieldStatus.PROBABLE, listOf(gtp)), tyreMultiplier = Sourced(5, FieldStatus.PROBABLE, listOf(gtp)),
        sources = listOf(gtp),
    )

    @Test fun example3SportSheetShowsSourcesConflictsAndNoInventedStop() {
        val c = edition(Series.DAILY_C, today.minusDays(1), Sourced(11, FieldStatus.CONFIRMED, listOf(gtp)), setOf(TyreCompound.RH, TyreCompound.RM), stops = null)
        val b = edition(Series.DAILY_B, today.minusDays(1), Sourced(null, FieldStatus.CONFLICT, listOf(gtp), "10 ou 12"), null, stops = 0)
        val old = edition(Series.DAILY_A, today.minusDays(20), Sourced(8, FieldStatus.PROBABLE, listOf(gtp)), null, stops = null)
        val sec = AnalysisFacts.sport(ScheduleState(mapOf(Series.DAILY_A to old, Series.DAILY_B to b, Series.DAILY_C to c), emptyList(), now - 3_600_000, null, stale = false), now)
        val cText = sec.facts.single { it.text.startsWith("Course Sport C (") }.text
        assertTrue("11 tours [confirmé]" in cText && "×3" in cText && "×5" in cText && "en cours" in cText, cText)
        assertTrue(sec.facts.any { it.text.startsWith("Course Sport C : les gommes") && "OBLIGATOIRES" in it.text && "même si aucun arrêt obligatoire n'est écrit" in it.text })
        assertTrue(sec.conflicts.any { "Course Sport B" in it && "tours" in it })
        assertTrue(sec.facts.single { it.text.startsWith("Course Sport B (") }.text.contains("tours contradictoire entre sources"), "un champ contradictoire n'a pas de valeur")
        assertTrue(sec.facts.single { "Course Sport A" in it.text }.text.startsWith("SEMAINE ANCIENNE"))
        assertTrue(sec.facts.all { it.provenance == FactProvenance.PUBLISHED })
        val rules = snapshot(mapOf(FactTopic.SPORT to sec)).result(PitwallDataTools.Tool.SPORT)
        assertTrue(rules.confidence.startsWith("faible"), "contradiction : confiance faible")
        val nothing = AnalysisFacts.sport(null, now)
        assertTrue(nothing.facts.isEmpty() && nothing.missing.single().contains("pas encore téléchargé"))
    }

    // ---------------------------------------------------------------- 4. « Fais mon débrief »

    @Test fun example4DebriefReadsTheRaceReviewAndInventsNoOpponent() {
        val review = RaceReview(
            sessionId = "session-2610-sim", finished = true, fuelPredicted = 7.1f, fuelSource = "MEASURED", fuelActual = 7.4f,
            plannedStops = listOf(6), actualStops = listOf(6), planChanges = 0, pitLossPredictedMs = 24_000, pitLossActualMs = listOf(26_500),
            gridPosition = 8, finishPosition = 5, adviceGiven = 3, adviceImproved = 2,
        )
        val sec = AnalysisFacts.debrief(review, listOf("T5 : freinage trop tardif sur 4 tours sur 6."))
        assertTrue(sec.facts.size >= 2, sec.facts.toString())
        assertTrue(sec.missing.any { "adversaires" in it })
        val empty = AnalysisFacts.debrief(null, emptyList())
        assertTrue(empty.facts.isEmpty() && empty.missing.any { "aucun bilan" in it })
    }

    // ---------------------------------------------------------------- Branchement au contrôleur et latence

    @Test fun theControllerSnapshotCarriesTheAnalysisSectionsWithoutSideEffects() {
        val rules = RaceRules(totalLaps = 12, fuelMultiplier = 7, mandatoryStops = 1, allowedTyres = setOf(TyreCompound.RH, TyreCompound.RM), startTyre = TyreCompound.RH)
        val sim = RaceSim(RaceController(storage = FakeStorage(rules = rules)), totalLaps = 12, perLap = 14f).also { it.start(); it.driveLaps(3) }
        val before = sim.controller.startTyreAdvice
        val snap = sim.controller.toolSnapshot(sim.controller.aiFacts(), 4, now)
        assertEquals(before, sim.controller.startTyreAdvice, "aucun conseil relancé")
        val sport = snap.result(PitwallDataTools.Tool.SPORT)
        assertTrue(sport.facts.isEmpty() && sport.missing.any { "pas encore téléchargé" in it })
        val start = snap.result(PitwallDataTools.Tool.START_TYRE)
        assertTrue(start.missing.any { "aucun conseil" in it })
        assertEquals(10, PitwallDataTools.Tool.entries.size)
        assertTrue(PitwallDataTools.Tool.entries.all { it.toolName.startsWith("get_") }, "toujours lecture seule")
    }

    @Test fun theLightQuestionDoesNotRepeatTheFacts() {
        val facts = AiFacts(listOf("Carburant : 38 % restant, 7,1 % par tour.", "Stratégie : arrêt fin du tour 6."))
        val ctx = EngineerPrompt.Context(true, 4, Topic.FUEL, facts, analysis = "Arrêt fin du tour 6, plein de 85 %.", memory = "", recent = emptyList())
        val full = EngineerPrompt.build("quel est mon plan carburant", ctx)
        val light = EngineerPrompt.buildForTools("quel est mon plan carburant", ctx)
        assertTrue("7,1 %" in full && "7,1 %" !in light, "faits non recopiés quand les outils sont actifs")
        assertTrue("Arrêt fin du tour 6, plein de 85 %" in light, "le calcul du moteur reste (il fait foi)")
        assertTrue(light.length < full.length)
    }

    @Test fun eachRoundIsTimedAndPercentilesAreComputed() {
        val sec = AnalysisFacts.corners(corners, null, null)
        val out = ask(snapshot(mapOf(FactTopic.CORNERS to sec)), "get_corner_comparison", "Au T5 tu perds 0,6 s.")
        assertEquals(2, out.stats.size)
        assertEquals(listOf(400L, 300L), out.stats.map { it.ttfbMs })
        assertEquals(1, out.stats.first().calls)
        assertTrue(out.stats[1].requestChars > out.stats[0].requestChars, "le 2e aller-retour porte la sortie de l'outil")
        assertTrue("aller-retour 2" in out.timing)
        val l = LatencyStats()
        listOf(3000L, 3200L, 3100L, 8230L, 2900L).forEach(l::record)
        assertEquals(3100L, l.percentile(50))
        assertEquals(8230L, l.percentile(95))
        assertTrue(l.summary()!!.startsWith("p50 3,1 s · p95 8,2 s"), l.summary())
    }
}
