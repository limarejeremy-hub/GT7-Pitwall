package com.gt7pitwall.core.radio

/**
 * V509 — moteur qui produit RÉELLEMENT la voix de Véga, tel que relevé sur le téléphone (diagnostic, rien n'est
 * supposé). Dans la build actuelle, Discord et le haut-parleur du Fold passent tous deux par la synthèse vocale
 * Android (TextToSpeech) du téléphone : aucune voix GPT n'est produite, même en mode ChatGPT (le texte peut venir de
 * GPT, le son jamais).
 */
data class VoiceEngineReport(
    /** Sortie : « Discord (casque PS5) » ou « Haut-parleur du Fold ». */
    val route: String,
    /** Paquet du moteur Android utilisé (« com.google.android.tts », « com.samsung.SMT »…), null avant l'initialisation. */
    val enginePackage: String?,
    val voiceName: String?,
    val locale: String?,
    /** La voix choisie par le moteur demande le réseau (voix « network » de Google), null si inconnu. */
    val networkVoice: Boolean?,
    /** Qualité déclarée par le moteur (Voice.QUALITY_* : 100 à 500), null si inconnue. */
    val quality: Int?,
    /** Fréquence native de la dernière phrase synthétisée, puis fréquence envoyée (Discord : 48 kHz Opus). */
    val nativeRateHz: Int? = null,
    val outputRateHz: Int? = null,
    /** Durée de la dernière synthèse (texte → échantillons), en ms. */
    val lastSynthesisMs: Long? = null,
) {
    val engineLabel: String get() = label(enginePackage)

    fun lines(): List<String> = buildList {
        add("$route : synthèse vocale Android du téléphone — ${engineLabel}" + (enginePackage?.let { " ($it)" } ?: ""))
        add("Voix : ${voiceName ?: "inconnue"}" + (locale?.let { ", $it" } ?: "") +
            when (networkVoice) {
                true -> ", voix réseau"
                false -> ", voix locale"
                null -> ""
            } + (quality?.let { ", qualité $it/500" } ?: ""))
        if (nativeRateHz != null) {
            add("Dernière phrase : $nativeRateHz Hz" + (outputRateHz?.takeIf { it != nativeRateHz }?.let { " rééchantillonnés en $it Hz" } ?: "") +
                (lastSynthesisMs?.let { ", synthèse $it ms" } ?: ""))
        }
        add("GPT ne produit aucun son dans cette build : seul le texte peut venir de GPT (mode ChatGPT).")
    }

    companion object {
        fun label(pkg: String?): String = when (pkg) {
            null -> "moteur pas encore initialisé"
            "com.google.android.tts" -> "Services vocaux Google"
            "com.samsung.SMT" -> "Samsung TTS"
            "com.samsung.android.bixby.wakeup", "com.samsung.android.bixby.agent" -> "Bixby (Samsung)"
            else -> "moteur Android tiers"
        }
    }
}

/**
 * Banc d'écoute A/B (V509) dans le même casque Discord : A = voix actuelle (synthèse Android), B = GPT-Live.
 *
 * B n'est pas disponible : GPT-Live exige une clé d'API de projet OpenAI facturée à la seconde, et le forfait ChatGPT
 * (Sign in with ChatGPT) ne couvre que l'API Responses en texte, sans entrée audio. Le pilote a choisi de ne pas
 * utiliser de clé payante : B reste désactivé, et rien n'est simulé à sa place.
 *
 * Le banc ne fait qu'observer les tours de parole déjà mesurés par la radio ([LatencyTrace]) et les interruptions,
 * dans l'ordre du protocole. Aucun son n'est enregistré ; seuls le texte reconnu et les délais le sont.
 */
class VoiceBench {
    enum class Variant(val label: String) {
        A("A — voix actuelle (synthèse Android)"),
        B("B — GPT-Live"),
    }

    enum class Step(val prompt: String, val judge: String) {
        FUEL("Dis « Véga, je finis avec ce carburant ? »", "délai avant la réponse, naturel"),
        FOLLOW_UP("Enchaîne « Et si je rentre maintenant ? » sans redire « Véga »", "prise sans mot d'appel, délai"),
        BARGE_IN("Pendant sa réponse, coupe-la : « Attends, il reste combien de tours ? »", "arrêt de la voix, reprise de la question"),
        PRONUNCIATION("Écoute « Onzième sur quinze. » (bouton Prononciation)", "prononciation de « onzième »"),
    }

    data class Result(
        val step: Step,
        val heard: String? = null,
        /** Fin de ta phrase → premier son envoyé au salon (le tampon Discord/PS5 s'y ajoute, non observable). */
        val delayMs: Long? = null,
        /** Faux : phrase prise sans « Véga » devant (conversation continue). */
        val addressed: Boolean? = null,
        /** Véga parlait depuis tant de ms quand tu l'as coupée ; null si aucune interruption détectée. */
        val cutAfterMs: Long? = null,
        val bargeInHeard: String? = null,
        val done: Boolean = false,
        val note: String? = null,
    )

