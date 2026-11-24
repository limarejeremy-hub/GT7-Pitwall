package com.gt7pitwall.lab

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Mécanique des modules V513 (`tyre-research`, `fuel-performance`, `pit-research`) sur la trace GABARIT d'endurance
 * (fabriquée, jamais une mesure). Ces tests vérifient le code, PAS les conclusions : les résultats sont établis sur les
 * traces réelles du pilote (docs/V513_ENDURANCE.md), qui ne sont jamais dans le dépôt.
 */
class EnduranceResearchTest {
    private val trace = Gabarit.writeEndurance(Files.createTempDirectory("v513").toFile())

    @Test
    fun lapsAreCutOncePerLapNumberAndTheRefuelIsSeen() {
        val race = RaceLaps.load(trace).single()
        assertEquals((1..4).toList(), race.laps.map { it.n })
        val stopLap = race.laps.first { it.stopped }
        assertEquals(2, stopLap.n)
        assertTrue(stopLap.fuelAdded > 25f, "plein lent cumulé : ${stopLap.fuelAdded}")
        assertTrue(stopLap.refuelled)
    }

    @Test
    fun tyreResearchReportsConstantFieldsAndNeverInventsAnAutonomy() {
        val r = TyreResearch.run(listOf(trace))
        val unknown = r.getJSONObject("piste_A_octets_inconnus").getJSONObject(trace.name)
        assertEquals(1, unknown.getJSONObject("0xD4").getInt("valeurs_distinctes"))
        val det = r.getJSONObject("detecteur_degradation").getJSONArray("relais")
        for (i in 0 until det.length()) {
            val a = det.getJSONObject(i).getJSONObject("autonomie")
            assertEquals("inconnue", a.getString("estimation"), a.toString())
        }
    }

    @Test
    fun spearmanAndSlopeAreCorrect() {
        assertEquals(1.0, RaceLaps.spearman(listOf(1.0, 2.0, 3.0, 4.0), listOf(10.0, 20.0, 30.0, 40.0))!!, 1e-9)
        assertEquals(-1.0, RaceLaps.spearman(listOf(1.0, 2.0, 3.0, 4.0), listOf(4.0, 3.0, 2.0, 1.0))!!, 1e-9)
        assertEquals(2.0, RaceLaps.slope(listOf(1.0, 2.0, 3.0), listOf(1.0, 3.0, 5.0))!!, 1e-9)
    }

    @Test
    fun pitResearchFindsTheStopAndItsRefuel() {
        val stops = PitResearch.run(listOf(trace)).getJSONArray("arrets")
        assertEquals(1, stops.length())
        val s = stops.getJSONObject(0)
        assertEquals(2, s.getInt("tour"))
        assertTrue(s.getDouble("carburant_ajoute_MESURE") > 25.0, s.toString())
        assertTrue(s.getDouble("immobile_s_MESURE") in 19.0..20.5, s.toString())
    }
}
