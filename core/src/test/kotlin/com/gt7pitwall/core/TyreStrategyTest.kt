package com.gt7pitwall.core

import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.race.EventProfile
import com.gt7pitwall.core.race.LapEntry
import com.gt7pitwall.core.race.ProfileId
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.RaceJson
import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.race.SessionContext
import com.gt7pitwall.core.race.SessionMode
import com.gt7pitwall.core.race.SessionOutcome
import com.gt7pitwall.core.race.SessionRecord
import com.gt7pitwall.core.race.StopEntry
import com.gt7pitwall.core.race.TyreEntry
import com.gt7pitwall.core.strategy.Confidence
import com.gt7pitwall.core.strategy.DataSource
import com.gt7pitwall.core.strategy.OptionKind
import com.gt7pitwall.core.strategy.RacePlanner
import com.gt7pitwall.core.strategy.RaceRules
import com.gt7pitwall.core.strategy.StopEvidence
import com.gt7pitwall.core.strategy.StrategyOptions
import com.gt7pitwall.core.strategy.TyreCompound
import com.gt7pitwall.core.strategy.TyreInputs
import com.gt7pitwall.core.strategy.TyreService
import com.gt7pitwall.core.strategy.Cost
import com.gt7pitwall.core.strategy.StopCosts
import com.gt7pitwall.core.strategy.StopKind
import com.gt7pitwall.core.tyres.TyreModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** D3 / D4 : apprentissage des relais pneus et stratégie pneus intégrée au moteur existant. */
class TyreStrategyTest {

    // ------------------------------------------------------------------ Comparaison (StrategyOptions)

    /** Course de 20 tours, carburant largement suffisant. [lap] : tour en cours ; [tyreAge] : âge des pneus. */
    private fun input(lap: Int = 11, tyreAge: Int = 10, fuelNow: Float = 60f, perLap: Float = 4f, r: RaceRules = RaceRules(totalLaps = 20)) =
        RacePlanner.Input(
            rules = r, gameTotalLaps = 20, currentLap = lap, fuelNow = fuelNow, tank = 100f, perLap = perLap,
            fuelSource = DataSource.MEASURED, avgLapMs = 100_000, raceElapsedMs = null, stopsDone = 0, tyreAgeLaps = tyreAge,
        )

    /** Arrêts pneus seuls observés : 25 s ± 2 s, changement des gommes compris (rien à ajouter). */
    private val observedTyreStops = StopCosts(base = null, observed = mapOf(StopKind.TYRES_ONLY to Cost(25_000.0, 2_000.0, "pneus seuls observés (4 arrêts)")))

    @Test
    fun fuelIsEnoughButTyresCostTimeSoATyresOnlyStopIsRecommended() {
        // Au tour 10 bouclé : chronos qui se dégradent nettement (400 ms par tour d'âge), arrêts pneus seuls déjà observés.
        val pace = StrategyOptions.Pace(100_000.0, slopeMsPerLap = 400.0, slopeSpread = 40.0, stopCosts = observedTyreStops)
        val rep = StrategyOptions.compare(input(), pace, emptyList(), emptyList(), TyreInputs(degrading = true))!!
        assertTrue(rep.option(OptionKind.NORMAL)!!.stopLaps.isEmpty(), "le carburant suffit")
        assertEquals(OptionKind.TYRES_NOW, rep.recommended, rep.reason)
        assertTrue(
            rep.reason.startsWith(
                "Le carburant suffit, mais tes pneus nous coûtent du temps. Je recommande un arrêt pour les pneus uniquement, " +
                    "fin du tour 11. Aucun ravitaillement nécessaire.",
            ),
            rep.reason,
        )
        val stop = rep.recommendedOption.plan!!.stops.single()
        assertTrue(stop.tyresOnly && stop.forTyres && stop.fuel == 0, stop.toString())
        assertEquals(listOf("pneus"), stop.reasons)
        assertTrue(rep.recommendedOption.assumptions.isEmpty(), "aucune hypothèse : le temps observé comprend le changement")
    }

