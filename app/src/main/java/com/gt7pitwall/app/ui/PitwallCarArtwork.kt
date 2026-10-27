package com.gt7pitwall.app.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import com.gt7pitwall.core.knowledge.CarImagePolicy

/**
 * GT7 official imagery belongs to Sony/Polyphony, not Pitwall. A personal
 * debug APK can preview a thumbnail on its original host. Commercial/release
 * builds always show Pitwall's self-drawn silhouette until licensed artwork
 * is available. No scraped images are checked into git.
 */
@Composable
fun PitwallCarArtwork(carCode: Int?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val preview = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    val retry by CarImageLoader.retries.collectAsState()
    val photo = produceState<Bitmap?>(initialValue = null, carCode, preview, retry) {
        value = when {
            carCode == null -> null
            !preview -> null.also { CarImageLoader.disabled("version de diffusion : photos officielles désactivées (droits Sony / Polyphony)") }
            else -> withContext(Dispatchers.IO) { CarImageLoader.load(context, carCode) }
        }
    }
    Box(modifier.clip(RoundedCornerShape(12.dp))
        .background(Brush.linearGradient(listOf(Color(0xFF283D4D), Color(0xFF101922)))),
        contentAlignment = Alignment.Center) {
        val bmp = photo.value
        if (bmp != null) {
            Image(bitmap = bmp.asImageBitmap(), contentDescription = "Voiture GT7",
                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height
                val path = Path().apply {
                    moveTo(w*.08f,h*.68f); lineTo(w*.16f,h*.53f)
                    lineTo(w*.34f,h*.48f); lineTo(w*.44f,h*.32f)
                    lineTo(w*.73f,h*.32f); lineTo(w*.84f,h*.51f)
                    lineTo(w*.94f,h*.60f); lineTo(w*.93f,h*.74f)
                    lineTo(w*.08f,h*.74f); close()
                }
                drawPath(path, Brush.verticalGradient(listOf(Color(0xFFA4B1BD), Color(0xFF3B5166))))
                drawLine(Color(0xFFFF761C), Offset(w*.08f,h*.75f), Offset(w*.93f,h*.75f),
                    strokeWidth=h*.022f)
                for (cx in listOf(.27f,.76f)) {
                    drawCircle(Color(0xFF0B141D),h*.12f,Offset(w*cx,h*.74f))
                    drawCircle(Color(0xFF8C9CA8),h*.046f,Offset(w*cx,h*.74f))
                }
            }
        }
    }
}

/**
 * Photo officielle de la voiture conduite (une image à la fois, cache borné sur le téléphone). Chaque tentative laisse
 * un diagnostic lisible dans l'Atelier ([status]) : course du 27/10, la silhouette restait sans explication.
 */
object CarImageLoader {
    private val _status = kotlinx.coroutines.flow.MutableStateFlow<CarImagePolicy.Outcome>(CarImagePolicy.Outcome.NoCar)
    val status: kotlinx.coroutines.flow.StateFlow<CarImagePolicy.Outcome> = _status

    /** Échecs récents par voiture : pas de nouvelle requête avant [CarImagePolicy.RETRY_AFTER_FAILURE_MS]. */
    private val failures = java.util.concurrent.ConcurrentHashMap<Int, Pair<Long, String>>()

    private val _retries = kotlinx.coroutines.flow.MutableStateFlow(0)
    /** Compteur des « Réessayer maintenant » : chaque appui relance un seul chargement. */
    val retries: kotlinx.coroutines.flow.StateFlow<Int> = _retries

    /** Détail de la dernière requête (adresse, code, type, taille) pour l'Atelier. Jamais d'identifiant privé. */
    private val _request = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val request: kotlinx.coroutines.flow.StateFlow<String?> = _request

    @Volatile
    private var lastCar: Int? = null

    /** « Réessayer maintenant » : oublie l'échec et l'image en cache de la voiture affichée, puis une seule requête. */
    fun retryNow(context: Context) {
        failures.clear()
        val id = lastCar ?: return
        val app = context.applicationContext
        file(app, id).delete()
        // Une requête, hors du fil de l'interface ; les vignettes affichées relisent ensuite le cache.
        kotlin.concurrent.thread(name = "car-image") {
            load(app, id)
            _retries.value++
        }
    }

    private fun file(context: Context, id: Int) =
        File(File(context.cacheDir, "car-images").apply { mkdirs() }, "v${CarImagePolicy.CACHE_VERSION}-car$id.img")

    fun disabled(reason: String) {
        _status.value = CarImagePolicy.Outcome.Disabled(reason)
    }

