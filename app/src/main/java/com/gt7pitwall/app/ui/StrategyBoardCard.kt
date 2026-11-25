package com.gt7pitwall.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import com.gt7pitwall.core.display.StrategyBoard
import com.gt7pitwall.core.strategy.StrategyAssurance.Verdict

/**
 * V515-C — Strategy Board : le muret des stands sur le Fold. Plan retenu, prochain arrêt, fenêtre, alternatives avec
 * leur écart et son incertitude, état du contrôleur. Une option sans données est « inconnue », jamais « mauvaise ».
 */
private object SColors {
    val Card = Color(0xFF12161C)
    val Cell = Color(0xFF1B2129)
    val Text = Color(0xFFE8ECF2)
    val Soft = Color(0xFFB4BDCB)
    val Ok = Color(0xFF4CAF50)
    val Warn = Color(0xFFFFB300)
    val Stop = Color(0xFFFF5252)
}

private fun verdictColor(v: Verdict) = when (v) {
    Verdict.APPROVED -> SColors.Ok
    Verdict.PROVISIONAL -> SColors.Warn
    Verdict.BLOCKED -> SColors.Stop
    Verdict.INSUFFICIENT -> SColors.Soft
}

@Composable
private fun SCard(title: String, verdict: Verdict, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(SColors.Card, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = SColors.Soft, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, modifier = Modifier.weight(1f))
            Text(
                verdict.label.uppercase(), color = verdictColor(verdict), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                modifier = Modifier.border(1.dp, verdictColor(verdict), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        content()
    }
}

@Composable
private fun NextStop(b: StrategyBoard) {
    val lap = b.nextStopLap
    Text(
        if (lap == null) "Aucun nouvel arrêt" else "Prochain arrêt : fin du tour $lap",
        color = SColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold,
    )
    if (lap != null) {
        Text(
            "Carburant : ${b.nextStopFuel?.let { if (it > 0) "+$it" else "aucun" } ?: "inconnu"} · Pneus : ${when (b.nextStopTyres) { true -> "changés"; false -> "gardés"; null -> "inconnu" }}",
            color = SColors.Soft, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
        )
    }
}

/** Version compacte (télémétrie en direct) : plan retenu, fenêtre et état du contrôleur. */
@Composable
fun StrategyBoardCompact(b: StrategyBoard) {
    SCard("STRATÉGIE", b.verdict) {
        NextStop(b)
        if (b.changed) Text("Rappels « Box » : ${b.boxLap?.let { "fin du tour $it (plan actuel)" } ?: "aucun arrêt au plan actuel"}", color = SColors.Warn, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        b.window?.let { Text(it, color = SColors.Soft, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        Text(b.statement, color = SColors.Text, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/** Version complète (onglet Stratégie) : alternatives, raisons, contrôles, undercut et overcut. */
@Composable
fun StrategyBoardCard(b: StrategyBoard) {
    SCard("STRATEGY BOARD", b.verdict) {
        NextStop(b)
        Text("Plan retenu : ${b.chosen}", color = SColors.Text, fontSize = 13.sp)
        if (b.changed) Text("Plan actuel du moteur : ${b.current} (les rappels « Box » suivent toujours ce plan)", color = SColors.Warn, fontSize = 12.sp)
        b.window?.let { Text(it, color = SColors.Soft, fontSize = 12.sp) }
        Text(b.statement, color = SColors.Text, fontSize = 12.sp)
        if (b.profitability.isNotEmpty()) Text("Rentabilité : ${b.profitability}", color = if (b.dependencies.isEmpty()) SColors.Soft else SColors.Warn, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        if (b.dependencies.isNotEmpty()) Text("Dépend de : ${b.dependencies.joinToString(" ; ")}", color = SColors.Warn, fontSize = 12.sp)
        if (b.drivers.isNotEmpty()) Text("Ce qui pèse : ${b.drivers.joinToString(" · ")}", color = SColors.Soft, fontSize = 12.sp)
        Text("OPTIONS", color = SColors.Soft, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        b.alternatives.forEach { r ->
            Row(
                Modifier.fillMaxWidth().background(SColors.Cell, RoundedCornerShape(10.dp)).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(22.dp).border(1.dp, SColors.Soft, CircleShape), contentAlignment = Alignment.Center) {
                    Text(r.code.toString(), color = SColors.Text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f)) {
                    Text(r.label, color = SColors.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(r.plan, color = SColors.Soft, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        r.delta, maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        color = when (r.status) {
                            StrategyBoard.Status.BETTER -> SColors.Ok
                            StrategyBoard.Status.IMPOSSIBLE -> SColors.Stop
                            StrategyBoard.Status.REFERENCE -> SColors.Text
                            else -> SColors.Soft
                        },
                    )
                }
            }
        }
        b.undercut?.let { Text(it, color = SColors.Soft, fontSize = 12.sp) }
        b.overcut?.let { Text(it, color = SColors.Soft, fontSize = 12.sp) }
        if (b.reasons.isNotEmpty()) Text("Réserves : ${b.reasons.joinToString(" ; ")}", color = SColors.Warn, fontSize = 12.sp)
        Text("CONTRÔLEUR", color = SColors.Soft, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        b.checks.forEach { Text(it, color = PitColors.Muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        if (b.unknowns.isNotEmpty()) Text("Non chiffré : ${b.unknowns.joinToString(" ; ")}", color = PitColors.Muted, fontSize = 11.sp)
        Text("Adversaires : GT7 ne transmet ni écarts ni arrêts adverses ; undercut et overcut restent des potentiels conditionnels.", color = PitColors.Muted, fontSize = 10.sp)
    }
}
