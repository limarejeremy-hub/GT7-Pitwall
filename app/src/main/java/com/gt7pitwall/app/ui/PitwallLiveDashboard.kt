package com.gt7pitwall.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gt7pitwall.app.engine.PitwallUiState
import com.gt7pitwall.app.ui.theme.PitColors
import com.gt7pitwall.core.engineer.EngineerMessage
import com.gt7pitwall.core.engineer.FrenchFormat
import com.gt7pitwall.core.engineer.Priority
import com.gt7pitwall.core.strategy.FuelAdvice
import java.util.Locale

/**
 * PSVR-friendly home screen. No fake opponent gap, tyre-wear percentage, or
 * opponent strategy: the GT7 UDP stream does not transmit those measurements.
 * Technical controls are kept in the dedicated "Atelier" tab.
 */
@Composable
fun PitwallLiveDashboard(
    state: PitwallUiState,
    messages: List<EngineerMessage>,
    onBriefing: () -> Unit,
    onAnalysis: () -> Unit,
    onAtelier: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 650.dp
        if (wide) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                RaceHero(state, Modifier.weight(1.05f).fillMaxWidth(), onBriefing)
                Row(Modifier.weight(1.8f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Chrono(state, Modifier.weight(1f).fillMaxHeight(), onAnalysis)
                    Fuel(state, Modifier.weight(1f).fillMaxHeight(), onBriefing)
                    Tyres(state, Modifier.weight(1f).fillMaxHeight(), onAtelier)
                }
                Engineer(messages, Modifier.weight(.7f).fillMaxWidth(), onAnalysis)
                Row(Modifier.weight(.9f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Rhythm(state, Modifier.weight(1f).fillMaxHeight(), onAnalysis)
                    Strategy(state, Modifier.weight(1f).fillMaxHeight(), onBriefing)
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                RaceHero(state, Modifier.fillMaxWidth().height(125.dp), onBriefing)
                Engineer(messages, Modifier.fillMaxWidth().height(90.dp), onAnalysis)
                Fuel(state, Modifier.fillMaxWidth().height(190.dp), onBriefing)
                Strategy(state, Modifier.fillMaxWidth().height(130.dp), onBriefing)
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Chrono(state, Modifier.weight(1f).height(195.dp), onAnalysis)
                    Tyres(state, Modifier.weight(1f).height(195.dp), onAtelier)
                }
                Rhythm(state, Modifier.fillMaxWidth().height(130.dp), onAnalysis)
            }
        }
    }
}

private val panelShape = RoundedCornerShape(17.dp)
private val white = Color(0xFFF3F5F8)
private val muted = Color(0xFF9AA7B8)

@Composable
private fun Panel(modifier: Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier.clip(panelShape)
            .background(Brush.linearGradient(listOf(Color(0xFF18242F), Color(0xFF0B121B))))
            .border(1.dp, Color(0xFF314252), panelShape)
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) { content() }
}

@Composable
private fun Eyebrow(value: String) {
    Text(value, color = muted, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        letterSpacing = .6.sp, maxLines = 1)
}

