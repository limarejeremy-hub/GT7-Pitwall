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
import com.gt7pitwall.core.display.StrategyBoard
import com.gt7pitwall.core.strategy.StrategyAssurance
import com.gt7pitwall.core.strategy.StrategyEngine
import com.gt7pitwall.core.strategy.StrategySimulations
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * V515-C : captures de CONTRÔLE du Strategy Board, construites à partir des scénarios SIMULÉS du moteur (pas une course
 * réelle) : mise en page, lisibilité, absence de chevauchement. Le rendu sur le Fold reste à confirmer.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class StrategyBoardScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun board(id: String): StrategyBoard {
        val c = StrategySimulations.cases.first { it.id == id }
        val r = StrategyEngine.analyze(c.input)
        return StrategyBoard.of(r, StrategyAssurance().review(StrategySimulations.state(c), r))
    }

    private fun shoot(name: String, full: Boolean, b: StrategyBoard) {
        rule.setContent {
            PitwallTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    if (full) StrategyBoardCard(b) else StrategyBoardCompact(b)
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
    fun compactCoverScreen() = shoot("10-strategie-compact-ecran-exterieur", false, board("undercut-rentable"))

    @Test @Config(qualifiers = "w690dp-h2200dp-port-xxhdpi")
    fun fullUnfolded() = shoot("11-strategy-board-deplie", true, board("economie-evite-arret"))

    @Test @Config(qualifiers = "w384dp-h2600dp-port-xxhdpi")
    fun fullCoverScreen() = shoot("12-strategy-board-ecran-exterieur", true, board("plein-insuffisant"))

    @Test @Config(qualifiers = "w690dp-h1400dp-port-xxhdpi")
    fun blocked() = shoot("13-strategy-board-bloque", true, board("reglement-impossible"))
}
