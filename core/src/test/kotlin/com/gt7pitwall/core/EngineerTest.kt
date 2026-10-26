package com.gt7pitwall.core

import com.gt7pitwall.core.TestPackets.packet
import com.gt7pitwall.core.engineer.FrenchFormat
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.engineer.Priority
import com.gt7pitwall.core.engineer.RaceEngineer
import com.gt7pitwall.core.radio.DriverQuery
import com.gt7pitwall.core.radio.DriverQueryParser
import com.gt7pitwall.core.session.SessionTracker
import com.gt7pitwall.core.strategy.FuelStrategy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngineerTest {

    private fun run(t: SessionTracker, e: RaceEngineer, p: com.gt7pitwall.core.telemetry.TelemetryPacket) =
        e.onUpdate(t.update(p), t.snapshot, FuelStrategy.compute(t.snapshot))

    @Test
    fun formats() {
        assertEquals("1:32.456", FrenchFormat.lapTime(92_456))
        assertEquals("--:--.---", FrenchFormat.lapTime(-1))
        assertEquals("+0.412", FrenchFormat.delta(412))
        assertEquals("-1.030", FrenchFormat.delta(-1030))
        assertEquals("1 minute 32 secondes 5", FrenchFormat.lapTimeSpeech(92_456))
        assertEquals("2 minutes 0 seconde 0", FrenchFormat.lapTimeSpeech(119_990))
        assertEquals("41 centièmes", FrenchFormat.deltaSpeech(412))
        assertEquals("2 centièmes", FrenchFormat.deltaSpeech(22))
        assertEquals("1 centième", FrenchFormat.deltaSpeech(-8))
        assertEquals("moins d'un centième", FrenchFormat.deltaSpeech(3))
        assertEquals("1 seconde", FrenchFormat.deltaSpeech(996))
        assertEquals("2,5 secondes", FrenchFormat.deltaSpeech(-2_480))
        assertEquals("3,5 tours", FrenchFormat.laps(3.46f))
        assertEquals("1 tour", FrenchFormat.laps(1f))
    }

    @Test
    fun announcesRadioCheckLapsAndFuel() {
        val t = SessionTracker()
        val e = RaceEngineer().apply { shortLapReports = false } // messages de tour détaillés
        val first = run(t, e, packet(lap = 1, totalLaps = 5, fuel = 100f))
        assertEquals(MessageCategory.RADIO, first.single().category)

        val lap1 = run(t, e, packet(lap = 2, totalLaps = 5, fuel = 90f, lastLapMs = 90_000))
        val text1 = lap1.single().text
        assertTrue(text1.startsWith("Tour 1 : 1 minute 30 secondes 0."), text1)
        // Début du tour 1 non observé => mesure en cours, aucune conso inventée.
        assertTrue("mesure la consommation" in text1, text1)

        val lap2 = run(t, e, packet(lap = 3, totalLaps = 5, fuel = 80f, lastLapMs = 89_600))
        val text2 = lap2.single().text
        assertTrue("Meilleur tour, 40 centièmes de mieux" in text2, text2)
        // Course du 26/10 : une seule annonce rassurante, puis silence sur le carburant tant que tout va bien.
        assertTrue("Essence suffisante pour l'arrivée" in text2, text2)
        val lap3 = run(t, e, packet(lap = 4, totalLaps = 5, fuel = 70f, lastLapMs = 89_700))
        assertTrue(lap3.none { "Essence" in it.text || "Carburant" in it.text }, lap3.toString())
        // À la demande, le détail reste disponible.
        assertTrue("Carburant pour" in e.answer(com.gt7pitwall.core.radio.DriverQuery.FUEL, t.snapshot, com.gt7pitwall.core.strategy.FuelStrategy.compute(t.snapshot)).text)
    }

    @Test
    fun pitCallAndLowFuelAlertsOnce() {
        val t = SessionTracker()
        val e = RaceEngineer().apply { shortLapReports = false } // messages de tour détaillés
        run(t, e, packet(lap = 1, totalLaps = 10, fuel = 30f))
        run(t, e, packet(lap = 2, totalLaps = 10, fuel = 20f, lastLapMs = 90_000))
        val msgs = run(t, e, packet(lap = 3, totalLaps = 10, fuel = 10f, lastLapMs = 91_000))
        // Il ne reste qu'un tour de carburant : c'est le tour où il faut rentrer. L'appel au stand est un
        // message critique séparé, placé avant le compte rendu du tour.
        val box = msgs.first()
        assertEquals(Priority.CRITICAL, box.priority)
        assertTrue(box.text.startsWith("Box, box ! Rentre au stand à la fin de ce tour"), box.text)
        val lapMsg = msgs.first { it.category == MessageCategory.LAP }
        assertTrue("Box" !in lapMsg.text, lapMsg.text)
        // 10 restants à 10/tour => moins de 2 tours : une seule alerte, critique (risque de panne).
        assertTrue(msgs.any { it.category == MessageCategory.ALERT && it.priority == Priority.CRITICAL && "moins de deux tours" in it.text })
        val again = run(t, e, packet(lap = 3, totalLaps = 10, fuel = 10f, lastLapMs = 91_000))
        assertTrue(again.none { it.category == MessageCategory.ALERT })
        val critical = run(t, e, packet(lap = 3, totalLaps = 10, fuel = 9f, lastLapMs = 91_000))
        assertEquals(Priority.CRITICAL, critical.single().priority)
    }

    @Test
    fun answersQueriesFromRealDataOnly() {
        val t = SessionTracker()
        val e = RaceEngineer().apply { shortLapReports = false } // messages de tour détaillés
        val none = e.answer(DriverQuery.FUEL, t.snapshot, null)
        assertEquals(RaceEngineer.NO_DATA, none.text)

        t.update(packet(lap = 4, totalLaps = 6, fuel = 50f, lastLapMs = 61_000, bestLapMs = 60_500))
        val snap = t.snapshot
        val fuel = FuelStrategy.compute(snap)
        assertEquals("Dernier tour : 1 minute 1 seconde 0.", e.answer(DriverQuery.LAST_LAP, snap, fuel).text)
        assertEquals("Meilleur tour : 1 minute 0 seconde 5.", e.answer(DriverQuery.BEST_LAP, snap, fuel).text)
        assertEquals("Tour 4 sur 6. Encore 2 tours après celui-ci.", e.answer(DriverQuery.RACE_STATUS, snap, fuel).text)
        assertTrue("Pas encore de tour complet" in e.answer(DriverQuery.FUEL, snap, fuel).text)
        assertTrue("au moins deux tours" in e.answer(DriverQuery.PACE, snap, fuel).text)
    }

    @Test
    fun parsesSpokenQueries() {
        assertEquals(DriverQuery.FUEL, DriverQueryParser.parse("Combien d'essence ?"))
        assertEquals(DriverQuery.LAST_LAP, DriverQueryParser.parse("mon dernier tour"))
        assertEquals(DriverQuery.BEST_LAP, DriverQueryParser.parse("meilleur tour ?"))
        assertEquals(DriverQuery.RACE_STATUS, DriverQueryParser.parse("il reste combien de tours"))
        assertEquals(null, DriverQueryParser.parse("bonjour"))
    }

    /** Rejoue la course réelle de la capture d'écran : 5 tours, carburant bloqué à 100. */
    @Test
    fun replayOfRealFiveLapRaceWithoutFuelConsumption() {
        val t = SessionTracker()
        val e = RaceEngineer().apply { shortLapReports = false } // messages de tour détaillés
        val laps = listOf(90_637, 100_007, 99_624, 90_615, 86_961)
        val all = mutableListOf<com.gt7pitwall.core.engineer.EngineerMessage>()
        all += run(t, e, packet(lap = 0, totalLaps = 5, fuel = 100f))
        all += run(t, e, packet(lap = 1, totalLaps = 5, fuel = 100f))
        var last = -1
        laps.forEachIndexed { i, ms ->
            all += run(t, e, packet(lap = i + 1, totalLaps = 5, fuel = 100f, lastLapMs = last))
            all += run(t, e, packet(lap = i + 2, totalLaps = 5, fuel = 100f, lastLapMs = ms))
            last = ms
        }
        val texts = all.map { it.text }
        val lap1 = texts.first { it.startsWith("Tour 1 :") }
        assertTrue("Encore 4 tours" in lap1, lap1)
        assertTrue("pas de consommation sur cette épreuve" in lap1, lap1)
        // Annonce "pas de consommation" une seule fois.
        assertEquals(1, texts.count { "pas de consommation" in it })
        val lap3 = texts.first { it.startsWith("Tour 3 :") }
        assertTrue("Rythme moyen" in lap3, lap3)
        val lap4 = texts.first { it.startsWith("Tour 4 :") }
        assertTrue("Meilleur tour, 2 centièmes de mieux" in lap4, lap4)
        assertTrue("Dernier tour, donne tout" in lap4, lap4)
        assertTrue(texts.none { it == "Dernier tour ! Donne tout." })
        // À l'arrivée : seulement « course terminée », le meilleur tour et la position (demandée si inconnue).
        assertTrue(texts.none { it.startsWith("Tour 5 :") }, texts.toString())
        val finish = texts.last()
        assertEquals("Course terminée ! Meilleur tour : 1 minute 27 secondes 0. Ta position à l'arrivée ?", finish)
        assertEquals(
            com.gt7pitwall.core.strategy.FuelAdvice.RaceFinished,
            FuelStrategy.compute(t.snapshot)!!.advice,
        )
    }

    @Test
    fun aSingleGridPacketIsNotAnnounced() {
        val t = SessionTracker()
        val e = RaceEngineer().apply { shortLapReports = false } // messages de tour détaillés
        val plain = TestPackets.plain(lap = 0, totalLaps = 5)
        java.nio.ByteBuffer.wrap(plain).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x84, 8)
            putShort(0x86, 16)
        }
        val p = com.gt7pitwall.core.telemetry.TelemetryParser.parseDecrypted(plain)!!
        // Valeur brute non filtrée (0x84 vaut un instant 1 au chargement) : la grille n'est annoncée qu'une fois stable
        // (voir LivePositionTest).
        val first = run(t, e, p).map { it.text }
        assertTrue(first.none { "position" in it }, first.toString())
        assertEquals(null, e.driverPosition)
    }
}
