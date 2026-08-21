package com.fabrice.spaceskysea.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.fabrice.spaceskysea.data.SettingsStore
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    settingsViewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
    onBackgroundTrackingChanged: ((Boolean) -> Unit)? = null,
) {
    val s by settingsViewModel.state.collectAsState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Paramètres", style = MaterialTheme.typography.headlineSmall)

        SectionTitle("Rayons de recherche (km)")
        ChoiceRow("Avions", SettingsStore.AIRCRAFT_RADII.map { it.toString() }, s.aircraftRadius.toString()) {
            settingsViewModel.setAircraftRadius(it.toInt())
        }
        ChoiceRow("Bateaux", SettingsStore.VESSEL_RADII.map { it.toString() }, s.vesselRadius.toString()) {
            settingsViewModel.setVesselRadius(it.toInt())
        }

        SectionTitle("Fréquence avions")
        ChoiceRow("Rafraîchissement", SettingsStore.REFRESH_OPTIONS_SECONDS.map { "${it}s" }, "${s.refreshMs / 1000}s") {
            settingsViewModel.setRefreshMs(it.removeSuffix("s").toLong() * 1000)
        }

        SectionTitle("Unité de vitesse")
        ChoiceRow("Unité", listOf("km/h", "nœuds", "mph"),
            when (s.speedUnit) { "knots" -> "nœuds"; "mph" -> "mph"; else -> "km/h" }) {
            settingsViewModel.setSpeedUnit(when (it) { "nœuds" -> "knots"; "mph" -> "mph"; else -> "kmh" })
        }

        SectionTitle("Couches")
        ToggleRow("Afficher les avions", s.aircraftLayer) { settingsViewModel.setAircraftLayer(it) }
        ToggleRow("Afficher les bateaux", s.vesselLayer) { settingsViewModel.setVesselLayer(it) }

        SectionTitle("Clés API")
        var importError by remember { mutableStateOf<String?>(null) }
        var connTest by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
        val scope = rememberCoroutineScope()
        fun runConnectionTest() {
            connTest = null
            scope.launch {
                val err = settingsViewModel.testOpenSkyConnection()
                connTest = if (err == null) true to "✅ Connexion OpenSky OK — 4000 req/jour"
                else false to err
            }
        }
        // Import du fichier credentials.json (portail OpenSky)
        val filePicker = rememberLauncherForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri: android.net.Uri? ->
            if (uri != null) {
                importError = settingsViewModel.importOpenSkyCredentials(uri)
                if (importError == null) runConnectionTest()
            }
        }
        if (s.openskyClientId.isNotBlank()) {
            Text(
                "✅ OpenSky authentifié : ${s.openskyClientId}",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text(
                "ℹ️ OpenSky anonyme (400 req/jour) — importez vos credentials pour 4000 req/jour + itinéraires",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Button(
            onClick = { filePicker.launch("*/*") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        ) {
            Text("📥 Importer credentials.json (OpenSky)")
        }
        // Plan B : coller le contenu du JSON directement
        var pasteJson by remember { mutableStateOf("") }
        OutlinedTextField(
            value = pasteJson,
            onValueChange = { pasteJson = it },
            label = { Text("Ou collez le contenu du credentials.json ici") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 3,
        )
        TextButton(
            onClick = {
                if (pasteJson.isNotBlank()) {
                    importError = settingsViewModel.applyOpenSkyJson(pasteJson)
                    if (importError == null) {
                        pasteJson = ""
                        runConnectionTest()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Appliquer le JSON collé")
        }
        // Test de connexion
        Button(
            onClick = { runConnectionTest() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            Text("🔌 Tester la connexion OpenSky")
        }
        connTest?.let { (ok, msg) ->
            Text(
                msg,
                color = if (ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        importError?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { importError = null }) { Text("Fermer") }
        }
        OutlinedTextField(
            value = s.openskyClientId,
            onValueChange = { settingsViewModel.setOpenSkyClientId(it) },
            label = { Text("OpenSky clientId") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SecretField(
            value = s.openskyClientSecret,
            onValueChange = { settingsViewModel.setOpenSkyClientSecret(it) },
            label = "OpenSky clientSecret",
        )
        SecretField(
            value = s.aisstreamKey,
            onValueChange = { settingsViewModel.setAisKey(it) },
            label = "AISstream clé API",
        )
        Text(
            "Clé AISstream gratuite sur aisstream.io — nécessaire pour voir les navires.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 2.dp),
        )

        SectionTitle("Suivi")
        ToggleRow("Suivi en arrière-plan (option)", s.backgroundTracking) {
            settingsViewModel.setBackgroundTracking(it)
            onBackgroundTrackingChanged?.invoke(it)
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        SectionTitle("À propos")
        val context = LocalContext.current
        val version = remember {
            try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
            } catch (_: Exception) {
                "?"
            }
        }
        Text("SpaceSkySea Radar v$version", style = MaterialTheme.typography.bodyMedium)

        // Vérification manuelle de mise à jour (GitHub Releases)
        var updateStatus by remember { mutableStateOf<String?>(null) }
        Button(
            onClick = {
                updateStatus = "Vérification en cours…"
                scope.launch {
                    val result = com.fabrice.spaceskysea.UpdateManager(context).checkNow()
                    updateStatus = when (result) {
                        is com.fabrice.spaceskysea.UpdateCheck.UpToDate ->
                            "✅ Vous avez la dernière version (v${result.current})"
                        is com.fabrice.spaceskysea.UpdateCheck.Downloading ->
                            "⬇️ v${result.version} disponible — téléchargement lancé : " +
                                "ouvrez l'APK depuis la barre de notifications (ou Téléchargements) pour installer"
                        is com.fabrice.spaceskysea.UpdateCheck.Failed ->
                            "❌ ${result.message}"
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        ) {
            Text("🔄 Vérifier les mises à jour")
        }
        updateStatus?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = if (it.startsWith("❌")) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
            )
        }

        Text(
            "Données : OpenSky Network (avions) · AISstream.io (navires)\n" +
                "Carte : © OpenStreetMap contributors © CARTO\n" +
                "github.com/ClawFabriceH92/spaceskysea-radar",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )
    }
}

@Composable
private fun SecretField(value: String, onValueChange: (String) -> Unit, label: String) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "Masquer" else "Afficher",
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onToggle) },
    )
}

@Composable
private fun ChoiceRow(label: String, options: List<String>, current: String, onPick: (String) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(
            Modifier
                .padding(top = 4.dp)
                .horizontalScroll(rememberScrollState())
        ) {
            options.forEach { opt ->
                val selected = opt == current
                androidx.compose.material3.FilterChip(
                    selected = selected,
                    onClick = { onPick(opt) },
                    label = { Text(opt) },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }
    }
}
