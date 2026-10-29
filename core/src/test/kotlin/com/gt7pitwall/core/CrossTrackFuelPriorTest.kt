package com.gt7pitwall.core

import com.gt7pitwall.core.race.SessionMode
import com.gt7pitwall.core.strategy.CrossTrackFuelPrior
import com.gt7pitwall.core.strategy.CrossTrackFuelPrior.Result
import com.gt7pitwall.core.strategy.CrossTrackFuelPrior.SourceLap
import com.gt7pitwall.core.strategy.CrossTrackFuelPrior.Target
import com.gt7pitwall.core.strategy.DataSource
import com.gt7pitwall.core.strategy.RacePlanner
import com.gt7pitwall.core.strategy.RaceRules
import com.gt7pitwall.core.strategy.TyreCompound
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Issue #52 : Subaru BRZ GT300 (3474), Suzuka ×2 (4,56811676 U/tour, 5,807 km) → SUGO (3,586 km, 14 tours).
 * Seules les données de Suzuka servent : rien de SUGO n'entre dans l'estimation.
 */
class CrossTrackFuelPriorTest {
    private val subaru = 3474
    private val ford = 3300
    private val porsche = 3400
    private val suzuka = 101
    private val sugo = 462
    private val suzukaM = 5807
    private val sugoM = 3586
    private val perLapSuzuka = 4.56811676f

    private fun lap(
        car: Int = subaru, track: Int = suzuka, length: Int? = suzukaM, mult: Int = 2, fuel: Float? = perLapSuzuka,
        refuelled: Boolean = false, inLap: Boolean = false, outLap: Boolean = false, version: String? = "1.66",
    ) = SourceLap(car, track, length, mult, fuel, refuelled, inLap, outLap, SessionMode.SPORT_OFFICIAL, version, bop = true)

    /** Quatre tours propres à Suzuka, plus des tours qui ne doivent jamais compter. */
    private val suzukaHistory = List(4) { lap() } + listOf(
        lap(fuel = 1.2f, outLap = true),
        lap(fuel = 3.1f, inLap = true),
        lap(fuel = 9.9f, refuelled = true),
        lap(fuel = null),
    )

    private val sugoRace = Target(subaru, sugo, sugoM, fuelMultiplier = 2, laps = 14, mode = SessionMode.SPORT_OFFICIAL, gameVersion = "1.66", bop = true)

    private fun estimate(history: List<SourceLap> = suzukaHistory, target: Target = sugoRace) =
        assertIs<Result.Estimate>(CrossTrackFuelPrior.predict(target, history, hasLocalModel = false))

    @Test fun subaruSuzukaToSugo() {
        val e = estimate()
        assertEquals(perLapSuzuka / 5.807f, e.fuelPerKm, 1e-4f)
        assertEquals(2.82f, e.perLap, 0.005f)
        assertEquals(39.49f, e.total!!, 0.02f)
        assertEquals(CrossTrackFuelPrior.Source.CROSS_TRACK_PROVISIONAL, e.source)
        // Suzuka est 1,62 fois plus long que SUGO : l'extrapolation reste de confiance faible, et le dit.
        assertEquals(CrossTrackFuelPrior.Confidence.LOW, e.confidence, e.caveats.toString())
        assertEquals(listOf("circuits de longueurs très différentes"), e.caveats)
        assertEquals(setOf(suzuka), e.sourceTracks)
        assertEquals(4, e.samples, "tours de sortie, d'entrée, ravitaillés et incomplets exclus")
    }

    @Test fun nothingFromSugoLeaksIntoTheEstimate() {
        // Tours réels d'autres voitures à SUGO, et un tour Subaru à SUGO d'un autre multiplicateur : aucun n'est une source.
        val noise = listOf(
            lap(car = ford, track = sugo, length = sugoM, fuel = 2.1f),
            lap(car = porsche, track = sugo, length = sugoM, fuel = 3.4f),
            lap(track = sugo, length = sugoM, mult = 1, fuel = 1.0f),
        )
        val e = estimate(suzukaHistory + noise)
        assertEquals(estimate().perLap, e.perLap, 1e-6f)
        assertEquals(setOf(suzuka), e.sourceTracks)
    }

