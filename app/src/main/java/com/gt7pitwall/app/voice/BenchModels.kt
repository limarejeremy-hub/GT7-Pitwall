package com.gt7pitwall.app.voice

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Modèles du banc d'essai sherpa-onnx (gratuits, archives officielles du projet sur GitHub). */
enum class BenchModel(
    val label: String,
    val detail: String,
    val archive: String,
    /** Fichiers à garder dans l'archive (le reste n'est pas écrit). */
    val files: List<String>,
    /** Ordre de grandeur du téléchargement (Mo). */
    val downloadMb: Int,
) {
    ZIPFORMER_FR(
        "Zipformer français (continu)",
        "reconnaît au fil de la voix, comme Vosk",
        "sherpa-onnx-streaming-zipformer-fr-2023-04-14",
        listOf(
            "encoder-epoch-29-avg-9-with-averaged-model.int8.onnx",
            "decoder-epoch-29-avg-9-with-averaged-model.onnx",
            "joiner-epoch-29-avg-9-with-averaged-model.int8.onnx",
            "tokens.txt",
        ),
        downloadMb = 120,
    ),
    WHISPER_BASE(
        "Whisper base (phrase entière)",
        "multilingue, réglé en français ; transcrit après la fin de la phrase",
        "sherpa-onnx-whisper-base",
        listOf("base-encoder.int8.onnx", "base-decoder.int8.onnx", "base-tokens.txt"),
        downloadMb = 210,
    ),
    ;

    val url: String get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$archive.tar.bz2"
}

/**
 * Téléchargement des modèles du banc d'essai : l'archive .tar.bz2 est décompressée au fil de l'eau et seuls les
 * fichiers utiles sont écrits ; le téléchargement s'arrête dès qu'ils sont tous là (le modèle français n'a besoin que
 * du début de son archive de 400 Mo).
 */
class BenchModelManager(context: Context) {
    private val root = File(context.filesDir, "bench")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _states = MutableStateFlow(BenchModel.entries.associateWith { stateOf(it) })
    val states: StateFlow<Map<BenchModel, ModelState>> = _states.asStateFlow()

    fun dir(model: BenchModel): File = File(root, model.archive)

    fun installed(model: BenchModel): Boolean = model.files.all { File(dir(model), it).isFile }

    private fun stateOf(model: BenchModel): ModelState = if (installed(model)) ModelState.Installed else ModelState.NotInstalled

    private fun set(model: BenchModel, state: ModelState) {
        _states.value = _states.value + (model to state)
    }

    /** Taille occupée par le modèle (Mo). */
    fun sizeMb(model: BenchModel): Long = (dir(model).listFiles()?.sumOf { it.length() } ?: 0L) / (1024 * 1024)

    fun download(model: BenchModel) {
        if (job?.isActive == true) return
        job = scope.launch {
            val dest = dir(model)
            try {
                set(model, ModelState.Downloading(0f))
                dest.deleteRecursively()
                dest.mkdirs()
                val conn = (URL(model.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                }
                try {
                    if (conn.responseCode !in 200..299) error("Serveur du modèle : HTTP ${conn.responseCode}")
                    val total = conn.contentLengthLong
                    val wanted = model.files.toMutableSet()
                    val counting = Counting(conn.inputStream.buffered(256 * 1024)) { read ->
                        set(model, ModelState.Downloading(if (total > 0) read.toFloat() / total else null))
                    }
                    TarArchiveInputStream(BZip2CompressorInputStream(counting)).use { tar ->
                        while (isActive && wanted.isNotEmpty()) {
                            val entry = tar.nextEntry ?: break
                            val name = entry.name.substringAfterLast('/')
                            if (entry.isDirectory || name !in wanted) continue
                            val part = File(dest, "$name.part")
                            part.outputStream().use { tar.copyTo(it, 256 * 1024) }
                            check(part.renameTo(File(dest, name))) { "Écriture impossible : $name" }
                            wanted -= name
                        }
                    }
                    if (wanted.isNotEmpty()) error("Archive incomplète : ${wanted.joinToString()} manquant")
                } finally {
                    conn.disconnect()
                }
                set(model, ModelState.Installed)
            } catch (e: Exception) {
                dest.deleteRecursively()
                set(model, ModelState.Failed(e.message ?: e.javaClass.simpleName))
            }
        }
    }

    fun delete(model: BenchModel) {
        if (job?.isActive == true) return
        dir(model).deleteRecursively()
        set(model, ModelState.NotInstalled)
    }

    /** Compte les octets reçus (progression), sans rien garder. */
    private class Counting(input: InputStream, private val onRead: (Long) -> Unit) : FilterInputStream(input) {
        private var count = 0L
        private var lastReport = 0L

        override fun read(): Int = super.read().also { if (it >= 0) add(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) add(it.toLong()) }

        private fun add(n: Long) {
            count += n
            if (count - lastReport >= 512 * 1024) {
                lastReport = count
                onRead(count)
            }
        }
    }
}
