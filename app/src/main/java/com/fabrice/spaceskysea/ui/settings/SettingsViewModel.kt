package com.fabrice.spaceskysea.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.fabrice.spaceskysea.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsUiState(
    val aircraftRadius: Int,
    val vesselRadius: Int,
    val refreshMs: Long,
    val speedUnit: String,
    val aircraftLayer: Boolean,
    val vesselLayer: Boolean,
    val openskyUser: String,
    val openskyPass: String,
    val aisstreamKey: String,
    val backgroundTracking: Boolean,
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val store = SettingsStore(application)

    private val _state = MutableStateFlow(readState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private fun readState() = SettingsUiState(
        aircraftRadius = store.aircraftRadiusKm,
        vesselRadius = store.vesselRadiusKm,
        refreshMs = store.refreshMs,
        speedUnit = store.speedUnit,
        aircraftLayer = store.aircraftLayerEnabled,
        vesselLayer = store.vesselLayerEnabled,
        openskyUser = store.openskyUsername,
        openskyPass = store.openskyPassword,
        aisstreamKey = store.aisstreamKey,
        backgroundTracking = store.backgroundTrackingEnabled,
    )

    fun setAircraftRadius(km: Int) { store.aircraftRadiusKm = km; _state.value = readState() }
    fun setVesselRadius(km: Int) { store.vesselRadiusKm = km; _state.value = readState() }
    fun setRefreshMs(ms: Long) { store.refreshMs = ms; _state.value = readState() }
    fun setSpeedUnit(u: String) { store.speedUnit = u; _state.value = readState() }
    fun setAircraftLayer(on: Boolean) { store.aircraftLayerEnabled = on; _state.value = readState() }
    fun setVesselLayer(on: Boolean) { store.vesselLayerEnabled = on; _state.value = readState() }
    fun setOpenSkyUser(v: String) { store.openskyUsername = v; _state.value = readState() }
    fun setOpenSkyPass(v: String) { store.openskyPassword = v; _state.value = readState() }
    fun setAisKey(v: String) { store.aisstreamKey = v; _state.value = readState() }
    fun setBackgroundTracking(on: Boolean) { store.backgroundTrackingEnabled = on; _state.value = readState() }
}
