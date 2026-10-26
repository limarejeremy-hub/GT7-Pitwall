package com.gt7pitwall.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gt7pitwall.app.profile.Gt7ProfileStore
import com.gt7pitwall.app.ui.theme.PitColors
import com.gt7pitwall.core.profile.Gt7CareerJson
import com.gt7pitwall.core.profile.Gt7CareerStats
import com.gt7pitwall.core.profile.Gt7PlayerProfile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * PSN ID is an identity label here, never a PlayStation login.
 * Official OAuth cannot be enabled until Sony approves a client and scopes.
 */
@Composable
fun Gt7CareerPanel(store: Gt7ProfileStore, editable: Boolean) {
    val profile by store.profile.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }
    val context = LocalContext.current
    fun acknowledge(text: String) {
        feedback=text
        Toast.makeText(context,text,Toast.LENGTH_LONG).show()
    }

    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("MON PILOTE GT7", color = PitColors.Orange,
                fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
            // Always show the save result directly below the title, rather than
            // after a long editor that can extend below the Fold viewport.
            if (feedback.isNotEmpty()) {
                Text(feedback, color = if (feedback.startsWith("Enregistr")) PitColors.Green
                    else PitColors.Yellow, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            if (profile.psnId.isBlank()) {
                Text("Profil PSN à associer • aucune connexion requise pour jouer.",
                    color = PitColors.Muted)
            } else {
                Text(profile.nickname.ifBlank { profile.psnId },
                    fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Text("Identifiant PSN : " + profile.psnId, color = PitColors.Muted)
            }
            val sample = profile.latest
            val stats = sample?.stats
            if (stats != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    CareerValue("Classement DR", stats.dr ?: "—")
                    CareerValue("Fair-play SR", stats.sr ?: "—")
                    CareerValue("Points DR", stats.drPoints?.toString() ?: "—")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    CareerValue("Courses", stats.races?.toString() ?: "—")
                    CareerValue("Victoires", stats.wins?.toString() ?: "—")
                    CareerValue("Ratio victoires", stats.victoryRate?.let {
                        ((it * 1000).roundToInt() / 10.0).toString() + " %"
                    } ?: "—")
                }
                val delta = profile.drPointsChange
                if (delta != null) Text(
                    "Évolution DR : " + (if (delta >= 0) "+" else "") + delta + " points",
                    color = if (delta >= 0) PitColors.Green else PitColors.Yellow
                )
                Text("Source : " + sample.evidence.label + " • " +
                    SimpleDateFormat("dd/MM/yyyy HH:mm",Locale.FRANCE).format(Date(sample.capturedAtMs)),
                    color = PitColors.Muted, fontSize = 11.sp)
            } else {
                Text("Statistiques DR/SR à importer ou à renseigner. Les valeurs absentes restent inconnues.",
                    color = PitColors.Muted, fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (Gt7PlayerProfile.validOfficialUrl(profile.officialProfileUrl)) {
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(profile.officialProfileUrl)))
                    }) { Text("Voir profil GT7") }
                }
                if (editable && profile.psnId.isNotBlank()) {
                    OutlinedButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("Pitwall GT7 profile export", Gt7CareerJson.encode(profile))
                        )
                        feedback="Export copié. Garde-le privé."
                    }) { Text("Exporter mes données") }
                }
                if (editable) {
                    Button(onClick = { editing = !editing }) {
                        Text(if (editing) "Fermer" else "Associer / importer")
                    }
                }
            }
            if (editable && editing) {
                CareerEditor(profile,
                    onSave = { id,nick,url,stats ->
                        runCatching { store.saveCompleteProfile(id,nick,url,stats) }
                            .onSuccess {
                                acknowledge(if (stats != null)
                                    "Enregistré : profil et chiffres GT7. Ouvre Briefing pour les voir."
                                else "Enregistré : identité PSN. Ajoute tes chiffres GT7 pour afficher les statistiques.")
                            }
                            .onFailure { acknowledge(it.message ?: "Enregistrement refusé") }
                    },
                    onImport = { json ->
                        runCatching { store.importUserJson(json) }
                            .onSuccess { acknowledge("Enregistré : statistiques importées.") }
                            .onFailure { acknowledge(it.message ?: "Import non reconnu") }
                    }
                )
                var deletionRequested by remember { mutableStateOf(false) }
                TextButton(onClick = {
                    if (deletionRequested) {
                        store.clear()
                        acknowledge("Profil local effacé.")
                        deletionRequested = false
                    } else deletionRequested = true
                }) { Text(if (deletionRequested) "Confirmer la suppression" else "Effacer mon profil local") }
            }
         }
    }
}

@Composable
private fun CareerValue(label:String,value:String) {
    Column {
        Text(label,color=PitColors.Muted,fontSize=11.sp)
        Text(value,fontSize=18.sp,fontWeight=FontWeight.Bold)
    }
}

