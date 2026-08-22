package com.fabrice.spaceskysea.ui.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fabrice.spaceskysea.data.Aircraft
import com.fabrice.spaceskysea.data.GeoUtils
import com.fabrice.spaceskysea.data.RadarState
import com.fabrice.spaceskysea.data.SettingsStore
import com.fabrice.spaceskysea.data.UserPosition
import com.fabrice.spaceskysea.data.ais.AisstreamRepository
import com.fabrice.spaceskysea.data.opensky.AircraftFeed
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
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

    // Base d'extrapolation : dernière liste reçue d'OpenSky + son horodatage
    private var baseAircraft: List<Aircraft> = emptyList()
    private var baseTimeMs = 0L

    init {
        // Avions : flux partagé — une seule requête OpenSky pour toute l'app
        viewModelScope.launch {
            feed.state.collect { f ->
                baseAircraft = f.aircraft
                if (f.lastUpdateMs > 0) baseTimeMs = f.lastUpdateMs
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
        // Fluidité : entre deux rafraîchissements OpenSky, chaque avion avance
        // chaque seconde selon sa vitesse et son cap (dead reckoning), comme
        // sur les grands radars de vols — sans requête supplémentaire.
        viewModelScope.launch {
            while (isActive) {
                delay(1000)
                publishExtrapolated()
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

    /** Avance chaque avion en vol selon (vitesse, cap) depuis le dernier lot. */
    private fun publishExtrapolated() {
        val base = baseAircraft
        if (base.isEmpty() || baseTimeMs == 0L) return
        val dt = ((System.currentTimeMillis() - baseTimeMs) / 1000.0)
            .coerceIn(0.0, 120.0) // données trop vieilles : on fige
        if (dt <= 0.5) return
        val moved = base.map { ac ->
            val v = ac.velocityMs
            val h = ac.heading
            if (ac.onGround || v == null || v < 1f || h == null ||
                ac.latitude == null || ac.longitude == null
            ) {
                ac
            } else {
                val (lat, lon) = GeoUtils.offsetPosition(
                    ac.latitude, ac.longitude, h.toDouble(), v * dt
                )
                ac.copy(latitude = lat, longitude = lon)
            }
        }
        _radar.value = _radar.value.copy(aircraft = moved)
    }

    /** Prochain instant où les itinéraires redeviennent disponibles (0 = OK). */
    val routeRetryAtMs: Long
        get() = feed.routes.retryAtMs

    /** Charge l'itinéraire (départ → arrivée) d'un avion au tap. */
    fun loadAircraftRoute(icao24: String, callsign: String) {
        routesCache[icao24]?.let {
            _selectedRoute.value = it
            _routeLoading.value = false
            return
        }
        _selectedRoute.value = null
        _routeLoading.value = true
        viewModelScope.launch {
            val route = feed.routes.fetchRoute(callsign, icao24)
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
