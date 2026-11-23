package com.gt7pitwall.core

import com.gt7pitwall.core.race.ProfileId
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.SessionKind
import com.gt7pitwall.core.race.SessionOutcome
import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.strategy.DataSource
import com.gt7pitwall.core.strategy.RaceRules
import com.gt7pitwall.core.telemetry.TelemetryParser
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V512, lot 1 : défauts démontrés par le journal de la Course Sport C du Mans (23/11/2026, session 1795428018225-3607).
 * Circuit simulé (RaceSim) : seuls les mécanismes sont reproduits, pas les chronos du Mans.
 */
class V512LeMansTest {
    private fun position(pos: Int, cars: Int): (ByteArray) -> Unit = { b ->
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putShort(0x84, pos.toShort()).putShort(0x86, cars.toShort())
    }

    /** Journal réel : arrêt prévu fin T7, pilote resté en piste ; au T8 « Un arrêt devient nécessaire, fin du tour 9 ». */
    @Test
    fun aMissedStopIsNamedAsTheCauseOfTheNewPlan() {
        val c = RaceController(storage = FakeStorage())
        val s = RaceSim(c, totalLaps = 14, perLap = 9.8f)
        c.setRules(RaceRules(mandatoryStops = 1))
        s.start()
        s.driveLaps(2)
        val first = c.plan!!.stops.map { it.endOfLap }
        assertEquals(listOf(7), first, "même plan que le journal réel : arrêt fin du tour 7")
        // Le pilote ne rentre pas à la fin du tour 7.
        while (s.lap < 9) s.step(80f)
        repeat(600) { s.step(80f) } // 10 s dans le tour 9 (journal réel : nouveau plan 45 s après la ligne)
        val now = c.plan!!.stops.map { it.endOfLap }
        assertEquals(listOf(9), now, "même replanification que le journal : fin du tour 9")
        val reason = assertNotNull(c.lastPlanChange).reason
        assertEquals("Arrêt du tour 7 non effectué : le tour 9 est le dernier possible avec le carburant mesuré.", reason)
        val said = s.texts.first { it.startsWith("Nouveau plan") }
        assertTrue("prochain arrêt fin du tour 9" in said && "Arrêt du tour 7 non effectué" in said, said)
        assertTrue(s.texts.none { "Un arrêt devient nécessaire" in it }, s.texts.toString())
        // « Pourquoi tu changes ma stratégie ? » : la raison est gardée pour la radio.
        assertTrue(c.strategyBrief!!.lastChange!!.contains("non effectué"))
    }

    /** Journal réel : fiche Suzuka du 16/11 (13 tours, usure ×3) gardée alors que GT7 annonce 14 tours. */
    @Test
    fun aStaleSportSheetIsFlaggedAndGameLapsWin() {
        val c = RaceController(storage = FakeStorage())
        c.selectProfile(ProfileId(Series.DAILY_C, SessionKind.RACE))
        c.setRules(RaceRules(totalLaps = 13, fuelMultiplier = 2, tyreMultiplier = 3, mandatoryStops = 1))
        assertNull(c.edition, "aucune édition courante : programme de la semaine absent")
        val s = RaceSim(c, totalLaps = 14, perLap = 9.8f)
        s.start()
        assertTrue(c.staleSportSheet)
        val warning = s.texts.firstOrNull { it.startsWith("Fiche Sport pas à jour") }
        assertNotNull(warning, s.texts.toString())
        assertTrue("elle prévoit 13 tours, GT7 en annonce 14" in warning, warning)
        s.driveLaps(2)
        val plan = c.plan!!
        assertEquals(14, plan.totalLaps)
        assertEquals(DataSource.GAME, plan.lapsSource)
        // Le coefficient d'usure n'est jamais présenté comme le règlement vérifié de la course.
        val tyreAsk = s.texts.first { "tiennent tes pneus" in it }
        assertTrue(tyreAsk.startsWith("D'après une fiche Sport pas à jour, l'usure serait fois 3"), tyreAsk)
        assertTrue(s.texts.none { it.startsWith("Règlement : usure des pneus fois") }, s.texts.toString())
    }