@Composable
private fun CareerEditor(
    profile: Gt7PlayerProfile,
    onSave:(String,String,String,Gt7CareerStats?)->Unit,
    onImport:(String)->Unit,
) {
    var psn by rememberSaveable(profile.psnId) { mutableStateOf(profile.psnId) }
    var nick by rememberSaveable(profile.psnId) { mutableStateOf(profile.nickname) }
    var url by rememberSaveable(profile.psnId) { mutableStateOf(profile.officialProfileUrl) }
    Text("1 · Identité PSN",fontWeight=FontWeight.Bold)
    OutlinedTextField(value=psn,onValueChange={psn=it.trim()},singleLine=true,
        label={Text("Identifiant PSN")},modifier=Modifier.fillMaxWidth())
    OutlinedTextField(value=nick,onValueChange={nick=it},singleLine=true,
        label={Text("Nom de pilote")},modifier=Modifier.fillMaxWidth())
    OutlinedTextField(value=url,onValueChange={url=it.trim()},maxLines=2,
        label={Text("Lien officiel GT7 (facultatif)")},modifier=Modifier.fillMaxWidth())
    Text("2 · Statistiques Sport",fontWeight=FontWeight.Bold)
    Text("Copie les chiffres visibles dans ton profil GT7, sans mot de passe PSN.",
        color=PitColors.Muted,fontSize=12.sp)
    val original=profile.latest?.stats
    var dr by rememberSaveable(profile.psnId) { mutableStateOf(original?.dr.orEmpty()) }
    var sr by rememberSaveable(profile.psnId) { mutableStateOf(original?.sr.orEmpty()) }
    var points by rememberSaveable(profile.psnId) { mutableStateOf(original?.drPoints?.toString().orEmpty()) }
    var races by rememberSaveable(profile.psnId) { mutableStateOf(original?.races?.toString().orEmpty()) }
    var wins by rememberSaveable(profile.psnId) { mutableStateOf(original?.wins?.toString().orEmpty()) }
    var poles by rememberSaveable(profile.psnId) { mutableStateOf(original?.poles?.toString().orEmpty()) }
    var top5 by rememberSaveable(profile.psnId) { mutableStateOf(original?.top5?.toString().orEmpty()) }
    var clean by rememberSaveable(profile.psnId) { mutableStateOf(original?.cleanRaces?.toString().orEmpty()) }
    var fastest by rememberSaveable(profile.psnId) { mutableStateOf(original?.fastestLaps?.toString().orEmpty()) }
    var garage by rememberSaveable(profile.psnId) { mutableStateOf(original?.garageCars?.toString().orEmpty()) }

    OutlinedTextField(value=dr,onValueChange={dr=it},label={Text("Classement DR")},
        singleLine=true,modifier=Modifier.fillMaxWidth())
    OutlinedTextField(value=sr,onValueChange={sr=it},label={Text("Fair-play SR")},
        singleLine=true,modifier=Modifier.fillMaxWidth())
    NumericCareerField("Points DR",points,{points=it})
    NumericCareerField("Courses Sport",races,{races=it})
    NumericCareerField("Victoires",wins,{wins=it})
    NumericCareerField("Poles",poles,{poles=it})
    NumericCareerField("Top 5",top5,{top5=it})
    NumericCareerField("Courses propres",clean,{clean=it})
    NumericCareerField("Meilleurs tours",fastest,{fastest=it})
    NumericCareerField("Voitures au garage",garage,{garage=it})
    fun optionalNumber(raw:String):Int?=if(raw.isBlank())null else raw.toIntOrNull()
    // One button prevents typing ID + DR/SR together then inadvertently losing
    // stats when the old identity-only save reset rememberSaveable fields.
    val hasStats = listOf(dr,sr,points,races,wins,poles,top5,clean,fastest,garage)
        .any { it.isNotBlank() }
    Button(onClick={
        val stats = if (!hasStats) null else Gt7CareerStats(
            dr=dr.trim().uppercase().takeIf{it.isNotBlank()},
            sr=sr.trim().uppercase().takeIf{it.isNotBlank()},
            drPoints=optionalNumber(points),races=optionalNumber(races),
            wins=optionalNumber(wins),poles=optionalNumber(poles),
            top5=optionalNumber(top5),cleanRaces=optionalNumber(clean),
            fastestLaps=optionalNumber(fastest),garageCars=optionalNumber(garage))
        onSave(psn,nick,url,stats)
    },enabled=Gt7PlayerProfile.validPsnId(psn),modifier=Modifier.fillMaxWidth()) {
        Text("ENREGISTRER MON PROFIL ET MES CHIFFRES")
    }
    Text("Le profil et les chiffres sont enregistrés ensemble sur le téléphone. " +
        "Un message de confirmation apparaît immédiatement.",
        color=PitColors.Muted,fontSize=12.sp)

    Text("3 · Import JSON (facultatif)",fontWeight=FontWeight.Bold)
    Text("Colle uniquement un export de statistiques qui t'appartient. Il doit inclure ton identifiant PSN.",
        color=PitColors.Muted,fontSize=12.sp)
    var json by remember { mutableStateOf("") }
    OutlinedTextField(value=json,onValueChange={json=it.take(120000)},
        label={Text("JSON de mes statistiques")},minLines=3,maxLines=5,
        modifier=Modifier.fillMaxWidth())
    OutlinedButton(onClick={onImport(json)},enabled=json.isNotBlank()&&profile.psnId.isNotBlank()) {
        Text("Importer mes statistiques")
    }
    Text("Pas de connexion PSN officielle sans autorisation de Sony. Ne partage jamais de cookie, code NPSSO ou mot de passe.",
        color=PitColors.Yellow,fontSize=11.sp)
}

@Composable
private fun NumericCareerField(label:String,value:String,onValue:(String)->Unit) {
    OutlinedTextField(value=value,onValueChange={onValue(it.filter(Char::isDigit))},
        label={Text(label)},singleLine=true,modifier=Modifier.fillMaxWidth())
}
