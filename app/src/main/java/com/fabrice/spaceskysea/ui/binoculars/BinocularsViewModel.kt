package com.fabrice.spaceskysea.ui.binoculars

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fabrice.spaceskysea.data.Aircraft
import com.fabrice.spaceskysea.data.GeoUtils
import com.fabrice.spaceskysea.data.SettingsStore
import com.fabrice.spaceskysea.data.UserPosition
import com.fabrice.spaceskysea.data.location.LocationRepository
import com.fabrice.spaceskysea.data.opensky.OpenSkyRepository
import com.fabrice.spaceskysea.data.opensky.OpenSkyResult
import kotlin.math.asin
import kotlin.math.atan2
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class Target(
    val label: String,
    val bearing: Float,      // relèvement depuis l'utilisateur (0=N, 90=E)
    val distanceKm: Float,
    val altitudeMeters: Float?,
    val speedKmh: Float?,
    val headingDeg: Float? = null,      // cap réel de l'avion
    val verticalRateMs: Float? = null,  // montée/descente (m/s)
    val country: String = "",           // pays d'origine
    val elevationDeg: Float = 0f,       // angle au-dessus de l'horizon (vue du ciel)
    val approaching: Boolean? = null,   // true=se rapproche, false=s'éloigne, null=inconnu
    val status: String = "",            // Stationnement/Décollage/Montée/Croisière/Descente/Atterrissage/Au sol
    val geoAltitudeMeters: Float? = null,
    val squawk: String? = null,
    val icao24: String = "",
    val originAirport: String? = null,
    val destinationAirport: String? = null,
)

data class BinocularsState(
    val heading: Float = 0f,          // cap boussole (téléphone à plat, axe haut de l'écran)
    val cameraAzimuth: Float = 0f,    // azimut visé par la caméra arrière (mode Ciel)
    val cameraElevation: Float = -90f, // élévation caméra : 0=horizon, 90=zénith, -90=sol
    val position: UserPosition = UserPosition(48.85, 2.35, 0f, 0f, 0f, false),
    val targets: List<Target> = emptyList(),      // cibles dans le cône de visée (±45°)
    val allAircraft: List<Target> = emptyList(),  // TOUT le trafic (mode contrôleur)
    val totalAircraft: Int = 0,
    val apiBlocked: Boolean = false,
    val maxDistanceKm: Int = 50,      // rayon de recherche configuré
)

/**
 * Mode Jumelles : boussole + avions dans le cône de visée (±45°).
 * L'orientation est dérivée de la matrice de rotation complète :
 * - [BinocularsState.heading] : azimut de l'axe haut du téléphone (modes à plat)
 * - [BinocularsState.cameraAzimuth]/[BinocularsState.cameraElevation] :
 *   direction de la caméra arrière (mode Ciel, téléphone levé) — stable même
 *   téléphone à la verticale, contrairement à getOrientation() seul.
 */
class BinocularsViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = SettingsStore(application)
    private val opensky = OpenSkyRepository(settings)
    private val location = LocationRepository(application)

    private val sensorManager =
        application.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val _state = MutableStateFlow(BinocularsState())
    val state: StateFlow<BinocularsState> = _state.asStateFlow()

    // Historique des distances par avion (pour rapproche/éloigne)
    private val lastDistance = mutableMapOf<String, Float>()

    // Cache des routes (départ/arrivée) par icao24 — succès uniquement
    private val routesCache = mutableMapOf<String, Pair<String, String>>()
    // Dernier essai par icao24 (retente après 5 min si échec)
    private val routesAttemptedAt = mutableMapOf<String, Long>()

    private var pollingJob: Job? = null

    // Lissage : EMA angulaire (gère le passage 359°→0°)
    private var smoothHeading: Float? = null
    private var smoothCamAz: Float? = null
    private var smoothCamElev: Float? = null

    private val sensorListener = object : SensorEventListener {
        private val rotation = FloatArray(9)

        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
            SensorManager.getRotationMatrixFromVector(rotation, event.values)
            // v_monde = R · v_appareil ; monde = (Est, Nord, Haut).
            // Axe haut de l'écran (+Y appareil) → cap boussole
            val flatAz = normalize(
                Math.toDegrees(atan2(rotation[1].toDouble(), rotation[4].toDouble())).toFloat()
            )
            // Caméra arrière (−Z appareil) → azimut + élévation de visée
            val camE = -rotation[2]
            val camN = -rotation[5]
            val camUp = (-rotation[8]).coerceIn(-1f, 1f)
            val camAz = normalize(
                Math.toDegrees(atan2(camE.toDouble(), camN.toDouble())).toFloat()
            )
            val camElev = Math.toDegrees(asin(camUp.toDouble())).toFloat()

            smoothHeading = smoothAngle(smoothHeading, flatAz)
            smoothCamAz = smoothAngle(smoothCamAz, camAz)
            smoothCamElev = ((smoothCamElev ?: camElev) * (1 - SMOOTH_K)) + camElev * SMOOTH_K

            val s = _state.value
            val newHeading = smoothHeading!!
            // Seuil de 0,5° : évite de recomposer l'UI à chaque micro-vibration
            if (GeoUtils.angularDiff(newHeading, s.heading) < 0.5f &&
                GeoUtils.angularDiff(smoothCamAz!!, s.cameraAzimuth) < 0.5f &&
                kotlin.math.abs(smoothCamElev!! - s.cameraElevation) < 0.5f
            ) return

            _state.value = s.copy(
                heading = newHeading,
                cameraAzimuth = smoothCamAz!!,
                cameraElevation = smoothCamElev!!,
                targets = coneFilter(s.allAircraft, newHeading),
            )
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    init {
        _state.value = _state.value.copy(maxDistanceKm = settings.aircraftRadiusKm)
        location.start { pos -> _state.value = _state.value.copy(position = pos) }
        pollingJob = viewModelScope.launch {
            while (isActive) {
                refreshAircraft()
                delay(settings.refreshMs)
            }
        }
    }

    private suspend fun refreshAircraft() {
        val pos = _state.value.position
        when (val result = opensky.fetchAircrafts(
            pos.latitude, pos.longitude, settings.aircraftRadiusKm.toDouble()
        )) {
            is OpenSkyResult.Success -> {
                val all = result.aircraft.filter { it.latitude != null && it.longitude != null }
                    .distinctBy { it.icao24 } // clé des listes UI : jamais de doublon
                    .map { ac ->
                        val bearing = GeoUtils.bearingTo(
                            pos.latitude, pos.longitude, ac.latitude!!, ac.longitude!!
                        )
                        val dist = GeoUtils.distanceKm(
                            pos.latitude, pos.longitude, ac.latitude!!, ac.longitude!!
                        ).toFloat()
                        val route = routesCache[ac.icao24]
                        Target(
                            label = ac.callsign.ifEmpty { ac.icao24 },
                            bearing = bearing,
                            distanceKm = dist,
                            altitudeMeters = ac.altitudeMeters,
                            speedKmh = ac.velocityMs?.let { it * 3.6f },
                            headingDeg = ac.heading,
                            verticalRateMs = ac.verticalRateMs,
                            country = ac.originCountry,
                            elevationDeg = elevationOf(ac.altitudeMeters, dist),
                            approaching = trendOf(ac.icao24, dist),
                            status = statusOf(ac),
                            geoAltitudeMeters = ac.geoAltitudeMeters,
                            squawk = ac.squawk,
                            icao24 = ac.icao24,
                            originAirport = route?.first,
                            destinationAirport = route?.second,
                        )
                    }
                    .sortedBy { it.distanceKm }
                // Purge l'historique des distances des avions disparus
                lastDistance.keys.retainAll(all.map { it.icao24 }.toSet())
                publishAircraft(all, totalCount = result.aircraft.size)
                fetchMissingRoutes(all)
            }
            OpenSkyResult.QuotaExceeded -> {
                _state.value = _state.value.copy(apiBlocked = true)
                delay(60_000)
            }
            is OpenSkyResult.Error -> Unit
        }
    }

    /** Départ/arrivée : max 3 avions (les plus proches) par cycle, espacés de 1,5 s. */
    private suspend fun fetchMissingRoutes(all: List<Target>) {
        val nowMs = System.currentTimeMillis()
        val missing = all.take(3).filter { t ->
            !routesCache.containsKey(t.icao24) &&
                (routesAttemptedAt[t.icao24]?.let { nowMs - it > 300_000 } ?: true)
        }
        if (missing.isEmpty()) return
        for (t in missing) {
            routesAttemptedAt[t.icao24] = nowMs
            val route = opensky.fetchFlightRoute(t.icao24)
            if (route != null && route.first != "LIMIT") routesCache[t.icao24] = route
            delay(1500) // respecte le rate limit OpenSky (1 req/s max)
        }
        val updated = _state.value.allAircraft.map { t ->
            routesCache[t.icao24]?.let { r ->
                t.copy(originAirport = r.first, destinationAirport = r.second)
            } ?: t
        }
        publishAircraft(updated, totalCount = _state.value.totalAircraft)
    }

    private fun publishAircraft(all: List<Target>, totalCount: Int) {
        val heading = _state.value.heading
        _state.value = _state.value.copy(
            targets = coneFilter(all, heading),
            allAircraft = all,
            totalAircraft = totalCount,
            apiBlocked = false,
            maxDistanceKm = settings.aircraftRadiusKm,
        )
    }

    private fun coneFilter(all: List<Target>, heading: Float): List<Target> =
        all.filter { GeoUtils.angularDiff(it.bearing, heading) <= CONE_HALF_ANGLE }

    /** Élévation de l'avion au-dessus de l'horizon (degrés) : atan(altitude / distance). */
    fun elevationOf(altitudeMeters: Float?, distanceKm: Float): Float {
        if (altitudeMeters == null || distanceKm <= 0f) return 0f
        return Math.toDegrees(atan2(altitudeMeters.toDouble(), distanceKm * 1000.0)).toFloat()
    }

    /** Compare la distance à la précédente → true=rapproche, false=éloigne, null=inconnu. */
    fun trendOf(key: String, dist: Float): Boolean? {
        val prev = lastDistance[key]
        lastDistance[key] = dist
        return prev?.let { dist < it - 0.2f }
    }

    /** Statut dérivé : Stationnement / Décollage / Montée / Croisière / Descente / Atterrissage / Au sol. */
    fun statusOf(ac: Aircraft): String {
        val alt = ac.altitudeMeters ?: 0f
        val vr = ac.verticalRateMs ?: 0f
        val speed = ac.velocityMs ?: 0f
        return when {
            ac.onGround && speed < 3f -> "Stationnement"
            ac.onGround -> "Au sol (roulage)"
            alt < 600f && vr > 2f -> "Décollage"
            alt < 600f && vr < -2f -> "Atterrissage"
            vr > 2f -> "Montée"
            vr < -2f -> "Descente"
            else -> "Croisière"
        }
    }

    private fun normalize(deg: Float): Float {
        var d = deg % 360f
        if (d < 0) d += 360f
        return d
    }

    /** EMA angulaire : lisse en passant par le plus court chemin (359°→0°). */
    private fun smoothAngle(prev: Float?, new: Float): Float {
        if (prev == null) return new
        return normalize(prev + SMOOTH_K * GeoUtils.signedAngleDelta(new, prev))
    }

    override fun onCleared() {
        sensorManager.unregisterListener(sensorListener)
        location.stop()
        super.onCleared()
    }

    fun onResume() {
        sensorManager.registerListener(
            sensorListener, rotationSensor, SensorManager.SENSOR_DELAY_UI
        )
    }

    fun onPause() {
        sensorManager.unregisterListener(sensorListener)
    }

    companion object {
        const val CONE_HALF_ANGLE = 45f
        private const val SMOOTH_K = 0.25f
    }
}