@Composable
private fun Value(value: String, size: Int = 31, color: Color = white) {
    Text(value, color = color, fontSize = size.sp, fontWeight = FontWeight.ExtraBold,
        lineHeight = (size + 1).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun Hint(value: String, color: Color = muted) {
    Text(value, color = color, fontSize = 11.sp, maxLines = 2,
        lineHeight = 13.sp, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun PanelTitle(icon: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(icon, fontSize = 18.sp, color = PitColors.Orange, fontWeight = FontWeight.Bold)
        Text(label, fontSize = 12.sp, color = white, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RaceHero(state: PitwallUiState, modifier: Modifier, onOpen: () -> Unit) {
    val packet = state.snapshot.packet
    Panel(modifier, onOpen) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1.5f), verticalArrangement = Arrangement.Center) {
                Eyebrow("COURSE ACTUELLE")
                Text(state.profile.id.label, color = PitColors.Orange, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1)
                Text(state.course?.label ?: state.edition?.trackName?.value ?: "Circuit à identifier",
                    color = white, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                    lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (packet == null) Hint("PS5 non connectée • ouvrir Atelier")
            }
            Column(Modifier.weight(.83f), horizontalAlignment = Alignment.CenterHorizontally) {
                Eyebrow("POSITION")
                Value(state.driverPosition?.let { "P" + it } ?: "—", 38,
                    if (state.positionMeasured) PitColors.Orange else white)
                Hint(if (state.positionMeasured) state.positionCars?.let { "sur " + it } ?: "GT7"
                    else if (state.driverPosition != null) "déclarée" else "non reçue")
            }
            Column(Modifier.weight(.85f), horizontalAlignment = Alignment.CenterHorizontally) {
                Eyebrow("TOUR")
                Value(packet?.currentLap?.takeIf { it > 0 }?.toString() ?: "—", 38)
                Hint(packet?.totalLaps?.takeIf { it > 0 }?.let { "sur " + it } ?: "avant départ")
            }
        }
    }
}

@Composable
private fun Chrono(state: PitwallUiState, modifier: Modifier, onOpen: () -> Unit) {
    val p = state.snapshot.packet
    Panel(modifier, onOpen) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            PanelTitle("◷", "TEMPS AU TOUR")
            Eyebrow("DERNIER")
            Value(FrenchFormat.lapTime(p?.lastLapMs?.takeIf { it > 0 }), 24)
            Eyebrow("MEILLEUR")
            Value(FrenchFormat.lapTime(p?.bestLapMs?.takeIf { it > 0 }), 24)
            val d = p?.let { if (it.lastLapMs > 0 && it.bestLapMs > 0) it.lastLapMs - it.bestLapMs else null }
            Hint("Écart avec ton meilleur : " + (d?.let { FrenchFormat.delta(it) } ?: "—"),
                if (d == null) muted else if (d <= 0) PitColors.Green else PitColors.Yellow)
        }
    }
}

@Composable
private fun Fuel(state: PitwallUiState, modifier: Modifier, onOpen: () -> Unit) {
    val f = state.fuel
    val level = state.snapshot.packet?.fuelFraction
    Panel(modifier, onOpen) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            PanelTitle("▣", "CARBURANT")
            Row(verticalAlignment = Alignment.Bottom) {
                Value(f?.lapsOfFuel?.let { "%.1f".format(Locale.FRANCE, it) } ?: "—", 39)
                Text(" tours", color = muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
            Hint("Autonomie estimée")
            Box(Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF31404D))) {
                Box(Modifier.fillMaxWidth((level ?: 0f).coerceIn(0f, 1f))
                    .fillMaxHeight().background(if (level != null && level < .15f) PitColors.Red else PitColors.Orange))
            }
            val outcome = when (f?.advice) {
                is FuelAdvice.Comfortable -> "Tu peux finir sans ravitailler"
                is FuelAdvice.Tight -> "Finir possible : économise"
                is FuelAdvice.PitRequired -> "Ravitaillement nécessaire"
                FuelAdvice.NoConsumption -> "Aucune consommation mesurée"
                FuelAdvice.Measuring -> "Calcul après un tour complet"
                FuelAdvice.RaceFinished -> "Course terminée"
                else -> "Autonomie à mesurer"
            }
            Text(outcome, color = when (f?.advice) {
                is FuelAdvice.Comfortable, FuelAdvice.NoConsumption -> PitColors.Green
                is FuelAdvice.PitRequired -> PitColors.Red
                else -> PitColors.Yellow
            }, fontSize = 13.sp, lineHeight = 15.sp, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Tyres(state: PitwallUiState, modifier: Modifier, onOpen: () -> Unit) {
    Panel(modifier, onOpen) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            PanelTitle("◉", "PNEUS")
            Row(verticalAlignment = Alignment.CenterVertically) {
                TyreDisc(Modifier.size(58.dp))
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.tyreStint?.compound?.spoken ?: "Gomme inconnue",
                        fontSize = 15.sp, fontWeight = FontWeight.Bold, color = white,
                        maxLines = 2, lineHeight = 16.sp, overflow = TextOverflow.Ellipsis)
                    Hint(state.tyreStint?.let {
                        it.age.toString() + " tour(s)" + if (it.certain) "" else " • incertain"
                    } ?: "Non déclarée")
                }
            }
            Text("Adhérence : " + state.grip.level.label, fontSize = 12.sp,
                fontWeight = FontWeight.Bold, color = when (state.grip.level) {
                    com.gt7pitwall.core.tyres.GripLevel.STABLE -> PitColors.Green
                    com.gt7pitwall.core.tyres.GripLevel.INSUFFICIENT -> muted
                    else -> PitColors.Yellow
                }, maxLines = 2)
            Hint("GT7 ne transmet pas l'usure exacte ›")
        }
    }
}

