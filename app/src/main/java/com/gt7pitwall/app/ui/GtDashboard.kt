package com.gt7pitwall.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.gt7pitwall.app.ui.theme.PitColors
import com.gt7pitwall.app.ui.theme.PitSurface
import com.gt7pitwall.core.display.GtDash
import com.gt7pitwall.core.display.RaceCommandBoard
import com.gt7pitwall.core.display.RaceCommandBoard.FuelRisk
import com.gt7pitwall.core.display.RaceCommandBoard.Link
import com.gt7pitwall.core.display.RaceCommandBoard.PitState
import com.gt7pitwall.core.display.RaceCommandBoard.Tone
import com.gt7pitwall.core.display.TelemetryBoard
import com.gt7pitwall.core.display.TelemetryBoard.TempBand
import com.gt7pitwall.core.engineer.EngineerMessage
import java.util.Locale
import kotlin.math.abs

/**
 * Écran RACE en tableau de bord « GT3 » (V507) : rampe de LED de rupteur, rapport engagé géant, vitesse et régime,
 * position et tour, chrono du tour en cours avec delta et secteurs en direct, pneus, carburant, arrêt, moteur, Véga.
 *
 * Un seul écran fixe, sans défilement : toutes les tailles sont calculées à partir de la place disponible (Fold déplié
 * en portrait ou en paysage, écran extérieur). Les instruments rapides viennent de [GtDash] (~30 images/s), le reste de
 * [RaceCommandBoard]. Une valeur absente s'écrit « — » ; rien n'est inventé.
 */
