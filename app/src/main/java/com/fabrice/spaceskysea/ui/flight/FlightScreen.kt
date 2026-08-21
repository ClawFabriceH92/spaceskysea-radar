package com.fabrice.spaceskysea.ui.flight

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fabrice.spaceskysea.data.SettingsStore
import com.fabrice.spaceskysea.data.TrackedFlight
import com.fabrice.spaceskysea.data.flight.FlightRepository
import com.fabrice.spaceskysea.data.location.LocationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FlightUiState(
    val searching: Boolean = false,
    val flight: TrackedFlight? = null,
    val error: String? = null,
)

class FlightViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = FlightRepository(SettingsStore(application))
    private val location = LocationRepository(application)

    private val _state = MutableStateFlow(FlightUiState())
    val state: StateFlow<FlightUiState> = _state.asStateFlow()

    init {
        // Capture la position pour centrer la recherche sur l'utilisateur
        location.start { }
    }

    fun search(company: String, number: String) {
        viewModelScope.launch {
            _state.value = FlightUiState(searching = true)
            val pos = location.lastPosition
            val result = if (pos != null) {
                repo.resolveFlight(company, number, pos.latitude, pos.longitude)
            } else {
                repo.resolveFlight(company, number)
            }
            _state.value = if (result != null) {
                FlightUiState(flight = result)
            } else {
                FlightUiState(error = "Vol introuvable dans la zone (rayon ~800 km). Vérifiez la compagnie et le numéro (ex : Air France AF1234) — le vol doit être en l'air ou au sol avec transpondeur actif.")
            }
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    override fun onCleared() {
        location.stop()
        super.onCleared()
    }
}

@Composable
fun FlightScreen(modifier: Modifier = Modifier, vm: FlightViewModel = viewModel()) {
    val s by vm.state.collectAsState()
    var company by rememberSaveable { mutableStateOf("") }
    var number by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Suivi d'un vol", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Entrez une compagnie et un numéro de vol, ex : Air France AF1234. " +
                "La recherche couvre un large rayon autour de votre position.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        OutlinedTextField(
            value = company,
            onValueChange = { company = it },
            label = { Text("Compagnie") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = number,
            onValueChange = { number = it },
            label = { Text("Numéro de vol (ex : AF1234)") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
        Button(
            onClick = { vm.search(company, number) },
            enabled = company.isNotBlank() && number.isNotBlank() && !s.searching,
            modifier = Modifier.padding(top = 12.dp),
        ) {
            Text(if (s.searching) "Recherche…" else "Rechercher")
        }

        s.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 12.dp),
            )
            TextButton(onClick = { vm.clearError() }) { Text("Fermer") }
        }

        s.flight?.let { f ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("🛫 ${f.callsign}", style = MaterialTheme.typography.titleMedium)
                    Text("Statut : ${f.status}")
                    f.latitude?.let { lat -> f.longitude?.let { lon ->
                        Text("Position : ${"%.4f".format(lat)}, ${"%.4f".format(lon)}")
                    } }
                    f.altitudeMeters?.let { Text("Altitude : ${it.toInt()} m") }
                    f.velocityMs?.let { Text("Vitesse : ${(it * 3.6).toInt()} km/h") }
                    f.heading?.let { Text("Cap : ${it.toInt()}°") }
                }
            }
        }
    }
}
