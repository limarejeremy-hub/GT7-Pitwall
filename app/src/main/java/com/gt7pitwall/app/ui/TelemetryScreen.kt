package com.gt7pitwall.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gt7pitwall.app.ui.theme.PitColors
import com.gt7pitwall.core.display.TelemetryBoard
import com.gt7pitwall.core.display.TelemetryBoard.SectorColor
import com.gt7pitwall.core.display.TelemetryBoard.TempBand
import com.gt7pitwall.core.strategy.TyreCompound
import java.util.Locale

/**
 * Écran 2 « Télémétrie » (maquette validée par le pilote le 09/11) : chrono, secteurs colorés (violet meilleur, vert en
 * progrès, jaune plus lent), 4 pneus colorés selon la température mesurée, gomme et son anneau de couleur, tendance de
 * chrono du relais (estimation : GT7 ne transmet pas l'usure), carburant, légende des gommes et frise des relais.
 * Deux colonnes sur le Fold déplié, une sur l'écran extérieur. Toutes les valeurs absentes s'affichent « — ».
 */
private object TColors {
    val Card = Color(0xFF12161C)
    val Cell = Color(0xFF1B2129)
    val Text = Color(0xFFE8ECF2)
    val Soft = Color(0xFFB4BDCB)
    val Best = Color(0xFFB24CF0)
    val BestText = Color(0xFFE3B6FF)
    val Improved = Color(0xFF00C853)
    val Slower = Color(0xFFFFD600)
    val Cold = Color(0xFF4FC3F7)
    val Ideal = Color(0xFF00C853)
    val Warm = Color(0xFFFF9100)
    val Hot = Color(0xFFFF1744)
    val Future = Color(0xFF2A323D)
}

private val Mono = FontFamily.Monospace

/** Couleur d'une gomme : tendres rouge, mediums jaune, durs blanc, intermédiaires vert, pluie bleu. */
fun compoundColor(c: TyreCompound?): Color = when {
    c == null -> PitColors.Muted
    c == TyreCompound.IM -> Color(0xFF00C853)
    c == TyreCompound.W -> Color(0xFF2979FF)
    c.hardness == TyreCompound.Hardness.SOFT -> Color(0xFFFF1744)
    c.hardness == TyreCompound.Hardness.MEDIUM -> Color(0xFFFFD600)
    c.hardness == TyreCompound.Hardness.HARD -> Color(0xFFF2F4F7)
    else -> PitColors.Muted
}

private fun compoundLetter(c: TyreCompound?): String = when {
    c == null -> "?"
    c == TyreCompound.IM -> "I"
    c == TyreCompound.W -> "W"
    c.hardness == TyreCompound.Hardness.SOFT -> "S"
    c.hardness == TyreCompound.Hardness.MEDIUM -> "M"
    c.hardness == TyreCompound.Hardness.HARD -> "H"
    else -> "?"
}

private fun bandColor(b: TempBand): Color = when (b) {
    TempBand.COLD -> TColors.Cold
    TempBand.IDEAL -> TColors.Ideal
    TempBand.WARM -> TColors.Warm
    TempBand.HOT -> TColors.Hot
    TempBand.UNKNOWN -> TColors.Cell
}

private fun num(v: Float?, digits: Int = 1): String = v?.let { String.format(Locale.FRANCE, "%.${digits}f", it) } ?: "—"

@Composable
fun TelemetryScreen(board: TelemetryBoard?, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (board == null) {
                Text("Télémétrie : en attente des premières données GT7.", color = PitColors.Muted)
                return@Column
            }
            Header(board)
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(5f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LapCard(board)
                        TyreCard(board)
                    }
                    Column(Modifier.weight(7f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectorCard(board)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.weight(1f)) { FuelCard(board) }
                            Box(Modifier.weight(1f)) { CompoundLegend() }
                        }
                    }
                }
            } else {
                LapCard(board)
                SectorCard(board)
                TyreCard(board)
                FuelCard(board)
                CompoundLegend()
            }
            StintCard(board)
        }
    }
}

@Composable
private fun Card(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(TColors.Card, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, color = PitColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        content()
    }
}

@Composable
private fun Header(b: TelemetryBoard) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Stat("TOUR", b.lap?.toString() ?: "—", b.totalLaps?.let { "/$it" })
        Stat("POSITION", b.position?.let { "P$it" } ?: "—", b.cars?.let { "/$it" })
    }
}

@Composable
private fun Stat(label: String, value: String, suffix: String?) {
    Column {
        Text(label, color = PitColors.Muted, fontSize = 11.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = TColors.Text, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 26.sp)
            suffix?.let { Text(it, color = PitColors.Muted, fontFamily = Mono, fontSize = 18.sp) }
        }
    }
}

