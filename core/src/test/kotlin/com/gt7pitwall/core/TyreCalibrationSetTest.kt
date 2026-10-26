package com.gt7pitwall.core

import com.gt7pitwall.core.session.PitAssessment
import com.gt7pitwall.core.tyres.ThermalPhase
import com.gt7pitwall.core.tyres.ThermalSample
import com.gt7pitwall.core.tyres.TyreChangeHunter
import com.gt7pitwall.core.tyres.TyreChangeVerdict
import com.gt7pitwall.core.tyres.TyreHunterCalibration
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Base d'exemples du Tyre Change Hunter : séries réelles étiquetées par le pilote (fichiers `*.series.json`) et
 * contre-exemples construits (étiquetés « synthétique »). Mesure les faux positifs et faux négatifs avant toute
 * automatisation. Aucun contre-exemple réel « pneus conservés » n'existe encore : la base le dit.
 */
class TyreCalibrationSetTest {
    private data class Case(val id: String, val real: Boolean, val changed: Boolean, val samples: List<ThermalSample>, val assessment: PitAssessment)

    private fun phases(spec: String, n: Int): List<ThermalPhase> {
        val out = mutableListOf<ThermalPhase>()
        for (run in spec.split(' ')) {
            val p = when (run[0]) { 'A' -> ThermalPhase.BEFORE; 'S' -> ThermalPhase.STOP; else -> ThermalPhase.EXIT }
            repeat(run.drop(1).toInt()) { out += p }
        }
        return out.take(n)
    }

    private fun realCases(): List<Case> = listOf("2026-10-25-lemans-963-t4", "2026-10-26-wrx-gr3-t8").map { id ->
        val o = JSONObject(javaClass.getResource("/tyre_calibration/$id.series.json")!!.readText())
        fun arr(k: String) = o.getJSONArray(k).let { a -> (0 until a.length()).map { a.getDouble(it).toFloat() } }
        val fl = arr("fl"); val fr = arr("fr"); val rl = arr("rl"); val rr = arr("rr"); val kmh = arr("kmh"); val fuel = arr("fuel")
        val ph = phases(o.getString("phases"), fl.size)
        val samples = fl.indices.map { i -> ThermalSample(i + 1, ph[i], listOf(fl[i], fr[i], rl[i], rr[i]), kmh[i], fuel[i]) }
        Case(id, real = true, changed = o.getString("label") == "TYRES_CHANGED", samples = samples, assessment = PitAssessment.valueOf(o.getString("assessment")))
    }

    /** Contre-exemples construits à partir d'une série réelle : on retire la remise à 60 °C de différentes façons. */
    private fun syntheticNegatives(base: Case): List<Case> {
        val s = base.samples
        val resetIdx = s.indexOfFirst { it.temps.all { t -> kotlin.math.abs(t - 60f) < 0.3f } }
        val before = s[resetIdx - 1].temps
        val cooling = s.mapIndexed { i, x -> if (i < resetIdx) x else x.copy(temps = before.map { it - 0.15f * (i - resetIdx + 1) }) }
        // Pause/chargement : coupure de mesures (secondes manquantes) au moment du saut.
        val gap = s.mapIndexed { i, x -> if (i < resetIdx) x else x.copy(second = x.second + 5) }
        // Saut à l'entrée des stands, voiture encore en mouvement.
        val moving = s.mapIndexed { i, x -> if (i == resetIdx || i == resetIdx - 1) x.copy(speedKmh = 60f) else x }
        return listOf(
            Case("${base.id}-synth-refroidissement", false, false, cooling, base.assessment),
            Case("${base.id}-synth-coupure-udp", false, false, gap, base.assessment),
            Case("${base.id}-synth-en-mouvement", false, false, moving, base.assessment),
        )
    }

    @Test
    fun theDetectorIsMeasuredOnLabelledExamples() {
        val real = realCases()
        val cases = real + real.flatMap(::syntheticNegatives)
        val examples = cases.map { c ->
            val v = TyreChangeHunter.analyze(1, c.assessment, c.samples)
            assertTrue(v.verdict != TyreChangeVerdict.CONFIRMED, "jamais de confirmation automatique : ${c.id}")
            TyreHunterCalibration.Example(suspected = v.verdict == TyreChangeVerdict.SUSPECTED, changed = c.changed, stepC = v.step?.deltas.orEmpty())
        }
        val status = TyreHunterCalibration.evaluate(examples)
        // Sur cette base : 2 changements réels détectés, 6 contre-exemples synthétiques sans fausse alerte.
        assertEquals(2, status.positives)
        assertEquals(0, status.falseNegatives, status.text)
        assertEquals(0, status.falsePositives, status.text)
        // Pas de contre-exemple réel : la fiabilité n'est pas démontrée, la confirmation reste au pilote.
        assertEquals(false, status.ready && real.any { !it.changed })
    }
}
