package com.gt7pitwall.core

import com.gt7pitwall.core.engineer.RaceEngineer
import com.gt7pitwall.core.engineer.TyreCoach
import com.gt7pitwall.core.radio.DriverCommand
import com.gt7pitwall.core.radio.DriverCommandParser
import com.gt7pitwall.core.session.LapRecord
import com.gt7pitwall.core.session.SessionTracker
import com.gt7pitwall.core.telemetry.TelemetryPacket
import com.gt7pitwall.core.telemetry.TelemetryParser
import com.gt7pitwall.core.tyres.StintContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TyreCoachTest {

    private var id = 0

    /** Un paquet par seconde de jeu (60 identifiants), voiture en piste à 150 km/h. */
    private fun second(temp: Float): TelemetryPacket {
        id += 60
        val plain = TestPackets.plain(packetId = id, speedMps = 41.7f)
        ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN).apply {
            putFloat(0x60, temp)
            putFloat(0x64, temp)
            putFloat(0x68, temp)
            putFloat(0x6C, temp)
        }
        return TelemetryParser.parseDecrypted(plain)!!
    }

    @Test
    fun announcesWarmTyresWhenTemperatureStabilises() {
        val coach = TyreCoach()
        val said = mutableListOf<String>()
        for (s in 0 until 60) coach.onPacket(second(40f + 45f * s / 60f))?.let { said += it.text } // montée 40 → 85
        assertTrue(said.isEmpty(), said.toString())
        for (s in 0 until 30) coach.onPacket(second(85f + (s % 3) * 0.3f))?.let { said += it.text } // stable
        assertEquals(1, said.size, said.toString())
        assertTrue(said[0].startsWith("Températures des pneus stabilisées, 85 degrés"), said[0])
        // Une température stable n'est pas la preuve d'une bonne adhérence : pas de « tu peux attaquer ».
        assertTrue("attaquer" !in said[0])
        // Surchauffe : +15 °C par rapport à la température stabilisée, annoncée une fois.
        val hot = (0 until 5).mapNotNull { coach.onPacket(second(101f))?.text }
        assertEquals(1, hot.size)
        assertTrue(hot[0].startsWith("Tes pneus chauffent : 101 degrés, contre 85"), hot[0])
    }

    @Test
    fun noWarmUpMessageWithoutRealRise() {
        val coach = TyreCoach()
        // Application lancée avec des pneus déjà chauds : rien à annoncer.
        val said = (0 until 60).mapNotNull { coach.onPacket(second(84f)) }
        assertTrue(said.isEmpty(), said.toString())
    }

    private fun lap(n: Int, ms: Int, temp: Float, tcs: Float, locks: Int = 1, spins: Int = 1, refuel: Boolean = false) =
        LapRecord(n, ms, 2f, refuel, null, 250f, tcs, avgTyreTemp = temp, lockups = locks, wheelspins = spins)

    @Test
    fun gripDropThenRecovery() {
        val coach = TyreCoach()
        val laps = mutableListOf(lap(1, 100_000, 86f, 0.05f), lap(2, 99_800, 85f, 0.05f))
        assertNull(coach.onLap(laps, StintContext()))
        laps += lap(3, 104_000, 70f, 0.20f) // pneus -15 °C, TCS 5 % → 20 %
        val drop = assertNotNull(coach.onLap(laps, StintContext()))
        assertTrue(drop.startsWith("Attention, l'adhérence baisse : pneus 15 degrés plus froids, TCS passé de 5 pour cent à 20 pour cent"), drop)
        laps += lap(4, 103_000, 72f, 0.18f)
        assertNull(coach.onLap(laps, StintContext())) // pas de répétition
        laps += lap(5, 100_200, 83f, 0.08f)
        val back = assertNotNull(coach.onLap(laps, StintContext()))
        assertTrue(back.startsWith("L'adhérence revient : pneus à 83 degrés"), back)
    }

    @Test
    fun reallyHardConditions() {
        val coach = TyreCoach()
        val laps = listOf(lap(1, 100_000, 80f, 0.05f), lap(2, 110_000, 78f, 0.35f, locks = 4, spins = 7))
        val msg = assertNotNull(coach.onLap(laps, StintContext()))
        assertTrue(msg.startsWith("C'est la galère : TCS actif 35 pour cent du tour, 4 blocages et 7 patinages"), msg)
        assertNull(coach.onLap(laps + lap(3, 110_500, 78f, 0.33f, 4, 7), StintContext())) // déjà signalé
    }

    @Test
    fun degradationOverTheStint() {
        val coach = TyreCoach()
        val laps = listOf(
            lap(1, 105_000, 70f, 0.02f), // tour de lancement, ignoré
            lap(2, 100_000, 85f, 0.02f),
            lap(3, 100_200, 86f, 0.02f),
            lap(4, 100_500, 87f, 0.03f),
            lap(5, 101_300, 88f, 0.07f),
            lap(6, 101_600, 88f, 0.08f),
        )
        var msg: String? = null
        for (i in 2..laps.size) msg = coach.onLap(laps.take(i), StintContext()) ?: msg
        val m = assertNotNull(msg)
        // D'abord prudent : une dégradation possible, pas une usure affirmée.
        assertTrue(m.startsWith("Possible dégradation : plus 80 centièmes sur tes meilleurs tours avec ces pneus"), m)
        assertTrue("Pas encore confirmée" in m, m)
        // Elle se confirme sur les tours suivants : persistante, avec les autres causes possibles.
        val more = laps + lap(7, 101_500, 88f, 0.06f) + lap(8, 101_700, 88f, 0.06f)
        var msg2: String? = null
        for (i in 7..more.size) msg2 = coach.onLap(more.take(i), StintContext()) ?: msg2
        val p = assertNotNull(msg2)
        assertTrue(p.startsWith("Dégradation persistante"), p)
        assertTrue("mais aussi le trafic ou de petites erreurs" in p, p)
        assertTrue(listOf("usé", "%").none { it in p }, p)
        // Sous la pluie, la baisse des chronos n'est pas attribuée aux pneus.
        assertNull(TyreCoach().onLap(laps, StintContext(raining = true)))
    }

    @Test
    fun rainCommands() {
        assertEquals(DriverCommand.Rain, DriverCommandParser.parse("il pleut"))
        assertEquals(DriverCommand.Rain, DriverCommandParser.parse("piste mouillée"))
        assertEquals(DriverCommand.Dry, DriverCommandParser.parse("piste sèche"))
        assertEquals(DriverCommand.Dry, DriverCommandParser.parse("plus de pluie"))
        assertEquals(DriverCommand.Ask(com.gt7pitwall.core.radio.DriverQuery.SECTORS), DriverCommandParser.parse("mes secteurs"))

        val t = SessionTracker()
        val e = RaceEngineer()
        e.onUpdate(t.update(TestPackets.packet(lap = 1, totalLaps = 10)), t.snapshot, null)
        e.onUpdate(t.update(TestPackets.packet(lap = 2, totalLaps = 10, lastLapMs = 90_000)), t.snapshot, null)
        assertTrue("piste mouillée" in e.handle(DriverCommand.Rain, t.snapshot, null).text)
        val wet = e.onUpdate(t.update(TestPackets.packet(lap = 3, totalLaps = 10, lastLapMs = 98_000)), t.snapshot, null)
        val text = wet.joinToString { it.text }
        assertTrue("Premier tour de référence sous la pluie" in text, text)
        assertTrue("sur ton meilleur" !in text, text)
    }
}
