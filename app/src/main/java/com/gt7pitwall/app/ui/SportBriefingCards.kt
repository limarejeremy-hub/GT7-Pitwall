package com.gt7pitwall.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gt7pitwall.app.ui.theme.PitColors
import com.gt7pitwall.core.display.SportCard
import com.gt7pitwall.core.sport.FieldStatus
import com.gt7pitwall.core.strategy.TyreCompound
import java.util.Locale

/**
 * Briefing (09/11) : les courses Sport A/B/C de la semaine, lisibles d'un coup d'œil. Le TRACÉ du circuit est dessiné par
 * Pitwall à partir des captures libres CC0 (`track_signatures.json`) ; la voiture précise montre sa photo officielle
 * GT7 dans l'APK de débogage seulement (droits Sony / Polyphony, voir [PitwallCarArtwork]), sinon une silhouette ; une
 * catégorie (Gr.3, Gr.4, N300…) est un badge dessiné. Un champ inconnu ou contradictoire est dit comme tel.
 */
@Composable
fun SportWeekCards(cards: List<SportCard>) {
    if (cards.isEmpty()) {
        Text("Programme Sport pas encore téléchargé : touche l'indicateur de fraîcheur en haut pour l'actualiser.", color = PitColors.Muted, fontSize = 13.sp)
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 720.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                cards.forEach { Box(Modifier.weight(1f)) { SportRaceCard(it) } }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { cards.forEach { SportRaceCard(it) } }
        }
    }
}

private fun classColor(c: String): Color = when {
    c == "Gr.1" -> Color(0xFFFF1744)
    c == "Gr.2" -> Color(0xFFFF6D00)
    c == "Gr.3" -> Color(0xFF2979FF)
    c == "Gr.4" -> Color(0xFF00C853)
    c == "Gr.B" -> Color(0xFFD500F9)
    c.startsWith("N") -> Color(0xFF00B8D4)
    else -> PitColors.Muted
}

