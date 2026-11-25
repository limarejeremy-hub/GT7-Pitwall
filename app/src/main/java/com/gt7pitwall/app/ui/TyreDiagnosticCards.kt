package com.gt7pitwall.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gt7pitwall.app.ui.theme.PitColors
import com.gt7pitwall.core.display.TyreBoard

/**
 * V514-A — carte PNEUS : diagnostic des quatre pneus d'après ce que GT7 transmet (températures, glissement des roues).
 * Couleurs NEUTRES : aucune teinte ne suggère une usure (non transmise par GT7). Seul un pneu porteur d'une anomalie
 * MESURÉE (température, patinage, blocages) reçoit un contour ambre ; chaque message affiche sa nature.
 */
private object DColors {
    val Card = Color(0xFF12161C)
    val Cell = Color(0xFF1B2129)
    val Text = Color(0xFFE8ECF2)
    val Soft = Color(0xFFB4BDCB)
    val Flag = Color(0xFFFFB300)
}

private val Mono = FontFamily.Monospace

@Composable
private fun DCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(DColors.Card, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, color = DColors.Soft, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        content()
    }
}

@Composable
private fun NatureTag(n: TyreBoard.Nature) {
    Text(
        n.label.uppercase(), color = DColors.Soft, fontSize = 9.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.border(1.dp, DColors.Soft.copy(alpha = 0.5f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

@Composable
private fun Message(l: TyreBoard.Line, maxLines: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
        NatureTag(l.nature)
        Text(
            l.text, color = if (l.alert) DColors.Flag else DColors.Text, fontSize = 12.sp,
            maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun WheelCell(c: TyreBoard.Cell, modifier: Modifier) {
    Column(
        modifier
            .background(DColors.Cell, RoundedCornerShape(12.dp))
            .then(if (c.flagged) Modifier.border(2.dp, DColors.Flag, RoundedCornerShape(12.dp)) else Modifier)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.label, color = DColors.Soft, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("  " + c.trend.symbol + (c.sinceRefC?.let { " ${if (it >= 0) "+" else "−"}${kotlin.math.abs(it)}" } ?: ""),
                color = if (c.flagged) DColors.Flag else DColors.Soft, fontSize = 11.sp, fontFamily = Mono, maxLines = 1)
        }
        Text(c.nowC?.let { "$it°" } ?: "—", color = DColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, maxLines = 1)
        Text("moy ${c.lapMeanC ?: "—"} · max ${c.lapMaxC ?: "—"}", color = DColors.Soft, fontSize = 11.sp, fontFamily = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("glis. ${c.slip ?: "—"}", color = DColors.Soft, fontSize = 11.sp, fontFamily = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("pat./bloc. ${c.spinsLastLap ?: "—"}/${c.locksLastLap ?: "—"}", color = DColors.Soft, fontSize = 11.sp, fontFamily = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun WheelGrid(b: TyreBoard) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in 0..1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (col in 0..1) b.cells.getOrNull(row * 2 + col)?.let { WheelCell(it, Modifier.weight(1f)) }
            }
        }
    }
}

/** Vue compacte en course : quatre pneus, trois messages au plus, rappel « pas d'usure ». */
@Composable
fun TyreDiagnosticCompact(b: TyreBoard) {
    DCard("PNEUS · DIAGNOSTIC") {
        Text(b.headline, color = DColors.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        WheelGrid(b)
        Text("▲▼ : écart à la référence du relais (°C) · glis. : glissement des roues motrices (patinage, pas une usure) · pat./bloc. : patinages forts / blocages du dernier tour",
            color = PitColors.Muted, fontSize = 11.sp)
        b.messages.take(TyreBoard.MAX_MESSAGES).forEach { Message(it, maxLines = 3) }
        Text("${TyreBoard.NO_WEAR} · confiance ${b.confidence}", color = PitColors.Muted, fontSize = 11.sp)
    }
}

/** Vue détaillée (Analyse) : train de pneus, chronos, comportement, chiffres par roue, historique, confiance. */
@Composable
fun TyreDiagnosticDetail(b: TyreBoard) {
    DCard("PNEUS · ANALYSE DU RELAIS") {
        Text(b.headline, color = DColors.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        WheelGrid(b)
        b.messages.forEach { Message(it, maxLines = 3) }
        b.details.forEach { Message(it, maxLines = 4) }
    }
}