@Composable
fun GtDashboard(
    dash: GtDash,
    board: RaceCommandBoard,
    title: String,
    vegaStatus: String,
    vegaActive: Boolean,
    message: EngineerMessage?,
    onOpen: (PitDest) -> Unit,
    onMenu: (() -> Unit)?,
) {
    // Hors flux réellement vivant (recherche, arrêt, silence, erreur) : tout est atténué, les valeurs sont figées.
    val live = dash.live && board.live
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 8.dp, vertical = 6.dp)) {
        val compact = maxWidth < 560.dp
        val gap = if (compact) 5.dp else 7.dp
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
            ShiftLights(dash.leds, Modifier.fillMaxWidth().height(if (compact) 26.dp else 34.dp))
            StatusStrip(board, title, vegaStatus, vegaActive, onMenu) { onOpen(PitDest.GARAGE) }
            val dim = if (live) 1f else 0.4f
            Row(Modifier.fillMaxWidth().weight(if (compact) 3.2f else 3.4f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                SpeedColumn(dash, Modifier.weight(1f).fillMaxHeight(), dim)
                GearBlock(dash, Modifier.weight(if (compact) 1.05f else 1.25f).fillMaxHeight(), dim)
                PositionColumn(board, Modifier.weight(1f).fillMaxHeight(), dim) { onOpen(PitDest.STRATEGY) }
            }
            TimingBlock(dash, Modifier.fillMaxWidth().weight(2.3f), dim) { onOpen(PitDest.TELEMETRY) }
            if (compact) {
                Row(Modifier.fillMaxWidth().weight(2.1f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    TyreTile(board.tyres, Modifier.weight(1f).fillMaxHeight(), dim) { onOpen(PitDest.TELEMETRY) }
                    FuelTile(board.fuel, Modifier.weight(1f).fillMaxHeight(), dim) { onOpen(PitDest.STRATEGY) }
                }
                Row(Modifier.fillMaxWidth().weight(1.5f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    PitTile(board, Modifier.weight(1f).fillMaxHeight(), dim) { onOpen(PitDest.STRATEGY) }
                    EngineTile(dash, Modifier.weight(1f).fillMaxHeight(), dim)
                }
            } else {
                Row(Modifier.fillMaxWidth().weight(2.4f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    TyreTile(board.tyres, Modifier.weight(1.15f).fillMaxHeight(), dim) { onOpen(PitDest.TELEMETRY) }
                    FuelTile(board.fuel, Modifier.weight(1f).fillMaxHeight(), dim) { onOpen(PitDest.STRATEGY) }
                    PitTile(board, Modifier.weight(1f).fillMaxHeight(), dim) { onOpen(PitDest.STRATEGY) }
                    EngineTile(dash, Modifier.weight(0.85f).fillMaxHeight(), dim)
                }
            }
            BottomRibbon(board, message, vegaStatus, vegaActive, Modifier.fillMaxWidth().height(if (compact) 54.dp else 62.dp)) { onOpen(PitDest.VEGA) }
        }
    }
}

// ============================================================================================== Briques

private val Off = Color(0xFF15181C)
private val TileBg = Color(0xFF0E1013)
private val TileLine = Color(0xFF23272E)
private val LedGreen = Color(0xFF20E070)
private val LedYellow = Color(0xFFFFC21A)
private val LedRed = Color(0xFFFF2E3E)
private val LedBlue = Color(0xFF2E7CFF)
private val Tab = TextStyle(fontFeatureSettings = "tnum")

@Composable
private fun px(dp: Dp): TextUnit = with(LocalDensity.current) { dp.toSp() }

/** Texte qui remplit sa boîte sans jamais être tronqué : taille déduite de la place, pas de la police du système. */
@Composable
private fun Fit(
    text: String,
    modifier: Modifier,
    color: Color = PitSurface.Text,
    weight: FontWeight = FontWeight.Black,
    maxHeight: Dp = 400.dp,
    charWidth: Float = 0.6f,
    align: Alignment = Alignment.Center,
) {
    BoxWithConstraints(modifier, contentAlignment = align) {
        val byWidth = maxWidth / (text.length.coerceAtLeast(1) * charWidth)
        val size = min(min(this.maxHeight * 0.92f, byWidth), maxHeight)
        Text(
            text, color = color, fontSize = px(size), lineHeight = px(size), fontWeight = weight, maxLines = 1, softWrap = false,
            style = Tab, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Caption(text: String, color: Color = PitColors.Muted, size: Dp = 11.dp, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(Locale.FRANCE), color = color, fontSize = px(size), fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp,
        maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false, modifier = modifier,
    )
}

@Composable
private fun Tile(modifier: Modifier, border: Color = TileLine, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.clip(shape).background(TileBg).border(1.5.dp, border, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        content = content,
    )
}

private fun toneColor(t: Tone): Color = when (t) {
    Tone.OK -> PitColors.Green
    Tone.CAUTION -> PitColors.Yellow
    Tone.CRITICAL -> PitColors.Red
    Tone.NEUTRAL -> PitColors.Muted
}

private fun lap(ms: Int?): String = TelemetryBoard.lapTime(ms)

// ============================================================================================== LED de rupteur

/** 15 LED : vert, jaune, rouge selon le régime ; toutes en bleu clignotant au moment de passer le rapport. */
@Composable
private fun ShiftLights(leds: GtDash.Leds, modifier: Modifier) {
    val blink = if (leds.shift) {
        val t = rememberInfiniteTransition(label = "rupteur")
        val v by t.animateFloat(0f, 1f, infiniteRepeatable(tween(110, easing = LinearEasing), RepeatMode.Reverse), label = "flash")
        v > 0.5f
    } else {
        true
    }
    Canvas(modifier) {
        val n = leds.total
        val gap = size.width * 0.012f
        val w = (size.width - gap * (n - 1)) / n
        val h = size.height
        for (i in 0 until n) {
            val on = leds.available && i < leds.lit && blink
            val c = if (!on) Off else when (leds.zone(i)) {
                GtDash.Zone.GREEN -> LedGreen
                GtDash.Zone.YELLOW -> LedYellow
                GtDash.Zone.RED -> LedRed
                GtDash.Zone.SHIFT -> LedBlue
            }
            val x = i * (w + gap)
            if (on) drawRoundRect(c.copy(alpha = 0.35f), Offset(x - 3f, -3f), Size(w + 6f, h + 6f), CornerRadius(h * 0.5f))
            drawRoundRect(c, Offset(x, 0f), Size(w, h), CornerRadius(h * 0.42f))
        }
    }
}

// ============================================================================================== Bandeau d'état

@Composable
private fun StatusStrip(board: RaceCommandBoard, title: String, vegaStatus: String, vegaActive: Boolean, onMenu: (() -> Unit)?, onConnect: () -> Unit) {
    val (text, color) = when (board.link) {
        Link.LIVE -> "GT7 EN DIRECT" to PitColors.Green
        Link.SEARCHING -> "RECHERCHE PS5" to PitColors.Yellow
        Link.SILENT -> "SIGNAL PERDU" + (board.silenceS?.let { " $it s" } ?: "") to PitColors.Red
        Link.ERROR -> "ERREUR" to PitColors.Red
        Link.STOPPED -> "ARRÊTÉ · CONNECTER" to PitColors.Muted
    }
    Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (onMenu != null) {
            Box(
                Modifier.height(30.dp).clip(RoundedCornerShape(8.dp)).background(PitSurface.PanelHi).clickable(onClick = onMenu).padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Caption("☰ MENU", PitSurface.Text, 12.dp) }
        }
        Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onConnect).padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).background(color, CircleShape))
            Spacer(Modifier.width(6.dp))
            Caption(text, if (board.link == Link.LIVE) PitSurface.Text else color, 12.dp)
        }
        Text(
            title.uppercase(Locale.FRANCE), color = PitColors.Muted, fontSize = px(12.dp), fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Box(Modifier.size(9.dp).background(if (vegaActive) PitColors.Cyan else PitColors.Muted, CircleShape))
        Caption("VÉGA", if (vegaActive) PitColors.Cyan else PitColors.Muted, 12.dp)
    }
}

