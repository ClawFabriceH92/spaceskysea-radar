package com.fabrice.spaceskysea.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fabrice.spaceskysea.data.SettingsStore

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

        SectionTitle("Fond de carte")
        ToggleRow("OpenTopoMap (alternatif)", s.useOpenTopoMap) { settingsViewModel.setOpenTopo(it) }

        SectionTitle("Clés API")
        OutlinedTextField(
            value = s.openskyUser,
            onValueChange = { settingsViewModel.setOpenSkyUser(it) },
            label = { Text("OpenSky utilisateur (optionnel)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = s.openskyPass,
            onValueChange = { settingsViewModel.setOpenSkyPass(it) },
            label = { Text("OpenSky mot de passe (optionnel)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = s.aisstreamKey,
            onValueChange = { settingsViewModel.setAisKey(it) },
            label = { Text("AISstream clé API") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        SectionTitle("Suivi")
        ToggleRow("Suivi en arrière-plan (option)", s.backgroundTracking) {
            settingsViewModel.setBackgroundTracking(it)
            onBackgroundTrackingChanged?.invoke(it)
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
    }
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
    androidx.compose.material3.ListItem(
        headlineContent = { Text(label) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onToggle) },
    )
}

@Composable
private fun ChoiceRow(label: String, options: List<String>, current: String, onPick: (String) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        androidx.compose.foundation.layout.Row(Modifier.padding(top = 4.dp)) {
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
