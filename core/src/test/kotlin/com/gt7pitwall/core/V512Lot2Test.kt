package com.gt7pitwall.core

import com.gt7pitwall.core.ai.RadioConversation
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.race.ProfileId
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.SessionKind
import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.race.StrategyDialogue
import com.gt7pitwall.core.strategy.FuelIntelligence
import com.gt7pitwall.core.strategy.FuelIntelligence.Kind
import com.gt7pitwall.core.strategy.RaceRules
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V512 lot 2 : défauts de la 2e course du Mans (23/11 à 14 h, session 1795438816146-3607) : plein insuffisant (37,4 %
 * pour 43 %), 2e arrêt de 6 % planifié « Un arrêt devient nécessaire », dernier tour « rentre aux stands », « je peux
 * encore tenir de tours » → numéro du tour, fausse alerte de chauffe après l'arrêt, 2e « Box » absent sans fiche de la
 * semaine. Circuit simulé (RaceSim) pour les mécanismes ; chiffres du Mans (MESURE du journal) dans les tests unitaires.
 */
class V512Lot2Test {
    // ------------------------------------------------------------------ FuelIntelligence (chiffres du journal réel)

    /** Après le plein du tour 7 : 67,4 à bord (MESURE), 10,02 par tour (MESURE), 7 tours à faire. */
    private fun leMans(pitLossMs: Double?, stopPossible: Boolean = true, fuel: Float = 67.4f, toGo: Float = 7f) =
        FuelIntelligence.decide(FuelIntelligence.Input(fuel, 10.02f, toGo, stopPossible, pitLossMs, 100f))

    @Test
    fun leMansDeficitIsQuantifiedWithTheSafetyMargin() {
        val r = leMans(pitLossMs = 30_000.0)
        assertEquals(5.7f, r.deficit, 0.1f)
        assertEquals(2.7f, r.deficitNoMargin, 0.1f)
        assertEquals(9.2f, r.targetPerLap!!, 0.05f)
        assertEquals(0.082, r.savingFraction, 0.005)
    }

    @Test
    fun savingWinsOnlyWhenItCostsClearlyLessThanTheStop() {
        // Arrêt entier de 30 s (historique) : l'économie (≈ 18 s, ESTIMATION) est retenue.
        assertEquals(Kind.SAVE, leMans(30_000.0).kind)
        // Arrêt court mesuré à 4 s : l'arrêt est plus sûr et moins coûteux.
        assertEquals(Kind.STOP, leMans(4_000.0).kind)
        // Coût de l'arrêt inconnu : jamais d'économie présentée comme plus rentable.
        assertEquals(Kind.STOP, leMans(null).kind)
    }

    @Test
    fun anUnrealisticSavingIsNeverAdvisedWhileAStopIsPossible() {
        // 20 % à économiser : arrêt, quel que soit le coût de l'arrêt.
        val r = leMans(120_000.0, fuel = 57f)
        assertTrue(r.savingFraction > FuelIntelligence.MAX_SAVING_FRACTION)
        assertEquals(Kind.STOP, r.kind)
        assertTrue(FuelIntelligence.sentence(r)!!.contains("trop forte"))
    }

    @Test
    fun theLastLapOnlyGetsAConsumptionTarget() {
        // Dernier tour, 6,7 à bord pour 0,8 tour à 9,69 (journal T14) : aucune option d'arrêt.
        val r = FuelIntelligence.decide(FuelIntelligence.Input(6.7f, 9.69f, 0.8f, stopPossible = false, pitLossMs = 30_000.0, tank = 100f))
        assertTrue(r.kind == Kind.SAVE_NO_STOP_LEFT || r.kind == Kind.CRITICAL, r.toString())
        val s = FuelIntelligence.sentence(r)!!
        assertTrue("stand" !in s && "arrêt" !in s, s)
    }

    @Test
    fun enoughFuelMeansNothingToDecide() {
        assertEquals(Kind.ENOUGH, leMans(30_000.0, fuel = 80f).kind)
        assertEquals(null, FuelIntelligence.sentence(leMans(30_000.0, fuel = 80f)))
    }

    // ------------------------------------------------------------------ Contrôleur (circuit simulé)

    @Test
    fun aShortRefuelIsComparedToTheRecommendationAndExplained() {
        val c = RaceController(storage = FakeStorage())
        c.setRules(RaceRules(mandatoryStops = 1))
        val s = RaceSim(c, totalLaps = 14, perLap = 10f)
        s.start()
        while (s.lap < 7) s.step(80f)
        val planned = c.plan!!.stops.first { it.endOfLap == 7 }
        assertTrue((planned.fuel ?: 0) >= 40, planned.toString())
        // Comme le 23/11 : 37,4 ajoutés au lieu de ~43.
        s.pitStop(fuel = 37.4f, stopSeconds = 30f)
        repeat(600) { s.step(80f) }
        val all = s.texts
        assertTrue(all.none { "Un arrêt devient nécessaire" in it }, all.toString())
        val said = all.firstOrNull { "Plein insuffisant" in it }
        assertNotNull(said, all.toString())
        assertTrue(Regex("Plein insuffisant : 37 % ajoutés pour 4\\d % prévus").containsMatchIn(said), said)
        assertNotNull(c.fuelDecision, "décision carburant prise")
        assertTrue(c.fuelDecision!!.kind in setOf(Kind.SAVE, Kind.STOP), c.fuelDecision.toString())
    }

