package com.gt7pitwall.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Identité V506 « cockpit d'ingénieur » : noir carbone et anthracite, UN accent de marque (orange Pitwall) et des
 * couleurs d'ÉTAT réservées à leur sens — vert = bon, jaune = attention, rouge = critique, violet = meilleur temps,
 * cyan = Véga. Une couleur n'est jamais décorative.
 */
object PitColors {
    val Orange = Color(0xFFFF7A1A)
    val Cyan = Color(0xFF4FD8FF)
    val Green = Color(0xFF2EE59D)
    val Yellow = Color(0xFFFFC533)
    val Red = Color(0xFFFF3B4E)
    val Purple = Color(0xFFB56CFF)
    val Muted = Color(0xFF8B93A1)
}

/** Surfaces et traits (du plus sombre au plus clair). */
object PitSurface {
    val Carbon = Color(0xFF08090B)
    val Rail = Color(0xFF0D0E11)
    val Panel = Color(0xFF14161A)
    val PanelHi = Color(0xFF1B1E23)
    val Track = Color(0xFF262A31)
    val Line = Color(0xFF2A2E35)
    val Text = Color(0xFFF2F4F7)
    val VegaPanel = Color(0xFF10171B)
    val VegaLine = Color(0xFF1F4654)
}

private val Scheme = darkColorScheme(
    primary = PitColors.Orange,
    onPrimary = Color.Black,
    secondary = PitColors.Cyan,
    onSecondary = Color.Black,
    background = PitSurface.Carbon,
    onBackground = PitSurface.Text,
    surface = PitSurface.Panel,
    onSurface = PitSurface.Text,
    surfaceVariant = PitSurface.PanelHi,
    onSurfaceVariant = Color(0xFFB4BDCB),
    outline = PitSurface.Line,
    outlineVariant = PitSurface.Line,
    error = PitColors.Red,
)

/** Chiffres à chasse fixe (« tnum ») : une valeur qui change ne fait pas bouger sa voisine. */
private const val TABULAR = "tnum"

private val Type = Typography().let { t ->
    fun TextStyle.tab() = copy(fontFamily = FontFamily.SansSerif, fontFeatureSettings = TABULAR)
    t.copy(
        displayLarge = t.displayLarge.tab().copy(fontWeight = FontWeight.Black),
        headlineMedium = t.headlineMedium.tab().copy(fontWeight = FontWeight.ExtraBold),
        titleLarge = t.titleLarge.tab().copy(fontWeight = FontWeight.Bold),
        titleMedium = t.titleMedium.tab().copy(fontWeight = FontWeight.Bold),
        bodyLarge = t.bodyLarge.tab(),
        bodyMedium = t.bodyMedium.tab(),
        bodySmall = t.bodySmall.tab(),
        labelLarge = t.labelLarge.tab().copy(fontWeight = FontWeight.Bold),
        labelMedium = t.labelMedium.tab().copy(fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp),
        labelSmall = t.labelSmall.tab().copy(fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp),
    )
}

/** Thème sombre unique : lisible en roulant, économe sur l'écran AMOLED du Fold. */
@Composable
fun PitwallTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Type, content = content)
}
