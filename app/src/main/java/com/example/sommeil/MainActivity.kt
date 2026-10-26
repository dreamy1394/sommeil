package com.example.sommeil

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val resumeCount = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SleepWorker.schedule(this)
        setContent {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SleepScreen(refreshKey = resumeCount.intValue)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeCount.intValue++ // revérifie la permission et recalcule au retour des réglages
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

private val zone: ZoneId = ZoneId.systemDefault()
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
private val dateFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.FRENCH)

private fun hhmm(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(zone).format(timeFmt)

private fun duration(min: Long): String = "%d h %02d".format(min / 60, min % 60)

@Composable
private fun SleepScreen(refreshKey: Int) {
    val ctx = LocalContext.current
    val storage = remember { Storage(ctx) }
    var hasPermission by remember { mutableStateOf(ScreenEvents.hasPermission(ctx)) }
    var nights by remember { mutableStateOf(storage.nights()) }
    var settings by remember { mutableStateOf(storage.settings) }
    var recomputeKey by remember { mutableIntStateOf(0) }
    var excluded by remember { mutableStateOf(storage.excludedPackages) }
    var showApps by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) } // (nom, package)

    LaunchedEffect(showApps) {
        if (showApps && apps.isEmpty()) {
            apps = withContext(Dispatchers.IO) {
                val pm = ctx.packageManager
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                pm.queryIntentActivities(intent, 0)
                    .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
                    .distinctBy { it.second }
                    .sortedBy { it.first.lowercase() }
            }
        }
    }

    LaunchedEffect(refreshKey, recomputeKey) {
        hasPermission = ScreenEvents.hasPermission(ctx)
        if (hasPermission) {
            withContext(Dispatchers.IO) { SleepRefresher.refresh(ctx, days = 7) }
        }
        nights = storage.nights()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Sommeil", style = MaterialTheme.typography.headlineMedium) }

        if (!hasPermission) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Autorisation nécessaire", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Pour détecter quand le téléphone n'est plus utilisé le soir et quand " +
                                "il l'est de nouveau le matin, l'appli a besoin de l'accès aux " +
                                "données d'utilisation. Rien ne quitte ton téléphone.",
                        )
                        Button(onClick = {
                            ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        }) { Text("Ouvrir les réglages") }
                    }
                }
            }
            return@LazyColumn
        }

        val last = nights.firstOrNull()
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Dernière nuit", style = MaterialTheme.typography.titleMedium)
                    if (last == null) {
                        Text("Pas encore de nuit détectée. Utilise ton téléphone ce soir et demain matin !")
                    } else {
                        Text(duration(last.durationMin), style = MaterialTheme.typography.displaySmall)
                        Text("Couché vers ${hhmm(last.sleepStart)} · réveillé à ${hhmm(last.sleepEnd)}")
                    }
                }
            }
        }

        val recent = nights.filter { !it.wakeDate.isBefore(LocalDate.now(zone).minusDays(6)) }
        if (recent.isNotEmpty()) {
            item {
                val avg = recent.map { it.durationMin }.average().toLong()
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Moyenne sur 7 jours", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${duration(avg)}  (${recent.size} nuit${if (recent.size > 1) "s" else ""})",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                    }
                }
            }
        }

        item { Text("Historique", style = MaterialTheme.typography.titleMedium) }
        items(nights.take(14)) { n ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(n.wakeDate.format(dateFmt))
                    Text("${duration(n.durationMin)}  (${hhmm(n.sleepStart)} → ${hhmm(n.sleepEnd)})")
                }
                LinearProgressIndicator(
                    progress = { (n.durationMin / 600f).coerceIn(0f, 1f) }, // 10 h = barre pleine
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item { Text("Réglages", style = MaterialTheme.typography.titleMedium) }
        item {
            Column {
                Text("Utilisation ignorée si < ${settings.minActivityMin} min")
                Slider(
                    value = settings.minActivityMin.toFloat(),
                    onValueChange = { settings = settings.copy(minActivityMin = it.toInt()) },
                    onValueChangeFinished = { storage.settings = settings; recomputeKey++ },
                    valueRange = 1f..15f,
                    steps = 13,
                )
                Text("Délai d'endormissement : ${settings.fallAsleepDelayMin} min")
                Slider(
                    value = settings.fallAsleepDelayMin.toFloat(),
                    onValueChange = { settings = settings.copy(fallAsleepDelayMin = it.toInt()) },
                    onValueChangeFinished = { storage.settings = settings; recomputeKey++ },
                    valueRange = 0f..30f,
                    steps = 5,
                )
                Text(
                    "Une consultation du téléphone plus courte que le seuil (ex. regarder l'heure à 3 h) " +
                        "ne coupe pas la nuit. Au-delà, elle la coupe en deux et la plus longue partie est retenue.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Applications ignorées", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Leur utilisation ne compte pas comme éveil : utile si tu t'endors avec une vidéo ou " +
                        "de la musique. Par défaut : YouTube et YouTube Music.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { showApps = !showApps }) {
                    Text(if (showApps) "Masquer la liste" else "Choisir les applications (${excluded.size})")
                }
            }
        }
        if (showApps) {
            items(apps, key = { it.second }) { (label, pkg) ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = pkg in excluded,
                        onCheckedChange = { checked ->
                            excluded = if (checked) excluded + pkg else excluded - pkg
                            storage.excludedPackages = excluded
                            recomputeKey++
                        },
                    )
                    Text(label)
                }
            }
        }
    }
}