// ============================================================================================== Vitesse, régime, pédales

@Composable
private fun SpeedColumn(d: GtDash, modifier: Modifier, dim: Float) {
    Tile(modifier) {
        Caption("km/h")
        Fit(d.speedKmh?.toString() ?: "—", Modifier.fillMaxWidth().weight(1.25f), PitSurface.Text.copy(alpha = dim), charWidth = 0.62f)
        Caption("tr/min")
        Fit(d.rpm?.let { String.format(Locale.FRANCE, "%,d", it).replace(' ', ' ').replace(' ', ' ') } ?: "—", Modifier.fillMaxWidth().weight(0.6f), PitSurface.Text.copy(alpha = dim * 0.9f), weight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().weight(0.55f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Pedal("ACC", d.throttle, PitColors.Green, Modifier.weight(1f).fillMaxHeight())
            Pedal("FREIN", d.brake, PitColors.Red, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/** Barre de pédale horizontale (0..1), étiquette à l'intérieur. */
@Composable
private fun Pedal(label: String, v: Float?, color: Color, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(Off), contentAlignment = Alignment.CenterStart) {
        if (v != null) Box(Modifier.fillMaxHeight().fillMaxWidth(v.coerceIn(0f, 1f)).background(color.copy(alpha = 0.85f)))
        Text(label, color = PitSurface.Text, fontSize = px(10.dp), fontWeight = FontWeight.Black, letterSpacing = 1.sp, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
    }
}

// ============================================================================================== Rapport engagé

@Composable
private fun GearBlock(d: GtDash, modifier: Modifier, dim: Float) {
    val shift = d.leds.shift
    val border = when {
        shift -> LedBlue
        d.paused -> PitColors.Yellow
        else -> TileLine
    }
    Tile(modifier, border) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Caption("Rapport", modifier = Modifier.weight(1f))
            if (d.tcs) Indicator("TCS", PitColors.Yellow)
            if (d.asm) {
                Spacer(Modifier.width(4.dp))
                Indicator("ASM", PitColors.Yellow)
            }
        }
        val gearColor = when {
            shift -> LedBlue
            d.gear == "R" -> PitColors.Red
            else -> PitSurface.Text
        }
        Fit(d.gear ?: "—", Modifier.fillMaxWidth().weight(1f), gearColor.copy(alpha = dim), charWidth = 0.66f)
        Box(Modifier.fillMaxWidth().height(26.dp), contentAlignment = Alignment.Center) {
            when {
                d.paused -> Caption("PAUSE", PitColors.Yellow, 13.dp)
                d.suggestedGear != null -> Caption("conseil GT7 : ${d.suggestedGear}", PitColors.Yellow, 13.dp)
                !d.leds.available && d.gear != null -> Caption("témoin de rupteur : seuils non reçus", PitColors.Muted, 10.dp)
            }
        }
    }
}

@Composable
private fun Indicator(text: String, color: Color) {
    Text(
        text, color = Color.Black, fontSize = px(11.dp), fontWeight = FontWeight.Black, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(color).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

// ============================================================================================== Position et tour

@Composable
private fun PositionColumn(b: RaceCommandBoard, modifier: Modifier, dim: Float, onClick: () -> Unit) {
    Tile(modifier, onClick = onClick) {
        Caption("Position")
        Row(Modifier.fillMaxWidth().weight(1.2f), verticalAlignment = Alignment.CenterVertically) {
            Fit(
                b.position?.let { "P$it" } ?: "P—", Modifier.weight(1f).fillMaxHeight(),
                (if (b.positionMeasured) PitSurface.Text else PitColors.Muted).copy(alpha = dim), charWidth = 0.62f,
            )
        }
        Caption(
            when {
                b.position == null -> "non transmise"
                !b.positionMeasured -> "déclarée"
                b.cars != null -> "sur ${b.cars}"
                else -> "GT7"
            },
        )
        Spacer(Modifier.height(4.dp))
        Caption("Tour")
        val lapText = when {
            b.finished -> "FIN"
            b.lap == null -> "—"
            b.totalLaps != null -> "${b.lap}/${b.totalLaps}"
            else -> "${b.lap}"
        }
        Fit(lapText, Modifier.fillMaxWidth().weight(0.9f), PitSurface.Text.copy(alpha = dim), charWidth = 0.6f)
        if (b.lap != null && b.totalLaps == null && !b.finished) Caption("total inconnu")
    }
}

// ============================================================================================== Chrono, delta, secteurs

@Composable
private fun TimingBlock(d: GtDash, modifier: Modifier, dim: Float, onClick: () -> Unit) {
    Tile(modifier, onClick = onClick) {
        Row(Modifier.fillMaxWidth().weight(1.35f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1.25f).fillMaxHeight()) {
                Caption(if (d.lapTimeSource == GtDash.TimeSource.PITWALL) "Tour en cours · mesuré Pitwall" else "Tour en cours")
                Fit(lap(d.lapTimeMs), Modifier.fillMaxWidth().weight(1f), PitSurface.Text.copy(alpha = dim), charWidth = 0.58f, align = Alignment.CenterStart)
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Caption("Delta / meilleur")
                val delta = d.deltaMs
                val color = when {
                    delta == null -> PitColors.Muted
                    delta <= 0 -> PitColors.Green
                    else -> PitColors.Red
                }
                Fit(delta?.let { String.format(Locale.FRANCE, "%+.2f", it / 1000f) } ?: "—", Modifier.fillMaxWidth().weight(1f), color.copy(alpha = dim), charWidth = 0.58f)
                DeltaBar(delta, Modifier.fillMaxWidth().height(8.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().weight(0.8f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            d.sectors.forEach { SectorBox(it, Modifier.weight(1f).fillMaxHeight()) }
            Column(Modifier.weight(1.25f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly) {
                LapLine("Dernier", lap(d.lastLapMs), PitSurface.Text)
                LapLine("Meilleur", lap(d.bestLapMs), PitColors.Purple)
            }
        }
    }
}

/** Barre centrée : vers la droite en rouge si plus lent, vers la gauche en vert si plus rapide (pleine à ±2 s). */
@Composable
private fun DeltaBar(deltaMs: Int?, modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(4.dp)).background(Off)) {
        val mid = size.width / 2f
        drawRect(TileLine, Offset(mid - 1f, 0f), Size(2f, size.height))
        if (deltaMs == null) return@Canvas
        val f = (abs(deltaMs) / 2000f).coerceIn(0f, 1f) * mid
        if (deltaMs > 0) drawRect(PitColors.Red, Offset(mid, 0f), Size(f, size.height))
        else drawRect(PitColors.Green, Offset(mid - f, 0f), Size(f, size.height))
    }
}

@Composable
private fun SectorBox(c: GtDash.SectorCell, modifier: Modifier) {
    val (bg, ink) = when (c.state) {
        GtDash.SectorState.BEST -> PitColors.Purple to Color.White
        GtDash.SectorState.IMPROVED -> PitColors.Green to Color(0xFF04140B)
        GtDash.SectorState.SLOWER -> PitColors.Yellow to Color(0xFF1A1300)
        GtDash.SectorState.CURRENT -> PitSurface.PanelHi to PitSurface.Text
        GtDash.SectorState.PENDING -> Off to PitColors.Muted
    }
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier.clip(shape).background(bg)
            .then(if (c.state == GtDash.SectorState.CURRENT) Modifier.border(2.dp, PitSurface.Text, shape) else Modifier)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("S${c.index}", color = ink.copy(alpha = 0.8f), fontSize = px(11.dp), fontWeight = FontWeight.Black, maxLines = 1)
        Fit(c.ms?.let { String.format(Locale.US, "%.2f", it / 1000f) } ?: if (c.state == GtDash.SectorState.CURRENT) "…" else "—", Modifier.fillMaxWidth().weight(1f), ink, weight = FontWeight.Bold, maxHeight = 30.dp)
    }
}

@Composable
private fun LapLine(label: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label.uppercase(Locale.FRANCE), color = PitColors.Muted, fontSize = px(11.dp), fontWeight = FontWeight.Bold, letterSpacing = 1.sp, maxLines = 1, modifier = Modifier.weight(1f))
        Text(value, color = if (value == "—") PitColors.Muted else color, fontSize = px(19.dp), fontWeight = FontWeight.Black, maxLines = 1, style = Tab, softWrap = false)
    }
}

// ============================================================================================== Pneus

private fun bandColor(b: TempBand): Color = when (b) {
    TempBand.COLD -> Color(0xFF4AA8FF)
    TempBand.IDEAL -> PitColors.Green
    TempBand.WARM -> PitColors.Yellow
    TempBand.HOT -> PitColors.Red
    TempBand.UNKNOWN -> Off
}

@Composable
private fun TyreTile(t: RaceCommandBoard.Tyres, modifier: Modifier, dim: Float, onClick: () -> Unit) {
    Tile(modifier, if (t.overheating) PitColors.Red else TileLine, onClick) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Caption("Pneus °C", modifier = Modifier.weight(1f))
            Box(Modifier.size(12.dp).clip(CircleShape).background(compoundColor(t.compound)))
            Spacer(Modifier.width(5.dp))
            Caption(t.compound?.name ?: "?", if (t.compound == null) PitColors.Yellow else PitSurface.Text)
        }
        Spacer(Modifier.height(4.dp))
        Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TyreCell(t.cells.getOrNull(0), Modifier.weight(1f).fillMaxHeight(), dim)
                TyreCell(t.cells.getOrNull(1), Modifier.weight(1f).fillMaxHeight(), dim)
            }
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TyreCell(t.cells.getOrNull(2), Modifier.weight(1f).fillMaxHeight(), dim)
                TyreCell(t.cells.getOrNull(3), Modifier.weight(1f).fillMaxHeight(), dim)
            }
        }
        Spacer(Modifier.height(3.dp))
        Caption(
            (t.ageLaps?.let { "$it t · " } ?: "") + when (t.compoundStatus) {
                RaceCommandBoard.CompoundStatus.DECLARED -> "gomme déclarée"
                RaceCommandBoard.CompoundStatus.UNCERTAIN -> "gomme à confirmer"
                RaceCommandBoard.CompoundStatus.UNKNOWN -> "gomme non déclarée"
            } + " · usure n/d",
            size = 10.dp,
        )
    }
}

