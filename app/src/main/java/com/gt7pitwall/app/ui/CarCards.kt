package com.gt7pitwall.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gt7pitwall.app.ui.theme.PitColors
import com.gt7pitwall.core.analysis.GForce
import com.gt7pitwall.core.analysis.TrackOutline
import com.gt7pitwall.core.telemetry.TelemetryPacket
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
private fun SmallCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = PitColors.Muted, letterSpacing = 1.5.sp)
            content()
        }
    }
}

/** Échelle de couleur purement visuelle pour les températures de surface. */
private fun tyreColor(t: Float): Color = when {
    t < 50f -> Color(0xFF2979FF)
    t < 70f -> PitColors.Cyan
    t < 95f -> PitColors.Green
    t < 110f -> PitColors.Yellow
    else -> PitColors.Red
}

private fun fmt(v: Float, digits: Int = 1) = String.format(Locale.FRANCE, "%.${digits}f", v)

/** Tout ce que GT7 transmet sur la voiture, hors chronos et carburant. */
@Composable
fun CarCard(p: TelemetryPacket?, g: GForce?) {
    SmallCard("Voiture") {
        if (p == null) {
            Text("En attente de télémétrie", color = PitColors.Muted)
            return@SmallCard
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TyreGrid(p, Modifier.weight(1f))
            GMeter(g, Modifier.weight(1f))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Indicator("TCS", p.isTcsActive, PitColors.Yellow, Modifier.weight(1f))
            Indicator("ASM", p.isAsmActive, PitColors.Yellow, Modifier.weight(1f))
            Indicator("LIMIT.", p.isRevLimiterAlert, PitColors.Red, Modifier.weight(1f))
            Indicator("F. MAIN", p.isHandbrake, PitColors.Red, Modifier.weight(1f))
            Indicator("PHARES", p.areLightsOn, PitColors.Cyan, Modifier.weight(1f))
        }

        p.steeringRad?.let { rad ->
            val deg = Math.toDegrees(rad.toDouble()).toFloat()
            Text("Volant ${deg.roundToInt()}°", style = MaterialTheme.typography.labelSmall, color = PitColors.Muted)
            // Barre centrée : la moitié gauche/droite représente ±180°.
            Box(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                LinearProgressIndicator(
                    progress = { (0.5f + deg / 360f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = PitColors.Cyan,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }

        // Format C : valeurs brutes de GT7, affichées seulement (aucune annonce radio avant validation sur PS5).
        p.currentLapTimeMs?.let { ms ->
            val shown = if (ms > 0) com.gt7pitwall.core.engineer.FrenchFormat.lapTime(ms) else "$ms (valeur brute)"
            Text("Chrono du tour (format C) $shown", fontFamily = FontFamily.Monospace)
        }
        p.surfaces?.let { s ->
            Text(
                "Surfaces AVG ${s.fl.label} • AVD ${s.fr.label} • ARG ${s.rl.label} • ARD ${s.rr.label}",
                color = if (s.wheelsOff > 0) PitColors.Yellow else PitColors.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        p.turboBar?.let { bar ->
            Text("Turbo ${if (bar >= 0) "+" else ""}${fmt(bar, 2)} bar", fontFamily = FontFamily.Monospace)
        }

        val details = buildList {
            add("Pression d'huile ${fmt(p.oilPressure)} bar")
            if (p.calculatedMaxSpeed > 0) add("Vmax boîte ${p.calculatedMaxSpeed} km/h")
            p.energyRecovery?.takeIf { p.isElectric }?.let { add("Récupération d'énergie ${fmt(it)}") }
            val tod = p.timeOfDayMs
            if (tod >= 0) add("Heure en piste %02d:%02d".format(tod / 3_600_000, (tod / 60_000) % 60))
            add("Code voiture ${p.carCode}")
            p.carCategory?.let { add("Catégorie $it") }
            p.wheelBaseM?.takeIf { it > 0f }?.let { add("Empattement ${fmt(it, 2)} m") }
        }
        Text(details.joinToString(" • "), color = PitColors.Muted, style = MaterialTheme.typography.bodySmall)

        val ratios = p.gearRatios.filter { it > 0f }
        if (ratios.isNotEmpty()) {
            Text(
                "Rapports : " + ratios.mapIndexed { i, r -> "${i + 1}:${fmt(r, 2)}" }.joinToString("  "),
                color = PitColors.Muted,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun TyreGrid(p: TelemetryPacket, modifier: Modifier) {
    val temps = p.tyreTemps.toList()
    val slips = p.wheelSlip?.toList()
    val labels = listOf("AVG", "AVD", "ARG", "ARD")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Pneus (surface)", style = MaterialTheme.typography.labelSmall, color = PitColors.Muted)
        for (row in 0..1) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (col in 0..1) {
                    val i = row * 2 + col
                    val t = temps[i]
                    val slip = slips?.get(i)
                    // Roue qui bloque au freinage / patine à l'accélération (comparaison roue / voiture).
                    val slipText = when {
                        slip == null -> ""
                        slip < 0.85f && p.brake > 20 -> "bloque"
                        slip > 1.15f && p.throttle > 20 -> "patine"
                        else -> ""
                    }
                    val c = tyreColor(t)
                    Column(
                        Modifier
                            .weight(1f)
                            .background(c.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
                            .padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(labels[i], style = MaterialTheme.typography.labelSmall, color = PitColors.Muted)
                        Text("${t.roundToInt()}°", color = c, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Text(
                            slipText.ifEmpty { " " },
                            color = if (slipText == "bloque") PitColors.Red else PitColors.Yellow,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GMeter(g: GForce?, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Forces G", style = MaterialTheme.typography.labelSmall, color = PitColors.Muted)
        val ring = MaterialTheme.colorScheme.surfaceVariant
        Canvas(Modifier.size(110.dp)) {
            val r = size.minDimension / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(ring, r, c, style = Stroke(2.dp.toPx()))
            drawCircle(ring, r / 2f, c, style = Stroke(1.dp.toPx()))
            drawLine(ring, Offset(c.x - r, c.y), Offset(c.x + r, c.y))
            drawLine(ring, Offset(c.x, c.y - r), Offset(c.x, c.y + r))
            if (g != null) {
                // Cercle extérieur = 2 g. Accélération vers le haut, freinage vers le bas.
                val x = (g.lateral / 2f).coerceIn(-1f, 1f) * r
                val y = (-g.longitudinal / 2f).coerceIn(-1f, 1f) * r
                drawCircle(PitColors.Orange, 6.dp.toPx(), Offset(c.x + x, c.y + y))
            }
        }
        if (g != null) {
            Text(
                "lat ${fmt(abs(g.lateral))} • long ${fmt(g.longitudinal)}",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Indicator(label: String, active: Boolean, color: Color, modifier: Modifier) {
    Text(
        label,
        modifier = modifier
            .background(if (active) color.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
            .padding(vertical = 4.dp),
        color = if (active) color else PitColors.Muted,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        style = MaterialTheme.typography.labelSmall,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

/** Carte du circuit dessinée avec les positions réelles de la voiture pendant le meilleur tour complet. */
@Composable
fun TrackMapCard(
    outline: TrackOutline?,
    p: TelemetryPacket?,
    /** Virages (libellé, X, Z) : « T5 » numérotation du circuit, « V3 » virage détecté au numéro estimé. */
    corners: List<Triple<String, Float, Float>> = emptyList(),
) {
    SmallCard("Circuit") {
        if (outline == null || outline.xs.size < 2) {
            Text(
                "La carte se dessine après un tour complet suivi depuis la ligne (positions transmises par GT7).",
                color = PitColors.Muted,
                style = MaterialTheme.typography.bodyMedium,
            )
            return@SmallCard
        }
        val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        Canvas(Modifier.fillMaxWidth().aspectRatio(1.3f)) {
            val minX = outline.xs.min()
            val maxX = outline.xs.max()
            val minZ = outline.zs.min()
            val maxZ = outline.zs.max()
            val pad = 14.dp.toPx()
            val spanX = (maxX - minX).coerceAtLeast(1f)
            val spanZ = (maxZ - minZ).coerceAtLeast(1f)
            val scale = min((size.width - 2 * pad) / spanX, (size.height - 2 * pad) / spanZ)
            val offX = (size.width - spanX * scale) / 2f
            val offY = (size.height - spanZ * scale) / 2f
            fun pt(x: Float, z: Float) = Offset(offX + (x - minX) * scale, offY + (z - minZ) * scale)

            val path = Path()
            outline.xs.indices.forEach { i ->
                val o = pt(outline.xs[i], outline.zs[i])
                if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
            }
            drawPath(path, track, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            // Ligne de départ et débuts des secteurs Pitwall.
            drawCircle(Color.White, 5.dp.toPx(), pt(outline.xs[0], outline.zs[0]))
            outline.sectorStarts.forEach { i -> drawCircle(PitColors.Purple, 5.dp.toPx(), pt(outline.xs[i], outline.zs[i])) }
            // Numéros des virages à leur apex (issue #30-C).
            if (corners.isNotEmpty()) {
                val paint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    textSize = 11.dp.toPx()
                    color = android.graphics.Color.WHITE
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                corners.forEach { (label, x, z) ->
                    val o = pt(x, z)
                    drawCircle(PitColors.Cyan, 3.dp.toPx(), o)
                    paint.color = if (label.startsWith("T")) android.graphics.Color.WHITE else android.graphics.Color.LTGRAY
                    drawContext.canvas.nativeCanvas.drawText(label, o.x + 5.dp.toPx(), o.y - 4.dp.toPx(), paint)
                }
            }
            if (p != null) drawCircle(PitColors.Orange, 7.dp.toPx(), pt(p.positionX, p.positionZ))
        }
        Text(
            "Tracé du tour ${outline.lapNumber} • blanc : ligne • violet : secteurs • orange : toi" +
                if (corners.any { it.first.startsWith("V") }) " • V : virage détecté, numéro estimé" else "",
            color = PitColors.Muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