@Composable
private fun SportRaceCard(c: SportCard) {
    val dim = !c.current
    Column(
        Modifier.fillMaxWidth().background(Color(0xFF12161C), RoundedCornerShape(18.dp))
            .border(if (c.current) 2.dp else 1.dp, if (c.current) PitColors.Orange else Color(0xFF263040), RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(40.dp).background(if (dim) Color(0xFF2A323D) else PitColors.Orange, CircleShape), contentAlignment = Alignment.Center) {
                Text(c.letter.toString(), color = if (dim) PitColors.Muted else Color.Black, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
            }
            Column(Modifier.weight(1f)) {
                Text("Course Sport ${c.letter}", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    "du %02d/%02d au %02d/%02d".format(c.validFrom.dayOfMonth, c.validFrom.monthValue, c.validTo.dayOfMonth, c.validTo.monthValue) +
                        if (c.current) " · cette semaine" else " · semaine ancienne",
                    color = if (c.current) PitColors.Green else PitColors.Yellow, fontSize = 12.sp,
                )
            }
            c.laps?.let {
                Column(horizontalAlignment = Alignment.End) {
                    Text("$it", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 26.sp)
                    Text("tours", color = PitColors.Muted, fontSize = 11.sp)
                }
            }
        }

        // Circuit : tracé dessiné (captures CC0), nom et longueur.
        Box(Modifier.fillMaxWidth().height(150.dp).background(Color(0xFF0E1218), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            val outline = c.outline
            if (outline != null) TrackOutline(outline, if (dim) PitColors.Muted else PitColors.Orange)
            else Text("Tracé non disponible", color = PitColors.Muted, fontSize = 12.sp)
        }
        Text(
            c.trackName ?: when (c.trackStatus) {
                FieldStatus.CONFLICT -> "Circuit : sources contradictoires"
                else -> "Circuit inconnu"
            },
            fontWeight = FontWeight.ExtraBold, fontSize = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = if (c.trackName == null) PitColors.Yellow else Color(0xFFE8ECF2),
        )
        c.lengthM?.let { Text(String.format(Locale.FRANCE, "%.3f km", it / 1000.0), color = PitColors.Muted, fontSize = 12.sp) }
        // Logo officiel du circuit et sa fiche (APK de débogage, catalogue gran-turismo.com en cache).
        OfficialTrackInfo(c.officialTrackId, c.trackLookupName)

        // Voiture : photo officielle (APK de débogage) pour une voiture précise, badge pour une catégorie.
        when {
            c.carCode != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PitwallCarArtwork(c.carCode, Modifier.size(width = 128.dp, height = 72.dp))
                Column(Modifier.weight(1f)) {
                    Text("Voiture imposée", color = PitColors.Muted, fontSize = 11.sp)
                    Text(c.carName ?: c.carsText.orEmpty(), fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            c.classes.isNotEmpty() -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                c.classes.forEach { cls ->
                    Box(Modifier.background(classColor(cls), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 8.dp)) {
                        Text(cls, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                    }
                }
                Text(
                    if (c.classesAgreeDespiteConflict) "toutes les sources s'accordent sur la catégorie" else c.carsText.orEmpty().takeIf { it.length > 6 } ?: "Catégorie",
                    color = PitColors.Muted, fontSize = 12.sp, maxLines = 2,
                )
            }
            c.carsText != null -> Text("Voitures : ${c.carsText}", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            else -> Text(
                if (c.carsStatus == FieldStatus.CONFLICT) "Voitures : les sources ne disent pas la même chose" else "Voitures : inconnu",
                color = PitColors.Yellow, fontSize = 13.sp,
            )
        }
        // Désaccord : ce que dit chaque source (rien n'est choisi à la place du pilote).
        if (c.carsBySource.isNotEmpty() && !c.classesAgreeDespiteConflict) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                c.carsBySource.forEach { (source, value) ->
                    Text("$source : $value", color = Color(0xFFE8ECF2), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        // Gommes : autorisées (anneaux de couleur), obligatoires mises en avant.
        val required = c.requiredTyres.orEmpty()
        c.allowedTyres?.takeIf { it.isNotEmpty() }?.let { allowed ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                allowed.sortedBy { it.ordinal }.forEach { t -> TyreChip(t, t in required) }
            }
        } ?: Text("Pneus : inconnu", color = PitColors.Yellow, fontSize = 12.sp)
        if (required.isNotEmpty()) {
            Text(
                "OBLIGATOIRES : " + required.joinToString(", ") { it.name } +
                    if (c.tyreChangeNeeded) " → au moins un passage au stand pour changer de gomme" else "",
                color = PitColors.Orange, fontWeight = FontWeight.Bold, fontSize = 12.sp,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Carburant", c.fuelMultiplier?.let { "×$it" } ?: "—")
            Chip("Usure", c.tyreMultiplier?.let { "×$it" } ?: "—")
            Chip("Arrêts obl.", c.mandatoryStops?.toString() ?: "—")
        }
        if (c.sources.isNotEmpty()) Text("Sources : ${c.sources.joinToString()}", color = PitColors.Muted, fontSize = 10.sp)
    }
}

@Composable
private fun TrackOutline(points: FloatArray, color: Color) {
    Canvas(Modifier.fillMaxWidth().height(150.dp).padding(14.dp)) {
        val side = minOf(size.width, size.height)
        val ox = (size.width - side) / 2f
        val oy = (size.height - side) / 2f
        val path = Path()
        for (i in points.indices step 2) {
            val x = ox + points[i] * side
            val y = oy + points[i + 1] * side
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, Color(0xFF263040), style = Stroke(width = 9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, color, style = Stroke(width = 4.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        // Départ / arrivée : premier point du tracé.
        drawCircle(Color.White, radius = 6f, center = Offset(ox + points[0] * side, oy + points[1] * side))
    }
}

@Composable
private fun TyreChip(t: TyreCompound, required: Boolean) {
    Row(
        Modifier.background(if (required) Color(0xFF2A1A0A) else Color(0xFF1B2129), RoundedCornerShape(999.dp))
            .border(if (required) 1.dp else 0.dp, if (required) PitColors.Orange else Color.Transparent, RoundedCornerShape(999.dp))
            .padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(22.dp).border(4.dp, compoundColor(t), CircleShape))
        Text(t.name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun Chip(label: String, value: String) {
    Column(Modifier.background(Color(0xFF1B2129), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text(label, color = PitColors.Muted, fontSize = 10.sp)
        Text(value, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}
