package com.fabrice.spaceskysea.data

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.roundToInt

/**
 * Paramètres persistants de l'application.
 * - rayons avions / bateaux (km)
 * - fréquence de rafraîchissement avions (s)
 * - unité de vitesse
 * - couches activées
 * - clés API optionnelles (OpenSky OAuth2, AISstream)
 * - suivi en arrière-plan (Foreground Service), désactivé par défaut
 * - fond de carte (OSM standard / OpenTopoMap)
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("spaceskysea_settings", Context.MODE_PRIVATE)

    companion object {
        const val RADIUS_AIRCRAFT_DEFAULT = 50
        const val RADIUS_VESSEL_DEFAULT = 25
        const val REFRESH_MS_DEFAULT = 25_000L
        const val MAX_RADIUS_KM = 200

        val AIRCRAFT_RADII = listOf(25, 50, 100, 200)
        val VESSEL_RADII = listOf(5, 10, 25, 50)
        val REFRESH_OPTIONS_SECONDS = listOf(15, 25, 30, 60)
        val SPEED_UNITS = listOf("kmh", "knots", "mph")
    }

    var aircraftRadiusKm: Int
        get() = prefs.getInt("aircraft_radius", RADIUS_AIRCRAFT_DEFAULT)
        set(v) = prefs.edit().putInt("aircraft_radius", v.coerceIn(5, MAX_RADIUS_KM)).apply()

    var vesselRadiusKm: Int
        get() = prefs.getInt("vessel_radius", RADIUS_VESSEL_DEFAULT)
        set(v) = prefs.edit().putInt("vessel_radius", v.coerceIn(5, MAX_RADIUS_KM)).apply()

    var refreshMs: Long
        get() = prefs.getLong("refresh_ms", REFRESH_MS_DEFAULT)
        set(v) = prefs.edit().putLong("refresh_ms", v).apply()

    var speedUnit: String
        get() = prefs.getString("speed_unit", "kmh") ?: "kmh"
        set(v) = prefs.edit().putString("speed_unit", v).apply()

    var aircraftLayerEnabled: Boolean
        get() = prefs.getBoolean("layer_aircraft", true)
        set(v) = prefs.edit().putBoolean("layer_aircraft", v).apply()

    var vesselLayerEnabled: Boolean
        get() = prefs.getBoolean("layer_vessel", true)
        set(v) = prefs.edit().putBoolean("layer_vessel", v).apply()

    var openskyClientId: String
        get() = prefs.getString("opensky_cid", "") ?: ""
        set(v) = prefs.edit().putString("opensky_cid", v).apply()

    var openskyClientSecret: String
        get() = prefs.getString("opensky_csecret", "") ?: ""
        set(v) = prefs.edit().putString("opensky_csecret", v).apply()

    var aisstreamKey: String
        get() = prefs.getString("aisstream_key", "") ?: ""
        set(v) = prefs.edit().putString("aisstream_key", v).apply()

    var backgroundTrackingEnabled: Boolean
        get() = prefs.getBoolean("background_tracking", false)
        set(v) = prefs.edit().putBoolean("background_tracking", v).apply()

    val hasOpenSkyCredentials: Boolean
        get() = openskyClientId.isNotBlank() && openskyClientSecret.isNotBlank()

    val hasAisstreamKey: Boolean
        get() = aisstreamKey.isNotBlank()

    /** Convertit une vitesse (m/s) selon l'unité choisie. */
    fun formatSpeed(ms: Float): String {
        val value = when (speedUnit) {
            "knots" -> ms * 1.94384f
            "mph" -> ms * 2.23694f
            else -> ms * 3.6f
        }
        return value.roundToInt().toString()
    }
}
