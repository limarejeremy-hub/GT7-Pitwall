package com.gt7pitwall.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gt7pitwall.app.ui.theme.PitColors
import com.gt7pitwall.app.ui.theme.PitSurface
import com.gt7pitwall.core.display.RaceCommandBoard
import com.gt7pitwall.core.display.RaceCommandBoard.FuelRisk
import com.gt7pitwall.core.display.RaceCommandBoard.Link
import com.gt7pitwall.core.display.RaceCommandBoard.PitState
import com.gt7pitwall.core.display.RaceCommandBoard.Tone
import com.gt7pitwall.core.display.TelemetryBoard
import com.gt7pitwall.core.display.TelemetryBoard.TempBand
import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.tyres.GripLevel
import com.gt7pitwall.core.tyres.StartTyreAdvisor
import java.util.Locale

/**
 * Écran RACE, « Race Command Center » (refonte V506, proposition « Apex Focus »). Hiérarchie : position et tour, puis
 * trois instruments (carburant, pneus, prochain arrêt), puis Véga. Une alerte réelle prend le haut de l'écran.
 *
 * L'écran n'invente et ne calcule rien : tout vient de [RaceCommandBoard] (testé). Une valeur absente s'écrit
 * « indisponible » ou « — » ; l'usure des pneus n'est pas transmise par GT7 et c'est dit tel quel. Les tailles des
 * instruments sont en dp (pas en sp) : la taille de police du système ne casse pas la mise en page.
 */
@Composable
fun RaceCommandCenter(
    board: RaceCommandBoard,
    /** « Sport C · Watkins Glen · Audi TT Cup '16 », ce qui est connu seulement. */
    title: String,
    stints: List<TelemetryBoard.StintCell>,
    startAdvice: StartTyreAdvisor.Advice?,
    vegaStatus: String,
    vegaActive: Boolean,
    message: EngineerMessage?,
    wide: Boolean,
    onOpen: (PitDest) -> Unit,
) {
    // Télémétrie perdue : les dernières valeurs restent visibles mais atténuées (elles sont figées).
    // Les anciens paquets peuvent rester affichés pendant la recherche ou après l'arrêt du service.
    // Toute valeur hors flux réellement vivant est visuellement atténuée.
    val frozen = !board.live
    val dim by animateFloatAsState(if (frozen) 0.45f else 1f, label = "dim")
    if (wide) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusLine(board, title, vegaStatus, vegaActive) { onOpen(PitDest.GARAGE) }
            AlertBanner(board.alerts)
            Hero(board, Modifier.weight(1f).fillMaxWidth().alpha(dim), big = true) { onOpen(if (board.link == Link.STOPPED) PitDest.GARAGE else PitDest.TELEMETRY) }
            Row(Modifier.weight(2.7f).fillMaxWidth().alpha(dim), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FuelPanel(board.fuel, Modifier.weight(1f).fillMaxHeight()) { onOpen(PitDest.STRATEGY) }
                TyrePanel(board.tyres, Modifier.weight(1f).fillMaxHeight()) { onOpen(PitDest.TELEMETRY) }
                PitPanel(board, stints, startAdvice, Modifier.weight(1f).fillMaxHeight()) { onOpen(PitDest.STRATEGY) }
            }
            VegaRibbon(vegaStatus, vegaActive, message, Modifier.fillMaxWidth().height(78.dp)) { onOpen(PitDest.VEGA) }
        }
    } else {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusLine(board, title, vegaStatus, vegaActive) { onOpen(PitDest.GARAGE) }
            AlertBanner(board.alerts)
            Hero(board, Modifier.fillMaxWidth().height(176.dp).alpha(dim), big = false) { onOpen(if (board.link == Link.STOPPED) PitDest.GARAGE else PitDest.TELEMETRY) }
            VegaRibbon(vegaStatus, vegaActive, message, Modifier.fillMaxWidth().heightIn(min = 74.dp)) { onOpen(PitDest.VEGA) }
            PitPanel(board, stints, startAdvice, Modifier.fillMaxWidth().height(238.dp).alpha(dim)) { onOpen(PitDest.STRATEGY) }
            FuelPanel(board.fuel, Modifier.fillMaxWidth().height(290.dp).alpha(dim)) { onOpen(PitDest.STRATEGY) }
            TyrePanel(board.tyres, Modifier.fillMaxWidth().height(310.dp).alpha(dim)) { onOpen(PitDest.TELEMETRY) }
        }
    }
}