    @Test
    fun theAssumedTyreTimeWeighsBeforeAnyGainIsAnnounced() {
        // Même situation, mais seul un temps d'arrêt au contenu non précisé est connu : le changement de pneus s'y ajoute
        // (10 s supposées, ± 10 s). Le gain n'est plus démontré, et l'hypothèse est dite.
        val pace = StrategyOptions.Pace(100_000.0, slopeMsPerLap = 400.0, slopeSpread = 40.0, pitLossMs = 25_000.0, pitLossSpread = 2_000.0)
        val rep = StrategyOptions.compare(input(), pace, emptyList(), emptyList(), TyreInputs(degrading = true))!!
        val now = rep.option(OptionKind.TYRES_NOW)!!
        assertTrue(now.assumptions.any { "hypothèse provisoire" in it }, now.toString())
        assertTrue(now.uncertaintyMs!! >= 10_000, "l'incertitude de l'hypothèse compte : $now")
        assertEquals(OptionKind.NORMAL, rep.recommended)
        assertTrue("je ne peux pas encore démontrer" in rep.reason, rep.reason)
        assertTrue(rep.basis.any { it.startsWith("hypothèse : ") && "10 s" in it }, rep.basis.toString())
    }

    // ------------------------------------------------------------------ Coût d'un arrêt selon son contenu

    private val lane = Cost(20_000.0, 2_000.0, "voie des stands")
    private val laneCosts = StopCosts(base = lane, baseIncludesRefuel = false)

    @Test
    fun stopCostsNeverCountAnInterventionTwice() {
        // Composés : passage aux stands + 10 s supposées pour les pneus ; le plein n'est pas compté (identique entre options).
        assertEquals(20_000.0, laneCosts.cost(StopKind.FUEL_ONLY)!!.ms)
        assertEquals(20_000.0, laneCosts.cost(StopKind.NONE)!!.ms)
        assertEquals(30_000.0, laneCosts.cost(StopKind.TYRES_ONLY)!!.ms)
        assertEquals(30_000.0, laneCosts.cost(StopKind.FUEL_AND_TYRES)!!.ms)
        assertTrue(laneCosts.cost(StopKind.TYRES_ONLY)!!.assumed)
        assertFalse(laneCosts.cost(StopKind.FUEL_ONLY)!!.assumed)
        // Observés : le temps total contient déjà les interventions, rien n'est ajouté.
        val observed = laneCosts.copy(observed = mapOf(StopKind.FUEL_AND_TYRES to Cost(34_000.0, 1_500.0, "plein + pneus observés")))
        assertEquals(34_000.0, observed.cost(StopKind.FUEL_AND_TYRES)!!.ms)
        assertFalse(observed.cost(StopKind.FUEL_AND_TYRES)!!.assumed)
        // Changement de pneus appris : il remplace l'hypothèse.
        val learned = laneCosts.copy(tyreService = Cost(7_000.0, 1_000.0, "changement appris"))
        assertEquals(27_000.0, learned.cost(StopKind.TYRES_ONLY)!!.ms)
        assertFalse(learned.cost(StopKind.TYRES_ONLY)!!.assumed)
    }

    @Test
    fun tyresOnlyFuelOnlyCombinedAndNoStopAreCostedByContent() {
        val pace = StrategyOptions.Pace(100_000.0, slopeMsPerLap = 400.0, slopeSpread = 40.0, stopCosts = laneCosts)
        // Sans arrêt (le carburant suffit) contre pneus seuls : l'arrêt pneus paie passage + changement (30 s).
        val noStop = StrategyOptions.compare(input(), pace, emptyList(), emptyList(), TyreInputs(degrading = true))!!
        val normal = noStop.option(OptionKind.NORMAL)!!
        val tyres = noStop.option(OptionKind.TYRES_NOW)!!
        assertTrue(normal.stopLaps.isEmpty())
        assertEquals(listOf(StopKind.TYRES_ONLY), tyres.stopKinds)
        // Écart = gain de dégradation (58 s - 18,4 s) moins 30 s d'arrêt.
        assertTrue(kotlin.math.abs((58_000L - 18_400L - 30_000L) + tyres.deltaMs!!) <= 50, tyres.toString())

        // Carburant nécessaire (8 % par tour) : ravitaillement seul contre ravitaillement + pneus au même arrêt.
        val fuel = StrategyOptions.compare(input(lap = 3, tyreAge = 2, fuelNow = 60f, perLap = 8f), pace, emptyList(), emptyList(), TyreInputs(degrading = true))!!
        val fuelOnly = fuel.option(OptionKind.NORMAL)!!
        val combined = fuel.option(OptionKind.TYRES_WITH_FUEL)!!
        assertEquals(listOf(StopKind.FUEL_ONLY), fuelOnly.stopKinds)
        assertEquals(listOf(StopKind.FUEL_AND_TYRES), combined.stopKinds)
        // Même passage aux stands : seul le changement de pneus (10 s supposées) s'ajoute, et son incertitude.
        val stop = combined.plan!!.stops.single().endOfLap
        val ageGain = (stop + 1..20).sumOf { (it - 3 + 2) * 400L } - (stop + 1..20).sumOf { (it - stop - 1) * 400L }
        assertTrue(kotlin.math.abs((ageGain - 10_000L) + combined.deltaMs!!) <= 50, "$ageGain ${combined.deltaMs}")
        assertTrue(combined.assumptions.isNotEmpty())
        assertTrue(combined.uncertaintyMs!! >= 10_000)
        // Arrêt pneus seuls en plus du ravitaillement : deux passages, chacun compté une fois.
        fuel.options.filter { it.stopKinds.count { k -> k == StopKind.TYRES_ONLY } == 1 && it.stopKinds.contains(StopKind.FUEL_ONLY) }.forEach { o ->
            assertEquals(2, o.stopLaps.size, o.toString())
        }
    }

