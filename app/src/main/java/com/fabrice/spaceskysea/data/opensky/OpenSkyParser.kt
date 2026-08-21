package com.fabrice.spaceskysea.data.opensky

import com.fabrice.spaceskysea.data.Aircraft
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Parsing pur de la réponse OpenSky (testable sans Android). */
object OpenSkyParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parseStates(body: String): List<Aircraft> {
        val root = json.parseToJsonElement(body).jsonObject
        val states: JsonArray = root["states"]?.jsonArray ?: return emptyList()
        val out = mutableListOf<Aircraft>()
        for (el: JsonElement in states) {
            if (el is JsonNull) continue
            val arr = el.jsonArray
            if (arr.size < 11) continue
            fun s(i: Int): String? = arr.getOrNull(i)?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
            fun d(i: Int): Double? = arr.getOrNull(i)?.takeIf { it !is JsonNull }?.jsonPrimitive?.doubleOrNull
            fun f(i: Int): Float? = arr.getOrNull(i)?.takeIf { it !is JsonNull }?.jsonPrimitive?.floatOrNull
            fun b(i: Int): Boolean = arr.getOrNull(i)?.takeIf { it !is JsonNull }?.jsonPrimitive?.let {
                it.contentOrNull == "1" || it.toString() == "true"
            } ?: false
            val icao24 = s(0) ?: continue
            out += Aircraft(
                icao24 = icao24,
                callsign = s(1)?.trim() ?: "",
                originCountry = s(2) ?: "",
                latitude = d(6),
                longitude = d(5),
                altitudeMeters = f(7),
                velocityMs = f(9),
                heading = f(10),
                onGround = b(8),
                verticalRateMs = f(11),
                geoAltitudeMeters = f(13),
                squawk = s(14),
                lastContactSec = arr.getOrNull(4)?.takeIf { it !is JsonNull }
                    ?.jsonPrimitive?.longOrNull,
                positionSource = arr.getOrNull(16)?.takeIf { it !is JsonNull }
                    ?.jsonPrimitive?.let {
                        if (it.contentOrNull != null) it.contentOrNull!!.toIntOrNull()
                        else it.toString().toIntOrNull()
                    },
            )
        }
        return out
    }
}
