package com.fabrice.spaceskysea.data.flight

import com.fabrice.spaceskysea.data.AirlineTable
import com.fabrice.spaceskysea.data.SettingsStore
import com.fabrice.spaceskysea.data.TrackedFlight
import com.fabrice.spaceskysea.data.opensky.OpenSkyRepository
import com.fabrice.spaceskysea.data.opensky.OpenSkyResult
import kotlinx.coroutines.delay

/**
 * Suivi d'un vol nommé (« Air France AF1234 »).
 * Résolution compagnie → callsign ICAO, puis recherche OpenSky par bounding
 * box élargie (2° → 8°) autour de la position donnée (défaut : Paris).
 */
class FlightRepository(settings: SettingsStore) {

    private val opensky = OpenSkyRepository(settings)

    /** Recherche un vol par compagnie + numéro. Retourne null si introuvable. */
    suspend fun resolveFlight(
        company: String,
        number: String,
        centerLat: Double = 48.85,
        centerLon: Double = 2.35,
    ): TrackedFlight? {
        val callsign = AirlineTable.resolveCallsign("$company $number")
            ?: return null
        val bounds = listOf(2.0, 3.0, 5.0, 8.0)
        for (delta in bounds) {
            val result = opensky.fetchBoundingBox(
                centerLat - delta, centerLon - delta,
                centerLat + delta, centerLon + delta,
            )
            if (result is OpenSkyResult.Success) {
                val match = result.aircraft.firstOrNull { it.callsign == callsign }
                if (match != null) {
                    return TrackedFlight(
                        callsign = match.callsign,
                        icao24 = match.icao24,
                        latitude = match.latitude,
                        longitude = match.longitude,
                        altitudeMeters = match.altitudeMeters,
                        velocityMs = match.velocityMs,
                        heading = match.heading,
                        status = statusOf(match.onGround, match.altitudeMeters),
                        originAirport = null,
                        destinationAirport = null,
                        etaMinutes = null,
                    )
                }
            }
            delay(500)
        }
        return null
    }

    private fun statusOf(onGround: Boolean, altitude: Float?): String {
        return when {
            onGround -> "Au sol"
            altitude != null && altitude > 50f -> "En vol"
            else -> "Position inconnue"
        }
    }
}