// ---------------------------------------------------------------------------------------------- Briques communes

private val PanelShape = RoundedCornerShape(18.dp)

/** Taille de texte fixe en dp (instruments) : insensible à la taille de police du système. */
@Composable
private fun fixed(dp: Dp): TextUnit = with(LocalDensity.current) { dp.toSp() }

private fun toneColor(t: Tone): Color = when (t) {
    Tone.OK -> PitColors.Green
    Tone.CAUTION -> PitColors.Yellow
    Tone.CRITICAL -> PitColors.Red
    Tone.NEUTRAL -> PitColors.Muted
}

@Composable
private fun Panel(modifier: Modifier, accent: Color = PitSurface.Line, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val line by animateColorAsState(accent, tween(400), label = "panel")
    Column(
        modifier.clip(PanelShape).background(PitSurface.Panel).border(1.dp, line, PanelShape).clickable(onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
private fun Label(text: String, color: Color = PitColors.Muted, modifier: Modifier = Modifier) {
    Text(text.uppercase(Locale.FRANCE), color = color, fontSize = fixed(11.dp), fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/** Étiquette d'état : dit si la valeur est mesurée, déclarée, juste, critique… */
@Composable
private fun Tag(text: String, color: Color = PitColors.Muted) {
    Text(
        text.uppercase(Locale.FRANCE), color = color, fontSize = fixed(10.dp), fontWeight = FontWeight.Bold, letterSpacing = 1.sp, maxLines = 1,
        modifier = Modifier.border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun PanelHead(label: String, tag: String?, tagColor: Color = PitColors.Muted, labelColor: Color = PitColors.Muted) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Label(label, labelColor, Modifier.weight(1f))
        if (tag != null) Tag(tag, tagColor)
    }
}

/** Ligne « libellé … valeur » ; une valeur inconnue s'écrit « indisponible », en gris. */
@Composable
private fun KeyValue(key: String, value: String?, valueColor: Color = PitSurface.Text) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(PitSurface.Line.copy(alpha = 0.7f)))
        Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(key, color = PitColors.Muted, fontSize = fixed(13.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(
                value ?: "indisponible", color = if (value == null) PitColors.Muted else valueColor, fontSize = fixed(14.dp),
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun dec(v: Float?): String? = v?.let { String.format(Locale.FRANCE, "%.1f", it) }

// ---------------------------------------------------------------------------------------------- Ligne d'état

@Composable
private fun StatusLine(board: RaceCommandBoard, title: String, vegaStatus: String, vegaActive: Boolean, onConnect: () -> Unit) {
    val (text, color) = when (board.link) {
        Link.LIVE -> "GT7 EN DIRECT" to PitColors.Green
        Link.SEARCHING -> "RECHERCHE DE LA PS5" to PitColors.Yellow
        Link.SILENT -> "TÉLÉMÉTRIE PERDUE" + (board.silenceS?.let { " · $it s" } ?: "") to PitColors.Red
        Link.ERROR -> "CONNEXION EN ERREUR" to PitColors.Red
        Link.STOPPED -> "TÉLÉMÉTRIE ARRÊTÉE · CONNECTER" to PitColors.Muted
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 26.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // Cible tactile généreuse : toute la zone d'état mène à la connexion.
        Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onConnect).padding(vertical = 4.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).background(color, CircleShape))
            Spacer(Modifier.width(7.dp))
            Text(text, color = if (board.link == Link.LIVE) PitSurface.Text else color, fontSize = fixed(11.5.dp), fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, maxLines = 1)
        }
        Text(title.uppercase(Locale.FRANCE), color = PitColors.Muted, fontSize = fixed(11.5.dp), fontWeight = FontWeight.Bold, letterSpacing = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("VÉGA $vegaStatus".uppercase(Locale.FRANCE), color = if (vegaActive) PitColors.Cyan else PitColors.Muted, fontSize = fixed(11.5.dp), fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------------------------- Alerte

/** L'alerte la plus grave, en clair. Seule une alerte CRITIQUE pulse (lentement) ; rien ne bouge sinon. */
@Composable
private fun AlertBanner(alerts: List<RaceCommandBoard.Alert>) {
    val alert = alerts.firstOrNull()
    AnimatedVisibility(alert != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        val a = alert ?: return@AnimatedVisibility
        // Ne jamais cacher une deuxième alerte critique derrière un simple compteur « +1 ».
        // Exemple : carburant critique ET arrêt au stand ce tour.
        val secondCritical = alerts.drop(1).firstOrNull { it.tone == Tone.CRITICAL }
        val otherCount = alerts.size - 1 - (if (secondCritical != null) 1 else 0)
        val color = toneColor(a.tone)
        val pulse = if (a.tone == Tone.CRITICAL) {
            val t = rememberInfiniteTransition(label = "alerte")
            val v by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "pouls")
            v
        } else {
            1f
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.16f))
                .border(2.dp, color.copy(alpha = pulse), RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(12.dp).alpha(pulse).background(color, CircleShape))
            Column(Modifier.weight(1f)) {
                Text(a.title, color = color, fontSize = fixed(19.dp), fontWeight = FontWeight.Black, letterSpacing = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(a.detail, color = PitSurface.Text, fontSize = fixed(14.dp), fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (secondCritical != null) {
                    Text(
                        "AUSSI : ${secondCritical.title} · ${secondCritical.detail}",
                        color = PitColors.Red,
                        fontSize = fixed(13.dp),
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (otherCount > 0) Tag("+$otherCount", color)
        }
    }
}

// ---------------------------------------------------------------------------------------------- Position, tour, chrono

@Composable
private fun Hero(board: RaceCommandBoard, modifier: Modifier, big: Boolean, onClick: () -> Unit) {
    BoxWithConstraints(
        modifier.clip(PanelShape).background(Brush.verticalGradient(listOf(Color(0xFF191C21), Color(0xFF121418))))
            .border(1.dp, PitSurface.Line, PanelShape).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        val position = board.position?.let { "P$it" } ?: "—"
        val posNote = when {
            board.position == null -> "non reçue"
            board.positionMeasured -> board.cars?.let { "/$it" } ?: "GT7"
            else -> "déclarée"
        }
        val lap = when {
            board.finished -> "FIN"
            board.lap != null -> board.lap.toString()
            else -> "—"
        }
        val lapNote = board.totalLaps?.let { "/$it" } ?: if (board.lap != null) "tours inconnus" else ""
        val delta = if (board.lastLapMs != null && board.bestLapMs != null) board.lastLapMs!! - board.bestLapMs!! else null
        if (big) {
            val h = maxHeight
            val posSize = (h * 0.66f).coerceIn(44.dp, 92.dp)
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1.15f)) {
                    Label("Position")
                    BigValue(position, posNote, posSize, if (board.positionMeasured) PitSurface.Text else PitColors.Muted)
                }
                VRule()
                Column(Modifier.weight(0.9f).padding(start = 18.dp)) {
                    Label("Tour")
                    BigValue(lap, lapNote, posSize * 0.72f, PitSurface.Text)
                }
                VRule()
                Column(Modifier.weight(1.45f).padding(start = 18.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Label("Dernier tour")
                    Text(TelemetryBoard.lapTime(board.lastLapMs), color = PitSurface.Text, fontSize = fixed(posSize * 0.5f), fontWeight = FontWeight.ExtraBold, maxLines = 1, softWrap = false)
                    BestLine(board.bestLapMs, delta)
                }
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1.2f)) {
                        Label("Position")
                        BigValue(position, posNote, 62.dp, if (board.positionMeasured) PitSurface.Text else PitColors.Muted)
                    }
                    Column(Modifier.weight(1f)) {
                        Label("Tour")
                        BigValue(lap, lapNote, 62.dp, PitSurface.Text)
                    }
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column {
                        Label("Dernier tour")
                        Text(TelemetryBoard.lapTime(board.lastLapMs), color = PitSurface.Text, fontSize = fixed(30.dp), fontWeight = FontWeight.ExtraBold, maxLines = 1, softWrap = false)
                    }
                    BestLine(board.bestLapMs, delta)
                }
            }
        }
    }
}

@Composable
private fun BigValue(value: String, note: String, size: Dp, color: Color) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, color = color, fontSize = fixed(size), lineHeight = fixed(size), fontWeight = FontWeight.Black, letterSpacing = (-1).sp, maxLines = 1, softWrap = false)
        if (note.isNotEmpty()) {
            Text(" $note", color = PitColors.Muted, fontSize = fixed((size * 0.28f).coerceAtLeast(13.dp)), fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, modifier = Modifier.padding(bottom = size * 0.12f))
        }
    }
}

@Composable
private fun BestLine(bestMs: Int?, deltaMs: Int?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Meilleur", color = PitColors.Muted, fontSize = fixed(13.dp), maxLines = 1)
        Text(TelemetryBoard.lapTime(bestMs), color = if (bestMs != null) PitColors.Purple else PitColors.Muted, fontSize = fixed(15.dp), fontWeight = FontWeight.Bold, maxLines = 1)
        if (deltaMs != null) {
            Text(
                String.format(Locale.FRANCE, "%+.3f", deltaMs / 1000f), color = if (deltaMs <= 0) PitColors.Green else PitColors.Yellow,
                fontSize = fixed(15.dp), fontWeight = FontWeight.Bold, maxLines = 1,
            )
        }
    }
}

@Composable
private fun VRule() {
    Box(Modifier.width(1.dp).fillMaxHeight(0.72f).background(PitSurface.Line))
}

// ---------------------------------------------------------------------------------------------- Carburant

@Composable
private fun FuelPanel(f: RaceCommandBoard.Fuel, modifier: Modifier, onClick: () -> Unit) {
    val tone = toneColor(f.risk.tone)
    val critical = f.risk == FuelRisk.CRITICAL
    Panel(modifier, if (f.risk.tone == Tone.NEUTRAL || f.risk.tone == Tone.OK) PitSurface.Line else tone.copy(alpha = 0.7f), onClick) {
        PanelHead("Carburant", f.risk.label, tone)
        // Jauge : l'arc est le NIVEAU du réservoir (mesuré) ; le chiffre est l'autonomie en tours (calcul).
        val level by animateFloatAsState(((f.levelPct ?: 0f) / 100f).coerceIn(0f, 1f), tween(600), label = "niveau")
        val arcColor = when {
            f.levelPct == null -> PitSurface.Track
            critical -> PitColors.Red
            f.risk.tone == Tone.CAUTION -> PitColors.Yellow
            else -> PitColors.Orange
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.BottomCenter) {
            val numSize = (maxHeight * 0.36f).coerceIn(26.dp, 54.dp)
            Canvas(Modifier.fillMaxSize()) {
                val stroke = (size.minDimension * 0.085f).coerceAtLeast(8f)
                val r = minOf(size.width / 2f, size.height) - stroke
                if (r <= 0f) return@Canvas
                val topLeft = Offset(size.width / 2f - r, size.height - r - stroke / 2f)
                val box = Size(r * 2f, r * 2f)
                drawArc(PitSurface.Track, 180f, 180f, false, topLeft, box, style = Stroke(stroke, cap = StrokeCap.Round))
                if (f.levelPct != null) drawArc(arcColor, 180f, 180f * level, false, topLeft, box, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(dec(f.lapsOfFuel) ?: "—", color = if (critical) PitColors.Red else PitSurface.Text, fontSize = fixed(numSize), lineHeight = fixed(numSize), fontWeight = FontWeight.Black, maxLines = 1, softWrap = false)
                Label(if (f.lapsOfFuel != null) "tours d'autonomie" else "autonomie indisponible")
            }
        }
        KeyValue("Niveau (mesuré)", f.levelPct?.let { "${it.toInt()} %" })
        KeyValue("Conso (mesurée)", dec(f.perLapPct)?.let { "$it % / tour" })
        KeyValue("Reste à couvrir", dec(f.lapsToGo)?.let { "$it tours" })
    }
}

// ---------------------------------------------------------------------------------------------- Pneus

private fun bandColor(b: TempBand): Color = when (b) {
    TempBand.COLD -> Color(0xFF5AB8FF)
    TempBand.IDEAL -> PitColors.Green
    TempBand.WARM -> PitColors.Yellow
    TempBand.HOT -> PitColors.Red
    TempBand.UNKNOWN -> PitSurface.PanelHi
}

@Composable
private fun TyrePanel(t: RaceCommandBoard.Tyres, modifier: Modifier, onClick: () -> Unit) {
    val gripBad = t.grip == GripLevel.SEVERE || t.grip == GripLevel.PERSISTENT
    Panel(modifier, if (t.overheating) PitColors.Red.copy(alpha = 0.7f) else if (gripBad) PitColors.Yellow.copy(alpha = 0.7f) else PitSurface.Line, onClick) {
        PanelHead(
            "Pneus" + (t.compound?.let { " · ${it.spoken}" } ?: ""),
            t.compoundStatus.label,
            if (t.compoundStatus == RaceCommandBoard.CompoundStatus.DECLARED) PitColors.Muted else PitColors.Yellow,
        )
        // Voiture vue de dessus : chaque pneu porte sa température de surface MESURÉE et la couleur de son repère.
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxHeight().width(54.dp)) {
                val w = size.width * 0.74f
                val left = (size.width - w) / 2f
                drawRoundRect(Color(0xFF1D2026), Offset(left, size.height * 0.04f), Size(w, size.height * 0.92f), androidx.compose.ui.geometry.CornerRadius(w * 0.42f))
                drawRoundRect(Color(0xFF0E1013), Offset(left + w * 0.16f, size.height * 0.3f), Size(w * 0.68f, size.height * 0.3f), androidx.compose.ui.geometry.CornerRadius(w * 0.16f))
            }
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                    TyreBlock(t.cells.getOrNull(0))
                    TyreBlock(t.cells.getOrNull(2))
                }
                Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                    TyreBlock(t.cells.getOrNull(1))
                    TyreBlock(t.cells.getOrNull(3))
                }
            }
        }
        KeyValue("Âge du train", t.ageLaps?.let { "$it tour" + if (it > 1) "s" else "" })
        KeyValue(
            "Adhérence (estimée)", t.grip.label.takeIf { t.grip != GripLevel.INSUFFICIENT },
            when (t.grip) {
                GripLevel.STABLE -> PitColors.Green
                GripLevel.SEVERE -> PitColors.Red
                else -> PitColors.Yellow
            },
        )
        // GT7 ne transmet pas l'usure : dit tel quel, jamais un pourcentage.
        KeyValue("Usure", null)
    }
}

