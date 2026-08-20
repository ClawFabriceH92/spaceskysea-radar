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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
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
    val heading: Float = 0f,          // cap du téléphone (boussole)
    val pitchDeg: Float = 0f,         // inclinaison verticale du téléphone (0=horizontal, 90=levé)
    val position: UserPosition = UserPosition(48.85, 2.35, 0f, 0f, 0f, false),
    val targets: List<Target> = emptyList(),      // cibles dans le cône de visée
    val allAircraft: List<Target> = emptyList(),  // TOUT le trafic (mode contrôleur)
    val totalAircraft: Int = 0,
    val apiBlocked: Boolean = false,
    val maxDistanceKm: Int = 50,      // rayon de recherche configuré
)

/**
 * Mode Jumelles : boussole + avions/bateaux dans le cône de visée (±30°).
 * On filtre les avions OpenSky par relèvement ; les bateaux AIS ne sont pas
 * encore intégrés ici (v1 : avions).
 */
class BinocularsViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = SettingsStore(application)
    private val opensky = OpenSkyRepository(settings)
    private val location = LocationRepository(application)

    private val sensorManager =
        application.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private var lastHeading = 0f

    private val _state = MutableStateFlow(BinocularsState())
    val state: StateFlow<BinocularsState> = _state.asStateFlow()

    // Historique des distances par avion (pour rapproche/éloigne)
    private val lastDistance = mutableMapOf<String, Float>()

    // Cache des routes (départ/arrivée) par icao24 — succès uniquement
    private val routesCache = mutableMapOf<String, Pair<String, String>?>()
    // Dernier essai par icao24 (retente après 5 min si échec)
    private val routesAttemptedAt = mutableMapOf<String, Long>()

    private var pollingJob: Job? = null

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                val rotation = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                val orientation = FloatArray(3)
                SensorManager.getOrientation(rotation, orientation)
                lastHeading = Math.toDegrees(orientation[0].toDouble()).toFloat()
                    .let { if (it < 0) it + 360f else it }
                val pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
                _state.value = _state.value.copy(
                    heading = lastHeading,
                    pitchDeg = pitch,
                )
            }
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
                    .map { ac ->
                        val bearing = bearingTo(pos.latitude, pos.longitude, ac.latitude!!, ac.longitude!!)
                        val dist = GeoUtils.distanceKm(
                            pos.latitude, pos.longitude, ac.latitude!!, ac.longitude!!
                        ).toFloat()
                        Target(
                            label = ac.callsign.ifEmpty { ac.icao24 },
                            bearing = bearing,
                            distanceKm = dist,
                            altitudeMeters = ac.altitudeMeters,
                            speedKmh = ac.velocityMs?.let { it * 3.6f },
                            verticalRateMs = ac.verticalRateMs,
                            country = ac.originCountry,
                            elevationDeg = elevationOf(ac.altitudeMeters, dist),
                            approaching = trendOf(ac.callsign.ifEmpty { ac.icao24 }, dist),
                            status = statusOf(ac),
                            geoAltitudeMeters = ac.geoAltitudeMeters,
                            squawk = ac.squawk,
                            icao24 = ac.icao24,
                        )
                    }
                    .sortedBy { it.distanceKm }
                val inCone = all.filter { angularDiff(it.bearing, _state.value.heading) <= 45f }
                _state.value = _state.value.copy(
                    targets = inCone,
                    allAircraft = all,
                    totalAircraft = result.aircraft.size,
                    apiBlocked = false,
                )
                // Départ/arrivée : requête par avion (icao24), limité aux 8 plus proches.
                // Cache les succès ; retente après 5 min si échec (vol récent peut apparaître).
                val nowMs = System.currentTimeMillis()
                val missing = all.take(8).filter { t ->
                    !routesCache.containsKey(t.icao24) &&
                        (routesAttemptedAt[t.icao24]?.let { nowMs - it > 300_000 } ?: true)
                }
                for (t in missing) {
                    routesAttemptedAt[t.icao24] = nowMs
                    val route = opensky.fetchFlightRoute(t.icao24)
                    if (route != null) routesCache[t.icao24] = route
                }
                if (missing.isNotEmpty()) {
                    val updated = all.map { t ->
                        if (routesCache.containsKey(t.icao24)) {
                            t.copy(
                                originAirport = routesCache[t.icao24]?.first,
                                destinationAirport = routesCache[t.icao24]?.second,
                            )
                        } else t
                    }
                    _state.value = _state.value.copy(
                        targets = updated.filter { angularDiff(it.bearing, _state.value.heading) <= 45f },
                        allAircraft = updated,
                    )
                }
            }
            OpenSkyResult.QuotaExceeded -> {
                _state.value = _state.value.copy(apiBlocked = true)
                delay(60_000)
            }
            is OpenSkyResult.Error -> Unit
        }
    }

    /** Relèvement (0=N, 90=E) depuis le point 1 vers le point 2. */
    fun bearingTo(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
                sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLon)
        val deg = Math.toDegrees(atan2(y, x).toDouble()).toFloat()
        return if (deg < 0) deg + 360f else deg
    }

    /** Différence angulaire minimale entre deux caps [0..180]. */
    fun angularDiff(a: Float, b: Float): Float {
        val d = (a - b) % 360
        return if (d > 180) 360 - d else if (d < -180) d + 360 else d
    }

    /** Élévation de l'avion au-dessus de l'horizon (degrés) : atan(altitude / distance). */
    fun elevationOf(altitudeMeters: Float?, distanceKm: Float): Float {
        if (altitudeMeters == null || distanceKm <= 0f) return 0f
        return Math.toDegrees(Math.atan2(altitudeMeters.toDouble(), distanceKm * 1000.0)).toFloat()
    }

    /** Compare la distance à la précédente → true=rapproche, false=éloigne, null=inconnu. */
    fun trendOf(label: String, dist: Float): Boolean? {
        val prev = lastDistance[label]
        lastDistance[label] = dist
        return prev?.let { dist < it - 0.2f }
    }

    /** Statut dérivé : Stationnement / Décollage / Montée / Croisière / Descente / Atterrissage / Au sol. */
    fun statusOf(ac: com.fabrice.spaceskysea.data.Aircraft): String {
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
}
