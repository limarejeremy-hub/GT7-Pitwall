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
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

sealed interface ModelState {
    data object NotInstalled : ModelState
    data class Downloading(val progress: Float?) : ModelState
    data object Installed : ModelState
    data class Failed(val message: String) : ModelState
}

/**
 * Modèle de reconnaissance vocale française Vosk (gratuit, ~41 Mo), téléchargé une seule fois
 * puis utilisé hors ligne. Rien de ce que dit le pilote ne quitte le téléphone.
 */
class VoiceModelManager(context: Context) {

    private val root = File(context.filesDir, "vosk")
    private val cacheDir = context.cacheDir
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow<ModelState>(
        if (installedDir() != null) ModelState.Installed else ModelState.NotInstalled,
    )
    val state: StateFlow<ModelState> = _state.asStateFlow()

    /** Dossier du modèle (celui qui contient "am" et "conf"), ou null s'il n'est pas installé. */
    fun installedDir(): File? =
        root.listFiles()?.firstOrNull { File(it, "am").isDirectory && File(it, "conf").isDirectory }

    fun download() {
        if (job?.isActive == true) return
        job = scope.launch {
            val zip = File(cacheDir, "vosk-model.zip")
            try {
                _state.value = ModelState.Downloading(0f)
                val conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                }
                if (conn.responseCode !in 200..299) error("Serveur du modèle : HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    zip.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (isActive) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            _state.value = ModelState.Downloading(if (total > 0) done.toFloat() / total else null)
                        }
                    }
                }
                _state.value = ModelState.Downloading(null)
                root.deleteRecursively()
                root.mkdirs()
                unzip(zip, root)
                _state.value = if (installedDir() != null) {
                    ModelState.Installed
                } else {
                    ModelState.Failed("Archive du modèle inattendue")
                }
            } catch (e: Exception) {
                root.deleteRecursively()
                _state.value = ModelState.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                zip.delete()
            }
        }
    }

    private fun unzip(zip: File, dest: File) {
        val base = dest.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val out = File(dest, entry.name)
                // Protection contre les chemins sortant du dossier de destination.
                if (!out.canonicalPath.startsWith(base)) error("Entrée d'archive invalide : ${entry.name}")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zin.copyTo(it) }
                }
            }
        }
    }

    companion object {
        const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip"
        const val MODEL_SIZE_MB = 41
    }
}
