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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * GT7 official imagery belongs to Sony/Polyphony, not Pitwall. A personal
 * debug APK can preview a thumbnail on its original host. Commercial/release
 * builds always show Pitwall's self-drawn silhouette until licensed artwork
 * is available. No scraped images are checked into git.
 */
@Composable
fun PitwallCarArtwork(carCode: Int?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val preview = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    val photo = produceState<Bitmap?>(initialValue = null, key1 = carCode, key2 = preview) {
        value = if (preview && carCode != null) withContext(Dispatchers.IO) {
            CarImagePreview.load(context, carCode)
        } else null
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

private object CarImagePreview {
    private const val MAX_BYTES = 2_500_000
    private const val HOST = "www.gran-turismo.com"
    private const val PATH = "/common/dist/gt7/carlist/car_images/"

    fun load(context: Context, id: Int): Bitmap? {
        if (id !in 1..20_000) return null
        val disk = File(context.cacheDir,"pitwall-car-" + id + ".webp")
        if (disk.isFile && disk.length() in 1..MAX_BYTES.toLong()) {
            BitmapFactory.decodeFile(disk.path)?.let { return it }
        }
        return runCatching {
            val url = URL("https://" + HOST + PATH + "car" + id + ".webp")
            require(url.protocol == "https" && url.host == HOST)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000; readTimeout = 6_000
                instanceFollowRedirects = false
                setRequestProperty("Accept","image/webp,image/*")
                setRequestProperty("User-Agent","GT7-Pitwall/0.8 (personal preview)")
            }
            try {
                if (connection.responseCode != 200 ||
                    !connection.contentType.orEmpty().lowercase().startsWith("image/") ||
                    connection.contentLengthLong > MAX_BYTES) return null
                val buf = ByteArray(16_384)
                val output = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (output.size() + n > MAX_BYTES) return null
                        output.write(buf,0,n)
                    }
                }
                val bytes = output.toByteArray()
                val image = BitmapFactory.decodeByteArray(bytes,0,bytes.size) ?: return null
                runCatching { disk.writeBytes(bytes) }
                image
            } finally { connection.disconnect() }
        }.getOrNull()
    }
}
