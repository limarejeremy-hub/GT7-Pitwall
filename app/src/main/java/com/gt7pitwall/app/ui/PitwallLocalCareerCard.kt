package com.gt7pitwall.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gt7pitwall.app.ui.theme.PitColors
import com.gt7pitwall.core.profile.PitwallLocalCareer

/**
 * Makes captured telemetry visible even when Gran Turismo's online career API
 * is inaccessible. Never suggests these totals equal Sony's global career.
 */
@Composable
fun PitwallLocalCareerCard(stats: PitwallLocalCareer) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("CE QUE PITWALL A VRAIMENT ENREGISTRÉ",
                fontSize=15.sp,fontWeight=FontWeight.ExtraBold,color=PitColors.Orange)
            Text("Données calculées automatiquement depuis la PS5 et archivées sur ton téléphone.",
                fontSize=11.sp,color=PitColors.Muted)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                LocalStat("Sessions",stats.sessionsSaved.toString())
                LocalStat("Terminées",stats.completedSessions.toString())
                LocalStat("Tours chronométrés",stats.timedLaps.toString())
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                LocalStat("Voitures utilisées",stats.recordedCars.toString())
                LocalStat("Circuits identifiés",stats.identifiedTracks.toString())
                LocalStat("Courses Sport",stats.verifiedSportRaces.toString())
            }
            if (stats.verifiedSportRaces > 0) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    LocalStat("Victoires",if(stats.sportFinishPositionsMeasured==0)"—" else stats.sportWins.toString())
                    LocalStat("Podiums",if(stats.sportFinishPositionsMeasured==0)"—" else stats.sportPodiums.toString())
                    LocalStat("Top 5",if(stats.sportFinishPositionsMeasured==0)"—" else stats.sportTop5.toString())
                }
                Text("Places d'arrivée connues sur " + stats.sportFinishPositionsMeasured +
                    " des " + stats.verifiedSportRaces + " courses Sport terminées.",
                    color=PitColors.Muted,fontSize=11.sp)
                stats.netPositionsGained?.let { gained ->
                    Text("Places gagnées sur les courses avec grille ET arrivée mesurées : " +
                        (if (gained >= 0) "+" else "") + gained,
                        color=PitColors.Green,fontSize=12.sp)
                }
            }
            Text(when {
                stats.sessionsSaved == 0 ->
                    "Aucune course locale archivée pour le moment. Démarre la télémétrie depuis Atelier, puis roule dans GT7."
                stats.verifiedSportRaces==0 ->
                    "Les sessions existent. Impossible de les compter comme courses Sport officielles sans mode confirmé."
                else -> "Ces chiffres reflètent uniquement les sessions observées par Pitwall. L'historique officiel GT7 reste séparé tant qu'il n'est pas disponible depuis une source vérifiée."
            }, color=PitColors.Muted,fontSize=11.sp)
        }
    }
}

@Composable
private fun LocalStat(label:String,value:String) {
    Column {
        Text(label,fontSize=10.sp,color=PitColors.Muted)
        Text(value,fontSize=19.sp,fontWeight=FontWeight.Bold)
    }
}
