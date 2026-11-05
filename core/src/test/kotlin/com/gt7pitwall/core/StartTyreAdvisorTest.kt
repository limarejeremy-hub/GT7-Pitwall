package com.gt7pitwall.core

import com.gt7pitwall.core.strategy.Confidence
import com.gt7pitwall.core.strategy.Cost
import com.gt7pitwall.core.strategy.Learned
import com.gt7pitwall.core.strategy.StopCosts
import com.gt7pitwall.core.strategy.TyreCompound
import com.gt7pitwall.core.strategy.TyreCompound.RH
import com.gt7pitwall.core.strategy.TyreCompound.RM
import com.gt7pitwall.core.strategy.TyreCompound.RS
import com.gt7pitwall.core.tyres.CompoundBook
import com.gt7pitwall.core.tyres.CompoundPerformance
import com.gt7pitwall.core.tyres.StartTyreAdvisor
import com.gt7pitwall.core.tyres.StartTyreAdvisor.GridZone
import com.gt7pitwall.core.tyres.StartTyreAdvisor.TrafficEffect
import com.gt7pitwall.core.tyres.TyreModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #65 : conseil de gomme de départ. Historiques construits à la main (rythme des pneus jeunes, perte par tour d'âge,
 * pertes de début de course selon la zone de départ) : le calcul est vérifié, pas une course réelle.
 */
class StartTyreAdvisorTest {
    private fun learned(mean: Double, spread: Double = 50.0, n: Int = 8) = Learned(mean, spread, n, 4, Confidence.FAIRLY_RELIABLE)

    private fun perf(c: TyreCompound, paceMs: Double, slopeMs: Double) =
        CompoundPerformance(c, TyreModel.EMPTY, learned(paceMs), learned(slopeMs, 10.0), true, 40, 8, 4)

    /** SUGO Gr.3 (rythme réel du 28/10 : RS ≈ 80,8 s, RM ≈ 82,3 s) ; dégradations différentes par gomme. */
    private val book = CompoundBook(
        mapOf(RS to perf(RS, 80_800.0, 180.0), RM to perf(RM, 81_600.0, 60.0), RH to perf(RH, 82_400.0, 30.0)),
        null, 0,
    )
    private val costs = StopCosts(Cost(22_000.0, 1_000.0, "stands mesurés"), tyreService = Cost(8_000.0, 1_000.0, "pneus mesurés"))

    private fun sportC(grid: Pair<Int, Int?>? = null, traffic: TrafficEffect = TrafficEffect.NONE, b: CompoundBook = book) = StartTyreAdvisor.Input(
        totalLaps = 14, allowed = setOf(RH, RM, RS), required = setOf(RM, RS), mandatoryStops = 0, tyreLife = null,
        book = b, costs = costs, grid = grid, traffic = traffic,
    )

    @Test fun mandatoryMediumsAndSoftsGiveALegalOneStopPlanWithACompleteSentence() {
        val a = StartTyreAdvisor.advise(sportC(grid = 4 to 16))
        assertEquals(setOf(RH, RM, RS), a.legalStarts, "les durs restent légaux au départ, au prix de deux arrêts")
        for (plan in a.bestByStart.values) {
            assertTrue(plan.compounds.containsAll(listOf(RM, RS)), plan.toString())
            assertEquals(if (plan.start == RH) 2 else 1, plan.stops.size, plan.toString())
        }
        assertTrue(a.sentence.startsWith("P4 sur 16 : "), a.sentence)
        assertTrue("Conseil seulement" in a.sentence || "options proches" in a.sentence, a.sentence)
    }

