package com.fabrice.spaceskysea.data.flight

import com.fabrice.spaceskysea.data.AirlineTable
import com.fabrice.spaceskysea.data.SettingsStore
import com.fabrice.spaceskysea.data.TrackedFlight
import com.fabrice.spaceskysea.data.opensky.OpenSkyResult
import com.fabrice.spaceskysea.data.opensky.OpenSkyRepository
import kotlinx.coroutines.delay

/**
 * Suivi d'un vol nommé (« Air France AF1234 »).
 * Résolution compagnie → callsign ICAO, recherche OpenSky par bounding box
 * élargie (2° → 8°) autour de Paris, puis polling toutes les 20 s.
 */
class FlightRepository(private val settings: SettingsStore) {

    private val opensky = OpenSkyRepository(settings)

    private val parisLat = 48.85
    private val parisLon = 2.35

    /** Recherche un vol par compagnie + numéro. Retourne null si introuvable. */
    suspend fun resolveFlight(company: String, number: String): TrackedFlight? {
        val callsign = AirlineTable.resolveCallsign("$company $number")
            ?: return null
        val bounds = listOf(2.0, 3.0, 5.0, 8.0)
        for (delta in bounds) {
            val bb = com.fabrice.spaceskysea.data.BoundingBox(
                latMin = parisLat - delta,
                lonMin = parisLon - delta,
                latMax = parisLat + delta,
                lonMax = parisLon + delta,
            )
            val url = "https://opensky-network.org/api/states/all" +
                "?lamin=${bb.latMin}&lomin=${bb.lonMin}&lamax=${bb.latMax}&lomax=${bb.lonMax}"
            val result = opensky.fetchBoundingBox(bb.latMin, bb.lonMin, bb.latMax, bb.lonMax)
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
            altitude != null && altitude > 50f -> "En vol"
            onGround -> "Au sol"
            else -> "Position inconnue"
        }
    }
}
