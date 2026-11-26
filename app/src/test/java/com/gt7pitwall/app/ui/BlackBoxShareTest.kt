package com.gt7pitwall.app.ui

import android.content.Intent
import androidx.activity.ComponentActivity
import com.gt7pitwall.core.blackbox.BlackBoxPack
import com.gt7pitwall.core.race.JournalEvent
import com.gt7pitwall.core.race.JournalSource
import com.gt7pitwall.core.race.JournalType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * Mission Black Box : le pack de course passe par le FileProvider de l'application (chemin `blackbox/exports/`), la
 * feuille de partage reçoit UN fichier ZIP lisible, et le contenu relu par le ContentResolver est le même pack vérifié.
 * Rien n'est envoyé : seul le choix du pilote dans la feuille de partage transmet le fichier.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BlackBoxShareTest {
    @Test fun oneZipIsSharedThroughTheFileProvider() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val exports = File(activity.filesDir, "blackbox/exports").apply { mkdirs() }
        val events = listOf(
            JournalEvent(1, 1_000L, "S1", null, JournalType.SESSION, JournalSource.SYSTEM, "Début de session : test"),
            JournalEvent(2, 2_000L, "S1", 1, JournalType.DRIVER, JournalSource.TRANSCRIPT, "Combien d'essence ?"),
        )
        val res = BlackBoxPack.write(
            File(exports, "Pitwall_2026-11-26_Test_Course1.zip"),
            listOf(BlackBoxPack.Course("S1", null, events)),
            BlackBoxPack.Device("test", "1", "Robolectric", "14"),
        )
        assertTrue(res.verification.status, res.verification.ok)

        shareZip(activity, res.file)
        val chooser = shadowOf(activity).nextStartedActivity
        assertNotNull("feuille de partage ouverte", chooser)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/zip", send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        @Suppress("DEPRECATION")
        val uri = send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("content", uri.scheme)
        assertTrue(uri.toString(), uri.toString().endsWith("/blackbox/Pitwall_2026-11-26_Test_Course1.zip"))

        // Le fichier relu par une autre application (ContentResolver) est le même pack, toujours vérifié.
        val copy = File(activity.cacheDir, "relu.zip")
        activity.contentResolver.openInputStream(uri)!!.use { input -> copy.outputStream().use { input.copyTo(it) } }
        assertTrue(BlackBoxPack.verify(copy).ok)
    }
}
