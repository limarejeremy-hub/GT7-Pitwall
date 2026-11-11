package com.gt7pitwall.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gt7pitwall.app.ui.theme.PitColors
import com.gt7pitwall.app.ui.theme.PitSurface

/** Les cinq espaces de Pitwall (refonte V506). L'ordre est celui de la navigation. */
enum class PitDest(val label: String, val description: String) {
    RACE("RACE", "Course : écran principal"),
    STRATEGY("STRATEGY", "Stratégie : carburant, pneus, arrêts, règlement"),
    TELEMETRY("TELEMETRY", "Télémétrie et analyse du pilotage"),
    VEGA("VEGA", "Véga : ingénieure et radio"),
    GARAGE("GARAGE", "Garage : connexion, réglages, sondes et journaux"),
}

/** Largeur à partir de laquelle l'écran est « large » : Fold déplié (rail latéral), sinon écran extérieur (barre basse). */
val WIDE_FROM: Dp = 600.dp

/**
 * Cadre de l'application : plein écran, contenu toujours dans la zone sûre (barres système, découpe de l'écran), rail
 * latéral sur le Fold déplié, barre basse sur l'écran extérieur. Cibles tactiles d'au moins 56 dp.
 * [raceAlert] : couleur de l'alerte de course en cours (pastille sur RACE quand on regarde un autre espace), ou null.
 */
@Composable
fun PitwallShell(
    selected: PitDest,
    onSelect: (PitDest) -> Unit,
    raceAlert: Color?,
    content: @Composable (wide: Boolean) -> Unit,
) {
    BoxWithConstraints(
        Modifier.fillMaxSize().background(PitSurface.Carbon).windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val wide = maxWidth >= WIDE_FROM
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier.width(84.dp).fillMaxHeight().background(PitSurface.Rail).padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("PITWALL", color = PitColors.Orange, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                    Spacer(Modifier.height(6.dp))
                    PitDest.entries.forEach { d ->
                        if (d == PitDest.GARAGE) Spacer(Modifier.weight(1f))
                        NavItem(d, d == selected, raceAlert.takeIf { d == PitDest.RACE && selected != PitDest.RACE }, Modifier.width(72.dp).height(68.dp)) { onSelect(d) }
                    }
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(PitSurface.Line))
                Box(Modifier.weight(1f).fillMaxHeight()) { content(true) }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) { content(false) }
                Box(Modifier.fillMaxWidth().height(1.dp).background(PitSurface.Line))
                Row(
                    Modifier.fillMaxWidth().background(PitSurface.Rail).padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    PitDest.entries.forEach { d ->
                        NavItem(d, d == selected, raceAlert.takeIf { d == PitDest.RACE && selected != PitDest.RACE }, Modifier.weight(1f).height(60.dp)) { onSelect(d) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavItem(dest: PitDest, active: Boolean, alert: Color?, modifier: Modifier, onClick: () -> Unit) {
    val color = if (active) PitSurface.Text else PitColors.Muted
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) PitSurface.PanelHi else Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = dest.description },
        contentAlignment = Alignment.Center,
    ) {
        // Repère de l'espace actif : un trait orange, pas un aplat de couleur.
        if (active) Box(Modifier.align(Alignment.TopCenter).width(28.dp).height(3.dp).background(PitColors.Orange, RoundedCornerShape(2.dp)))
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Box {
                PitIcon(dest, color, Modifier.size(24.dp))
                if (alert != null) Box(Modifier.align(Alignment.TopEnd).size(9.dp).background(alert, CircleShape))
            }
            Text(dest.label, color = color, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, maxLines = 1, softWrap = false)
        }
    }
}

/** Pictogrammes au trait, dessinés ici (aucune bibliothèque d'icônes ajoutée). */
@Composable
fun PitIcon(dest: PitDest, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(w * x, h * y)
        // Liste (et non vararg) : Offset est une classe de valeur, interdite en vararg.
        fun path(pts: List<Offset>) = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            pts.drop(1).forEach { lineTo(it.x, it.y) }
        }
        when (dest) {
            // Drapeau.
            PitDest.RACE -> {
                drawPath(path(listOf(p(.22f, .9f), p(.22f, .14f))), color, style = stroke)
                drawPath(path(listOf(p(.22f, .16f), p(.82f, .16f), p(.68f, .36f), p(.82f, .56f), p(.22f, .56f))), color, style = stroke)
            }
            // Frise des relais : trois segments et un arrêt.
            PitDest.STRATEGY -> {
                drawPath(path(listOf(p(.1f, .3f), p(.42f, .3f))), color, style = stroke)
                drawPath(path(listOf(p(.58f, .3f), p(.9f, .3f))), color, style = stroke)
                drawCircle(color, w * .07f, p(.5f, .3f))
                drawPath(path(listOf(p(.1f, .7f), p(.62f, .7f))), color, style = stroke)
                drawPath(path(listOf(p(.78f, .7f), p(.9f, .7f))), color, style = stroke)
                drawCircle(color, w * .07f, p(.7f, .7f))
            }
            // Courbe de télémétrie.
            PitDest.TELEMETRY -> drawPath(path(listOf(p(.08f, .74f), p(.3f, .46f), p(.48f, .6f), p(.66f, .22f), p(.92f, .56f))), color, style = stroke)
            // Onde radio autour d'un point.
            PitDest.VEGA -> {
                drawCircle(color, w * .1f, p(.5f, .56f))
                drawArc(color, 200f, 140f, false, p(.24f, .3f), androidx.compose.ui.geometry.Size(w * .52f, h * .52f), style = stroke)
                drawArc(color, 205f, 130f, false, p(.08f, .14f), androidx.compose.ui.geometry.Size(w * .84f, h * .84f), style = stroke)
            }
            // Clé plate.
            PitDest.GARAGE -> {
                drawPath(path(listOf(p(.2f, .8f), p(.62f, .38f))), color, style = Stroke(width = w * 0.13f, cap = StrokeCap.Round))
                drawArc(color, -20f, 270f, false, p(.52f, .1f), androidx.compose.ui.geometry.Size(w * .36f, h * .36f), style = stroke)
            }
        }
    }
}