    @Test fun theGridAloneDecidesNothingButMeasuredStartLossesCanReverseTheChoice() {
        // Sans effet mesuré, P2 et P14 donnent le même conseil : la place seule ne dicte rien. Ici RS → RM et RM → RS ont
        // le même temps total (le modèle n'a rien qui dépende de l'ordre) : options proches, dit comme tel.
        val front = StartTyreAdvisor.advise(sportC(grid = 2 to 16))
        val back = StartTyreAdvisor.advise(sportC(grid = 14 to 16))
        assertEquals(front.recommended, back.recommended)
        assertNull(front.recommended, front.sentence)
        assertTrue(back.basis.any { "ne change pas le calcul" in it }, back.basis.toString())
        // Historique du pilote : partant derrière, les tendres ont perdu 2,5 s par tour sur les trois premiers tours
        // (trafic), les mediums 0,3 s ; partant devant, ce sont les mediums qui ont perdu 1,5 s par tour. L'ordre s'inverse.
        val traffic = TrafficEffect(
            mapOf(
                (GridZone.BACK to RS) to learned(2_500.0, 300.0, 4), (GridZone.BACK to RM) to learned(300.0, 300.0, 4),
                (GridZone.FRONT to RS) to learned(0.0, 200.0, 4), (GridZone.FRONT to RM) to learned(1_500.0, 200.0, 4),
            ),
        )
        val p2 = StartTyreAdvisor.advise(sportC(grid = 2 to 16, traffic = traffic))
        val p14 = StartTyreAdvisor.advise(sportC(grid = 14 to 16, traffic = traffic))
        assertEquals(RS, p2.recommended, p2.sentence)
        assertEquals(RM, p14.recommended, p14.sentence)
        assertTrue(p14.sentence.startsWith("P14 sur 16 : je conseille de partir en course mediums"), p14.sentence)
    }

    @Test fun closeOptionsAreSaidAsSuch() {
        val even = CompoundBook(mapOf(RS to perf(RS, 81_000.0, 60.0), RM to perf(RM, 81_000.0, 60.0)), null, 0)
        val a = StartTyreAdvisor.advise(sportC(b = even))
        assertNull(a.recommended)
        assertTrue("options proches" in a.sentence, a.sentence)
        assertTrue(a.sentence.startsWith("Sans place de grille mesurée : "), a.sentence)
    }

    @Test fun withoutHistoryOnlyTheLegalChoicesAreSaid() {
        val a = StartTyreAdvisor.advise(sportC(b = CompoundBook.EMPTY))
        assertNull(a.recommended)
        assertEquals("Sans place de grille mesurée : départ possible en course durs ou course mediums ou course tendres ; historique insuffisant pour en conseiller un.", a.sentence)
    }

    @Test fun aSingleStintIsNotEnoughForACompound() {
        val thin = CompoundBook(
            mapOf(RS to perf(RS, 80_800.0, 180.0), RM to CompoundPerformance(RM, TyreModel.EMPTY, null, null, false, 4, 1, 1)),
            null, 0,
        )
        val a = StartTyreAdvisor.advise(sportC(b = thin))
        assertNull(a.recommended, "les RM sont imposées mais non chiffrables : aucun plan complet")
        assertTrue(a.bestByStart.isEmpty())
    }

    @Test fun hardMandatoryAndNoStopRaces() {
        // RH obligatoire, aucune autre obligation : départ en durs ou arrêt pour les durs, toujours légal.
        val hard = StartTyreAdvisor.advise(
            StartTyreAdvisor.Input(10, setOf(RH, RM, RS), setOf(RH), 0, null, book, costs),
        )
        assertTrue(hard.bestByStart.values.all { RH in it.compounds }, hard.bestByStart.toString())
        // Aucune obligation, course courte : pas d'arrêt dans le meilleur plan de chaque départ.
        val sprint = StartTyreAdvisor.advise(StartTyreAdvisor.Input(5, setOf(RM, RS), emptySet(), 0, null, book, costs))
        assertTrue(sprint.bestByStart.values.all { it.stops.isEmpty() }, sprint.bestByStart.toString())
        assertEquals(RS, sprint.recommended, sprint.sentence)
    }

    @Test fun contradictoryOrImpossibleRulesGiveNoAdvice() {
        val contradictory = StartTyreAdvisor.advise(StartTyreAdvisor.Input(14, setOf(RS), setOf(RM, RS), 0, null, book, costs))
        assertNull(contradictory.recommended)
        assertTrue("contradictoire" in contradictory.sentence, contradictory.sentence)
        val unknown = StartTyreAdvisor.advise(StartTyreAdvisor.Input(0, setOf(RM, RS), setOf(RM, RS), 0, null, book, costs))
        assertNull(unknown.recommended)
    }

    @Test fun aDeclaredTyreLifeForcesMoreStints() {
        // Usure ×8, pneus qui tiennent 5 tours d'après le pilote : au moins 2 arrêts sur 14 tours.
        val a = StartTyreAdvisor.advise(sportC().copy(tyreLife = 5))
        assertTrue(a.bestByStart.values.all { it.stops.size >= 2 }, a.bestByStart.toString())
        a.bestByStart.values.forEach { p ->
            val stints = (listOf(0) + p.stops + 14).zipWithNext { x, y -> y - x }
            assertTrue(stints.all { it <= 5 }, stints.toString())
        }
    }
}