@Composable
private fun TyreBlock(cell: TelemetryBoard.TyreCell?) {
    val known = cell?.tempC != null
    val bg by animateColorAsState(bandColor(cell?.band ?: TempBand.UNKNOWN), tween(500), label = "pneu")
    val ink = if (known) Color(0xFF07130D) else PitColors.Muted
    Column(
        Modifier.size(width = 62.dp, height = 60.dp).clip(RoundedCornerShape(11.dp)).background(bg),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(cell?.tempC?.let { "${it.toInt()}°" } ?: "—", color = ink, fontSize = fixed(22.dp), lineHeight = fixed(23.dp), fontWeight = FontWeight.Black, maxLines = 1)
        Text(cell?.label ?: "", color = ink.copy(alpha = 0.75f), fontSize = fixed(10.dp), fontWeight = FontWeight.Bold, letterSpacing = 1.sp, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------------------------- Prochain arrêt

@Composable
private fun PitPanel(
    board: RaceCommandBoard,
    stints: List<TelemetryBoard.StintCell>,
    startAdvice: StartTyreAdvisor.Advice?,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val pit = board.pit
    val accent = when (pit.state) {
        PitState.THIS_LAP, PitState.CONFIRM -> PitColors.Red
        PitState.NEXT_LAP -> PitColors.Orange
        PitState.PLANNED -> PitColors.Orange.copy(alpha = 0.45f)
        else -> PitSurface.Line
    }
    Panel(modifier, accent, onClick) {
        val tag = when (pit.state) {
            PitState.THIS_LAP -> "maintenant"
            PitState.NEXT_LAP -> "dans 1 tour"
            PitState.PLANNED -> pit.lapsUntil?.let { "dans $it tours" }
            PitState.CONFIRM -> "à confirmer"
            else -> null
        }
        PanelHead("Prochain arrêt", tag, if (accent == PitSurface.Line) PitColors.Muted else accent.copy(alpha = 1f), if (pit.state == PitState.NONE || pit.state == PitState.UNKNOWN || pit.state == PitState.FINISHED) PitColors.Muted else PitColors.Orange)
        val (headline, color) = when (pit.state) {
            PitState.THIS_LAP -> "BOX CE TOUR" to PitColors.Red
            PitState.NEXT_LAP, PitState.PLANNED -> "BOX T${pit.stopLap}" to PitColors.Orange
            PitState.CONFIRM -> "ARRÊT T${pit.confirmLap} ?" to PitColors.Red
            PitState.NONE -> "AUCUN ARRÊT" to PitSurface.Text
            PitState.FINISHED -> "ARRIVÉE" to PitSurface.Text
            PitState.UNKNOWN -> "PLAN INDISPONIBLE" to PitColors.Muted
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Titre ajusté à la largeur du panneau (« PLAN INDISPONIBLE » est long).
            val size = (maxWidth / (headline.length * 0.62f)).coerceIn(18.dp, 46.dp)
            Text(headline, color = color, fontSize = fixed(size), lineHeight = fixed(size), fontWeight = FontWeight.Black, maxLines = 1, softWrap = false)
        }
        val sub = when (pit.state) {
            PitState.THIS_LAP, PitState.NEXT_LAP, PitState.PLANNED -> RaceCommandBoard.pitAction(pit)
            PitState.CONFIRM -> "Réponds oui ou non à la radio."
            PitState.NONE -> "Le plan (calcul) ne prévoit plus d'arrêt."
            PitState.FINISHED -> "Course terminée."
            PitState.UNKNOWN -> "Calculé après le départ, avec tes tours mesurés."
        }
        Text(sub, color = PitSurface.Text, fontSize = fixed(15.dp), fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        // Avant le départ : conseil de gomme (issue #65). Un conseil, jamais la gomme montée.
        if (startAdvice != null) {
            Text(
                (startAdvice.recommended?.let { "Conseil de départ : ${it.spoken}" } ?: startAdvice.leaning?.let { "Tendance de départ : ${it.spoken}" } ?: "Gomme de départ : ${startAdvice.status.label}") +
                    " (${startAdvice.status.label})",
                color = PitColors.Green.takeIf { startAdvice.recommended != null } ?: PitColors.Muted,
                fontSize = fixed(13.dp), fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        if (stints.isNotEmpty() && stints.size <= 40) StintStrip(stints, pit.stopLap.takeIf { pit.state == PitState.PLANNED || pit.state == PitState.NEXT_LAP || pit.state == PitState.THIS_LAP })
        Spacer(Modifier.weight(1f))
        KeyValue("Arrêts faits", pit.stopsDone.toString().takeIf { board.lap != null })
        // Obligations : seulement quand elles sont connues.
        if (pit.requiredTyres.isNotEmpty()) {
            KeyValue(
                "Gommes imposées",
                if (pit.missingTyres.isEmpty()) "toutes montées" else "manque " + pit.missingTyres.joinToString(", ") { it.name },
                if (pit.missingTyres.isEmpty()) PitColors.Green else PitColors.Yellow,
            )
        }
        if (pit.rulesIncomplete) KeyValue("Règlement", "à vérifier", PitColors.Yellow)
    }
}

/** Une case par tour : gomme déclarée du relais, arrêt prévu en orange, tour en cours cerclé de blanc. */
@Composable
private fun StintStrip(stints: List<TelemetryBoard.StintCell>, plannedStop: Int?) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        stints.forEach { c ->
            val color = when {
                c.lap == plannedStop && !c.current -> PitColors.Orange
                c.future -> PitSurface.Track
                else -> compoundColor(c.compound).copy(alpha = if (c.compound == null) 0.35f else 0.9f)
            }
            Box(
                Modifier.weight(1f).height(18.dp).clip(RoundedCornerShape(3.dp)).background(color)
                    .then(if (c.current) Modifier.border(2.dp, Color.White, RoundedCornerShape(3.dp)) else Modifier),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------- Véga

@Composable
private fun VegaRibbon(status: String, active: Boolean, message: EngineerMessage?, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier.clip(shape).background(PitSurface.VegaPanel).border(1.dp, PitSurface.VegaLine, shape).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Canvas(Modifier.size(40.dp)) {
            val c = Offset(size.width * 0.38f, size.height * 0.34f)
            val colors = if (active) listOf(Color(0xFF9BEBFF), Color(0xFF1B8FB0), Color(0xFF0A3340)) else listOf(Color(0xFF6B7480), Color(0xFF30363E), Color(0xFF14171B))
            drawCircle(Brush.radialGradient(colors, center = c, radius = size.minDimension * 0.75f), size.minDimension / 2f)
        }
        Column(Modifier.weight(1f)) {
            Label("Véga · $status", if (active) PitColors.Cyan else PitColors.Muted)
            Text(
                message?.text ?: "Aucun message pour l'instant.", color = if (message != null) PitSurface.Text else PitColors.Muted,
                fontSize = fixed(17.dp), lineHeight = fixed(20.dp), fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