@Composable
private fun TyreCell(c: TelemetryBoard.TyreCell?, modifier: Modifier, dim: Float) {
    val known = c?.tempC != null
    Box(modifier.clip(RoundedCornerShape(7.dp)).background(bandColor(c?.band ?: TempBand.UNKNOWN).copy(alpha = if (known) dim else 1f)), contentAlignment = Alignment.Center) {
        Fit(c?.tempC?.toInt()?.toString() ?: "—", Modifier.fillMaxSize().padding(2.dp), if (known) Color(0xFF07130D) else PitColors.Muted, maxHeight = 34.dp)
    }
}

// ============================================================================================== Carburant

@Composable
private fun FuelTile(f: RaceCommandBoard.Fuel, modifier: Modifier, dim: Float, onClick: () -> Unit) {
    val tone = toneColor(f.risk.tone)
    val critical = f.risk == FuelRisk.CRITICAL
    Tile(modifier, if (f.risk.tone == Tone.CAUTION || critical) tone else TileLine, onClick) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Caption("Carburant", modifier = Modifier.weight(1f))
            Caption(f.risk.label, tone, 10.dp)
        }
        Fit(
            f.lapsOfFuel?.let { String.format(Locale.FRANCE, "%.1f", it) } ?: "—", Modifier.fillMaxWidth().weight(1f),
            (if (critical) PitColors.Red else PitSurface.Text).copy(alpha = dim), maxHeight = 64.dp,
        )
        Caption(if (f.lapsOfFuel != null) "tours d'autonomie (calcul)" else "autonomie après un tour", size = 10.dp)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(9.dp).clip(RoundedCornerShape(5.dp)).background(Off)) {
            f.levelPct?.let { lvl ->
                Box(Modifier.fillMaxHeight().fillMaxWidth((lvl / 100f).coerceIn(0f, 1f)).background(if (critical) PitColors.Red else PitColors.Orange))
            }
        }
        Spacer(Modifier.height(3.dp))
        Row(Modifier.fillMaxWidth()) {
            Caption(f.levelPct?.let { "${it.toInt()} %" } ?: "—", PitSurface.Text, 11.dp)
            Spacer(Modifier.weight(1f))
            Caption(f.perLapPct?.let { String.format(Locale.FRANCE, "%.1f %%/t", it) } ?: "conso —", PitSurface.Text, 11.dp)
        }
    }
}

