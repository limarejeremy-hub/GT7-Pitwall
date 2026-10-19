package com.gt7pitwall.core.strategy

import kotlin.math.roundToInt

/**
 * Unité du carburant. GT7 transmet un niveau et une capacité de réservoir (100 pour la plupart des voitures, 5 en kart,
 * 0 en électrique, quelle que soit la taille réelle du réservoir) : aucune documentation ne prouve que ce soient des
 * litres. Pitwall parle donc en pourcentage du réservoir (niveau ÷ capacité, toujours exact) et en tours d'autonomie ;
 * sans capacité connue, en « unités GT7 ». Jamais en litres.
 */
object FuelUnits {
    /** Pourcentage du réservoir, null si GT7 ne donne pas de capacité. */
    fun percent(units: Float, capacity: Float?): Float? = capacity?.takeIf { it > 0f }?.let { units / it * 100f }

    /** « 23 % du réservoir » (« 2,5 % » sous 10) ; sans capacité : « 23 unités GT7 ». */
    fun amount(units: Float, capacity: Float?): String {
        val pct = percent(units, capacity) ?: return "${number(units)} unités GT7"
        return "${number(pct)} % du réservoir"
    }

    /** Consommation par tour : « 4,2 % du réservoir par tour ». */
    fun perLap(units: Float, capacity: Float?): String {
        val pct = percent(units, capacity) ?: return "${oneDecimal(units)} unités GT7 par tour"
        return "${oneDecimal(pct)} % du réservoir par tour"
    }

    /** Pour l'écran : « 23 % » ou « 23 u. ». */
    fun short(units: Float, capacity: Float?): String = percent(units, capacity)?.let { "${number(it)} %" } ?: "${number(units)} u."

    private fun number(v: Float): String = if (v >= 10f) v.roundToInt().toString() else oneDecimal(v)

    private fun oneDecimal(v: Float): String {
        val r = (v * 10).roundToInt() / 10f
        return if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString().replace('.', ',')
    }
}