    @Test fun aLocalModelAlwaysWins() {
        val r = CrossTrackFuelPrior.predict(sugoRace, suzukaHistory, hasLocalModel = true)
        assertIs<Result.Abstain>(r)
    }

    @Test fun refusals() {
        fun abstains(t: Target, h: List<SourceLap> = suzukaHistory) = assertIs<Result.Abstain>(CrossTrackFuelPrior.predict(t, h, false))
        abstains(sugoRace.copy(carCode = ford))
        abstains(sugoRace.copy(carCode = porsche))
        abstains(sugoRace.copy(fuelMultiplier = 4))
        abstains(sugoRace.copy(trackId = null))
        abstains(sugoRace.copy(trackLengthM = null))
        abstains(sugoRace, suzukaHistory.filterNot { it.clean })
        abstains(sugoRace, listOf(lap(length = null)))
        abstains(sugoRace.copy(gameVersion = "1.70"))
    }

    @Test fun uncertainContextLowersConfidence() {
        // Circuit de longueur voisine (5,5 km), contexte identique, 4 tours propres : confiance moyenne, jamais plus.
        val close = estimate(target = sugoRace.copy(trackId = 999, trackLengthM = 5500))
        assertEquals(CrossTrackFuelPrior.Confidence.MEDIUM, close.confidence, close.caveats.toString())
        val fewLaps = estimate(listOf(lap(), lap()))
        assertEquals(CrossTrackFuelPrior.Confidence.LOW, fewLaps.confidence)
        val otherMode = estimate(target = sugoRace.copy(mode = SessionMode.CUSTOM))
        assertEquals(CrossTrackFuelPrior.Confidence.LOW, otherMode.confidence)
        val unknownBop = estimate(target = sugoRace.copy(bop = null))
        assertEquals(CrossTrackFuelPrior.Confidence.LOW, unknownBop.confidence)
        // Même valeur : la confiance change, pas le calcul.
        assertEquals(estimate().perLap, unknownBop.perLap, 1e-6f)
    }

    @Test fun liveMeasurementTakesOverMonotonically() {
        val prior = estimate()
        val live = 3.10f
        val steps = (0..4).map { CrossTrackFuelPrior.withLive(prior, live, it, laps = 14).perLap }
        assertEquals(prior.perLap, steps[0], 1e-6f)
        for (k in 1 until steps.size) assertTrue(steps[k] >= steps[k - 1], steps.toString())
        assertEquals(live, steps[2], 1e-6f)
        assertEquals(CrossTrackFuelPrior.Source.LIVE, CrossTrackFuelPrior.withLive(prior, live, 2).source)
        assertEquals(CrossTrackFuelPrior.Source.CROSS_TRACK_PROVISIONAL, CrossTrackFuelPrior.withLive(prior, live, 1).source)
        assertEquals(live * 14, CrossTrackFuelPrior.withLive(prior, live, 3).total!!, 1e-3f)
    }

    @Test fun noRefuelNeededStillKeepsTheRmRsChange() {
        // 39,5 U pour 14 tours, réservoir de 100 : pas d'essence à ajouter, mais RM et RS restent obligatoires.
        val e = estimate()
        val p = assertNotNull(
            RacePlanner.plan(
                RacePlanner.Input(
                    rules = RaceRules(fuelMultiplier = 2, requiredTyres = setOf(TyreCompound.RM, TyreCompound.RS), startTyre = TyreCompound.RM),
                    gameTotalLaps = 14, currentLap = 0, fuelNow = 100f, tank = 100f, perLap = e.perLap,
                    fuelSource = DataSource.ESTIMATED, avgLapMs = 90_000, raceElapsedMs = null, stopsDone = 0,
                    tyresMissing = listOf(TyreCompound.RS),
                ),
            ),
        )
        assertTrue(p.stops.isNotEmpty(), "au moins un arrêt pour monter les RS")
        assertTrue(p.stops.none { it.forFuel }, p.stops.toString())
        assertTrue(p.stops.any { it.tyreToFit == TyreCompound.RS }, p.stops.toString())
    }
}