// ============================================================================================== Arrêt

@Composable
private fun PitTile(b: RaceCommandBoard, modifier: Modifier, dim: Float, onClick: () -> Unit) {
    val pit = b.pit
    val (headline, color) = when (pit.state) {
        PitState.THIS_LAP -> "BOX" to PitColors.Red
        PitState.NEXT_LAP, PitState.PLANNED -> "T${pit.stopLap}" to PitColors.Orange
        PitState.CONFIRM -> "T${pit.confirmLap} ?" to PitColors.Red
        PitState.NONE -> "—" to PitSurface.Text
        PitState.FINISHED -> "FIN" to PitSurface.Text
        PitState.UNKNOWN -> "—" to PitColors.Muted
    }
    val border = when (pit.state) {
        PitState.THIS_LAP, PitState.CONFIRM -> PitColors.Red
        PitState.NEXT_LAP -> PitColors.Orange
        else -> TileLine
    }
    Tile(modifier, border, onClick) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Caption("Arrêt", modifier = Modifier.weight(1f))
            Caption("${pit.stopsDone} fait" + if (pit.stopsDone > 1) "s" else "", size = 10.dp)
        }
        Fit(headline, Modifier.fillMaxWidth().weight(1f), color.copy(alpha = dim), maxHeight = 64.dp)
        Caption(
            when (pit.state) {
                PitState.THIS_LAP -> "box ce tour"
                PitState.NEXT_LAP -> "box fin du tour ${pit.stopLap}"
                PitState.PLANNED -> "dans ${pit.lapsUntil} tours"
                PitState.CONFIRM -> "arrêt à confirmer"
                PitState.NONE -> "aucun arrêt prévu"
                PitState.FINISHED -> "course terminée"
                PitState.UNKNOWN -> "plan après le départ"
            },
            if (border == TileLine) PitColors.Muted else border, 10.dp,
        )
        Spacer(Modifier.height(3.dp))
        val action = when {
            pit.state in setOf(PitState.THIS_LAP, PitState.NEXT_LAP, PitState.PLANNED) -> RaceCommandBoard.pitAction(pit)
            pit.missingTyres.isNotEmpty() -> "Manque " + pit.missingTyres.joinToString(", ") { it.name }
            pit.requiredTyres.isNotEmpty() -> "Gommes imposées faites"
            pit.rulesIncomplete -> "Règlement à vérifier"
            else -> ""
        }
        Text(action, color = PitSurface.Text, fontSize = px(11.dp), fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = px(13.dp))
    }
}

