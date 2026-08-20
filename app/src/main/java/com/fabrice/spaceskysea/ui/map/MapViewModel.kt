package com.fabrice.spaceskysea.ui.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fabrice.spaceskysea.data.RadarState
import com.fabrice.spaceskysea.data.SettingsStore
import com.fabrice.spaceskysea.data.UserPosition
import com.fabrice.spaceskysea.data.ais.AisstreamRepository
import com.fabrice.spaceskysea.data.location.LocationRepository
import com.fabrice.spaceskysea.data.opensky.OpenSkyRepository
import com.fabrice.spaceskysea.data.opensky.OpenSkyResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MapViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = SettingsStore(application)
    private val opensky = OpenSkyRepository(settings)
    private val ais = AisstreamRepository(settings)
    private val location = LocationRepository(application)

    private val _userPosition = MutableStateFlow(
        UserPosition(48.85, 2.35, 0f, 0f, 0f, hasFix = false)
    )
    val userPosition: StateFlow<UserPosition> = _userPosition.asStateFlow()

    private val _radar = MutableStateFlow(RadarState())
    val radar: StateFlow<RadarState> = _radar.asStateFlow()

    private val _selectedRoute = MutableStateFlow<Pair<String, String>?>(null)
    val selectedRoute: StateFlow<Pair<String, String>?> = _selectedRoute.asStateFlow()

    private val _routeLoading = MutableStateFlow(false)
    val routeLoading: StateFlow<Boolean> = _routeLoading.asStateFlow()

    private var pollingJob: Job? = null

    init {
        startTracking()
    }

    fun startTracking() {
        location.start { pos -> _userPosition.value = pos }
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive) {
                val pos = _userPosition.value
                if (pos.hasFix || true) {
                    refreshAircraft(pos)
                }
                delay(settings.refreshMs)
            }
        }
        // Le flux AISstream est poussé : on re-souscrit à chaque mouvement de la boîte
        ais.start(
            _userPosition.value.latitude,
            _userPosition.value.longitude,
            settings.vesselRadiusKm.toDouble(),
        ) { vessels ->
            _radar.value = _radar.value.copy(
                vessels = _radar.value.vessels + vessels,
                vesselCount = _radar.value.vessels.size + 1,
                lastUpdateMs = System.currentTimeMillis(),
            )
        }
    }

    private suspend fun refreshAircraft(pos: UserPosition) {
        when (val result = opensky.fetchAircrafts(
            pos.latitude, pos.longitude, settings.aircraftRadiusKm.toDouble()
        )) {
            is OpenSkyResult.Success -> {
                _radar.value = _radar.value.copy(
                    aircraft = result.aircraft,
                    aircraftCount = result.aircraft.size,
                    lastUpdateMs = System.currentTimeMillis(),
                    apiBlocked = false,
                    apiBlockedSource = null,
                )
            }
            OpenSkyResult.QuotaExceeded -> {
                _radar.value = _radar.value.copy(
                    apiBlocked = true,
                    apiBlockedSource = "OpenSky",
                )
                delay(60_000)
            }
            is OpenSkyResult.Error -> Unit
        }
    }

    fun updateAisBoundingBox() {
        val pos = _userPosition.value
        ais.updateBoundingBox(pos.latitude, pos.longitude, settings.vesselRadiusKm.toDouble())
    }

    fun clearVessels() {
        _radar.value = _radar.value.copy(vessels = emptyList(), vesselCount = 0)
    }

    fun dismissApiBlocked() {
        _radar.value = _radar.value.copy(apiBlocked = false, apiBlockedSource = null)
    }

    /** Charge l'itinéraire (départ → arrivée) d'un avion au tap. */
    fun loadAircraftRoute(icao24: String, callsign: String) {
        _selectedRoute.value = null
        _routeLoading.value = true
        viewModelScope.launch {
            val route = opensky.fetchFlightRoute(icao24)
            _selectedRoute.value = route
            _routeLoading.value = false
        }
    }

    fun clearSelectedRoute() {
        _selectedRoute.value = null
        _routeLoading.value = false
    }

    override fun onCleared() {
        location.stop()
        ais.stop()
        super.onCleared()
    }
}
