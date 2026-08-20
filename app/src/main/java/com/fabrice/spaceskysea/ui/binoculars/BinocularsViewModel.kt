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
)

data class BinocularsState(
    val heading: Float = 0f,          // cap du téléphone (boussole)
    val position: UserPosition = UserPosition(48.85, 2.35, 0f, 0f, 0f, false),
    val targets: List<Target> = emptyList(),
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
                _state.value = _state.value.copy(heading = lastHeading)
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
                val targets = result.aircraft.filter { it.latitude != null && it.longitude != null }
                    .mapNotNull { ac ->
                        val bearing = bearingTo(pos.latitude, pos.longitude, ac.latitude!!, ac.longitude!!)
                        val rel = angularDiff(bearing, _state.value.heading)
                        if (rel <= 45f) {
                            Target(
                                label = ac.callsign.ifEmpty { ac.icao24 },
                                bearing = bearing,
                                distanceKm = GeoUtils.distanceKm(
                                    pos.latitude, pos.longitude, ac.latitude!!, ac.longitude!!
                                ).toFloat(),
                                altitudeMeters = ac.altitudeMeters,
                                speedKmh = ac.velocityMs?.let { it * 3.6f },
                            )
                        } else null
                    }
                    .sortedBy { it.distanceKm }
                _state.value = _state.value.copy(
                    targets = targets,
                    totalAircraft = result.aircraft.size,
                    apiBlocked = false,
                )
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
