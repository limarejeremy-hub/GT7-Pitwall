package com.gt7pitwall.app.voice

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import com.gt7pitwall.core.radio.BenchSeries
import com.gt7pitwall.core.radio.UtteranceSegmenter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Banc d'essai de reconnaissance locale (sherpa-onnx) : il écoute le même son que Vosk (micro du casque PS5 ou du
 * Fold), transcrit de son côté et mesure ; il n'agit JAMAIS sur la radio. Aucun son n'est enregistré : les blocs
 * passent en mémoire puis sont oubliés ; seuls les textes et les mesures restent, à l'écran.
 */
class SherpaBench(private val context: Context, val models: BenchModelManager) {
    data class Status(
        val running: Boolean = false,
        val model: BenchModel? = null,
        val message: String = "arrêté",
        /** Chargement : durée et mémoire native prise par le modèle. */
        val load: String? = null,
        /** Texte en cours (moteur continu). */
        val partial: String = "",
        val dropped: Int = 0,
        val nativeHeapMb: Long? = null,
        val battery: String? = null,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val series = BenchSeries()
    private val _attempts = MutableStateFlow<List<BenchSeries.Attempt>>(emptyList())
    val attempts: StateFlow<List<BenchSeries.Attempt>> = _attempts.asStateFlow()
    private val _summary = MutableStateFlow(series.summary())
    val summary: StateFlow<BenchSeries.Summary> = _summary.asStateFlow()
    private val _phrase = MutableStateFlow<String?>(null)
    /** Phrase à dire (série en cours), sinon null. */
    val phrase: StateFlow<String?> = _phrase.asStateFlow()
    private val _progress = MutableStateFlow("")
    val progress: StateFlow<String> = _progress.asStateFlow()

    /** Série en cours : la radio ne réagit pas aux phrases lues (aucune commande ne part). */
    val seriesActive: Boolean get() = synchronized(series) { series.active }

    private class Block(val at: Long, val samples: ShortArray)

    private val queue = LinkedBlockingQueue<Block>(QUEUE_BLOCKS)

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var runningFlag = false

    /** Numéro du lancement en cours : un ancien fil de calcul (arrêt puis relance rapides) s'arrête de lui-même. */
    @Volatile
    private var generation = 0

    private var batteryAtStart: String? = null

    /** Bloc audio de la boucle d'écoute (fil audio). Copié ; ignoré si le banc est arrêté. Ne bloque jamais. */
    fun feed(samples: ShortArray, n: Int) {
        if (!runningFlag || n <= 0) return
        if (!queue.offer(Block(SystemClock.elapsedRealtime(), samples.copyOf(n)))) {
            _status.value = _status.value.copy(dropped = _status.value.dropped + 1)
        }
    }

    /** Ce que Vosk a entendu au même moment (comparaison). */
    fun reference(text: String, free: Boolean) {
        if (!runningFlag) return
        synchronized(series) { series.reference(SystemClock.elapsedRealtime(), text, free) }
        publish()
    }

    fun start(model: BenchModel) {
        if (runningFlag) stop()
        if (!models.installed(model)) {
            _status.value = Status(model = model, message = "modèle non téléchargé")
            return
        }
        runningFlag = true
        queue.clear()
        batteryAtStart = battery()
        _status.value = Status(running = true, model = model, message = "chargement du modèle…", battery = batteryAtStart)
        val gen = ++generation
        worker = Thread({ run(model, gen) }, "pitwall-bench").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        runningFlag = false
        generation++
        worker?.interrupt()
        worker = null
        queue.clear()
        synchronized(series) { series.stop() }
        _status.value = _status.value.copy(running = false, message = "arrêté", partial = "")
        publish()
    }

    fun startSeries() {
        synchronized(series) { series.start() }
        publish()
    }

    fun skipPhrase() {
        synchronized(series) { series.skip() }
        publish()
    }

    fun stopSeries() {
        synchronized(series) { series.stop() }
        publish()
    }

    private fun publish() {
        synchronized(series) {
            _attempts.value = series.attempts.toList()
            _summary.value = series.summary()
            _phrase.value = series.current
            _progress.value = if (series.active) "${series.index + 1}/${series.phrases.size}" else ""
        }
    }


    private fun run(model: BenchModel, gen: Int) {
        fun current() = runningFlag && generation == gen
        var engine: BenchEngine? = null
        try {
            val dir = models.dir(model)
            val heapBefore = Debug.getNativeHeapAllocatedSize()
            val t0 = SystemClock.elapsedRealtime()
            engine = if (model == BenchModel.ZIPFORMER_FR) ZipformerEngine(dir) else WhisperEngine(dir)
            val loadMs = SystemClock.elapsedRealtime() - t0
            val heapMb = (Debug.getNativeHeapAllocatedSize() - heapBefore) / (1024 * 1024)
            if (!current()) return
            _status.value = _status.value.copy(
                message = "à l'écoute (n'agit pas sur la radio)",
                load = "chargé en ${"%.1f".format(loadMs / 1000.0)} s, +$heapMb Mo de mémoire",
            )
            var lastVoiceAt = 0L
            var lastPartial = ""
            while (current()) {
                val block = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (UtteranceSegmenter.rms(block.samples) >= VOICE_RMS) lastVoiceAt = block.at
                val heard = engine.accept(block.samples)
                val partial = engine.partial()
                if (partial != lastPartial) {
                    lastPartial = partial
                    _status.value = _status.value.copy(partial = partial)
                }
                if (heard != null && heard.text.isNotBlank()) {
                    val now = SystemClock.elapsedRealtime()
                    synchronized(series) {
                        series.candidate(
                            atMs = now,
                            engine = model.label,
                            text = heard.text.trim(),
                            latencyMs = if (lastVoiceAt > 0) now - lastVoiceAt else 0,
                            computeMs = heard.computeMs,
                            audioMs = heard.audioMs,
                            screenOff = !screenOn(),
                        )
                    }
                    _status.value = _status.value.copy(
                        nativeHeapMb = Debug.getNativeHeapAllocatedSize() / (1024 * 1024),
                        battery = listOfNotNull(batteryAtStart, battery()).distinct().joinToString(" → "),
                    )
                    publish()
                }
            }
        } catch (e: InterruptedException) {
            // Arrêt demandé.
        } catch (e: Throwable) {
            // Bibliothèque native absente, modèle illisible, mémoire... : le banc s'arrête, la radio continue.
            if (generation != gen) return
            runningFlag = false
            _status.value = _status.value.copy(running = false, message = "erreur : ${e.javaClass.simpleName} ${e.message ?: ""}".trim())
        } finally {
            runCatching { engine?.release() }
        }
    }

    private fun screenOn(): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true

    /** Température et niveau de la batterie (chauffe et consommation pendant l'essai). */
    private fun battery(): String? {
        val i: Intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (temp == Int.MIN_VALUE || level < 0) return null
        return "${"%.1f".format(temp / 10.0)} °C, ${level * 100 / scale} %"
    }

    companion object {
        private const val VOICE_RMS = BenchEngine.VOICE_RMS

        /** ~16 s d'audio en attente au plus : au-delà, des blocs sont perdus (compté et affiché). */
        private const val QUEUE_BLOCKS = 64
    }
}
