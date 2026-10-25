package com.gt7pitwall.core

import com.gt7pitwall.core.race.EventProfile
import com.gt7pitwall.core.race.LapEntry
import com.gt7pitwall.core.race.ProfileId
import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.race.SessionContext
import com.gt7pitwall.core.race.SessionMode
import com.gt7pitwall.core.race.SessionOutcome
import com.gt7pitwall.core.race.SessionRecord
import com.gt7pitwall.core.race.StopEntry
import com.gt7pitwall.core.race.TyreEntry
import com.gt7pitwall.core.strategy.Confidence
import com.gt7pitwall.core.strategy.Cost
import com.gt7pitwall.core.strategy.RaceRules
import com.gt7pitwall.core.strategy.StopCosts
import com.gt7pitwall.core.strategy.StopEvidence
import com.gt7pitwall.core.strategy.TyreCompound
import com.gt7pitwall.core.strategy.TyreService
import com.gt7pitwall.core.tyres.CompoundBook
import com.gt7pitwall.core.tyres.TyreModel
import com.gt7pitwall.core.tyres.TyreScenarioKind
import com.gt7pitwall.core.tyres.TyreScenarios
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Intelligence de performance par gomme : rythme, perte par tour d'âge corrigée du carburant, et scénarios jusqu'à
 * l'arrivée. L'historique est synthétique, avec des valeurs vraies connues, pour vérifier ce que le modèle retrouve.
 */
class CompoundPerformanceTest {

    private val profile = ProfileId.of(SessionMode.SPORT_OFFICIAL, Series.DAILY_B)
    private val rules = RaceRules(totalLaps = 20, trackId = 462, tyreMultiplier = 8)
    private val ctx = SessionContext(EventProfile(id = profile), rules, 3607, 100f)

    /** Vraies valeurs : niveau à pneus neufs et carburant nul (ms), perte par tour d'âge (ms), effet du carburant. */
    private val base = mapOf(TyreCompound.RS to 98_000, TyreCompound.RM to 99_000, TyreCompound.RH to 100_000)
    private val slope = mapOf(TyreCompound.RS to 500, TyreCompound.RM to 250, TyreCompound.RH to 100)
    private val msPerFuel = 30.0

    /**
     * Course de 20 tours : relais [a] jusqu'au tour 10, pneus [b] ensuite. [refuelOnly] : plein sans changement de pneus à
     * la fin du tour 15 (c'est lui qui sépare l'effet du carburant de celui de l'âge des pneus).
     */
    private fun race(n: Int, a: TyreCompound, b: TyreCompound, refuelOnly: Boolean = true): SessionRecord {
        val rnd = Random(n)
        var fuel = 80f
        val laps = (1..20).map { l ->
            val c = if (l <= 10) a else b
            val age = if (l <= 10) l - 1 else l - 11
            val mid = fuel - 2f
            fuel -= 4f
            if (l == 10) fuel = 70f
            if (refuelOnly && l == 15) fuel += 40f
            val t = base.getValue(c) + slope.getValue(c) * age + msPerFuel * mid + rnd.nextInt(-120, 120)
            LapEntry(l, t.toInt(), 4f, refuelled = l == 10 || (refuelOnly && l == 15), fuelAfter = fuel, tyreAge = age, tyreCompound = c)
        }
        val stops = listOf(StopEntry(10, StopEvidence.REFUEL, 50f, TyreService.CHANGED)) +
            if (refuelOnly) listOf(StopEntry(15, StopEvidence.REFUEL, 40f, TyreService.NOT_CHANGED)) else emptyList()
        return SessionRecord("h$n", ctx, n.toLong(), n.toLong(), SessionOutcome.FINISHED, laps, stops, startTyre = a, tyres = listOf(TyreEntry(11, b)))
    }

    private val pairs = listOf(
        TyreCompound.RS to TyreCompound.RM, TyreCompound.RM to TyreCompound.RH, TyreCompound.RH to TyreCompound.RS,
    )
    private fun history(refuelOnly: Boolean = true) = (0 until 6).map { race(it + 1, pairs[it % 3].first, pairs[it % 3].second, refuelOnly) }

