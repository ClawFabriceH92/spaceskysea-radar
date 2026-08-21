package com.fabrice.spaceskysea.data

import kotlinx.serialization.Serializable

/** Position de l'utilisateur avec vitesse/cap GPS (vitesses en m/s). */
data class UserPosition(
    val latitude: Double,
    val longitude: Double,
    val speedMs: Float,
    val maxSpeedMs: Float,
    val bearing: Float,
    val hasFix: Boolean,
)

/** Un avion vu par OpenSky (tous les champs disponibles). */
@Serializable
data class Aircraft(
    val icao24: String,
    val callsign: String,
    val originCountry: String,
    val latitude: Double?,
    val longitude: Double?,
    val altitudeMeters: Float?,      // altitude barométrique
    val velocityMs: Float?,
    val heading: Float?,
    val onGround: Boolean,
    val verticalRateMs: Float? = null,
    val geoAltitudeMeters: Float? = null,  // altitude géométrique (GPS)
    val squawk: String? = null,           // code transpondeur (ex: 7000)
    val lastContactSec: Long? = null,     // timestamp Unix (s) du dernier contact
    val positionSource: Int? = null,      // 0=ADS-B, 1=ASTERIX, 2=MLAT
)

/** Un navire vu par AISstream. */
@Serializable
data class Vessel(
    val mmsi: Long,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val speedKnots: Float,
    val course: Float,
    val type: String,
    val destination: String?,
    val eta: String?,
)

/** Un vol suivi (recherche compagnie + numéro). */
data class TrackedFlight(
    val callsign: String,
    val icao24: String?,
    val latitude: Double?,
    val longitude: Double?,
    val altitudeMeters: Float?,
    val velocityMs: Float?,
    val heading: Float?,
    val status: String,
    val originAirport: String?,
    val destinationAirport: String?,
    val etaMinutes: Int?,
)

/** État de la couche radar (avions/bateaux autour de l'utilisateur). */
data class RadarState(
    val aircraft: List<Aircraft> = emptyList(),
    val vessels: List<Vessel> = emptyList(),
    val aircraftCount: Int = 0,
    val vesselCount: Int = 0,
    val lastUpdateMs: Long = 0L,
    val loading: Boolean = false,
    val rateLimitRemaining: Int? = null,
    val blockedUntilMs: Long = 0L,   // quota OpenSky atteint jusqu'à cet instant (0 = OK)
)
