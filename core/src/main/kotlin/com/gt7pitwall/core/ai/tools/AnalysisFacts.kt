package com.gt7pitwall.core.ai.tools

import com.gt7pitwall.core.ai.FactProvenance
import com.gt7pitwall.core.analysis.CornerReport
import com.gt7pitwall.core.sport.FieldStatus
import com.gt7pitwall.core.sport.ScheduleState
import com.gt7pitwall.core.sport.Sourced
import com.gt7pitwall.core.strategy.RaceReview
import com.gt7pitwall.core.tyres.StartTyreAdvisor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Issue #70 / #68 (V492) : faits d'analyse pour les outils de Véga, lus dans les moteurs locaux qui les calculent déjà
 * ([CornerReport], [RaceReview], [StartTyreAdvisor], programme Sport). Rien n'est recalculé ici : on met en forme, on
 * étiquette (provenance, source, fiabilité) et on dit ce qui manque ou se contredit.
 */
object AnalysisFacts {

    /** Résultat d'une section : faits, manques, contradictions. */
    data class Section(val facts: List<ToolFact>, val missing: List<String> = emptyList(), val conflicts: List<String> = emptyList()) {
        companion object {
            val EMPTY = Section(emptyList())
        }
    }

    // ------------------------------------------------------------------ Virages (bilan par virage de la session)

    /**
     * Comparaison par virage : passages valides du même virage comparés au meilleur. Un virage avec moins de
     * [CornerReport.MIN_PASSES] passages n'est pas comparable : il est signalé, pas chiffré.
     */
    fun corners(lines: List<CornerReport.Line>, tyreOrDriving: String?, traction: String?): Section {
        val comparable = lines.filter { it.passes >= CornerReport.MIN_PASSES }
        val facts = comparable.map {
            ToolFact(
                it.text, FactProvenance.CALCULATED,
                "bilan par virage (passages GT7 du même virage comparés au meilleur)",
                "confiance ${it.confidence.label}, ${it.passes} passages",
            )
        }.toMutableList()
        tyreOrDriving?.takeIf { it.isNotBlank() }?.let { facts += ToolFact("Pneus ou pilotage : $it", FactProvenance.CALCULATED, "analyse pneus/pilotage par virage") }
        traction?.takeIf { it.isNotBlank() }?.let { facts += ToolFact("Motricité : $it", FactProvenance.CALCULATED, "motricité à commande comparable") }
        val missing = buildList {
            if (lines.isEmpty()) add("aucun virage mesuré dans cette session (carte des virages pas encore construite)")
            val few = lines.filter { it.passes < CornerReport.MIN_PASSES }.map { it.label }
            if (few.isNotEmpty()) add("pas assez de passages comparables pour : ${few.joinToString()}")
            add("secteurs officiels GT7 et données des adversaires (non transmis)")
        }
        return Section(facts, missing)
    }

    // ------------------------------------------------------------------ Débrief (dernier bilan de course)

    fun debrief(review: RaceReview?, findings: List<String>): Section {
        val facts = mutableListOf<ToolFact>()
        review?.lines()?.forEach { facts += ToolFact(it, FactProvenance.CALCULATED, "bilan de course (session ${review.sessionId.take(12)})", if (review.finished) "course terminée" else "course interrompue") }
        findings.forEach { facts += ToolFact(it, FactProvenance.CALCULATED, "profil de pilotage (constats mesurés par virage)") }
        val missing = buildList {
            if (review == null) add("aucun bilan de course enregistré (il est fait à la fin d'une course)")
            if (findings.isEmpty()) add("aucun constat de pilotage mesuré")
            add("résultats et pneus des adversaires (GT7 ne les transmet pas)")
        }
        return Section(facts, missing)
    }

    // ------------------------------------------------------------------ Gomme de départ (StartTyreAdvisor)

    /** Conseil déjà calculé par [StartTyreAdvisor] ; null : aucun conseil calculé pour cette session (rien n'est relancé). */
    fun startTyre(advice: StartTyreAdvisor.Advice?): Section {
        if (advice == null) return Section(emptyList(), listOf("aucun conseil de gomme de départ calculé pour cette session (il est calculé en qualification ou sur la grille)"))
        val facts = mutableListOf(ToolFact(advice.sentence, FactProvenance.CALCULATED, "conseil de gomme de départ du moteur", "statut : ${advice.status.label}"))
        if (advice.legalStarts.isNotEmpty()) facts += ToolFact("Départs permis par le règlement connu : ${advice.legalStarts.joinToString(", ") { it.name }}.", FactProvenance.CALCULATED, "règlement connu")
        advice.basis.forEach { facts += ToolFact("Base du conseil : $it", FactProvenance.CALCULATED, "conseil de gomme de départ du moteur") }
        val missing = buildList {
            if (advice.status != StartTyreAdvisor.Status.RECOMMENDED) add("pas de recommandation ferme : ${advice.status.label}")
            add("gommes et rythme des adversaires (non transmis)")
        }
        return Section(facts, missing, if (advice.status == StartTyreAdvisor.Status.IMPOSSIBLE) listOf("règlement impossible ou contradictoire pour le conseil de départ") else emptyList())
    }

