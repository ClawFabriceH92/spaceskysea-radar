package com.fabrice.spaceskysea.data.routes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parsing pur des réponses ADSBdb (api.adsbdb.com/v0/callsign/{indicatif}).
 * Succès : { "response": { "flightroute": { "origin": {...}, "destination": {...} } } }
 * Indicatif inconnu : { "response": "unknown callsign" } (HTTP 404).
 */
object AdsbdbParser {

    private val json = Json { ignoreUnknownKeys = true }

    /** ("CDG Paris", "KIX Osaka") ou null si la route est inconnue. */
    fun parseRoute(body: String): Pair<String, String>? {
        return try {
            val root = json.parseToJsonElement(body).jsonObject
            val resp = root["response"] as? JsonObject ?: return null
            val fr = resp["flightroute"]?.jsonObject ?: return null
            val origin = airportLabel(fr["origin"]) ?: return null
            val dest = airportLabel(fr["destination"]) ?: return null
            origin to dest
        } catch (_: Exception) {
            null
        }
    }

    /** {iata_code, icao_code, municipality} → "CDG Paris" (ville optionnelle). */
    private fun airportLabel(el: JsonElement?): String? {
        val obj = el as? JsonObject ?: return null
        fun s(key: String) = obj[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val code = s("iata_code").ifBlank { s("icao_code") }
        if (code.isBlank()) return null
        val city = s("municipality")
        return if (city.isBlank()) code else "$code $city"
    }
}
