package com.fabrice.spaceskysea.ui.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fabrice.spaceskysea.data.RadarState
import com.fabrice.spaceskysea.data.SettingsStore
import com.fabrice.spaceskysea.data.UserPosition
import com.fabrice.spaceskysea.data.ais.AisstreamRepository
import com.fabrice.spaceskysea.data.opensky.AircraftFeed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MapViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = SettingsStore(application)
    private val feed = AircraftFeed.get(application)
    private val ais = AisstreamRepository(settings)

    /** Position GPS partagée avec le reste de l'app (une seule écoute). */
    val userPosition: StateFlow<UserPosition> = feed.userPosition

    private val _radar = MutableStateFlow(RadarState())
    val radar: StateFlow<RadarState> = _radar.asStateFlow()

    private val _selectedRoute = MutableStateFlow<Pair<String, String>?>(null)
    val selectedRoute: StateFlow<Pair<String, String>?> = _selectedRoute.asStateFlow()

    private val _routeLoading = MutableStateFlow(false)
    val routeLoading: StateFlow<Boolean> = _routeLoading.asStateFlow()

    // Cache des itinéraires partagé avec les Jumelles (rempli en arrière-plan)
    private val routesCache = feed.routesCache

    init {
        // Avions : flux partagé — une seule requête OpenSky pour toute l'app
        viewModelScope.launch {
            feed.state.collect { f ->
                _radar.value = _radar.value.copy(
                    aircraft = f.aircraft,
                    aircraftCount = f.aircraft.size,
                    lastUpdateMs = if (f.lastUpdateMs > 0) f.lastUpdateMs else _radar.value.lastUpdateMs,
                    loading = f.loading,
                    rateLimitRemaining = f.rateLimitRemaining,
                    blockedUntilMs = f.blockedUntilMs,
                    authFailed = f.authFailed,
                )
                // AIS : (re)connecte si une clé vient d'être saisie et suit
                // la position (re-souscription quand la boîte a bougé)
                val pos = feed.userPosition.value
                ais.ensureConnected(
                    pos.latitude, pos.longitude, settings.vesselRadiusKm.toDouble()
                )
            }
        }
        // Le flux AISstream est poussé : le repository fusionne et renvoie la
        // liste complète des navires vus récemment (dédupliqués par MMSI)
        ais.start(
            feed.userPosition.value.latitude,
            feed.userPosition.value.longitude,
            settings.vesselRadiusKm.toDouble(),
        ) { vessels ->
            _radar.value = _radar.value.copy(
                vessels = vessels,
                vesselCount = vessels.size,
                lastUpdateMs = System.currentTimeMillis(),
            )
        }
    }

    /** Charge l'itinéraire (départ → arrivée) d'un avion au tap. */
    fun loadAircraftRoute(icao24: String) {
        routesCache[icao24]?.let {
            _selectedRoute.value = it
            _routeLoading.value = false
            return
        }
        _selectedRoute.value = null
        _routeLoading.value = true
        viewModelScope.launch {
            val route = feed.repository.fetchFlightRoute(icao24)
            if (route != null && route.first != "LIMIT") routesCache[icao24] = route
            _selectedRoute.value = route
            _routeLoading.value = false
        }
    }

    fun clearSelectedRoute() {
        _selectedRoute.value = null
        _routeLoading.value = false
    }

    override fun onCleared() {
        ais.stop()
        super.onCleared()
    }
}