    // ------------------------------------------------------------------ Programme Sport (cache local, aucune requête réseau)

    private val PARIS: ZoneId = ZoneId.of("Europe/Paris")

    /**
     * Courses Sport A/B/C de la semaine connues de Pitwall (programme déjà téléchargé et mis en cache). Chaque champ
     * porte sa source et son statut ; un champ contradictoire n'est jamais présenté comme une valeur.
     */
    fun sport(state: ScheduleState?, nowMs: Long): Section {
        if (state == null) return Section(emptyList(), listOf("programme Sport pas encore téléchargé (aucune donnée en cache)"))
        val today = Instant.ofEpochMilli(nowMs).atZone(PARIS).toLocalDate()
        val facts = mutableListOf<ToolFact>()
        val missing = mutableListOf<String>()
        val conflicts = mutableListOf<String>()
        if (state.stale) missing += "programme connu périmé : aucune édition ne couvre aujourd'hui"
        state.lastFetchMs?.let { facts += ToolFact("Programme Sport lu le ${fmtDate(Instant.ofEpochMilli(it).atZone(PARIS).toLocalDate())}.", FactProvenance.PUBLISHED, "cache local du programme Sport") }
        state.lastError?.let { missing += "dernière mise à jour en échec : ${it.take(80)}" }
        for ((series, ed) in state.editions.toSortedMap()) {
            val current = !today.isBefore(ed.validFrom) && !today.isAfter(ed.validTo)
            val src = ed.sources.joinToString { it.name }.ifEmpty { "source inconnue" }
            val head = "${series.label} (du ${fmtDate(ed.validFrom)} au ${fmtDate(ed.validTo)}${if (current) ", en cours" else ", PAS la semaine en cours"})"
            val fields = listOf(
                "circuit" to ed.trackName.show { it },
                "tours" to ed.laps.show { "$it tours" },
                "voitures" to ed.cars.show { it },
                "pneus autorisés" to ed.allowedTyres.show { t -> t.joinToString(", ") { it.name } },
                "pneus imposés" to ed.requiredTyres.show { t -> t.joinToString(", ") { it.name } },
                "arrêts obligatoires" to ed.mandatoryStops.show { if (it == 0) "aucun (dit explicitement)" else "$it" },
                "carburant" to ed.fuelMultiplier.show { "×$it" },
                "usure des pneus" to ed.tyreMultiplier.show { "×$it" },
                "BoP" to ed.bop.show { it },
            )
            val known = fields.filter { it.second.first != null }
            facts += ToolFact(
                "$head : " + known.joinToString(" ; ") { (k, v) -> "$k ${v.first} [${v.second}]" } + ".",
                FactProvenance.PUBLISHED, src,
                if (current) "édition en cours" else "édition hors de la semaine en cours",
            )
            fields.filter { it.second.first == null && it.second.second == FieldStatus.UNKNOWN.label }.map { it.first }
                .takeIf { it.isNotEmpty() }?.let { missing += "${series.label} : inconnu pour ${it.joinToString()}" }
            fields.filter { it.second.second == FieldStatus.CONFLICT.label }.forEach { (k, _) -> conflicts += "${series.label} : sources contradictoires sur « $k » : rien n'est appliqué." }
            // Deux gommes imposées ne font pas un arrêt explicite (consigne #70).
            if ((ed.requiredTyres.value?.size ?: 0) >= 2 && ed.mandatoryStops.value == null) {
                missing += "${series.label} : gommes imposées publiées, mais aucun arrêt obligatoire explicite"
            }
        }
        if (state.editions.isEmpty()) missing += "aucune édition A/B/C connue"
        return Section(facts, missing, conflicts)
    }

    /** (valeur affichable ou null, statut) ; un champ contradictoire n'a pas de valeur. */
    private fun <T> Sourced<T>.show(f: (T) -> String): Pair<String?, String> =
        (if (status == FieldStatus.CONFLICT || status == FieldStatus.UNKNOWN) null else value?.let(f)) to status.label

    private fun fmtDate(d: LocalDate) = "%02d/%02d".format(d.dayOfMonth, d.monthValue)
}
