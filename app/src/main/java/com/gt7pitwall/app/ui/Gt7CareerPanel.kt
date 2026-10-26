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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.gt7pitwall.app.profile.Gt7GridStatsPublicPage
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
import com.gt7pitwall.core.profile.PitwallLocalCareer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.text.NumberFormat
import com.gt7pitwall.core.profile.ProfileEvidence
import kotlin.math.roundToInt

/**
 * PSN ID is an identity label here, never a PlayStation login.
 * Official OAuth cannot be enabled until Sony approves a client and scopes.
 */
@Composable
fun Gt7CareerPanel(store: Gt7ProfileStore, editable: Boolean, localCareer: PitwallLocalCareer = PitwallLocalCareer.EMPTY) {
    val profile by store.profile.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshingPublic by remember { mutableStateOf(false) }
    fun acknowledge(text: String) {
        feedback=text
        Toast.makeText(context,text,Toast.LENGTH_LONG).show()
    }

    // Opt-in lookup of a public, third-party rating page. No PlayStation login.
    suspend fun refreshPublicRating() {
        if (refreshingPublic) return
        refreshingPublic = true
        val requestedPsn = store.read().psnId
        store.markPublicAttempt()
        try {
            val stats = Gt7GridStatsPublicPage.fetch(requestedPsn)
            if (!store.read().psnId.equals(requestedPsn, ignoreCase = true)) {
                feedback = "Le compte PSN a changé pendant la lecture : résultat ignoré."
            } else {
                store.recordPublicRating(stats)
                feedback = "Profil actualisé : " +
                    (if (stats.races != null) "carrière Sport recoupée (" + stats.races + " courses)."
                    else "DR/SR lus, bilan Sport indisponible ou non vérifiable.")
            }
        } catch (ex: Exception) {
            feedback = "GridStats public indisponible : " +
                (ex.message ?: "lecture impossible") + ". Données existantes conservées."
        } finally {
            refreshingPublic = false
        }
    }

    LaunchedEffect(profile.psnId, profile.samples.size) {
        if (store.publicRefreshDue()) refreshPublicRating()
    }

    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PitwallLocalCareerCard(localCareer)
            Text("MON PROFIL GT7 EN LIGNE", color = PitColors.Orange,
                fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
            // Always show the save result directly below the title, rather than
            // after a long editor that can extend below the Fold viewport.
            if (feedback.isNotEmpty()) {
                Text(feedback, color = if (feedback.startsWith("Enregistr") || feedback.startsWith("Profil actualisé")) PitColors.Green
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
                // Show facts, not a wall of em dashes. Missing public data is unknown.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    stats.dr?.let { CareerValue("Classement DR", it) }
                    stats.sr?.let { CareerValue("Fair-play SR", it) }
                    stats.drPoints?.let {
                        CareerValue("Points DR", NumberFormat.getIntegerInstance(Locale.FRANCE).format(it))
                    }
                }
                if (stats.races != null || stats.wins != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        stats.races?.let { CareerValue("Courses Sport", NumberFormat.getIntegerInstance(Locale.FRANCE).format(it)) }
                        stats.wins?.let { CareerValue("Victoires", NumberFormat.getIntegerInstance(Locale.FRANCE).format(it)) }
                        stats.victoryRate?.let {
                            CareerValue("Taux de victoire", "%.1f %%".format(Locale.FRANCE, it * 100.0))
                        }
                    }
                }
                if (stats.poles != null || stats.top5 != null || stats.cleanRaces != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        stats.poles?.let { CareerValue("Pole positions", it.toString()) }
                        stats.top5?.let { CareerValue("Top 5", NumberFormat.getIntegerInstance(Locale.FRANCE).format(it)) }
                        stats.cleanRaces?.let { CareerValue("Courses propres", it.toString()) }
                    }
                }
                if (stats.garageCars != null || stats.collectorLevel != null ||
                    stats.fastestLaps != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        stats.garageCars?.let { CareerValue("Mon garage", it.toString() + " voitures") }
                        stats.collectorLevel?.let { CareerValue("Niv. collectionneur", it.toString()) }
                        stats.fastestLaps?.let { CareerValue("Meilleurs tours", it.toString()) }
                    }
                }
                stats.distanceKm?.let {
                    Text("Distance de carrière : " +
                        NumberFormat.getIntegerInstance(Locale.FRANCE).format(it) + " km",
                        fontSize = 13.sp, color = PitColors.Muted)
                }
                if (sample.evidence == ProfileEvidence.GRIDSTATS_PUBLIC && stats.races == null) {
                    Text("Bilan Sport non vérifiable sur cette lecture publique. " +
                        "Pitwall conserve « inconnu » plutôt que d'afficher de faux zéros.",
                        fontSize = 12.sp, color = PitColors.Yellow)
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
                Text("Identifiant enregistré, mais aucune statistique GT7 sauvegardée. " +
                    "Un ID PSN seul ne permet pas de récupérer automatiquement DR, SR et victoires.",
                    color = PitColors.Yellow, fontSize = 12.sp)
            }
            if (Gt7PlayerProfile.validGoogleShareLink(profile.officialProfileUrl)) {
                Text("Lien court Google enregistré : la destination Gran Turismo n'est pas vérifiée. " +
                    "Il s'ouvre dans ton navigateur ; il ne synchronise pas les statistiques.",
                    color = PitColors.Yellow, fontSize = 11.sp)
            }
            if (profile.psnId.isNotBlank()) {
                Text("Lecture publique GT GridStats : chiffres disponibles et vérifiés uniquement. " +
                    "Source tierce non officielle, sans mot de passe PlayStation.",
                    color = PitColors.Muted, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !refreshingPublic, onClick = {
                        if (!store.publicSyncEnabled()) store.enablePublicSync()
                        scope.launch { refreshPublicRating() }
                    }) {
                        Text(when {
                            refreshingPublic -> "Lecture..."
                            store.publicSyncEnabled() -> "Actualiser mon profil"
                            else -> "Activer le profil public"
                        })
                    }
                    TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                            "https://gt-gridstats.com/player/" + Uri.encode(profile.psnId)
                        )))
                    }) { Text("Voir la source") }
                }
                if (store.publicSyncEnabled()) {
                    TextButton(onClick = {
                        store.disablePublicSync()
                        feedback = "Synchronisation publique désactivée. Historique conservé."
                    }) { Text("Désactiver les mises à jour publiques") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (Gt7PlayerProfile.validProfileLink(profile.officialProfileUrl)) {
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(profile.officialProfileUrl)))
                    }) {
                        Text(if (Gt7PlayerProfile.validOfficialUrl(profile.officialProfileUrl))
                            "Voir profil GT7" else "Ouvrir le partage Google")
                    }
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
        label={Text("Lien GT7 ou partage Google (facultatif)")},modifier=Modifier.fillMaxWidth())
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
