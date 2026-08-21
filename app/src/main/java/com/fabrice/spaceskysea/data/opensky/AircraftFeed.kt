package com.fabrice.spaceskysea.data.opensky

import android.content.Context
import com.fabrice.spaceskysea.data.Aircraft
import com.fabrice.spaceskysea.data.SettingsStore
import com.fabrice.spaceskysea.data.UserPosition
import com.fabrice.spaceskysea.data.location.LocationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Source UNIQUE du trafic aérien pour toute l'application.
 *
 * Avant, la Carte et les Jumelles interrogeaient chacune OpenSky de leur
 * côté : deux fois plus de requêtes, quota anonyme (400/jour) épuisé en
 * ~1 h 30. Ce singleton fait UNE requête par cycle et partage :
 * - la liste d'avions ([state]),
 * - la position GPS ([userPosition]),
 * - le repository (token OAuth + cooldown itinéraires communs).
 *
 * Gestion du quota :
 * - 429 → attend le délai exact demandé par le serveur
 *   (X-Rate-Limit-Retry-After-Seconds), exposé via [FeedState.blockedUntilMs]
 * - quota du jour presque épuisé → l'intervalle s'étire automatiquement
 */
class AircraftFeed private constructor(context: Context) {

    private val settings = SettingsStore(context)
    val repository = OpenSkyRepository(settings)
    private val location = LocationRepository(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    data class FeedState(
        val aircraft: List<Aircraft> = emptyList(),
        val lastUpdateMs: Long = 0L,
        val loading: Boolean = false,
        val rateLimitRemaining: Int? = null,
        val blockedUntilMs: Long = 0L,   // 0 = pas bloqué par un 429
    )

    private val _state = MutableStateFlow(FeedState())
    val state: StateFlow<FeedState> = _state.asStateFlow()

    /**
     * Cache partagé des itinéraires (icao24 → départ/arrivée) : rempli par le
     * balayage de fond des Jumelles et par les fiches de la Carte — chaque
     * avion n'est interrogé qu'une fois pour toute l'app.
     */
    val routesCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()

    private val _userPosition = MutableStateFlow(
        UserPosition(48.85, 2.35, 0f, 0f, 0f, hasFix = false)
    )
    val userPosition: StateFlow<UserPosition> = _userPosition.asStateFlow()

    private val onLocation: (UserPosition) -> Unit = { _userPosition.value = it }

    init {
        location.start(onLocation)
        scope.launch {
            while (isActive) {
                // La permission a pu être accordée après le démarrage
                location.start(onLocation)
                delay(refreshOnce())
            }
        }
    }

    /** À appeler quand la permission localisation vient d'être accordée. */
    fun retryLocationStart() {
        location.start(onLocation)
    }

    /** Un cycle de rafraîchissement ; renvoie le délai avant le prochain. */
    private suspend fun refreshOnce(): Long {
        val pos = _userPosition.value
        _state.value = _state.value.copy(loading = true)
        val result = repository.fetchAircrafts(
            pos.latitude, pos.longitude, settings.aircraftRadiusKm.toDouble()
        )
        val now = System.currentTimeMillis()
        return when (result) {
            is OpenSkyResult.Success -> {
                _state.value = FeedState(
                    aircraft = result.aircraft,
                    lastUpdateMs = now,
                    loading = false,
                    rateLimitRemaining = repository.lastRateLimitRemaining,
                    blockedUntilMs = 0L,
                )
                stretchedInterval()
            }
            is OpenSkyResult.QuotaExceeded -> {
                val waitMs = ((result.retryAfterSec ?: 60L) * 1000)
                    .coerceIn(30_000L, 24 * 3600_000L)
                _state.value = _state.value.copy(
                    loading = false,
                    rateLimitRemaining = repository.lastRateLimitRemaining,
                    blockedUntilMs = now + waitMs,
                )
                waitMs
            }
            is OpenSkyResult.Error -> {
                _state.value = _state.value.copy(
                    loading = false,
                    rateLimitRemaining = repository.lastRateLimitRemaining,
                )
                settings.refreshMs
            }
        }
    }

    /** Étire l'intervalle quand le quota du jour s'épuise (le préserve). */
    private fun stretchedInterval(): Long {
        val remaining = repository.lastRateLimitRemaining ?: return settings.refreshMs
        return when {
            remaining < 10 -> maxOf(settings.refreshMs, 180_000L)
            remaining < 50 -> maxOf(settings.refreshMs, 60_000L)
            else -> settings.refreshMs
        }
    }

    companion object {
        @Volatile
        private var instance: AircraftFeed? = null

        fun get(context: Context): AircraftFeed =
            instance ?: synchronized(this) {
                instance ?: AircraftFeed(context.applicationContext).also { instance = it }
            }
    }
}