    @Test
    fun thePerCompoundModelSeparatesFuelFromTyreAge() {
        val book = CompoundBook.learn(ctx, history())
        val k = assertNotNull(book.fuelEffect, "pleins sans changement : effet du carburant séparable")
        assertEquals(msPerFuel, k.msPerUnit, 5.0)
        for (c in listOf(TyreCompound.RS, TyreCompound.RM, TyreCompound.RH)) {
            val p = assertNotNull(book[c])
            assertTrue(p.fuelCorrected)
            assertEquals(slope.getValue(c).toDouble(), p.slopeMsPerLap!!.mean, 40.0, "${c.spoken} : ${p.summary()}")
            assertTrue(p.confidence != Confidence.INSUFFICIENT, p.summary())
            assertTrue("corrigé du carburant" in p.summary(), p.summary())
        }
        // Rythme des pneus jeunes : les tendres plus rapides que les mediums, eux-mêmes plus rapides que les durs.
        val pace = listOf(TyreCompound.RS, TyreCompound.RM, TyreCompound.RH).map { book[it]!!.paceMs!!.mean }
        assertEquals(pace.sorted(), pace)
    }

    @Test
    fun withoutRefuelOnlyStopsTheFuelEffectIsNotInvented() {
        val book = CompoundBook.learn(ctx, history(refuelOnly = false))
        assertNull(book.fuelEffect)
        val rh = book[TyreCompound.RH]!!
        assertFalse(rh.fuelCorrected)
        assertTrue("effet du carburant non séparé" in rh.summary(), rh.summary())
        // La pente brute est faussée par l'allègement de la voiture (4 unités par tour × 30 ms) : c'est dit, pas corrigé au hasard.
        assertEquals(slope.getValue(TyreCompound.RH) - 4 * msPerFuel, rh.slopeMsPerLap!!.mean, 40.0)
    }

    @Test
    fun noComparableHistoryMeansNoCompoundAdvice() {
        assertTrue(CompoundBook.learn(ctx, emptyList()).compounds.isEmpty())
        // Autre multiplicateur d'usure : incompatible, rien n'est réutilisé.
        val other = SessionContext(EventProfile(id = profile), rules.copy(tyreMultiplier = 1), 3607, 100f)
        assertTrue(CompoundBook.learn(other, history()).compounds.isEmpty())
        val r = TyreScenarios.compare(input(book = CompoundBook.EMPTY))
        assertNull(r.recommended)
        assertTrue("Historique insuffisant" in r.reason, r.reason)
    }

    private val costs = StopCosts(base = Cost(25_000.0, 1_500.0, "test"), tyreService = Cost(8_000.0, 1_000.0, "test"))

    private fun input(
        book: CompoundBook = CompoundBook.learn(ctx, history()),
        current: TyreCompound? = TyreCompound.RM,
        lap: Int = 6,
        age: Int = 5,
        planned: List<Int> = listOf(13),
        allowed: Set<TyreCompound> = setOf(TyreCompound.RS, TyreCompound.RM, TyreCompound.RH),
        missing: Set<TyreCompound> = emptySet(),
        certain: Boolean = true,
    ) = TyreScenarios.Input(
        currentLap = lap, totalLaps = 20, current = current, tyreAge = age, ageCertain = certain,
        liveLaps = (1 until age).map { a -> a to (base.getValue(current ?: TyreCompound.RM) + slope.getValue(current ?: TyreCompound.RM) * a + 2_000) },
        book = book, costs = costs, plannedStops = planned, allowed = allowed, requiredMissing = missing, tyreLife = null,
    )