    fun load(context: Context, id: Int): Bitmap? {
        val address = CarImagePolicy.url(id) ?: return null.also { _status.value = CarImagePolicy.Outcome.Disabled("identifiant de voiture $id hors limites") }
        lastCar = id
        val disk = file(context, id)
        val dir = disk.parentFile!!
        // Ancien chemin supposé (APK #426) : ses fichiers ne sont jamais relus.
        dir.listFiles { f -> f.name.startsWith("car") }?.forEach { it.delete() }
        val now = System.currentTimeMillis()
        if (disk.isFile && disk.length() in 1..CarImagePolicy.MAX_BYTES.toLong() && now - disk.lastModified() < CarImagePolicy.CACHE_MAX_AGE_MS) {
            BitmapFactory.decodeFile(disk.path)?.let {
                _status.value = CarImagePolicy.Outcome.Shown(id, disk.length().toInt(), fromCache = true)
                return it
            }
        }
        failures[id]?.let { (at, why) ->
            if (now - at < CarImagePolicy.RETRY_AFTER_FAILURE_MS) {
                _status.value = CarImagePolicy.Outcome.Waiting(id, why)
                return null
            }
        }
        val outcome = fetch(id, address)
        _status.value = outcome.first
        val bytes = outcome.second
        if (bytes == null) {
            failures[id] = now to outcome.first.text
            return null
        }
        val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (image == null) {
            val o = CarImagePolicy.Outcome.Undecodable(id, CarImagePolicy.imageFormat(bytes.copyOf(minOf(16, bytes.size))) ?: "?")
            _status.value = o
            failures[id] = now to o.text
            return null
        }
        runCatching {
            val tmp = File(dir, "v${CarImagePolicy.CACHE_VERSION}-car$id.tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(disk)
            // Cache borné : les images les plus anciennes partent d'abord.
            dir.listFiles { f -> f.name.endsWith(".img") }?.sortedByDescending { it.lastModified() }
                ?.drop(CarImagePolicy.MAX_CACHED)?.forEach { it.delete() }
        }
        failures.remove(id)
        return image
    }

    /** Téléchargement (hors fil de l'interface) : redirections seulement vers le site officiel, image vérifiée à ses octets. */
    private fun fetch(id: Int, first: String): Pair<CarImagePolicy.Outcome, ByteArray?> = try {
        var url = URL(first)
        var result: Pair<CarImagePolicy.Outcome, ByteArray?>? = null
        var hops = 0
        while (result == null) {
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000; readTimeout = 6_000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "image/webp,image/*;q=0.9,*/*;q=0.5")
                setRequestProperty("User-Agent", "GT7-Pitwall/0.8 (Android; one car image)")
            }
            try {
                val code = connection.responseCode
                _request.value = "$url → HTTP $code" + (connection.contentType?.let { ", $it" } ?: "") +
                    (connection.contentLengthLong.takeIf { it >= 0 }?.let { ", ${it / 1024} Ko annoncés" } ?: "")
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                    if (!CarImagePolicy.redirectAllowed(location) || ++hops > CarImagePolicy.MAX_REDIRECTS) {
                        result = CarImagePolicy.Outcome.Http(id, code) to null
                    } else {
                        url = URL(url, location)
                    }
                    continue
                }
                if (code != 200) {
                    result = CarImagePolicy.Outcome.Http(id, code) to null
                    continue
                }
                if (connection.contentLengthLong > CarImagePolicy.MAX_BYTES) {
                    result = CarImagePolicy.Outcome.TooLarge(id) to null
                    continue
                }
                val buf = ByteArray(16_384)
                val output = ByteArrayOutputStream()
                var tooLarge = false
                connection.inputStream.use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (output.size() + n > CarImagePolicy.MAX_BYTES) { tooLarge = true; break }
                        output.write(buf, 0, n)
                    }
                }
                val bytes = output.toByteArray()
                result = when {
                    tooLarge -> CarImagePolicy.Outcome.TooLarge(id) to null
                    // Le type annoncé peut être générique (octet-stream) : seuls les octets font foi.
                    CarImagePolicy.imageFormat(bytes.copyOf(minOf(16, bytes.size))) == null ->
                        CarImagePolicy.Outcome.NotAnImage(id, connection.contentType) to null
                    else -> CarImagePolicy.Outcome.Shown(id, bytes.size, fromCache = false) to bytes
                }
            } finally {
                connection.disconnect()
            }
        }
        result
    } catch (e: Exception) {
        _request.value = "$first → aucune réponse"
        CarImagePolicy.Outcome.Network(id, (e.javaClass.simpleName + (e.message?.let { " : ${it.take(80)}" } ?: ""))) to null
    }
}
