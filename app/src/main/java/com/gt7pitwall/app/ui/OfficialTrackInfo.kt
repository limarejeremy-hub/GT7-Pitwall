package com.gt7pitwall.app.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gt7pitwall.app.ui.theme.PitColors
import com.gt7pitwall.core.knowledge.TrackCatalogPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Logo officiel du circuit et sa fiche (pays, virages, dénivelé, plus longue ligne droite), lus sur le catalogue
 * gran-turismo.com (voir [TrackCatalogPolicy]). Contenus de Sony / Polyphony : APK de débogage seulement, comme la photo
 * des voitures ; rien dans le dépôt ; catalogue relu au plus une fois par semaine, un logo par site, en cache.
 * Échec ou version de diffusion : rien n'est affiché (le tracé dessiné reste).
 */
@Composable
fun OfficialTrackInfo(officialId: String?) {
    val context = LocalContext.current
    val debug = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    val loaded = produceState<Pair<TrackCatalogPolicy.Entry?, Bitmap?>>(null to null, officialId, debug) {
        value = if (officialId == null || !debug) null to null else withContext(Dispatchers.IO) { TrackCatalogLoader.load(context, officialId) }
    }.value
    val entry = loaded.first ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        loaded.second?.let { bmp ->
            Box(Modifier.fillMaxWidth().height(64.dp).background(Color.White, RoundedCornerShape(12.dp)).padding(6.dp), contentAlignment = Alignment.Center) {
                Image(bitmap = bmp.asImageBitmap(), contentDescription = "Logo officiel du circuit", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            entry.country?.let { Fact("Pays", it, Modifier.weight(1.3f)) }
            entry.corners?.let { Fact("Virages", "$it", Modifier.weight(1f)) }
            entry.elevationM?.let { Fact("Dénivelé", String.format(Locale.FRANCE, "%.0f m", it), Modifier.weight(1f)) }
            entry.straightM?.let { Fact("Ligne droite", String.format(Locale.FRANCE, "%.0f m", it), Modifier.weight(1.2f)) }
        }
        Text("Fiche officielle gran-turismo.com", color = PitColors.Muted, fontSize = 10.sp)
    }
}

@Composable
private fun Fact(label: String, value: String, modifier: Modifier) {
    Column(modifier.background(Color(0xFF1B2129), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 6.dp)) {
        Text(label, color = PitColors.Muted, fontSize = 10.sp)
        Text(value, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
    }
}

/** Lecture du catalogue officiel et des logos, avec cache disque et une seule lecture à la fois. */
object TrackCatalogLoader {
    @Volatile
    private var catalog: Map<String, TrackCatalogPolicy.Entry>? = null

    @Volatile
    private var logoModules: Map<String, String> = emptyMap()

    @Volatile
    private var failedAt = 0L

    private val lock = Any()

    fun load(context: Context, officialId: String): Pair<TrackCatalogPolicy.Entry?, Bitmap?> = synchronized(lock) {
        val app = context.applicationContext
        val entry = catalog(app)?.get(officialId) ?: return null to null
        entry to logo(app, entry.baseId)
    }

    private fun dir(context: Context) = File(context.cacheDir, "track-catalog").apply { mkdirs() }

    private fun catalog(context: Context): Map<String, TrackCatalogPolicy.Entry>? {
        catalog?.let { return it }
        val now = System.currentTimeMillis()
        val catFile = File(dir(context), "tracks.fr.js")
        val idxFile = File(dir(context), "index.js")
        if (catFile.isFile && idxFile.isFile && now - catFile.lastModified() < TrackCatalogPolicy.CATALOG_MAX_AGE_MS) {
            runCatching {
                val c = TrackCatalogPolicy.parseCatalog(catFile.readText())
                if (c.isNotEmpty()) {
                    logoModules = TrackCatalogPolicy.logoModules(idxFile.readText())
                    catalog = c
                    return c
                }
            }
        }
        // Après un échec réseau : pas de nouvel essai avant 6 h (aucune requête répétée à chaque affichage).
        if (now - failedAt < 6 * 3_600_000L) return null
        return runCatching {
            val html = text(TrackCatalogPolicy.PAGE) ?: error("page")
            val idxUrl = TrackCatalogPolicy.indexScript(html) ?: error("script principal introuvable")
            val idx = text(idxUrl) ?: error("script")
            val catUrl = TrackCatalogPolicy.catalogModule(idx) ?: error("catalogue introuvable")
            val cat = text(catUrl) ?: error("catalogue")
            val c = TrackCatalogPolicy.parseCatalog(cat).takeIf { it.isNotEmpty() } ?: error("catalogue vide")
            idxFile.writeText(idx)
            catFile.writeText(cat)
            logoModules = TrackCatalogPolicy.logoModules(idx)
            catalog = c
            c
        }.getOrElse {
            failedAt = now
            null
        }
    }

    private fun logo(context: Context, baseId: String): Bitmap? {
        val file = File(dir(context), "logo-$baseId.png")
        if (file.isFile && System.currentTimeMillis() - file.lastModified() < TrackCatalogPolicy.LOGO_MAX_AGE_MS) {
            BitmapFactory.decodeFile(file.path)?.let { return it }
        }
        return runCatching {
            val module = logoModules[baseId] ?: return null
            val png = TrackCatalogPolicy.logoPng(text(module) ?: return null) ?: return null
            val bytes = bytes(png, TrackCatalogPolicy.MAX_LOGO_BYTES) ?: return null
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            file.writeBytes(bytes)
            bmp
        }.getOrNull()
    }

    private fun text(url: String): String? = bytes(url, TrackCatalogPolicy.MAX_SCRIPT_BYTES)?.toString(Charsets.UTF_8)

    /** GET sur le site officiel seulement, redirections limitées au site, taille bornée. */
    private fun bytes(first: String, max: Int): ByteArray? {
        var url = URL(first)
        repeat(4) {
            if (url.protocol != "https" || !(url.host == "gran-turismo.com" || url.host.endsWith(".gran-turismo.com"))) return null
            val c = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 6_000; readTimeout = 8_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "GT7-Pitwall/0.9 (Android; track catalog)")
            }
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location")
                    if (!TrackCatalogPolicy.redirectAllowed(loc)) return null
                    url = URL(url, loc)
                    return@repeat
                }
                if (code != 200) return null
                c.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > max) return null
                    }
                    return out.toByteArray()
                }
            } finally {
                c.disconnect()
            }
        }
        return null
    }
}