    @Test
    fun scenariosAreComparedOnTheTotalTimeToTheFlag() {
        val r = TyreScenarios.compare(input())
        assertEquals(TyreScenarioKind.entries.toSet(), r.scenarios.map { it.kind }.toSet())
        assertTrue(r.scenarios.all { it.estimable }, r.scenarios.toString())
        // Changer au ravitaillement prévu (fin du tour 13) ne coûte que le changement de pneus : c'est là qu'on change.
        for (s in r.scenarios.filter { it.kind != TyreScenarioKind.CONTINUE }) assertEquals(13, s.stopLap, s.toString())
        // Continuer : niveau mesuré dans ce relais + perte apprise sur les âges 5 à 19 (15 tours).
        val cont = r.of(TyreScenarioKind.CONTINUE)!!
        val expected = (base.getValue(TyreCompound.RM) + 2_000) * 15.0 + slope.getValue(TyreCompound.RM) * (5..19).sum()
        assertEquals(expected, cont.estimatedMs!!.toDouble(), expected * 0.002)
        assertTrue(r.basis.any { "effet du carburant" in it }, r.basis.toString())

        // Tendres usés en fin de relais : continuer coûte cher, changer est clairement meilleur.
        val worn = TyreScenarios.compare(input(current = TyreCompound.RS, age = 12, planned = listOf(8)))
        assertTrue(worn.changeAdvised, worn.reason)
        assertTrue(worn.recommended?.kind != TyreScenarioKind.CONTINUE, worn.reason)
        assertTrue("de mieux que" in worn.reason, worn.reason)
        // Mediums usés modérément, arrêt prévu au tour 13 : écarts faibles, rien d'affirmé au-delà de l'incertitude.
        val r2 = TyreScenarios.compare(input())
        if (r2.recommended != null) assertTrue("de mieux que" in r2.reason && "±" in r2.reason, r2.reason)
    }

    @Test
    fun scenariosRespectTheRulesAndNeverGuess() {
        // Durs imposés : ni continuer, ni tendres.
        val req = TyreScenarios.compare(input(missing = setOf(TyreCompound.RH)))
        assertFalse(req.of(TyreScenarioKind.CONTINUE)!!.feasible)
        assertFalse(req.of(TyreScenarioKind.SOFT)!!.feasible)
        assertTrue(req.of(TyreScenarioKind.HARD)!!.feasible)
        assertTrue(req.recommended == null || req.recommended!!.kind == TyreScenarioKind.HARD)
        // Tendres non autorisés.
        val allowed = TyreScenarios.compare(input(allowed = setOf(TyreCompound.RM, TyreCompound.RH)))
        assertTrue(allowed.of(TyreScenarioKind.SOFT)!!.issues.any { "non autorisés" in it })
        // Gomme actuelle inconnue, âge incertain : aucune comparaison.
        assertTrue(TyreScenarios.compare(input(current = null)).scenarios.isEmpty())
        assertTrue("Gomme actuelle inconnue" in TyreScenarios.compare(input(current = null)).reason)
        assertTrue("Âge des pneus incertain" in TyreScenarios.compare(input(certain = false)).reason)
        // Historique des seuls mediums : tendres et durs non chiffrés, jamais devinés.
        val onlyMedium = CompoundBook.learn(ctx, history().map { s ->
            s.copy(laps = s.laps.map { it.copy(tyreCompound = TyreCompound.RM) }, startTyre = TyreCompound.RM, tyres = listOf(TyreEntry(11, TyreCompound.RM)))
        })
        val r = TyreScenarios.compare(input(book = onlyMedium))
        for (k in listOf(TyreScenarioKind.SOFT, TyreScenarioKind.HARD)) {
            val s = r.of(k)!!
            assertNull(s.estimatedMs)
            assertTrue(s.issues.any { "historique insuffisant" in it }, s.issues.toString())
        }
        assertTrue(r.recommended == null || r.recommended!!.kind in setOf(TyreScenarioKind.CONTINUE, TyreScenarioKind.MEDIUM))
        // Jamais de pourcentage d'usure, ni dans la raison ni dans la base.
        for (rep in listOf(req, allowed, r)) assertFalse(Regex("\\d+ ?%").containsMatchIn(rep.reason + rep.basis), rep.reason)
        // TyreModel (déjà existant) reste cohérent avec le même historique.
        assertNotNull(TyreModel.learn(ctx, history(), TyreCompound.RM).slopeMsPerLap)
    }
}
