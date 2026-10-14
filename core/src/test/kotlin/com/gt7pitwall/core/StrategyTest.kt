package com.gt7pitwall.core

import com.gt7pitwall.core.knowledge.CarTrackMemory
import com.gt7pitwall.core.knowledge.Gt7Database
import com.gt7pitwall.core.session.LapRecord
import com.gt7pitwall.core.strategy.DataSource
import com.gt7pitwall.core.strategy.PlanWarning
import com.gt7pitwall.core.strategy.RacePlan
import com.gt7pitwall.core.strategy.RacePlanner
import com.gt7pitwall.core.strategy.RaceRules
import com.gt7pitwall.core.strategy.TyreCompound
import java.io.File
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StrategyTest {

    private fun input(
        rules: RaceRules = RaceRules(),
        gameLaps: Int = 0,
        lap: Int = 1,
        remaining: Float = 1f,
        fuel: Float = 100f,
        tank: Float = 100f,
        perLap: Float? = 14.5f,
        avg: Int? = 110_000,
        elapsed: Long? = null,
        stopsDone: Int = 0,
        tyresMissing: List<TyreCompound> = emptyList(),
    ) = RacePlanner.Input(
        rules = rules, gameTotalLaps = gameLaps, currentLap = lap, currentLapRemaining = remaining, fuelNow = fuel, tank = tank,
        perLap = perLap, fuelSource = DataSource.MEASURED, avgLapMs = avg, raceElapsedMs = elapsed, stopsDone = stopsDone,
        tyresMissing = tyresMissing, pitLaneDeltaS = 25f,
    )

    private fun plan(i: RacePlanner.Input) = assertNotNull(RacePlanner.plan(i))

    /**
     * Vérifie un plan en rejouant le carburant relais par relais :
     * jamais sous la marge de sécurité, pas d'arrêt au dernier tour, arrêts croissants, plein ≤ réservoir.
     */
    private fun assertConsistent(p: RacePlan, i: RacePlanner.Input) {
        val perLap = i.perLap!!
        val lapIndex = maxOf(i.currentLap, 1)
        var fuel = i.fuelNow
        var lap = lapIndex - 1
        var first = true
        val ends = p.stops.map { it.endOfLap } + p.totalLaps
        p.stops.zipWithNext().forEach { (a, b) -> assertTrue(a.endOfLap < b.endOfLap, "arrêts croissants") }
        for ((idx, end) in ends.withIndex()) {
            val laps = end - lap
            val used = if (first) (i.currentLapRemaining + laps - 1) * perLap else laps * perLap
            fuel -= used
            assertTrue(fuel >= RacePlanner.SAFETY_LAPS * perLap * 0.99f - 0.6f, "carburant sous la marge à la fin du tour $end : $fuel")
            if (idx < p.stops.size) {
                val stop = p.stops[idx]
                assertTrue(stop.endOfLap < p.totalLaps, "pas d'arrêt à la fin du dernier tour")
                val add = stop.liters!!
                assertTrue(fuel + add <= i.tank + 0.5f, "plein supérieur au réservoir")
                fuel += add
            }
            lap = end
            first = false
        }
    }

    @Test
    fun realRaceOneStop() {
        // Course réelle du 13/10 : 10 tours, 14,5 L/tour, réservoir de 100 L.
        val p = plan(input(gameLaps = 10))
        assertEquals(1, p.stops.size)
        assertEquals(5, p.stops[0].endOfLap)
        assertEquals(49, p.stops[0].liters)
        assertTrue(p.stops[0].forFuel)
        assertEquals(DataSource.GAME, p.lapsSource)
        assertEquals(DataSource.MEASURED, p.fuelSource)
    }

    @Test
    fun lapRacesWithFuelMultipliers() {
        // Consommation de base 2,5 L/tour, multipliée par l'épreuve ; les données GT7 la donnent déjà multipliée.
        for (laps in listOf(5, 10, 15, 30)) {
            for (mult in listOf(1, 3, 6)) {
                val perLap = 2.5f * mult
                val i = input(gameLaps = laps, perLap = perLap)
                val p = plan(i)
                val need = (laps + RacePlanner.SAFETY_LAPS) * perLap
                val expectedStops = if (need <= 100f) 0 else ceil((need - 100f) / 100f).toInt()
                assertEquals(expectedStops, p.stops.size, "$laps tours ×$mult")
                assertTrue(p.warnings.isEmpty(), "$laps tours ×$mult : ${p.warnings}")
                assertConsistent(p, i)
            }
        }
        // Points de repère : 5 tours ×1 sans arrêt, 30 tours ×6 (450 L) en 4 arrêts.
        assertTrue(plan(input(gameLaps = 5, perLap = 2.5f)).stops.isEmpty())
        assertEquals(4, plan(input(gameLaps = 30, perLap = 15f)).stops.size)
    }

    @Test
    fun timedRaces() {
        // 100 s au tour : on termine le tour en cours à l'expiration du temps.
        for ((minutes, expected) in listOf(20 to 13, 30 to 19, 60 to 37)) {
            val p = plan(input(rules = RaceRules(durationMin = minutes), lap = 0, perLap = 3f, avg = 100_000))
            assertEquals(expected, p.totalLaps, "$minutes min")
            assertEquals(DataSource.ESTIMATED, p.lapsSource)
        }
        // En course : après 10 min au début du tour 7 d'une course de 30 min, toujours 19 tours.
        assertEquals(19, plan(input(rules = RaceRules(durationMin = 30), lap = 7, perLap = 3f, avg = 100_000, elapsed = 600_000)).totalLaps)
        // Sans rythme connu : pas de plan inventé.
        assertNull(RacePlanner.plan(input(rules = RaceRules(durationMin = 30), avg = null)))
    }

    @Test
    fun mandatoryStopsAreSeparatedFromFuelStops() {
        // Aucun besoin d'essence, un arrêt imposé.
        val one = plan(input(rules = RaceRules(mandatoryStops = 1), gameLaps = 10, perLap = 5f))
        assertEquals(1, one.stops.size)
        assertTrue(one.stops[0].mandatory && !one.stops[0].forFuel && one.stops[0].mandatoryOnly)
        // Deux arrêts imposés, un seul nécessaire pour l'essence : deux arrêts, le premier compte aussi pour l'essence.
        val i = input(rules = RaceRules(mandatoryStops = 2), gameLaps = 10)
        val two = plan(i)
        assertEquals(2, two.stops.size)
        assertTrue(two.stops.all { it.mandatory })
        assertTrue(two.stops.any { it.forFuel })
        assertConsistent(two, i)
        // Arrêts déjà faits : plus rien d'imposé.
        assertTrue(plan(input(rules = RaceRules(mandatoryStops = 1), gameLaps = 10, perLap = 5f, stopsDone = 1)).stops.isEmpty())
    }

    @Test
    fun partialTankAtStart() {
        val i = input(gameLaps = 10, fuel = 40f, perLap = 10f)
        val p = plan(i)
        // 40 L à 10 L/tour avec 0,3 tour de marge : arrêt au plus tard à la fin du tour 3.
        assertTrue(p.stops.first().endOfLap <= 3, p.toString())
        assertConsistent(p, i)
    }

    @Test
    fun consumptionIncreaseIsAbsorbedByThePlan() {
        val i1 = input(gameLaps = 12, lap = 3, fuel = 80f, perLap = 10f)
        val i2 = i1.copy(perLap = 14f)
        val before = plan(i1)
        val after = plan(i2)
        // Le relais reste équilibré (arrêt à mi-course) mais il faut plus d'essence, et le plan reste tenable.
        assertEquals(before.stops.first().endOfLap, after.stops.first().endOfLap)
        assertTrue(after.stops.first().liters!! > before.stops.first().liters!!)
        assertConsistent(after, i2)
        // Forte hausse : le premier relais ne tient plus, l'arrêt avance.
        val i3 = i1.copy(perLap = 20f)
        val worse = plan(i3)
        assertTrue(worse.stops.first().endOfLap < before.stops.first().endOfLap)
        assertConsistent(worse, i3)
    }

    @Test
    fun earlyStopAndIncompleteRefuel() {
        // Arrêt anticipé au tour 3 avec seulement 20 L ajoutés : un nouvel arrêt devient nécessaire.
        val i = input(gameLaps = 10, lap = 4, fuel = 60f, perLap = 14.5f, stopsDone = 1)
        val p = plan(i)
        assertEquals(1, p.stops.size)
        assertTrue(p.stops[0].forFuel)
        assertConsistent(p, i)
    }

    @Test
    fun lateStopAndFuelTooLowToReachThePits() {
        // Arrêt prévu dépassé : il ne reste qu'un demi-tour de carburant pour un demi-tour à faire.
        val late = plan(input(gameLaps = 10, lap = 6, remaining = 0.5f, fuel = 8f, perLap = 14.5f))
        assertEquals(6, late.stops.first().endOfLap) // rentrer à la fin de ce tour
        assertTrue(PlanWarning.CANNOT_REACH_PITS !in late.warnings)
        // Moins que le reste du tour : le pilote ne rejoindra peut-être pas les stands.
        val dry = plan(input(gameLaps = 10, lap = 6, remaining = 0.5f, fuel = 5f, perLap = 14.5f))
        assertTrue(PlanWarning.CANNOT_REACH_PITS in dry.warnings)
    }

    @Test
    fun currentLapProgressIsTakenIntoAccount() {
        val full = plan(input(gameLaps = 10, lap = 5, remaining = 1f, fuel = 50f, perLap = 10f))
        val half = plan(input(gameLaps = 10, lap = 5, remaining = 0.5f, fuel = 50f, perLap = 10f))
        assertTrue(half.stops.first().liters!! <= full.stops.first().liters!!)
    }

    @Test
    fun lastLapAndFinish() {
        // Dernier tour : aucun arrêt possible ni utile.
        assertTrue(plan(input(gameLaps = 10, lap = 10, fuel = 30f, perLap = 14.5f)).stops.isEmpty())
        // Course terminée.
        assertTrue(plan(input(gameLaps = 10, lap = 11, fuel = 10f)).stops.isEmpty())
        // Arrêt obligatoire non fait au dernier tour : signalé, jamais planifié après l'arrivée.
        val p = plan(input(rules = RaceRules(mandatoryStops = 1), gameLaps = 10, lap = 10, fuel = 50f, perLap = 5f))
        assertTrue(p.stops.isEmpty())
        assertTrue(PlanWarning.MANDATORY_STOPS_IMPOSSIBLE in p.warnings)
    }

    @Test
    fun tyreObligationsNeedStops() {
        val i = input(rules = RaceRules(requiredTyres = setOf(TyreCompound.RH, TyreCompound.RM), startTyre = TyreCompound.RM), gameLaps = 8, perLap = 5f, tyresMissing = listOf(TyreCompound.RH))
        val p = plan(i)
        assertEquals(1, p.stops.size)
        assertEquals(TyreCompound.RH, p.stops[0].tyreToFit)
        assertTrue(!p.startTyreUnknown)
        // Trop de gommes manquantes pour les tours restants.
        val late = plan(input(gameLaps = 8, lap = 7, perLap = 5f, tyresMissing = listOf(TyreCompound.RH, TyreCompound.RS)))
        assertTrue(PlanWarning.TYRES_IMPOSSIBLE in late.warnings)
        assertEquals(1, late.stops.size)
    }

    @Test
    fun unknownConsumptionKeepsLitresUnknown() {
        val p = plan(input(rules = RaceRules(totalLaps = 12, mandatoryStops = 2), perLap = null))
        assertEquals(listOf(4, 8), p.stops.map { it.endOfLap })
        assertTrue(p.stops.all { it.liters == null })
        assertEquals(DataSource.RULES, p.lapsSource)
        assertNull(RacePlanner.plan(input(rules = RaceRules(totalLaps = 12), perLap = null)))
    }

    @Test
    fun communityDatabaseAndMemory() {
        val dir = File("../app/src/main/assets/gt7db")
        val db = Gt7Database.parse(File(dir, "cars.csv").readText(), File(dir, "maker.csv").readText(), File(dir, "course.csv").readText())
        assertEquals("963 '24", db.car(3607)?.name) // voiture de la course du 13/10
        assertTrue(db.cars.size > 500)
        assertTrue("Circuit de Spa-Francorchamps" in db.coursesMatching(7070f).map { it.name })
        assertEquals(25f, db.course(462)?.pitLaneDeltaS)

        val laps = listOf(LapRecord(1, 111_000, 29f, false), LapRecord(2, 110_000, 29f, false), LapRecord(3, 140_000, null, true))
        val rec = CarTrackMemory.update(null, 3607, 462, laps, fuelMultiplier = 2)
        assertEquals(110_000, rec.bestLapMs)
        assertEquals(14.5f, rec.fuelPerLapX1!!, 0.001f) // 29 L à ×2 = 14,5 L à ×1
        assertEquals(110_500, rec.typicalLapMs) // le tour au stand est exclu
    }

    @Test
    fun tyreNamesFromSpeech() {
        val racing = setOf(TyreCompound.RH, TyreCompound.RM, TyreCompound.RS)
        assertEquals(TyreCompound.RH, TyreCompound.fromSpeech("j'ai mis les durs", racing, emptySet()))
        assertEquals(TyreCompound.RM, TyreCompound.fromSpeech("je pars en mediums", racing, emptySet()))
        assertEquals(TyreCompound.SS, TyreCompound.fromSpeech("pneus sport tendres", emptySet(), emptySet()))
        assertEquals(TyreCompound.IM, TyreCompound.fromSpeech("j'ai mis les intermediaires", emptySet(), emptySet()))
        assertNull(TyreCompound.fromSpeech("j'ai mis les durs", emptySet(), emptySet())) // famille inconnue : on demande
        assertEquals(setOf(TyreCompound.RH, TyreCompound.RM, TyreCompound.IM), TyreCompound.parseCodes("RH|RM|IM|XX"))
    }
}
