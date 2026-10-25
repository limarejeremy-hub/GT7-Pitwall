package com.gt7pitwall.core

import com.gt7pitwall.core.ai.RadioConversation
import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.race.PositionTracker
import com.gt7pitwall.core.race.ProfileId
import com.gt7pitwall.core.race.RaceController
import com.gt7pitwall.core.race.Series
import com.gt7pitwall.core.race.SessionKind
import com.gt7pitwall.core.radio.DriverCommand
import com.gt7pitwall.core.radio.DriverCommandParser
import com.gt7pitwall.core.radio.DriverQuery
import com.gt7pitwall.core.telemetry.TelemetryPacket
import com.gt7pitwall.core.telemetry.TelemetryParser
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Position mesurée (0x84 / 0x86), d'après les essais PS5 : matchmaking ignoré, chargement et oscillations filtrés,
 * déconnexion d'un participant, arrivée figée, qualification exclue, repli sur la position déclarée.
 */
class LivePositionTest {

    private val onTrack = TelemetryPacket.FLAG_ON_TRACK
    private val loading = TelemetryPacket.FLAG_LOADING
    private val paused = TelemetryPacket.FLAG_ON_TRACK or TelemetryPacket.FLAG_PAUSED

    private var id = 0

    private fun packet(lap: Int, total: Int, pos: Int, cars: Int, flags: Int = onTrack): TelemetryPacket {
        val plain = TestPackets.plain(lap = lap, totalLaps = total, flags = flags, packetId = ++id, speedMps = if (lap >= 1) 50f else 0f)
        ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x84, pos.toShort())
            putShort(0x86, cars.toShort())
        }
        return TelemetryParser.parseDecrypted(plain)!!
    }

    /** Une ligne du journal PS5 : à partir de [t] (s), GT7 envoie ces valeurs. */
    private data class Seg(val t: Double, val lap: Int, val total: Int, val pos: Int, val cars: Int, val flags: Int)

    private class Replay(val c: RaceController, var now: Long) {
        val said = mutableListOf<EngineerMessage>()
    }

    /** Rejoue un journal à 20 paquets par seconde (le filtre est temporel). */
    private fun replay(segs: List<Seg>, kind: SessionKind = SessionKind.RACE, until: Double? = null, quiet: Boolean = false, onTime: (Double, Replay) -> Unit = { _, _ -> }): Replay {
        var now = 0L
        val c = RaceController(storage = FakeStorage(), clock = { now })
        c.engineer.quietRace = quiet
        c.selectProfile(ProfileId(Series.DAILY_A, kind))
        val r = Replay(c, 0)
        val end = until ?: (segs.last().t + 10)
        var t = segs.first().t
        var i = 0
        val hooks = mutableSetOf<Double>()
        while (t < end) {
            while (i + 1 < segs.size && segs[i + 1].t <= t) i++
            val s = segs[i]
            now = (t * 1000).toLong()
            r.now = now
            r.said += c.onPacket(packet(s.lap, s.total, s.pos, s.cars, s.flags))
            onTime(t, r)
            t += 0.05
        }
        return r
    }

    private fun texts(r: Replay) = r.said.filter { it.category != MessageCategory.DRIVER }.map { it.text }

    /** Journal réel de la Course Sport A (heures ramenées en secondes depuis 10:40:00). */
    private val sportA = listOf(
        Seg(10.5, -1, -1, -1, -1, 0),
        Seg(11.5, -1, 0, 1, 1, loading),
        Seg(11.53, -1, 0, 1, 1, onTrack),
        Seg(15.0, -1, 0, 2, 2, onTrack), Seg(15.4, -1, 0, 3, 3, onTrack), Seg(15.5, -1, 0, 4, 4, onTrack),
        Seg(15.7, -1, 0, 6, 6, onTrack), Seg(16.6, -1, 0, 8, 8, onTrack), Seg(17.4, -1, 0, 11, 11, onTrack),
        Seg(18.3, -1, 0, 12, 12, onTrack),
        Seg(57.3, 0, 0, 1, 12, loading), Seg(57.32, 0, 0, 1, 12, onTrack),
        Seg(76.75, 0, 5, 7, 12, loading), Seg(76.76, 0, 5, 7, 12, onTrack),
        Seg(115.4, 1, 5, 7, 12, onTrack),
        Seg(189.29, 1, 5, 6, 12, onTrack), Seg(189.62, 1, 5, 7, 12, onTrack),
        Seg(205.34, 1, 5, 6, 12, onTrack), Seg(209.2, 1, 5, 7, 12, onTrack), Seg(210.6, 1, 5, 8, 12, onTrack),
        Seg(222.9, 2, 5, 8, 12, onTrack),
        Seg(243.3, 2, 5, 9, 12, onTrack), Seg(243.66, 2, 5, 8, 12, onTrack), Seg(244.8, 2, 5, 9, 12, onTrack),
        Seg(247.8, 2, 5, 10, 12, onTrack), Seg(250.0, 2, 5, 11, 12, onTrack), Seg(278.7, 2, 5, 10, 12, onTrack),
        Seg(320.7, 2, 5, 11, 12, onTrack), Seg(329.3, 3, 5, 11, 12, onTrack), Seg(364.5, 3, 5, 10, 12, onTrack),
        Seg(398.9, 3, 5, 11, 12, onTrack), Seg(400.9, 3, 5, 10, 12, onTrack), Seg(427.1, 3, 5, 11, 12, onTrack),
        Seg(435.9, 4, 5, 11, 12, onTrack), Seg(501.4, 4, 5, 10, 12, onTrack), Seg(529.0, 4, 5, 11, 12, onTrack),
        Seg(537.7, 5, 5, 11, 12, onTrack), Seg(544.8, 5, 5, 10, 12, onTrack), Seg(549.73, 5, 5, 9, 12, onTrack),
        Seg(549.75, 5, 5, 9, 11, onTrack), Seg(569.5, 5, 5, 8, 11, onTrack), Seg(570.8, 5, 5, 9, 11, onTrack),
        Seg(572.5, 5, 5, 8, 11, onTrack), Seg(578.0, 5, 5, 7, 11, onTrack), Seg(579.4, 5, 5, 8, 11, onTrack),
        Seg(601.7, 5, 5, 7, 11, onTrack), Seg(603.9, 5, 5, 8, 11, onTrack), Seg(606.3, 5, 5, 9, 11, onTrack),
        Seg(612.4, 5, 5, 8, 11, onTrack), Seg(620.1, 5, 5, 7, 11, onTrack), Seg(629.2, 5, 5, 8, 11, onTrack),
        Seg(640.6, 6, 5, 8, 11, onTrack),
        Seg(650.2, 6, 0, 8, 11, onTrack),
    )

    @Test
    fun realSportARaceIsFollowedLikeTheHud() {
        var duringMatchmaking: Int? = 0
        var atHudP10: String? = null
        var lap1After6: Int? = null
        val announceTimes = mutableListOf<Double>()
        var seen = 0
        val r = replay(sportA) { t, rp ->
            // Matchmaking : 0x84 compte les voitures chargées (12/12 alors que le HUD indique P7) : jamais lu.
            if (t in 48.0..48.05) duringMatchmaking = rp.c.engineer.currentPosition
            if (t in 192.0..192.05) lap1After6 = rp.c.engineer.currentPosition
            if (t in 390.5..390.55) atHudP10 = rp.c.engineer.answer(DriverQuery.POSITION, rp.c.snapshot, rp.c.fuel).text
            if (rp.said.size > seen) {
                if (rp.said.drop(seen).any { Regex("^P\\d+\\.$").containsMatchIn(it.text) }) announceTimes += t
                seen = rp.said.size
            }
        }
        val said = texts(r)
        assertNull(duringMatchmaking, "matchmaking ignoré")
        assertTrue("Tu pars en position 7 sur 12. Bonne course !" in said, said.toString())
        assertFalse(said.any { "position 1 sur" in it || "position 12" in it }, "ni le 1 du chargement ni le 12 du matchmaking")
        assertEquals(7, lap1After6, "un 6 de 0,3 s (roue dans roue) n'est pas retenu")
        assertEquals("Tu es P10 sur 12.", atHudP10, "HUD P10 au tour 3")

        // Anti-spam : jamais deux annonces de position à moins de 8 s, malgré la bagarre du dernier tour.
        assertTrue(announceTimes.isNotEmpty(), said.toString())
        assertTrue(announceTimes.zipWithNext().all { (a, b) -> b - a >= 7.99 }, announceTimes.toString())

        // Arrivée : P8 sur 11 (une voiture s'est déconnectée au tour 5), figée malgré la fin de session.
        val m = r.c.engineer.measured
        assertEquals(8, m.finalPosition)
        assertEquals(11, m.finalCars)
        assertEquals(7, m.startPosition)
        assertEquals(12, m.startCars)
        assertTrue(said.any { "Tu termines P8 sur 11" in it }, said.toString())
        assertFalse(said.any { "Ta position à l'arrivée ?" in it }, "inutile de la demander")
        val places = r.c.engineer.answer(DriverQuery.PLACES, r.c.snapshot, r.c.fuel).text
        assertEquals("Tu as perdu une place : P7 au départ, P8 à l'arrivée. Une voiture a quitté la course depuis le départ.", places)
    }

    @Test
    fun announcementsAreSpacedAndUseTheStablePosition() {
        val segs = listOf(
            Seg(0.0, 0, 5, 10, 16, onTrack),
            Seg(10.0, 1, 5, 10, 16, onTrack),
            Seg(20.0, 1, 5, 9, 16, onTrack),
            Seg(25.0, 1, 5, 8, 16, onTrack),
            Seg(30.0, 1, 5, 7, 16, onTrack),
            Seg(80.0, 1, 5, 7, 16, onTrack),
        )
        val said = texts(replay(segs))
        val announces = said.filter { Regex("^P\\d+\\.$").containsMatchIn(it) }
        // Deux dépassements à 5 s d'intervalle : regroupés, seule la nouvelle place est dite.
        assertEquals(listOf("P7."), announces, said.toString())
    }

    @Test
    fun loadingPausesAndMinusOneAreIgnored() {
        val t = PositionTracker()
        var now = 0L
        fun feed(lap: Int, total: Int, pos: Int, cars: Int, flags: Int = onTrack, seconds: Double) {
            repeat((seconds * 20).toInt()) {
                t.onPacket(packet(lap, total, pos, cars, flags), now)
                now += 50
            }
        }
        feed(0, 0, 12, 12, seconds = 10.0) // matchmaking : tours inconnus
        assertNull(t.position)
        feed(0, 5, 1, 12, loading, 5.0) // chargement
        assertNull(t.position)
        feed(0, 5, -1, -1, seconds = 10.0) // -1
        assertNull(t.position)
        feed(0, 5, 13, 12, seconds = 10.0) // incohérent : position > participants
        assertNull(t.position)
        feed(0, 5, 4, 12, seconds = 3.5)
        assertEquals(4, t.position)
        assertEquals(4, t.startPosition)
        feed(1, 5, 2, 12, paused, 10.0) // pause : aucun changement
        assertEquals(4, t.position)
    }

    @Test
    fun lostSignalFallsBackToTheDeclaredPosition() {
        val segs = listOf(
            Seg(0.0, 0, 5, 6, 12, onTrack),
            Seg(10.0, 1, 5, 6, 12, onTrack),
            Seg(20.0, 1, 5, -1, -1, onTrack),
        )
        val r = replay(segs, until = 40.0)
        val said = texts(r)
        assertTrue("Je ne reçois plus ta position de GT7. Donne-la-moi si besoin." in said, said.toString())
        assertNull(r.c.engineer.measured.position)
        // Repli : la dernière position mesurée sert de base aux déclarations du pilote.
        assertEquals(6, r.c.engineer.currentPosition)
        val reply = r.c.command(DriverCommand.Overtook).last().text
        assertEquals("Bien joué ! Tu es P5.", reply)
        assertTrue(r.c.engineer.answer(DriverQuery.POSITION, r.c.snapshot, r.c.fuel).text.startsWith("Je ne reçois plus ta position de GT7."))
    }

    @Test
    fun measuredPositionTakesPriorityOverDeclarationsAndTheLapQuestion() {
        val segs = listOf(Seg(0.0, 0, 5, 6, 12, onTrack), Seg(10.0, 1, 5, 6, 12, onTrack))
        val r = replay(segs, until = 20.0)
        val c = r.c
        assertEquals("Noté, mais GT7 me donne P6 : je garde sa mesure.", c.command(DriverCommand.ReportPosition(3)).last().text)
        assertEquals(6, c.engineer.currentPosition)
        assertTrue(c.command(DriverCommand.Overtook).last().text.startsWith("Bien joué ! Je suis ta position avec GT7 : P6"))
        assertEquals(6, c.engineer.currentPosition, "un « j'ai doublé » ne remplace pas la mesure")
        assertEquals("Tu es P6 sur 12.", c.engineer.answer(DriverQuery.POSITION, c.snapshot, c.fuel).text)

    }

    @Test
    fun lapQuestionIsDroppedWhileGt7GivesThePosition() {
        fun lapMessages(measured: com.gt7pitwall.core.race.MeasuredPosition): List<String> {
            val t = com.gt7pitwall.core.session.SessionTracker()
            val e = com.gt7pitwall.core.engineer.RaceEngineer()
            e.askPositionEachLap = true
            e.measured = measured
            val out = mutableListOf<EngineerMessage>()
            fun send(lap: Int, last: Int, n: Int) = repeat(n) {
                val plain = TestPackets.plain(lap = lap, totalLaps = 5, lastLapMs = last, packetId = ++id, speedMps = 50f)
                out += e.onUpdate(t.update(TelemetryParser.parseDecrypted(plain)!!), t.snapshot, null)
            }
            send(1, -1, 30)
            send(2, 90_000, 30)
            send(3, 91_000, 30)
            return out.filter { it.category == MessageCategory.LAP }.map { it.text }
        }
        val without = lapMessages(com.gt7pitwall.core.race.MeasuredPosition.NONE)
        assertTrue(without.isNotEmpty() && without.all { it.endsWith("Ta position ?") }, without.toString())
        val with = lapMessages(com.gt7pitwall.core.race.MeasuredPosition(position = 5, cars = 12, tracked = true))
        assertTrue(with.isNotEmpty() && with.none { "Ta position ?" in it } && with.all { it.endsWith("P5.") }, with.toString())
    }

    @Test
    fun qualifyingNeverUsesTheField() {
        val segs = listOf(Seg(0.0, 0, 5, 6, 12, onTrack), Seg(10.0, 1, 5, 3, 12, onTrack), Seg(30.0, 6, 5, 2, 12, onTrack))
        val r = replay(segs, kind = SessionKind.QUALIFYING, until = 40.0)
        val said = texts(r)
        assertFalse(said.any { Regex("P\\d|position \\d").containsMatchIn(it) }, said.toString())
        assertNull(r.c.engineer.measured.finalPosition)
        assertTrue(r.c.engineer.answer(DriverQuery.POSITION, r.c.snapshot, r.c.fuel).text.startsWith("En qualification"))
        assertTrue(r.c.engineer.answer(DriverQuery.PLACES, r.c.snapshot, r.c.fuel).text.startsWith("En qualification"))
    }

    @Test
    fun finishFreezesTheLinePositionNotLaterValues() {
        val segs = listOf(
            Seg(0.0, 0, 3, 5, 10, onTrack),
            Seg(10.0, 1, 3, 4, 10, onTrack),
            Seg(30.0, 4, 3, 3, 10, onTrack), // arrivée en P3
            Seg(35.0, 4, 3, 9, 10, onTrack), // valeurs d'après-course
            Seg(40.0, 4, 0, 12, 12, onTrack), // retour au salon
        )
        val r = replay(segs, until = 50.0)
        assertEquals(3, r.c.engineer.measured.finalPosition)
        assertEquals("Tu termines P3 sur 10, d'après GT7.", r.c.engineer.answer(DriverQuery.POSITION, r.c.snapshot, r.c.fuel).text)
        assertEquals("Tu as gagné 2 places : P5 au départ, P3 à l'arrivée.", r.c.engineer.answer(DriverQuery.PLACES, r.c.snapshot, r.c.fuel).text)
    }

    @Test
    fun placesQuestionsAreQuestionsNotOvertakes() {
        for (s in listOf("combien de places gagnées", "j'ai perdu combien de places", "j'ai gagné combien de places", "combien de places j'ai perdu")) {
            assertEquals(DriverCommand.Ask(DriverQuery.PLACES), DriverCommandParser.parse(s), s)
        }
        assertEquals(DriverCommand.Ask(DriverQuery.POSITION), DriverCommandParser.parse("quelle est ma position"))
        // Par la radio : réponse, aucun dépassement enregistré.
        val r = replay(listOf(Seg(0.0, 0, 5, 6, 12, onTrack), Seg(10.0, 1, 5, 4, 12, onTrack)), until = 20.0)
        val conv = RadioConversation(r.c, clock = { r.now })
        val out = runBlocking { conv.onUtterance("j'ai perdu combien de places", "j'ai perdu combien de places", null) }
            .filter { it.category != MessageCategory.DRIVER }
        assertEquals("Tu as gagné 2 places : P6 au départ, P4 maintenant.", out.single().text)
        assertEquals(4, r.c.engineer.currentPosition)
    }

    @Test
    fun aDoubleOvertakeOrAPileUpIsAnnouncedAsTheNewPlaceOnly() {
        // Essai réel (20 octobre, 22:13) : P17, puis 16 et 15 à 3 s d'intervalle → une seule annonce, « P15. ».
        val double = listOf(
            Seg(0.0, 0, 12, 18, 20, onTrack), Seg(10.0, 2, 12, 17, 20, onTrack),
            Seg(47.37, 2, 12, 16, 20, onTrack), Seg(50.51, 2, 12, 15, 20, onTrack), Seg(80.0, 2, 12, 15, 20, onTrack),
        )
        val said = texts(replay(double)).filter { Regex("^P\\d+\\.$").matches(it) }
        assertEquals(listOf("P17.", "P15."), said)
        // Accident devant : six places en quelques secondes → la nouvelle place, pas six annonces.
        val crash = listOf(Seg(0.0, 0, 12, 10, 20, onTrack), Seg(10.0, 3, 12, 10, 20, onTrack)) +
            (1..6).map { Seg(30.0 + it * 0.8, 3, 12, 10 - it, 20, onTrack) } + Seg(60.0, 3, 12, 4, 20, onTrack)
        val crashSaid = texts(replay(crash)).filter { Regex("^P\\d+\\.$").matches(it) }
        assertEquals(listOf("P4."), crashSaid)
    }

    @Test
    fun lapReportsAreShortByDefault() {
        val segs = listOf(Seg(0.0, 0, 5, 6, 12, onTrack), Seg(10.0, 1, 5, 6, 12, onTrack))
        val r = replay(segs, until = 20.0)
        val t = com.gt7pitwall.core.session.SessionTracker()
        val e = r.c.engineer
        // Un tour bouclé avec un temps : temps, écart avec le meilleur, position. Ni secteurs, ni rythme, ni carburant large.
        e.measured = com.gt7pitwall.core.race.MeasuredPosition(position = 3, cars = 12, tracked = true)
        fun send(lap: Int, last: Int) = repeat(30) {
            val plain = TestPackets.plain(lap = lap, totalLaps = 8, lastLapMs = last, packetId = ++id, speedMps = 50f)
            e.onUpdate(t.update(TelemetryParser.parseDecrypted(plain)!!), t.snapshot, null)
        }
        send(1, -1)
        send(2, 98_000)
        send(3, 98_400)
        val msgs = mutableListOf<String>()
        repeat(30) {
            val plain = TestPackets.plain(lap = 4, totalLaps = 8, lastLapMs = 98_150, packetId = ++id, speedMps = 50f)
            msgs += e.onUpdate(t.update(TelemetryParser.parseDecrypted(plain)!!), t.snapshot, null).map { it.text }
        }
        val lap = msgs.single { it.startsWith("Tour 3") }
        assertEquals("Tour 3 : 1 minute 38 secondes 2. Plus 15 centièmes sur ton meilleur. P3.", lap)
        assertTrue(e.answer(com.gt7pitwall.core.radio.DriverQuery.DETAILS, t.snapshot, null).text.startsWith("Tour 3"), "le détail reste disponible")
    }

    /**
     * Course réelle du 25/10, tour 3 (temps relevés dans le journal, en secondes) : une place annoncée presque toutes les
     * 10 s, parfois deux d'affilée. Course silencieuse : au plus une place toutes les 30 s, toujours la place du moment.
     */
    @Test
    fun aQuietRaceAnnouncesFewFreshPositions() {
        val moves = listOf(0.0 to 15, 10.0 to 14, 17.0 to 13, 24.0 to 12, 32.0 to 13, 44.0 to 12, 79.0 to 11, 84.0 to 9,
            103.0 to 8, 122.0 to 7, 131.0 to 8, 159.0 to 7)
        val segs = listOf(Seg(0.0, 0, 7, 15, 20, onTrack), Seg(5.0, 1, 7, 15, 20, onTrack)) +
            moves.map { (t, p) -> Seg(20.0 + t, 1, 7, p, 20, onTrack) }
        val announce = Regex("P(\\d+)\\.$")
        fun announces(r: Replay) = r.said.filter { it.category == MessageCategory.RADIO && announce.containsMatchIn(it.text) }
        val loud = announces(replay(segs, until = 200.0))
        var times = mutableListOf<Pair<Long, Int>>()
        val quietRun = replay(segs, until = 200.0, quiet = true) { _, r ->
            val n = announces(r).size
            if (n > times.size) times += r.now to r.c.engineer.measured.position!!
        }
        val quiet = announces(quietRun)
        assertTrue(quiet.size in 2 until loud.size, "${quiet.size} annonces contre ${loud.size}")
        assertTrue(times.zipWithNext().all { (a, b) -> b.first - a.first >= 30_000 }, times.toString())
        // Chaque annonce donne la place mesurée au moment où elle est dite.
        quiet.zip(times).forEach { (m, t) -> assertEquals("P${t.second}.", announce.find(m.text)!!.value, m.text) }
    }
}
