package com.gt7pitwall.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.gt7pitwall.app.ui.theme.PitwallTheme
import com.gt7pitwall.core.display.GtDash
import com.gt7pitwall.core.display.RaceCommandBoard
import com.gt7pitwall.core.display.TelemetryBoard
import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.engineer.MessageCategory
import com.gt7pitwall.core.engineer.Priority
import com.gt7pitwall.core.strategy.TyreCompound
import com.gt7pitwall.core.tyres.GripLevel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Captures de CONTRÔLE de l'écran RACE (V507), rendues par Robolectric à partir du vrai code Compose, avec des données
 * de démonstration (pas une course réelle). Fichiers : app/build/screens (PNG), publiés par la CI. Elles servent à
 * vérifier la mise en page (rien de tronqué, rien qui déborde) ; le rendu sur le Fold reste à confirmer.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class GtDashboardScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun dash(shift: Boolean = false, lit: Int = 9) = GtDash(
        live = true, paused = false, gear = "4", suggestedGear = null, speedKmh = 213, rpm = 7140,
        leds = GtDash.Leds(15, if (shift) 15 else lit, shift, true), throttle = 0.92f, brake = 0f, tcs = false, asm = false,
        lapTimeMs = 58_312, lapTimeSource = GtDash.TimeSource.PITWALL, deltaMs = 352, sector = 2,
        sectors = listOf(
            GtDash.SectorCell(1, 30_412, GtDash.SectorState.BEST),
            GtDash.SectorCell(2, null, GtDash.SectorState.CURRENT),
            GtDash.SectorCell(3, null, GtDash.SectorState.PENDING),
        ),
        lastLapMs = 91_604, bestLapMs = 91_274, waterC = 88, oilC = 104, turboBar = null,
    )

    private fun board(alerts: List<RaceCommandBoard.Alert> = emptyList()) = RaceCommandBoard(
        link = RaceCommandBoard.Link.LIVE, silenceS = null, live = true, position = 7, cars = 16, positionMeasured = true,
        lap = 5, totalLaps = 11, finished = false, lastLapMs = 91_604, bestLapMs = 91_274,
        fuel = RaceCommandBoard.Fuel(46f, 8.9f, 5.1f, 6.4f, -1.3f, RaceCommandBoard.FuelRisk.PIT_NEEDED, 8),
        tyres = RaceCommandBoard.Tyres(
            listOf(78f to "AVG", 82f to "AVD", 97f to "ARG", 99f to "ARD").map { (t, l) -> TelemetryBoard.TyreCell(l, t, TelemetryBoard.band(t)) },
            TyreCompound.RM, RaceCommandBoard.CompoundStatus.DECLARED, 4, GripLevel.STABLE, overheating = false,
        ),
        pit = RaceCommandBoard.Pit(
            RaceCommandBoard.PitState.PLANNED, 8, 3, TyreCompound.RH, refuel = true, changeTyres = true, stopsDone = 0,
            missingTyres = setOf(TyreCompound.RH), requiredTyres = setOf(TyreCompound.RM, TyreCompound.RH), rulesIncomplete = false, confirmLap = null,
        ),
        alerts = alerts,
    )

    private fun shoot(name: String, d: GtDash, b: RaceCommandBoard, menu: Boolean = true) {
        // Horloge des animations figée : les LED et l'alerte clignotent sans fin, l'écran ne serait jamais « au repos ».
        rule.mainClock.autoAdvance = false
        rule.setContent {
            PitwallTheme {
                GtDashboard(
                    d, b, "Sport A · Deep Forest Raceway · Porsche 911 RSR", "en veille · dis « radio »", true,
                    EngineerMessage("Tour 4 : 1 minute 31 secondes 3. Meilleur tour, 27 centièmes de mieux.", Priority.NORMAL, MessageCategory.RADIO),
                    onOpen = {}, onMenu = if (menu) ({}) else null,
                )
            }
        }
        rule.mainClock.advanceTimeBy(600)
        // Dessin logiciel de la fenêtre (captureToImage attend une copie matérielle des pixels, absente sous Robolectric).
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(Canvas(bmp)) }
        val dir = File("build/screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test @Config(qualifiers = "w690dp-h760dp-port-xxhdpi")
    fun foldUnfoldedPortrait() = shoot("1-fold-deplie-portrait", dash(), board())

    @Test @Config(qualifiers = "w760dp-h690dp-land-xxhdpi")
    fun foldUnfoldedLandscape() = shoot("2-fold-deplie-paysage", dash(shift = true), board())

    @Test @Config(qualifiers = "w384dp-h780dp-port-xxhdpi")
    fun coverScreen() = shoot("3-ecran-exterieur", dash(), board(), menu = false)

    @Test @Config(qualifiers = "w690dp-h760dp-port-xxhdpi")
    fun criticalAlerts() = shoot(
        "4-alertes-critiques", dash(lit = 4),
        board(
            listOf(
                RaceCommandBoard.Alert(RaceCommandBoard.AlertKind.FUEL_CRITICAL, RaceCommandBoard.Tone.CRITICAL, "CARBURANT CRITIQUE", "Autonomie 0,8 tour(s), 3,0 à couvrir. Rentre au stand."),
                RaceCommandBoard.Alert(RaceCommandBoard.AlertKind.BOX_THIS_LAP, RaceCommandBoard.Tone.CRITICAL, "BOX CE TOUR", "Ravitailler · Monter les course durs"),
            ),
        ),
    )
}
