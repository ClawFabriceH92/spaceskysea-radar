package com.fabrice.spaceskysea.data.ais

import com.fabrice.spaceskysea.data.Vessel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Parsing pur des messages AISstream (testable sans Android). */
object AisParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parsePositionReport(text: String): Vessel? {
        return try {
            val root: JsonObject = json.parseToJsonElement(text).jsonObject
            if (root["MessageType"]?.jsonPrimitive?.contentOrNull != "PositionReport") return null
            val meta = root["MetaData"]?.jsonObject ?: return null
            val msg = root["Message"]?.jsonObject ?: return null
            val mmsi = meta["MMSI"]?.jsonPrimitive?.longOrNull ?: return null
            val lat = msg["Latitude"]?.jsonPrimitive?.doubleOrNull ?: return null
            val lon = msg["Longitude"]?.jsonPrimitive?.doubleOrNull ?: return null
            Vessel(
                mmsi = mmsi,
                name = meta["ShipName"]?.jsonPrimitive?.contentOrNull ?: "Navire $mmsi",
                latitude = lat,
                longitude = lon,
                speedKnots = msg["SpeedOverGround"]?.jsonPrimitive?.floatOrNull ?: 0f,
                course = msg["CourseOverGround"]?.jsonPrimitive?.floatOrNull ?: 0f,
                type = msg["ShipType"]?.jsonPrimitive?.contentOrNull ?: "",
                destination = msg["Destination"]?.jsonPrimitive?.contentOrNull,
                eta = msg["ETA"]?.jsonPrimitive?.contentOrNull,
            )
        } catch (_: Exception) {
            null
        }
    }
}
