package com.gt7pitwall.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.gt7pitwall.app.ui.theme.PitwallTheme
import com.gt7pitwall.core.display.TyreBoard
import com.gt7pitwall.core.strategy.TyreCompound
import com.gt7pitwall.core.tyres.GripLevel
import com.gt7pitwall.core.tyres.TyreDiagnostics
import com.gt7pitwall.core.tyres.TyreDiagnostics.Confidence
import com.gt7pitwall.core.tyres.TyreDiagnostics.Issue
import com.gt7pitwall.core.tyres.TyreDiagnostics.IssueKind
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * V514-A : captures de CONTRÔLE de la carte PNEUS (données de démonstration, pas une course réelle) : mise en page,
 * lisibilité, absence de chevauchement, sur l'écran extérieur et déplié. Le rendu sur le Fold reste à confirmer.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class TyreDiagnosticScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun wheel(label: String, now: Float, mean: Float, max: Float, since: Float?, axle: Float, slip: Float?, driven: Boolean, spins: Int, locks: Int) =
        TyreDiagnostics.Wheel(label, now, mean, max, since, axle, slip, driven, spins * 8, spins, locks * 6, locks, Confidence.MEDIUM, spins / 3, locks / 3)

    private fun report(issues: List<Issue>, recovering: Boolean = false) = TyreDiagnostics.Report(
        listOf(
            wheel("AVG", 94f, 92.6f, 94.9f, 10.2f, 4.8f, null, false, 0, 45),
            wheel("AVD", 88f, 87.9f, 90.6f, 9.7f, -4.8f, null, false, 0, 59),
            wheel("ARG", 66f, 65.9f, 67.9f, 2.4f, 8.4f, 1.5f, true, 24, 0),
            wheel("ARD", 57f, 57.4f, 59f, 0.6f, -8.4f, 1.5f, true, 22, 0),
        ),
        stintStartLap = 1, measuredLaps = 6, referenceLap = 2, recovering = recovering, surfacesKnown = false,
        drivenAxle = TyreDiagnostics.DrivenAxle.REAR, issues = issues, gaps = 0, confidence = Confidence.MEDIUM,
        confidenceWhy = listOf("surface sous les pneus non transmise (format A, B ou ~)"),
        history = listOf(TyreDiagnostics.StintSummary(1, 7, 6, listOf(84f, 82f, 70f, 66f), listOf(0, 0, 9, 8), listOf(30, 33, 0, 0))),
    )

    private val train = TyreDiagnostics.Train(
        ageLaps = 6, ageCertain = true, compound = TyreCompound.RM, compoundDeclared = true, grip = GripLevel.POSSIBLE,
        paceSlopeMsPerLap = 180.0, paceLossMs = 900, lossDemonstrated = false, autonomy = "inconnue : pas assez de relais comparables",
        behaviour = "rotation stable dans la plupart des virages",
    )

    private val issues = listOf(
        Issue(IssueKind.TEMPERATURE, 0, "Essieu avant : écart gauche / droite de 14 °C, 9 de plus qu'en début de relais", "MESURE"),
        Issue(IssueKind.WHEELSPIN, 2, "Arrière gauche : 5 patinages forts au dernier tour, contre 0,5 en début de relais", "CALCUL"),
        Issue(IssueKind.DEGRADATION_SUSPECTED, null, "Signes possibles de baisse de performance, non démontrés", "CALCUL"),
    )

    private fun shoot(name: String, detail: Boolean, b: TyreBoard) {
        rule.setContent {
            PitwallTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    if (detail) TyreDiagnosticDetail(b) else TyreDiagnosticCompact(b)
                }
            }
        }
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(Canvas(bmp)) }
        val dir = File("build/screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test @Config(qualifiers = "w384dp-h780dp-port-xxhdpi")
    fun compactCoverScreen() = shoot("5-pneus-compact-ecran-exterieur", false, TyreBoard.of(report(issues), train))

    @Test @Config(qualifiers = "w690dp-h760dp-port-xxhdpi")
    fun compactUnfolded() = shoot("6-pneus-compact-deplie", false, TyreBoard.of(report(issues), train))

    @Test @Config(qualifiers = "w384dp-h780dp-port-xxhdpi")
    fun pitExitRecovery() = shoot("7-pneus-sortie-stands", false, TyreBoard.of(report(emptyList(), recovering = true), train))

    @Test @Config(qualifiers = "w690dp-h1500dp-port-xxhdpi")
    fun detailUnfolded() = shoot("8-pneus-analyse-deplie", true, TyreBoard.of(report(issues), train))
}