/** Dimensional tyre icon, decorative only; no invented wear value. */
@Composable
private fun TyreDisc(modifier: Modifier) {
    Canvas(modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension * .47f
        drawCircle(Brush.radialGradient(listOf(Color(0xFF87919A), Color(0xFF212A34), Color.Black),
            center = center, radius = radius), radius, center)
        drawCircle(Color(0xFF576575), radius * .69f, center, style = Stroke(radius * .17f))
        drawCircle(PitColors.Orange, radius * .76f, center, style = Stroke(radius * .07f))
        drawCircle(Color(0xFF0C1218), radius * .30f, center)
        for (n in 0 until 7) {
            val angle = n * (2 * Math.PI / 7)
            val d = Offset(kotlin.math.cos(angle).toFloat(), kotlin.math.sin(angle).toFloat())
            drawLine(Color(0xFF9FAAB6), center + d * (radius * .29f), center + d * (radius * .61f),
                strokeWidth = radius * .075f, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun Engineer(messages: List<EngineerMessage>, modifier: Modifier, onOpen: () -> Unit) {
    val msg = messages.firstOrNull()
    val accent = if (msg?.priority == Priority.HIGH || msg?.priority == Priority.CRITICAL)
        PitColors.Orange else PitColors.Cyan
    Box(modifier.clip(panelShape).border(1.dp, accent, panelShape)
        .background(Color(0xFF17222C)).clickable(onClick = onOpen)
        .padding(horizontal = 15.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("◖))", color = accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Column(Modifier.weight(1f)) {
                Eyebrow("INGÉNIEUR DE COURSE")
                Text(msg?.text ?: "Prêt à t'aider pendant la course.",
                    color = white, fontSize = 14.sp, lineHeight = 16.sp,
                    fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text("›", color = accent, fontSize = 23.sp)
        }
    }
}

@Composable
private fun Rhythm(state: PitwallUiState, modifier: Modifier, onOpen: () -> Unit) {
    Panel(modifier, onOpen) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            PanelTitle("▥", "RYTHME & RÉGULARITÉ")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Eyebrow("MOYENNE RÉCENTE")
                    Value(FrenchFormat.lapTime(state.stats.recentAverageMs), 21)
                }
                Column(Modifier.weight(1f)) {
                    Eyebrow("RÉGULARITÉ")
                    Text(state.stats.recentStdDevMs?.let {
                        "± %.2f s".format(Locale.FRANCE, it / 1000f)
                    } ?: "À mesurer", color = PitColors.Green,
                        fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
            Hint(state.stats.lapCount.toString() + " tour(s) enregistrés • Analyse ›")
        }
    }
}

@Composable
private fun Strategy(state: PitwallUiState, modifier: Modifier, onOpen: () -> Unit) {
    val lap = (state.snapshot.packet?.currentLap ?: 1).coerceAtLeast(1)
    val stop = state.plan?.nextStop(lap)
    Panel(modifier, onOpen) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            PanelTitle("⚑", "PLAN DE COURSE")
            Value(stop?.let { "Box : tour " + it.endOfLap } ?: "Aucun arrêt prévu",
                20, if (stop == null) white else PitColors.Orange)
            Hint(when {
                state.ruleGaps.isNotEmpty() -> "Règles incomplètes : plan à vérifier."
                stop?.tyreToFit != null -> "Monter " + stop.tyreToFit!!.spoken
                stop?.forFuel == true -> "Ravitaillement prévu"
                stop?.changeTyres == true -> "Changement de pneus prévu"
                else -> "Le plan peut évoluer selon ton rythme."
            })
            Hint("Voir le briefing ›", PitColors.Orange)
        }
    }
}