    @Test
    fun theLastLapNeverSaysGoToThePits() {
        val c = RaceController(storage = FakeStorage())
        val s = RaceSim(c, totalLaps = 3, perLap = 38f)
        s.start()
        while (s.lap < 3) s.step(80f) // aucun arrêt : 24 à bord au début du dernier tour, 38 par tour
        repeat(3500) { s.step(40f) } // dernier tour plus lent : le plan se recalcule après la fenêtre d'arrêt
        val lastLap = s.texts
        assertTrue(lastLap.none { "rentre aux stands" in it }, lastLap.toString())
        assertTrue(lastLap.none { "un relais dépasse un plein" in it }, lastLap.toString())
        assertTrue(lastLap.any { it.startsWith("Carburant critique pour l'arrivée") || it.startsWith("Carburant critique : économise") }, lastLap.toString())
    }

    /** Course réelle : sans programme de la semaine, le rappel du dernier secteur ne partait jamais. */
    @Test
    fun theFinalSectorPitCallNoLongerNeedsTheWeeklySheet() {
        val c = RaceController(storage = FakeStorage())
        c.selectProfile(ProfileId(Series.DAILY_C, SessionKind.RACE))
        c.setRules(RaceRules(totalLaps = 13, mandatoryStops = 1))
        val s = RaceSim(c, totalLaps = 14, perLap = 9.8f)
        s.start()
        while (s.lap < 7) s.step(80f)
        assertEquals(7, c.plan!!.nextStop(7)!!.endOfLap)
        val start = s.said.size
        repeat(2300) { s.step(80f) } // ~0,99 tour à 80 m/s sur 3 016 m
        val lap7 = s.said.drop(start).map { it.text }
        assertTrue(lap7.any { it.startsWith("Box à la fin de ce tour") }, lap7.toString())
    }

    // ------------------------------------------------------------------ Radio

    @Test
    fun autonomyAndSavingQuestionsAreUnderstood() {
        for (q in listOf("je peux encore tenir de tours", "combien de tours je peux tenir", "est ce que je peux finir")) {
            assertEquals(StrategyDialogue.Ask.FuelToFinish, StrategyDialogue.classify(q), q)
        }
        for (q in listOf("dois-je économiser", "est ce que je dois faire du livre un cost pour arriver", "lift and coast")) {
            assertEquals(StrategyDialogue.Ask.FuelSave, StrategyDialogue.classify(q), q)
        }
        // « combien de tours tiennent mes pneus » reste une question pneus.
        assertEquals(StrategyDialogue.Ask.TyreLife, StrategyDialogue.classify("combien de tours tiennent mes pneus"))
        assertEquals(StrategyDialogue.Ask.OthersPitting, StrategyDialogue.classify("pour info je crois qu'il y a deux concurrents qui se rentrer aux stands"))
    }

    @Test
    fun theRadioAnswersWithAutonomyNotTheLapNumber() {
        val c = RaceController(storage = FakeStorage())
        val s = RaceSim(c, totalLaps = 10, perLap = 8f)
        s.start()
        s.driveLaps(3)
        val conv = RadioConversation(c)
        val a = runBlocking { conv.onUtterance("je peux encore tenir de tours", "je peux encore tenir de tours", null) }
            .filter { it.category != MessageCategory.DRIVER }.joinToString(" ") { it.text }
        assertTrue(!a.startsWith("Tour 4 sur 10"), a)
        assertTrue("autonomie" in a || "pour finir" in a, a)
        val save = runBlocking { conv.onUtterance("dois-je économiser", "dois-je économiser", null) }
            .filter { it.category != MessageCategory.DRIVER }.joinToString(" ") { it.text }
        assertTrue("économis" in save || "marge" in save, save)
    }

    // ------------------------------------------------------------------ Pneus

    @Test
    fun coldTyresAfterAStopDoNotBecomeTheNewReference() {
        val coach = com.gt7pitwall.core.engineer.TyreCoach()
        var id = 0
        fun feed(temp: Float, seconds: Int): List<String> = (0 until seconds).mapNotNull {
            id += 60
            val p = com.gt7pitwall.core.telemetry.TelemetryParser.parseDecrypted(
                TestPackets.plain(lap = 5, totalLaps = 14, speedMps = 60f, packetId = id, tyreTemps = floatArrayOf(temp, temp, temp, temp)),
            )!!
            coach.onPacket(p)?.text
        }
        // Relais 1 : montée de 62 à 76 °C puis stable.
        (62..76).forEach { feed(it.toFloat(), 3) }
        feed(76f, 30)
        assertEquals(76f, coach.stableTemp!!, 1f)
        // Arrêt : pneus neufs à 59 °C, montée lente.
        coach.pitVisit()
        val after = (59..77).flatMap { feed(it.toFloat(), 8) } + feed(77f, 60)
        assertTrue(after.none { it.startsWith("Tes pneus chauffent") }, after.toString())
        assertTrue((coach.stableTemp ?: 0f) >= 71f, "référence ${coach.stableTemp}")
    }
}