    @Test
    fun aMatchingSheetIsNotFlagged() {
        val c = RaceController(storage = FakeStorage())
        c.selectProfile(ProfileId(Series.DAILY_C, SessionKind.RACE))
        c.setRules(RaceRules(totalLaps = 14, tyreMultiplier = 4, mandatoryStops = 1))
        val s = RaceSim(c, totalLaps = 14, perLap = 9.8f)
        s.start()
        s.driveLaps(1)
        assertTrue(!c.staleSportSheet)
        assertTrue(s.texts.none { it.startsWith("Fiche Sport pas à jour") })
        assertTrue(s.texts.first { "tiennent tes pneus" in it }.startsWith("Règlement : usure des pneus fois 4"))
    }

    /**
     * Journal réel : au tour 14/14, P5/9, GT7 envoie « 0 tour » puis fige la télémétrie ; la session est archivée
     * « interrompue », 13 tours, sans distinction. Une coupure dans le dernier tour est classée à part, SANS résultat.
     */
    @Test
    fun aCutInTheLastLapIsNotAFinishButIsKeptApart() {
        val store = FakeStorage()
        val c = RaceController(storage = store)
        val s = RaceSim(c, totalLaps = 3, perLap = 5f)
        s.tap = position(5, 9)
        s.start()
        while (s.lap < 3) s.step(80f)
        repeat(600) { s.step(80f) } // mi-tour 3 sur 3
        val sessionId = assertNotNull(c.currentRecord()).id
        // GT7 : « 0 tour » à vitesse non nulle, puis données figées.
        var id = 100_000
        fun send(lap: Int, total: Int, speed: Float, x: Float) {
            val plain = TestPackets.plain(lap = lap, totalLaps = total, fuel = 40f, speedMps = speed, carCode = 3607, packetId = ++id, posX = x)
            position(5, 9)(plain)
            c.onPacket(TelemetryParser.parseDecrypted(plain)!!)
        }
        repeat(120) { send(3, 0, 52f, 1234f) }
        // Retour au menu puis nouvelle épreuve.
        repeat(180) { send(0, 0, 0f, 0f) }
        repeat(60) { send(1, 5, 30f, it.toFloat()) }
        val archived = store.loadSessions().first { it.id == sessionId }
        assertEquals(SessionOutcome.LAST_LAP_CUT, archived.outcome)
        assertEquals(2, archived.completedLaps)
        assertEquals(5, archived.lastRacePosition)
        assertEquals(9, archived.lastRaceCars)
        assertEquals(3, archived.lastRaceLap)
        // Aucun résultat inventé : ni ligne d'arrivée, ni classement final.
        assertNull(archived.lineFinishPosition)
        assertNull(archived.finishPosition)
    }

    /** Une coupure avant le dernier tour reste « interrompue ». */
    @Test
    fun aCutBeforeTheLastLapStaysInterrupted() {
        val store = FakeStorage()
        val c = RaceController(storage = store)
        val s = RaceSim(c, totalLaps = 5, perLap = 5f)
        s.start()
        while (s.lap < 3) s.step(80f)
        val sessionId = assertNotNull(c.currentRecord()).id
        var id = 100_000
        fun send(lap: Int, total: Int, speed: Float, x: Float) =
            c.onPacket(TelemetryParser.parseDecrypted(TestPackets.plain(lap = lap, totalLaps = total, speedMps = speed, packetId = ++id, posX = x))!!)
        repeat(180) { send(0, 0, 0f, 0f) }
        repeat(60) { send(1, 4, 30f, it.toFloat()) }
        assertEquals(SessionOutcome.INTERRUPTED, store.loadSessions().first { it.id == sessionId }.outcome)
    }
}