// ============================================================================================== Moteur

@Composable
private fun EngineTile(d: GtDash, modifier: Modifier, dim: Float) {
    Tile(modifier) {
        Caption("Moteur")
        Spacer(Modifier.height(4.dp))
        Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.SpaceEvenly) {
            EngineLine("EAU", d.waterC?.let { "$it°" }, d.waterC?.let { it >= 110 } == true, dim)
            EngineLine("HUILE", d.oilC?.let { "$it°" }, d.oilC?.let { it >= 130 } == true, dim)
            EngineLine("TURBO", d.turboBar?.let { String.format(Locale.FRANCE, "%+.1f", it) }, false, dim)
        }
    }
}

@Composable
private fun EngineLine(label: String, value: String?, alert: Boolean, dim: Float) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = PitColors.Muted, fontSize = px(11.dp), fontWeight = FontWeight.Bold, letterSpacing = 1.sp, maxLines = 1, modifier = Modifier.weight(1f))
        Text(
            value ?: "—", color = (if (alert) PitColors.Red else if (value == null) PitColors.Muted else PitSurface.Text).copy(alpha = dim),
            fontSize = px(17.dp), fontWeight = FontWeight.Black, maxLines = 1, style = Tab,
        )
    }
}

// ============================================================================================== Alerte / Véga