    @Test
    fun withoutAReliableGainTheEngineSaysSoAndKeepsThePlan() {
        // Dégradation faible et incertaine : un arrêt de plus n'est pas démontré rentable.
        val pace = StrategyOptions.Pace(100_000.0, slopeMsPerLap = 150.0, slopeSpread = 120.0, pitLossMs = 25_000.0, pitLossSpread = 2_000.0)
        val rep = StrategyOptions.compare(input(), pace, emptyList(), emptyList(), TyreInputs(degrading = true))!!
        assertEquals(OptionKind.NORMAL, rep.recommended)
        assertTrue(
            "Les pneus montrent des signes de dégradation, mais je ne peux pas encore démontrer qu'un arrêt nous ferait gagner du temps." in rep.reason,
            rep.reason,
        )
    }

    @Test
    fun anExtraStopMustClearlyBeatItsCostAndTheUncertainty() {
        // Gain théorique minuscule (quelques dixièmes) : pas d'arrêt de plus, même si les pneus semblent fatigués.
        val pace = StrategyOptions.Pace(
            100_000.0, slopeMsPerLap = 255.0, slopeSpread = 20.0,
            stopCosts = StopCosts(base = null, observed = mapOf(StopKind.TYRES_ONLY to Cost(25_000.0, 1_000.0, "pneus seuls observés"))),
        )
        val rep = StrategyOptions.compare(input(), pace, emptyList(), emptyList(), TyreInputs(degrading = true))!!
        val now = rep.option(OptionKind.TYRES_NOW)!!
        assertTrue((now.deltaMs ?: 0) < 0, "un léger gain théorique : $now")
        assertEquals(OptionKind.NORMAL, rep.recommended, rep.reason)
        // Perte aux stands inconnue : aucun classement en secondes entre des stratégies au nombre d'arrêts différent.
        val unknown = StrategyOptions.compare(input(), pace.copy(stopCosts = null), emptyList(), emptyList(), TyreInputs(degrading = true))!!
        assertEquals(OptionKind.NORMAL, unknown.recommended)
    }

    @Test
    fun tyresAreChangedAtTheRefuelWhenWorthIt() {
        // Un ravitaillement nécessaire (8 % par tour, 60 % à bord) : changer les pneus au même arrêt plutôt qu'un arrêt de plus.
        val pace = StrategyOptions.Pace(100_000.0, slopeMsPerLap = 900.0, slopeSpread = 50.0, pitLossMs = 25_000.0, pitLossSpread = 2_000.0)
        val rep = StrategyOptions.compare(input(lap = 3, tyreAge = 2, fuelNow = 60f, perLap = 8f), pace, emptyList(), emptyList(), TyreInputs(degrading = true))!!
        val withFuel = rep.option(OptionKind.TYRES_WITH_FUEL)!!
        val stop = withFuel.plan!!.stops.single { it.changeTyres }
        assertTrue(stop.forFuel && stop.changeTyres, stop.toString())
        assertTrue(rep.recommended in StrategyOptions.TYRE_KINDS, rep.reason)
        assertTrue(rep.recommended != OptionKind.NORMAL)
    }

