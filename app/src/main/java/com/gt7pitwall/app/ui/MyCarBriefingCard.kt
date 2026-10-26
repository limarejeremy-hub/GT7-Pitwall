package com.gt7pitwall.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gt7pitwall.app.engine.PitwallUiState
import com.gt7pitwall.app.ui.theme.PitColors
import com.gt7pitwall.core.engineer.FrenchFormat
import com.gt7pitwall.core.knowledge.CarEvidence

/** Show the driver a known car, NOT an event meta car mistaken for their own. */
@Composable
fun MyCarBriefingCard(state: PitwallUiState) {
    val myCar = state.garageCar
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("MA VOITURE", style = MaterialTheme.typography.labelMedium,
                color = PitColors.Orange, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PitwallCarArtwork(myCar.code,Modifier.width(180.dp).height(99.dp))
                Column(Modifier.weight(1f)) {
                    Text(myCar.name ?: if (myCar.code != null) "GT7 voiture n°" + myCar.code
                        else "En attente de ta voiture", fontSize = 19.sp,
                        fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(myCar.evidence.label, fontSize = 12.sp,
                        color = if (myCar.liveConfirmed) PitColors.Green else PitColors.Yellow)
                }
            }
            if (myCar.raceCount > 0) {
                Text("TON HISTORIQUE AVEC CETTE VOITURE", color = PitColors.Muted,
                    fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat("Courses",myCar.raceCount.toString())
                    Stat("Tours propres",myCar.completedLaps.toString())
                    Stat("Meilleur tour",FrenchFormat.lapTime(myCar.bestCleanLapMs))
                    Stat("Meilleure place",myCar.bestFinishPosition?.let{"P"+it} ?: "—")
                }
                Text("Mesures de Pitwall uniquement • même épreuve et même circuit quand identifiés.",
                    color = PitColors.Muted, fontSize = 11.sp)
            } else {
                Text(if (myCar.evidence == CarEvidence.UNKNOWN)
                    "Lance une session PS5 : la voiture se reconnaît automatiquement. Aucune saisie nécessaire."
                else "Pas encore de course comparable archivée avec cette voiture.",
                    color = PitColors.Muted, fontSize = 12.sp)
            }
            if (myCar.evidence != CarEvidence.LIVE && myCar.evidence != CarEvidence.LIVE_UNNAMED) {
                Text("Cette voiture n'est pas confirmée comme étant celle engagée maintenant.",
                    color = PitColors.Muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun Stat(label:String,value:String){
    Column {
        Text(label, color=PitColors.Muted,fontSize=10.sp)
        Text(value,color=MaterialTheme.colorScheme.onSurface,
            fontSize=13.sp,fontWeight=FontWeight.Bold)
    }
}
