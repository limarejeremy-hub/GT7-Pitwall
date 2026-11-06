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
    data class Section(
        val facts: List<ToolFact>,
        val missing: List<String> = emptyList(),
        val conflicts: List<String> = emptyList(),
        /** V493 : règles Sport structurées, pour le contrôle du SENS de la réponse (pas seulement des chiffres). */
        val sportRules: List<SportRule> = emptyList(),
    ) {
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
     * Règle de gommes d'une course Sport, telle que le cache la connaît. [required] null : obligation non vérifiée
     * (inconnue ou contradictoire) — rien ne doit alors être affirmé dans un sens ou dans l'autre.
     */
    data class SportRule(
        val series: com.gt7pitwall.core.race.Series,
        val current: Boolean,
        val allowed: Set<com.gt7pitwall.core.strategy.TyreCompound>?,
        val required: Set<com.gt7pitwall.core.strategy.TyreCompound>?,
        val requiredStatus: FieldStatus,
        val mandatoryStops: Int?,
    ) {
        /** Au moins deux gommes obligatoires : un passage aux stands est nécessaire pour changer de gomme. */
        val tyreChangeNeeded: Boolean get() = (required?.size ?: 0) >= 2
    }

    /**
     * Courses Sport A/B/C de la semaine connues de Pitwall (programme déjà téléchargé par le TÉLÉPHONE et mis en cache ;
     * pas une recherche web de GPT). Chaque champ porte sa source et son statut ; un champ inconnu est dit « inconnu »,
     * un champ contradictoire n'a jamais de valeur. Les gommes OBLIGATOIRES sont dites comme telles, avec leur
     * conséquence (passage aux stands), même sans arrêt obligatoire explicite (revue #72). Une semaine ancienne ou un
     * programme périmé ne sont jamais présentés comme valables aujourd'hui.
     */
    fun sport(state: ScheduleState?, nowMs: Long): Section {
        if (state == null) return Section(emptyList(), listOf("programme Sport pas encore téléchargé (aucune donnée en cache)"))
        val today = Instant.ofEpochMilli(nowMs).atZone(PARIS).toLocalDate()
        val facts = mutableListOf<ToolFact>()
        val missing = mutableListOf<String>()
        val conflicts = mutableListOf<String>()
        val rules = mutableListOf<SportRule>()
        if (state.stale) missing += "PROGRAMME PÉRIMÉ : aucune édition connue ne couvre aujourd'hui ; ne rien présenter comme la course du jour"
        state.lastFetchMs?.let {
            val t = Instant.ofEpochMilli(it).atZone(PARIS)
            facts += ToolFact(
                "Programme Sport actualisé par le téléphone le ${fmtDate(t.toLocalDate())} à %02dh%02d (cache local de Pitwall, pas une recherche web).".format(t.hour, t.minute),
                FactProvenance.PUBLISHED, "cache local du programme Sport",
            )
        }
        state.lastError?.let { missing += "dernière actualisation en échec : ${it.take(80)}" }
        for ((series, ed) in state.editions.toSortedMap()) {
            val current = !state.stale && !today.isBefore(ed.validFrom) && today.isBefore(ed.validTo)
            val src = ed.sources.joinToString { it.name }.ifEmpty { "source inconnue" }
            val head = if (current) {
                "${series.label} (semaine du ${fmtDate(ed.validFrom)} au ${fmtDate(ed.validTo)}, en cours)"
            } else {
                "SEMAINE ANCIENNE, ne vaut pas pour aujourd'hui — ${series.label} (du ${fmtDate(ed.validFrom)} au ${fmtDate(ed.validTo)})"
            }
            val tyres: (Set<com.gt7pitwall.core.strategy.TyreCompound>) -> String = { t -> t.joinToString(", ") { it.name } }
            val fields = listOf(
                "circuit" to ed.trackName.describe { it },
                "tours" to ed.laps.describe { "$it tours" },
                "voitures" to ed.cars.describe { it },
                "pneus autorisés" to ed.allowedTyres.describe(tyres),
                "pneus OBLIGATOIRES" to ed.requiredTyres.describe(tyres),
                "arrêts obligatoires" to ed.mandatoryStops.describe { if (it == 0) "aucun (dit explicitement)" else "$it" },
                "carburant" to ed.fuelMultiplier.describe { "×$it" },
                "usure des pneus" to ed.tyreMultiplier.describe { "×$it" },
                "BoP" to ed.bop.describe { it },
            )
            facts += ToolFact("$head : " + fields.joinToString(" ; ") { (k, v) -> "$k $v" } + ".", FactProvenance.PUBLISHED, src,
                if (current) "édition en cours" else "édition ancienne")
            // Gommes obligatoires : la règle et sa conséquence, en clair.
            val req = ed.requiredTyres
            when {
                req.known && (req.value?.size ?: 0) >= 2 -> facts += ToolFact(
                    "${series.label} : les gommes ${tyres(req.value!!)} sont OBLIGATOIRES (il faut utiliser chacune d'elles) : " +
                        "au moins un passage aux stands pour changer de gomme est donc nécessaire" +
                        (if (ed.mandatoryStops.value == null) ", même si aucun arrêt obligatoire n'est écrit" else "") + ".",
                    FactProvenance.PUBLISHED, req.sources.joinToString { it.name }.ifEmpty { src }, "gommes obligatoires ${req.status.label}",
                )
                req.known && req.value?.size == 1 -> facts += ToolFact(
                    "${series.label} : la gomme ${tyres(req.value!!)} est OBLIGATOIRE.", FactProvenance.PUBLISHED,
                    req.sources.joinToString { it.name }.ifEmpty { src }, "gomme obligatoire ${req.status.label}",
                )
                req.known && req.value?.isEmpty() == true -> Unit
                else -> missing += "${series.label} : obligation de gommes NON VÉRIFIÉE (${req.status.label}) : ne rien conclure sur les gommes obligatoires"
            }
            rules += SportRule(series, current, ed.allowedTyres.takeIf { it.known }?.value, req.takeIf { it.known }?.value, req.status, ed.mandatoryStops.takeIf { it.known }?.value)
            fields.filter { it.second.startsWith("inconnu") }.map { it.first }
                .takeIf { it.isNotEmpty() }?.let { missing += "${series.label} : inconnu pour ${it.joinToString()}" }
            listOf(
                "circuit" to ed.trackName, "tours" to ed.laps, "voitures" to ed.cars, "pneus autorisés" to ed.allowedTyres,
                "pneus obligatoires" to ed.requiredTyres, "arrêts obligatoires" to ed.mandatoryStops, "carburant" to ed.fuelMultiplier,
                "usure des pneus" to ed.tyreMultiplier, "BoP" to ed.bop,
            ).filter { it.second.status == FieldStatus.CONFLICT }.forEach { (k, f) ->
                conflicts += "${series.label} : sources contradictoires sur « $k »${f.note?.let { " ($it)" } ?: ""} : rien n'est appliqué."
            }
        }
        if (state.editions.isEmpty()) missing += "aucune édition A/B/C connue"
        return Section(facts, missing, conflicts, rules)
    }

    /** « valeur [statut] », « inconnu », ou « contradictoire entre sources » : jamais une valeur devinée. */
    private fun <T> Sourced<T>.describe(f: (T) -> String): String = when {
        status == FieldStatus.CONFLICT -> "contradictoire entre sources (non appliqué)"
        status == FieldStatus.UNKNOWN || value == null -> "inconnu"
        else -> "${f(value)} [${status.label}]"
    }

    private fun fmtDate(d: LocalDate) = "%02d/%02d".format(d.dayOfMonth, d.monthValue)
}