/** Alerte de course en priorité (deux critiques visibles ensemble) ; sinon le dernier message de Véga. */
@Composable
private fun BottomRibbon(b: RaceCommandBoard, message: EngineerMessage?, vegaStatus: String, vegaActive: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val alert = b.topAlert
    val shape = RoundedCornerShape(12.dp)
    if (alert != null) {
        val color = toneColor(alert.tone)
        val second = b.alerts.drop(1).firstOrNull { it.tone == Tone.CRITICAL }
        val pulse = if (alert.tone == Tone.CRITICAL) {
            val t = rememberInfiniteTransition(label = "alerte")
            val v by t.animateFloat(0.45f, 1f, infiniteRepeatable(tween(650, easing = LinearEasing), RepeatMode.Reverse), label = "pouls")
            v
        } else {
            1f
        }
        Row(
            modifier.clip(shape).background(color.copy(alpha = 0.18f)).border(2.dp, color.copy(alpha = pulse), shape).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(alert.title, color = color, fontSize = px(18.dp), fontWeight = FontWeight.Black, maxLines = 1, softWrap = false)
            Text(
                alert.detail + (second?.let { " · AUSSI : ${it.title}" } ?: ""), color = PitSurface.Text, fontSize = px(13.dp), fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = px(15.dp), modifier = Modifier.weight(1f),
            )
        }
        return
    }
    Row(
        modifier.clip(shape).background(PitSurface.VegaPanel).border(1.dp, PitSurface.VegaLine, shape).clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Canvas(Modifier.size(26.dp)) {
            val colors = if (vegaActive) listOf(Color(0xFF9BEBFF), Color(0xFF1B8FB0), Color(0xFF0A3340)) else listOf(Color(0xFF6B7480), Color(0xFF30363E), Color(0xFF14171B))
            drawCircle(Brush.radialGradient(colors, center = Offset(size.width * 0.38f, size.height * 0.34f), radius = size.minDimension * 0.75f), size.minDimension / 2f)
        }
        Column(Modifier.weight(1f)) {
            Text("VÉGA · ${vegaStatus.uppercase(Locale.FRANCE)}", color = if (vegaActive) PitColors.Cyan else PitColors.Muted, fontSize = px(10.dp), fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                message?.text ?: "Aucun message pour l'instant.", color = if (message != null) PitSurface.Text else PitColors.Muted, fontSize = px(15.dp),
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