    @Test
    fun requiredCompoundsStayInEveryOption() {
        val r = RaceRules(totalLaps = 20, requiredTyres = setOf(TyreCompound.RH), startTyre = TyreCompound.RM)
        val pace = StrategyOptions.Pace(100_000.0, slopeMsPerLap = 400.0, slopeSpread = 40.0, pitLossMs = 25_000.0, pitLossSpread = 2_000.0)
        val i = input(lap = 3, tyreAge = 2, r = r).copy(tyresMissing = listOf(TyreCompound.RH))
        val rep = StrategyOptions.compare(i, pace, emptyList(), emptyList(), TyreInputs(degrading = true))!!
        for (o in rep.options.filter { it.feasible }) {
            assertTrue(o.plan!!.stops.any { it.tyreToFit == TyreCompound.RH }, "${o.kind} garde la gomme imposée")
        }
    }

    // ------------------------------------------------------------------ Apprentissage des relais (TyreModel)

    private val profile = ProfileId.of(SessionMode.SPORT_OFFICIAL, Series.DAILY_B)
    private val rules = RaceRules(totalLaps = 20, trackId = 462, tyreMultiplier = 3)
    private fun ctx(id: ProfileId = profile, r: RaceRules = rules) = SessionContext(EventProfile(id = id), r, 3607, 100f)

    /** Course archivée de 20 tours : dégradation [slope] ms par tour d'âge, pneus changés au tour [change]. */
    private fun history(n: Int, slope: Int = 400, change: Int = 10, compound: TyreCompound? = TyreCompound.RM, c: SessionContext = ctx(), front: Boolean = true): SessionRecord {
        val rnd = Random(n)
        val laps = (1..20).map { l ->
            val age = if (l <= change) l - 1 else l - change - 1
            val pit = l == change || l == change + 1
            LapEntry(
                l, 100_000 + slope * age + rnd.nextInt(-150, 150) + (if (pit) 12_500 else 0) + (if (l == 1) 3_000 else 0), 4f, false,
                tyreAge = age, tyreCompound = compound, frontEvents = if (front) age / 2 else 1, rearEvents = 1,
            )
        }
        return SessionRecord(
            "h$n-${c.profile.id.code}", c, n.toLong(), n.toLong(), SessionOutcome.FINISHED, laps,
            stops = listOf(StopEntry(change, StopEvidence.DRIVER_CONFIRMED, null, TyreService.CHANGED)),
            startTyre = compound,
            tyres = listOf(TyreEntry(change + 1, compound)),
        )
    }

    @Test
    fun noHistoryAndIncompatibleHistoryTeachNothing() {
        assertNull(TyreModel.learn(ctx(), emptyList(), TyreCompound.RM).slopeMsPerLap)
        // Multiplicateur d'usure différent, autre gomme, autre type de session : rien n'est réutilisé.
        val other = listOf(
            history(1, c = ctx(r = rules.copy(tyreMultiplier = 1))),
            history(2, compound = TyreCompound.RS),
            history(3, c = ctx(id = ProfileId.of(SessionMode.CUSTOM, Series.OTHER))),
        )
        val m = TyreModel.learn(ctx(), other, TyreCompound.RM)
        assertNull(m.slopeMsPerLap)
        assertEquals(0, m.stints)
        assertEquals(1, m.sessionsExcluded)
    }

    @Test
    fun comparableStintsTeachDegradationLifeAndFreshTyreGain() {
        val one = TyreModel.learn(ctx(), listOf(history(1)), TyreCompound.RM)
        assertEquals(2, one.stints)
        assertEquals(Confidence.PROVISIONAL, one.slopeMsPerLap!!.confidence, "une seule course : provisoire")
        val many = TyreModel.learn(ctx(), (1..4).map { history(it) }, TyreCompound.RM)
        val slope = many.slopeMsPerLap!!
        assertEquals(Confidence.FAIRLY_RELIABLE, slope.confidence)
        assertTrue(kotlin.math.abs(slope.mean - 400) < 40, "${slope.mean}")
        // Perte nette (+0,7 s sur les meilleurs tours du début de relais) vers 4 tours d'âge.
        assertTrue(many.performantLaps!!.mean in 3.0..5.0, "${many.performantLaps}")
        assertTrue(many.freshGainMs!!.mean > 1_500, "pneus neufs plus rapides : ${many.freshGainMs}")
        assertEquals(com.gt7pitwall.core.tyres.GripArea.FRONT, many.axle)
        // Gomme non déclarée : jamais « relativement fiable ».
        assertEquals(Confidence.PROVISIONAL, TyreModel.learn(ctx(), (1..4).map { history(it, compound = null) }, null).slopeMsPerLap!!.confidence)
        // Entraînement identique : retenu, mais au plus provisoire.
        val practice = ProfileId.of(SessionMode.SPORT_PRACTICE, Series.DAILY_B)
        val p = TyreModel.learn(ctx(), (1..4).map { history(it, c = ctx(id = practice)) }, TyreCompound.RM)
        assertEquals(4, p.relatedUsed)
        assertEquals(Confidence.PROVISIONAL, p.slopeMsPerLap!!.confidence)
    }

