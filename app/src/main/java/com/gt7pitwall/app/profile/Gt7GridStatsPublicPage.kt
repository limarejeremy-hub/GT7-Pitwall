package com.gt7pitwall.app.profile

import com.gt7pitwall.core.profile.Gt7CareerStats
import com.gt7pitwall.core.profile.Gt7PlayerProfile
import com.gt7pitwall.core.profile.Gt7PublicProfileParser
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/** Experimental read-only public HTML source. No PSN credentials or GridStats API key. */
object Gt7GridStatsPublicPage {
    suspend fun fetch(psnId: String): Gt7CareerStats = withContext(Dispatchers.IO) {
        require(Gt7PlayerProfile.validPsnId(psnId)) { "ID PSN invalide" }
        val url = URL("https://gt-gridstats.com/player/" + URLEncoder.encode(psnId, "UTF-8"))
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 10000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "text/html")
            connection.setRequestProperty("User-Agent", "GT7-Pitwall/0.8 (personal public profile reader)")
            val status = connection.responseCode
            require(status == HttpURLConnection.HTTP_OK) {
                "HTTP " + status + " (aucun contournement de restriction)"
            }
            require(connection.contentType?.lowercase()?.contains("text/html") == true) {
                "La source ne renvoie pas de page HTML"
            }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16384)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    require(output.size() + n <= 1500000) { "Page trop volumineuse" }
                    output.write(buffer, 0, n)
                }
            }
            val text = Jsoup.parse(String(output.toByteArray(), Charsets.UTF_8)).body().text()
            Gt7PublicProfileParser.parse(text, psnId)
        } finally {
            connection.disconnect()
        }
    }
}
