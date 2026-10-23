package com.gt7pitwall.core.radio

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Découpe un flux 16 bits en phrases pour une reconnaissance « phrase entière » (Whisper) : une phrase commence à la
 * première voix (avec [preRollMs] d'avance pour ne pas couper la première syllabe) et finit après [endSilenceMs] de
 * silence, ou à [maxMs]. Moins de [minVoiceMs] de voix : bruit, ignoré. Le temps est compté en échantillons reçus.
 */
class UtteranceSegmenter(
    private val sampleRate: Int = 16_000,
    private val speechRms: Double = 400.0,
    private val preRollMs: Long = 300,
    private val endSilenceMs: Long = 800,
    private val minVoiceMs: Long = 300,
    private val maxMs: Long = 12_000,
) {
    private val preRoll = ArrayDeque<ShortArray>()
    private var preRollSamples = 0
    private val current = ArrayList<ShortArray>()
    private var currentSamples = 0
    private var voiceSamples = 0
    private var silenceSamples = 0
    private var inSpeech = false

    /** Bloc reçu ; renvoie une phrase complète quand elle se termine, sinon null. */
    fun accept(samples: ShortArray, n: Int): ShortArray? {
        if (n <= 0) return null
        val block = samples.copyOf(n)
        val voiced = rms(block) >= speechRms
        if (!inSpeech) {
            if (!voiced) {
                preRoll.addLast(block)
                preRollSamples += n
                while (preRoll.isNotEmpty() && preRollSamples - preRoll.first().size >= ms(preRollMs)) {
                    preRollSamples -= preRoll.removeFirst().size
                }
                return null
            }
            inSpeech = true
            current.addAll(preRoll)
            currentSamples = preRollSamples
            preRoll.clear()
            preRollSamples = 0
            voiceSamples = 0
            silenceSamples = 0
        }
        current.add(block)
        currentSamples += n
        if (voiced) {
            voiceSamples += n
            silenceSamples = 0
        } else {
            silenceSamples += n
        }
        if (silenceSamples >= ms(endSilenceMs) || currentSamples >= ms(maxMs)) return close()
        return null
    }

    /** Phrase en cours rendue telle quelle (fin du banc d'essai), ou null. */
    fun flush(): ShortArray? = if (inSpeech) close() else null

    private fun close(): ShortArray? {
        val enough = voiceSamples >= ms(minVoiceMs)
        val out = if (enough) ShortArray(currentSamples).also { dst ->
            var at = 0
            for (b in current) {
                b.copyInto(dst, at)
                at += b.size
            }
        } else {
            null
        }
        current.clear()
        currentSamples = 0
        inSpeech = false
        return out
    }

    private fun ms(v: Long): Int = (v * sampleRate / 1000).toInt()

    companion object {
        fun rms(block: ShortArray): Double {
            if (block.isEmpty()) return 0.0
            var sum = 0.0
            for (s in block) sum += s.toDouble() * s
            return sqrt(sum / block.size)
        }
    }
}

/** Taux d'erreur par mots (WER), après normalisation : accents, ponctuation, apostrophes, nombres écrits en chiffres. */
object WordErrorRate {
    fun words(text: String): List<String> =
        DriverCommandParser.normalize(text)
            .replace("[unk]", " ")
            .replace(Regex("[’'`]"), " ")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(' ')
            .filter { it.isNotBlank() }
            .flatMap { w -> w.toIntOrNull()?.let { FrenchNumbers.words(it) } ?: listOf(w) }

    /** 0 = identique ; 1 = tout faux (peut dépasser 1 si l'hypothèse invente des mots). */
    fun of(reference: String, hypothesis: String): Double {
        val r = words(reference)
        val h = words(hypothesis)
        if (r.isEmpty()) return if (h.isEmpty()) 0.0 else 1.0
        var prev = IntArray(h.size + 1) { it }
        for (i in 1..r.size) {
            val cur = IntArray(h.size + 1)
            cur[0] = i
            for (j in 1..h.size) {
                val cost = if (r[i - 1] == h[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[h.size].toDouble() / r.size
    }
}

/** Nombres de 0 à 999 en mots, sans traits d'union (« 21 » → « vingt et un »), pour comparer Whisper et Vosk. */
object FrenchNumbers {
    private val units = listOf(
        "zero", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix",
        "onze", "douze", "treize", "quatorze", "quinze", "seize",
    )
    private val tens = mapOf(2 to "vingt", 3 to "trente", 4 to "quarante", 5 to "cinquante", 6 to "soixante")

    fun words(n: Int): List<String> = when {
        n < 0 || n > 999 -> n.toString().map { units[it - '0'] }
        n <= 16 -> listOf(units[n])
        n < 20 -> listOf("dix", units[n - 10])
        n < 70 -> {
            val t = tens.getValue(n / 10)
            when (val u = n % 10) {
                0 -> listOf(t)
                1 -> listOf(t, "et", "un")
                else -> listOf(t, units[u])
            }
        }
        n < 80 -> if (n == 71) listOf("soixante", "et", "onze") else listOf("soixante") + words(n - 60)
        n < 100 -> listOf("quatre", "vingt") + if (n == 80) emptyList() else words(n - 80)
        else -> {
            val h = n / 100
            val rest = n % 100
            (if (h == 1) listOf("cent") else listOf(units[h], "cent")) + if (rest == 0) emptyList() else words(rest)
        }
    }
}

/**
 * Série d'essai du banc de reconnaissance : le pilote lit des phrases radio connues ; chaque phrase rendue par le
 * moteur testé est comparée à la phrase attendue, et à ce que Vosk a entendu au même moment. Rien n'agit sur la radio.
 */
class BenchSeries(val phrases: List<String> = PHRASES, private val pairWindowMs: Long = 5_000) {
    data class Attempt(
        val atMs: Long,
        val expected: String?,
        val engine: String,
        val text: String,
        val wer: Double?,
        /** Fin de la voix → texte disponible. */
        val latencyMs: Long,
        /** Temps de calcul du moteur pour cette phrase. */
        val computeMs: Long,
        /** Durée d'audio traitée. */
        val audioMs: Long,
        val screenOff: Boolean,
        val vosk: String? = null,
        val voskWer: Double? = null,
        val voskFree: Boolean = false,
    )

    private data class Reference(val atMs: Long, val text: String, val free: Boolean)

    private val references = ArrayDeque<Reference>()
    private val _attempts = ArrayList<Attempt>()
    val attempts: List<Attempt> get() = _attempts

    /** Série en cours (sinon : écoute libre, sans phrase attendue). */
    var active = false
        private set
    var index = 0
        private set

    val current: String? get() = if (active) phrases.getOrNull(index) else null

    fun start() {
        active = true
        index = 0
        _attempts.clear()
        references.clear()
    }

    fun stop() {
        active = false
    }

    fun skip() {
        if (!active) return
        index++
        if (index >= phrases.size) active = false
    }

    /** Ce que Vosk a entendu ([free] : sa transcription libre, sinon la reconnaissance radio). */
    fun reference(atMs: Long, text: String, free: Boolean) {
        if (WordErrorRate.words(text).isEmpty()) return
        val ref = Reference(atMs, text, free)
        // Arrivée après le moteur testé : rattachée à sa phrase.
        val i = _attempts.indexOfLast { abs(it.atMs - atMs) <= pairWindowMs && (it.vosk == null || (free && !it.voskFree)) }
        if (i >= 0) {
            _attempts[i] = withVosk(_attempts[i], ref)
            return
        }
        references.addLast(ref)
        while (references.size > 10) references.removeFirst()
    }

    /** Phrase rendue par le moteur testé : attribuée à la phrase attendue en cours, qui avance. */
    fun candidate(
        atMs: Long,
        engine: String,
        text: String,
        latencyMs: Long,
        computeMs: Long,
        audioMs: Long,
        screenOff: Boolean,
    ): Attempt {
        val expected = current
        var attempt = Attempt(
            atMs, expected, engine, text, expected?.let { WordErrorRate.of(it, text) }, latencyMs, computeMs, audioMs, screenOff,
        )
        val near = references.filter { abs(it.atMs - atMs) <= pairWindowMs }
        (near.firstOrNull { it.free } ?: near.lastOrNull())?.let { ref ->
            attempt = withVosk(attempt, ref)
            references.removeAll(near)
        }
        _attempts += attempt
        if (expected != null) skip()
        return attempt
    }

    private fun withVosk(a: Attempt, ref: Reference) =
        a.copy(vosk = ref.text, voskFree = ref.free, voskWer = a.expected?.let { WordErrorRate.of(it, ref.text) })

    data class Summary(
        val phrases: Int,
        val engineWer: Double?,
        val engineExact: Int,
        val voskPhrases: Int,
        val voskWer: Double?,
        val voskExact: Int,
        val meanLatencyMs: Long?,
        /** Temps de calcul / durée d'audio : < 1 = plus rapide que le temps réel. */
        val realTimeFactor: Double?,
        val screenOff: Int,
    )

    fun summary(): Summary {
        val scored = _attempts.filter { it.wer != null }
        val vosk = scored.filter { it.voskWer != null }
        val audio = _attempts.sumOf { it.audioMs }
        return Summary(
            phrases = scored.size,
            engineWer = scored.map { it.wer!! }.takeIf { it.isNotEmpty() }?.average(),
            engineExact = scored.count { it.wer == 0.0 },
            voskPhrases = vosk.size,
            voskWer = vosk.map { it.voskWer!! }.takeIf { it.isNotEmpty() }?.average(),
            voskExact = vosk.count { it.voskWer == 0.0 },
            meanLatencyMs = _attempts.map { it.latencyMs }.takeIf { it.isNotEmpty() }?.average()?.toLong(),
            realTimeFactor = if (audio > 0) _attempts.sumOf { it.computeMs }.toDouble() / audio else null,
            screenOff = _attempts.count { it.screenOff },
        )
    }

    companion object {
        /** Phrases radio types, sans le mot de réveil (la radio ne réagit pas pendant la série). */
        val PHRASES = listOf(
            "est-ce que tu m'entends",
            "combien d'essence il me reste",
            "combien de tours avant l'arrêt",
            "je rentre aux stands au prochain tour",
            "quel est l'écart avec la voiture devant",
            "mes pneus arrière commencent à lâcher",
            "il commence à pleuvoir sur la piste",
            "on passe en pneus intermédiaires",
            "je suis en économie de carburant",
            "quelle est ma position",
            "donne-moi mon meilleur tour",
            "la voiture derrière revient sur moi",
            "je viens de doubler la voiture vingt",
            "est-ce qu'on fait un ou deux arrêts",
            "combien de litres faut-il mettre",
            "j'ai perdu du temps dans le dernier secteur",
            "la piste est en train de sécher",
            "rappelle-moi les obligations de la course",
            "je garde ce rythme jusqu'à la fin",
            "merci, bien reçu",
        )
    }
}