@Composable
private fun LapCard(b: TelemetryBoard) {
    Card("TOUR EN COURS") {
        Text(TelemetryBoard.lapTime(b.currentLapMs), color = TColors.Text, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 50.sp)
        if (b.currentLapMs == null) Text("Chrono du tour en cours non transmis par cette version de GT7.", color = PitColors.Muted, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TimeBox("DERNIER TOUR", TelemetryBoard.lapTime(b.lastLapMs), TColors.Text, null, Modifier.weight(1f))
            TimeBox(
                "MEILLEUR TOUR" + (b.bestLapNumber?.let { " · T$it" } ?: ""), TelemetryBoard.lapTime(b.bestLapMs),
                TColors.BestText, TColors.Best, Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TimeBox(label: String, value: String, color: Color, ring: Color?, modifier: Modifier) {
    val base = modifier.background(TColors.Cell, RoundedCornerShape(12.dp))
    Column((if (ring != null) base.border(2.dp, ring, RoundedCornerShape(12.dp)) else base).padding(10.dp)) {
        Text(label, color = if (ring != null) TColors.BestText else TColors.Soft, fontSize = 11.sp)
        Text(value, color = color, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 20.sp)
    }
}

@Composable
private fun SectorCard(b: TelemetryBoard) {
    Card("SECTEURS") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Legend(TColors.Best, "meilleur")
            Legend(TColors.Improved, "en progrès")
            Legend(TColors.Slower, "plus lent")
        }
        if (b.laps.isEmpty()) {
            Text("Aucun tour complet mesuré pour l'instant.", color = PitColors.Muted, fontSize = 13.sp)
            return@Card
        }
        val n = b.laps.maxOf { it.sectors.size }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("TOUR", color = PitColors.Muted, fontSize = 11.sp, modifier = Modifier.width(44.dp))
            for (i in 1..n) Text("S$i", color = PitColors.Muted, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            Text("TEMPS", color = PitColors.Muted, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.width(96.dp))
        }
        for (row in b.laps) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("T${row.lap}", color = TColors.Soft, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.width(44.dp))
                for (i in 0 until n) {
                    val cell = row.sectors.getOrNull(i)
                    val (bg, fg) = when (cell?.color) {
                        SectorColor.BEST -> TColors.Best to Color.White
                        SectorColor.IMPROVED -> TColors.Improved to Color(0xFF04140A)
                        SectorColor.SLOWER -> TColors.Slower to Color(0xFF1A1500)
                        else -> TColors.Cell to TColors.Soft
                    }
                    Box(Modifier.weight(1f).height(32.dp).background(bg, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                        Text(TelemetryBoard.lapTime(cell?.ms), color = fg, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
                Text(
                    TelemetryBoard.lapTime(row.totalMs), color = if (row.bestLap) TColors.BestText else TColors.Text,
                    fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp, textAlign = TextAlign.End, modifier = Modifier.width(96.dp),
                )
            }
        }
        Text("Secteurs calculés par Pitwall à partir de la position GT7 (pas les secteurs officiels du jeu).", color = PitColors.Muted, fontSize = 11.sp)
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).background(color, RoundedCornerShape(3.dp)))
        Text(label, color = TColors.Soft, fontSize = 11.sp)
    }
}

@Composable
private fun CompoundRing(c: TyreCompound?, size: Dp = 26.dp) {
    Box(Modifier.size(size).border(4.dp, compoundColor(c), CircleShape), contentAlignment = Alignment.Center) {
        Text(compoundLetter(c), color = TColors.Text, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun TyreCard(b: TelemetryBoard) {
    Card("PNEUS") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompoundRing(b.compound)
            Text(b.compound?.spoken?.replaceFirstChar { it.uppercase() } ?: "Gomme inconnue", color = TColors.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            if (!b.compoundCertain && b.compound != null) Text("(non confirmée)", color = PitColors.Muted, fontSize = 12.sp)
            b.tyreAgeLaps?.let { Text("· $it tour${if (it > 1) "s" else ""}", color = TColors.Soft, fontSize = 12.sp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) { Tyre(b.tyres.getOrNull(0)); Tyre(b.tyres.getOrNull(1)) }
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) { Tyre(b.tyres.getOrNull(2)); Tyre(b.tyres.getOrNull(3)) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("TEMPÉRATURE (mesurée par GT7)", color = TColors.Soft, fontSize = 11.sp)
                Box(
                    Modifier.fillMaxWidth().height(10.dp).background(
                        Brush.horizontalGradient(listOf(TColors.Cold, TColors.Ideal, TColors.Ideal, TColors.Warm, TColors.Hot)),
                        RoundedCornerShape(50),
                    ),
                )
                Row { Text("froid", color = PitColors.Muted, fontSize = 10.sp); Spacer(Modifier.weight(1f)); Text("idéal", color = PitColors.Muted, fontSize = 10.sp); Spacer(Modifier.weight(1f)); Text("chaud", color = PitColors.Muted, fontSize = 10.sp) }
                Spacer(Modifier.height(4.dp))
                Text("TENDANCE DU RELAIS (estimation)", color = TColors.Soft, fontSize = 11.sp)
                val trend = b.paceTrendSPerLap
                Text(
                    trend?.let { (if (it >= 0) "+" else "−") + String.format(Locale.FRANCE, "%.2f", kotlin.math.abs(it)) + " s/tour" } ?: "— (pas assez de tours propres)",
                    color = when {
                        trend == null -> PitColors.Muted
                        trend <= 0.05 -> TColors.Ideal
                        trend <= 0.3 -> TColors.Slower
                        else -> TColors.Hot
                    },
                    fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                )
                Text("GT7 ne transmet pas l'usure : tendance de tes chronos sur ce relais, pas une mesure d'usure.", color = PitColors.Muted, fontSize = 10.sp)
            }
        }
        Text("Repères de température généraux, pas une fenêtre propre à chaque gomme.", color = PitColors.Muted, fontSize = 10.sp)
    }
}

@Composable
private fun Tyre(t: TelemetryBoard.TyreCell?) {
    val band = t?.band ?: TempBand.UNKNOWN
    val dark = band != TempBand.UNKNOWN
    Column(
        Modifier.width(64.dp).height(84.dp).background(bandColor(band), RoundedCornerShape(14.dp)),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(t?.label ?: "—", color = if (dark) Color(0xFF0A0C10) else TColors.Soft, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(t?.tempC?.let { "${it.toInt()}°" } ?: "—", color = if (dark) Color(0xFF0A0C10) else TColors.Soft, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }
}

@Composable
private fun FuelCard(b: TelemetryBoard) {
    Card("CARBURANT") {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(num(b.fuelPct, 0), color = TColors.Text, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 36.sp)
            Text("%", color = PitColors.Muted, fontSize = 18.sp)
        }
        val frac = ((b.fuelPct ?: 0f) / 100f).coerceIn(0f, 1f)
        Box(Modifier.fillMaxWidth().height(12.dp).background(TColors.Cell, RoundedCornerShape(50))) {
            Box(Modifier.fillMaxWidth(frac).height(12.dp).background(PitColors.Cyan, RoundedCornerShape(50)))
        }
        KeyValue("Conso.", b.fuelPerLapPct?.let { "${num(it)} %/tour" } ?: "—")
        KeyValue("Autonomie", b.fuelLapsLeft?.let { "${num(it)} tours" } ?: "—")
    }
}

@Composable
private fun KeyValue(k: String, v: String) {
    Row {
        Text(k, color = TColors.Soft, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Text(v, color = TColors.Text, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun CompoundLegend() {
    Card("GOMMES") {
        listOf(TyreCompound.RS to "Tendres · rouge", TyreCompound.RM to "Mediums · jaune", TyreCompound.RH to "Durs · blanc",
            TyreCompound.IM to "Intermédiaires · vert", TyreCompound.W to "Pluie · bleu").forEach { (c, label) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CompoundRing(c, 24.dp)
                Text(label, color = TColors.Text, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun StintCard(b: TelemetryBoard) {
    Card("RELAIS") {
        if (b.requiredTyres.isNotEmpty()) {
            Text("Gommes obligatoires : " + b.requiredTyres.joinToString(", ") { it.name }, color = TColors.Soft, fontSize = 12.sp)
        }
        if (b.stints.isEmpty()) {
            Text("Nombre de tours inconnu : frise indisponible.", color = PitColors.Muted, fontSize = 12.sp)
            return@Card
        }
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (s in b.stints) {
                val bg = if (s.future) TColors.Future else compoundColor(s.compound)
                val fg = if (s.future || s.compound == null) TColors.Soft else Color(0xFF0A0C10)
                val base = Modifier.weight(1f).height(28.dp).background(bg, RoundedCornerShape(6.dp))
                Box(
                    if (s.current) base.border(2.dp, PitColors.Orange, RoundedCornerShape(6.dp)) else base,
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (s.pit) "▼" else "${s.lap}", color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text("▼ = arrêt enregistré · cadre orange = tour en cours", color = PitColors.Muted, fontSize = 11.sp)
    }
}