    val bUnavailable: String =
        "B indisponible : GPT-Live exige une clé d'API OpenAI payante (0,05 \$/min de voix, plus le modèle). Le forfait " +
            "ChatGPT ne couvre pas l'audio. Aucune clé configurée, par choix : rien n'est simulé."

    var variant: Variant = Variant.A
        private set
    var running: Boolean = false
        private set
    private val results = Step.entries.associateWith { Result(it) }.toMutableMap()
    private var index = 0
    private var lastAudibleMs: Long? = null

    val current: Step? get() = if (running) Step.entries.getOrNull(index) else null

    /** Démarre le protocole ; B est refusé (raison dans [bUnavailable]). */
    fun start(v: Variant = Variant.A): Boolean {
        if (v == Variant.B) return false
        variant = v
        running = true
        index = 0
        lastAudibleMs = null
        Step.entries.forEach { results[it] = Result(it) }
        return true
    }

    fun stop() {
        running = false
    }

    /** Premier son d'une réponse de Véga (pour mesurer depuis quand elle parlait si tu la coupes). */
    fun onAudible(atMs: Long) {
        if (running) lastAudibleMs = atMs
    }

    /** Un tour de parole complet : ce que la radio a reconnu et le délai jusqu'au premier son de la réponse. */
    fun onTurn(heard: String, addressed: Boolean, delayMs: Long?) {
        val step = current ?: return
        when (step) {
            Step.FUEL, Step.FOLLOW_UP -> {
                results[step] = results.getValue(step).copy(heard = heard, delayMs = delayMs, addressed = addressed, done = true,
                    note = if (step == Step.FOLLOW_UP && addressed) "« Véga » redit ou conversation refermée : la relance n'a pas été prise seule" else null)
                index++
            }
            Step.BARGE_IN -> {
                val r = results.getValue(step)
                // La question qui coupe Véga : son délai de réponse ; sans interruption détectée, le tour est noté quand même.
                results[step] = r.copy(heard = heard, delayMs = delayMs, addressed = addressed, done = true,
                    note = if (r.cutAfterMs == null) "aucune interruption détectée : Véga ne parlait plus, ou la coupure n'a pas été reconnue" else null)
                index++
            }
            Step.PRONUNCIATION -> Unit
        }
    }

    /** La radio a détecté que tu coupais Véga ([atMs] : instant de la détection). */
    fun onBargeIn(heard: String, atMs: Long) {
        if (current != Step.BARGE_IN) return
        results[Step.BARGE_IN] = results.getValue(Step.BARGE_IN).copy(
            cutAfterMs = lastAudibleMs?.let { (atMs - it).coerceAtLeast(0) },
            bargeInHeard = heard,
        )
    }

    /** La phrase de prononciation a été jouée dans le casque. */
    fun onPronunciationPlayed() {
        if (current != Step.PRONUNCIATION) return
        results[Step.PRONUNCIATION] = results.getValue(Step.PRONUNCIATION).copy(done = true, note = "à juger à l'oreille")
        index++
        running = false
    }

    fun results(): List<Result> = Step.entries.map { results.getValue(it) }

    fun lines(): List<String> = results().map { r ->
        val head = "${r.step.ordinal + 1}. ${r.step.prompt}"
        if (!r.done && r.cutAfterMs == null) return@map "$head — en attente"
        val parts = listOfNotNull(
            r.heard?.let { "entendu « $it »" },
            r.delayMs?.let { "réponse en $it ms" } ?: r.heard?.let { "délai non mesuré" },
            r.addressed?.takeIf { r.step == Step.FOLLOW_UP }?.let { if (it) "avec mot d'appel" else "prise sans « Véga »" },
            r.cutAfterMs?.let { "coupée après $it ms de parole" + (r.bargeInHeard?.let { h -> " (« $h »)" } ?: "") },
            r.note,
        )
        "$head — ${parts.joinToString(", ")}"
    }

    /** Export texte à comparer d'une session à l'autre (aucun son, aucun identifiant). */
    fun export(engine: List<String>): String = buildString {
        appendLine("# Pitwall — banc voix A/B (${variant.label})")
        engine.forEach { appendLine("# $it") }
        appendLine("# $bUnavailable")
        appendLine("# Délai = fin de ta phrase → premier son envoyé au salon ; le tampon Discord et PS5 s'y ajoute.")
        this@VoiceBench.lines().forEach { appendLine(it) }
    }

    companion object {
        /** Phrases d'écoute de la prononciation (texte d'origine ; la normalisation audio s'applique ensuite). */
        val PRONUNCIATION = listOf(
            "Onzième sur quinze.",
            "Tu es onzième sur quinze, à un dixième du dixième.",
            "P11 sur 15.",
        )
    }
}
