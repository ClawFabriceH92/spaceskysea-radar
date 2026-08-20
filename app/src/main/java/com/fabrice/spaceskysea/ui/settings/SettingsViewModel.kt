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
    val openskyClientId: String,
    val openskyClientSecret: String,
    val aisstreamKey: String,
    val backgroundTracking: Boolean,
)
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val store = SettingsStore(application)
    private val opensky = com.fabrice.spaceskysea.data.opensky.OpenSkyRepository(store)

    private val _state = MutableStateFlow(readState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private fun readState() = SettingsUiState(
        aircraftRadius = store.aircraftRadiusKm,
        vesselRadius = store.vesselRadiusKm,
        refreshMs = store.refreshMs,
        speedUnit = store.speedUnit,
        aircraftLayer = store.aircraftLayerEnabled,
        vesselLayer = store.vesselLayerEnabled,
        openskyClientId = store.openskyClientId,
        openskyClientSecret = store.openskyClientSecret,
        aisstreamKey = store.aisstreamKey,
        backgroundTracking = store.backgroundTrackingEnabled,
    )

    fun setAircraftRadius(km: Int) { store.aircraftRadiusKm = km; _state.value = readState() }
    fun setVesselRadius(km: Int) { store.vesselRadiusKm = km; _state.value = readState() }
    fun setRefreshMs(ms: Long) { store.refreshMs = ms; _state.value = readState() }
    fun setSpeedUnit(u: String) { store.speedUnit = u; _state.value = readState() }
    fun setAircraftLayer(on: Boolean) { store.aircraftLayerEnabled = on; _state.value = readState() }
    fun setVesselLayer(on: Boolean) { store.vesselLayerEnabled = on; _state.value = readState() }
    fun setOpenSkyClientId(v: String) { store.openskyClientId = v; _state.value = readState() }
    fun setOpenSkyClientSecret(v: String) { store.openskyClientSecret = v; _state.value = readState() }
    fun setAisKey(v: String) { store.aisstreamKey = v; _state.value = readState() }
    fun setBackgroundTracking(on: Boolean) { store.backgroundTrackingEnabled = on; _state.value = readState() }

    /** Teste la connexion OpenSky (token + requête). Null = OK. */
    suspend fun testOpenSkyConnection(): String? = opensky.testCredentials()
    fun importOpenSkyCredentials(uri: android.net.Uri): String? {
        return try {
            val input = getApplication<Application>().contentResolver.openInputStream(uri) ?: return "Fichier illisible"
            val text = input.bufferedReader().use { it.readText() }
            applyOpenSkyJson(text)
        } catch (e: Exception) {
            "Erreur de lecture : ${e.message}"
        }
    }

    /** Applique le contenu d'un credentials.json (colle ou import). */
    fun applyOpenSkyJson(raw: String): String? {
        return try {
            val text = raw.trim().removePrefix("\uFEFF") // BOM UTF-8 éventuel
            val json = org.json.JSONObject(text)
            val cid = json.optString("clientId").ifBlank { json.optString("client_id") }
            val secret = json.optString("clientSecret").ifBlank { json.optString("client_secret") }
            if (cid.isBlank() || secret.isBlank()) return "Format invalide (clientId/clientSecret manquants)"
            setOpenSkyClientId(cid)
            setOpenSkyClientSecret(secret)
            null // succès
        } catch (e: Exception) {
            "JSON invalide : ${e.message}"
        }
    }
}