    @Test
    fun aStintWithUnknownTyresAfterAStopIsNotLearned() {
        val h = history(1).let { r ->
            r.copy(
                laps = r.laps.map { if (it.lap > 10) it.copy(tyreCertain = false, tyreAgeCertain = false) else it },
                stops = listOf(StopEntry(10, StopEvidence.REFUEL, 30f, TyreService.UNKNOWN)),
                tyres = emptyList(),
            )
        }
        assertEquals(1, TyreModel.learn(ctx(), listOf(h), TyreCompound.RM).stints)
    }

    // ------------------------------------------------------------------ Course complète simulée

    /**
     * Course de 20 tours au carburant suffisant, chronos qui se dégradent avec l'âge des pneus, historique comparable :
     * l'ingénieur recommande un arrêt pour les pneus seuls, l'appelle au bon tour, et le plan reste stable.
     */
    @Test
    fun fullRaceTyresForceAStopWhileFuelIsEnough() {
        val store = FakeStorage()
        val c = RaceController(storage = store)
        c.selectProfile(profile)
        c.setRules(rules.copy(startTyre = TyreCompound.RM))
        // Historique comparable : quatre courses avec un changement de pneus au tour 10 (perte par arrêt et dégradation apprises).
        val base = 37_700
        for (n in 1..4) {
            val h = history(n).let { r -> r.copy(laps = r.laps.map { l -> l.copy(timeMs = l.timeMs!! - 100_000 + base) }) }
            store.sessionsJson[h.id] = RaceJson.session(h)
        }
        val c2 = RaceController(storage = store) // relit l'historique
        val sim = RaceSim(c2, totalLaps = 20, perLap = 2f)
        c2.selectProfile(profile)
        sim.start()
        // Les chronos se dégradent de ~0,4 s par tour : la vitesse baisse un peu à chaque tour. Le pilote suit l'appel au stand.
        fun speed() = 80f * (1 - 0.0105f * (sim.lap - 1))
        var box: String? = null
        while (box == null && sim.lap < 20) {
            sim.driveLaps(1, speed = speed())
            box = sim.texts.lastOrNull { it.startsWith("Box, box") }
        }
        val stopLap = sim.lap
        assertNotNull(box, sim.texts.takeLast(6).toString())
        assertTrue("pour les pneus uniquement, pas besoin d'essence" in box!!, box)
        assertTrue(stopLap in 6..14, "arrêt pneus au tour $stopLap")
        val plan = c2.plan!!
        assertTrue(plan.stops.none { it.forFuel }, "aucun ravitaillement nécessaire : $plan")
        assertTrue(sim.texts.any { "Le carburant suffit, mais tes pneus nous coûtent du temps" in it }, sim.texts.filter { "plan" in it.lowercase() || "pneus" in it || "Stratégie" in it }.toString())
        // Arrêt sans essence, confirmé, pneus changés : nouveau relais pneus, le plan pneus est fait.
        sim.pitStop(fuel = 0f)
        sim.say("oui")
        // Tant que le pilote n'a pas dit s'il a changé les pneus, l'ingénieur ne relance pas un arrêt pneus.
        assertFalse(sim.texts.takeLast(3).any { "Je recommande un arrêt pour les pneus" in it }, sim.texts.takeLast(3).toString())
        sim.say("oui")
        assertEquals(1, c2.ledger.stopCount)
        assertEquals(TyreService.CHANGED, c2.ledger.stopList.single().tyres)
        assertTrue(c2.performanceTyreLaps.isEmpty())
        while (sim.lap <= 20) sim.driveLaps(1)
        // Pas de nouvel arrêt pneus inventé sur des pneus neufs, et jamais de pourcentage d'usure.
        assertEquals(1, c2.ledger.stopCount)
        val all = sim.said.filter { it.category != MessageCategory.DRIVER }.map { it.text }
        assertFalse(all.any { Regex("usure .*\\d+ ?%|\\d+ ?% d'usure|pneus usés à").containsMatchIn(it) }, all.toString())
        val plans = all.count { it.startsWith("Nouveau plan") }
        assertTrue(plans <= 4, "pas de changement de stratégie incessant : ${all.filter { it.startsWith("Nouveau plan") || "carburant suffit" in it }}")
    }
}
