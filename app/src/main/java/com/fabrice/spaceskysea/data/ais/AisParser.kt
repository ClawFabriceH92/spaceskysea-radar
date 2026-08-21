package com.fabrice.spaceskysea.data.ais

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Mise à jour issue d'un message AISstream (testable sans Android). */
sealed class AisUpdate {
    /** Position d'un navire (message PositionReport). */
    data class Position(
        val mmsi: Long,
        val name: String?,
        val latitude: Double,
        val longitude: Double,
        val speedKnots: Float,
        val course: Float,
    ) : AisUpdate()

    /** Fiche statique d'un navire (message ShipStaticData). */
    data class Static(
        val mmsi: Long,
        val name: String?,
        val typeLabel: String,
        val destination: String?,
        val eta: String?,
    ) : AisUpdate()
}

/**
 * Parsing pur des messages AISstream.io. Format réel :
 * { "MessageType": "PositionReport",
 *   "MetaData": { "MMSI": ..., "ShipName": "...", ... },
 *   "Message": { "PositionReport": { "Latitude": ..., "Longitude": ..., "Sog": ..., "Cog": ... } } }
 */
object AisParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): AisUpdate? {
        return try {
            val root: JsonObject = json.parseToJsonElement(text).jsonObject
            val type = root["MessageType"]?.jsonPrimitive?.contentOrNull ?: return null
            val meta = root["MetaData"]?.jsonObject ?: return null
            val mmsi = meta["MMSI"]?.jsonPrimitive?.longOrNull ?: return null
            val name = meta["ShipName"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }
            val message = root["Message"]?.jsonObject ?: return null
            when (type) {
                "PositionReport" -> {
                    val body = message["PositionReport"]?.jsonObject ?: return null
                    AisUpdate.Position(
                        mmsi = mmsi,
                        name = name,
                        latitude = body["Latitude"]?.jsonPrimitive?.doubleOrNull ?: return null,
                        longitude = body["Longitude"]?.jsonPrimitive?.doubleOrNull ?: return null,
                        speedKnots = body["Sog"]?.jsonPrimitive?.floatOrNull ?: 0f,
                        course = body["Cog"]?.jsonPrimitive?.floatOrNull ?: 0f,
                    )
                }
                "ShipStaticData" -> {
                    val body = message["ShipStaticData"]?.jsonObject ?: return null
                    AisUpdate.Static(
                        mmsi = mmsi,
                        name = body["Name"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null } ?: name,
                        typeLabel = shipTypeLabel(body["Type"]?.jsonPrimitive?.intOrNull),
                        destination = body["Destination"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null },
                        eta = body["Eta"]?.jsonObject?.let(::formatEta),
                    )
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** ETA AIS : objet {Month, Day, Hour, Minute} → "21/08 14:30" (champs à 0 = inconnus). */
    private fun formatEta(eta: JsonObject): String? {
        val month = eta["Month"]?.jsonPrimitive?.intOrNull ?: 0
        val day = eta["Day"]?.jsonPrimitive?.intOrNull ?: 0
        val hour = eta["Hour"]?.jsonPrimitive?.intOrNull ?: 24
        val minute = eta["Minute"]?.jsonPrimitive?.intOrNull ?: 60
        if (month !in 1..12 || day !in 1..31) return null
        val time = if (hour in 0..23 && minute in 0..59) {
            " %02d:%02d".format(hour, minute)
        } else ""
        return "%02d/%02d%s".format(day, month, time)
    }

    /** Code type AIS (ITU-R M.1371) → libellé lisible. */
    fun shipTypeLabel(code: Int?): String = when (code) {
        null, 0 -> ""
        30 -> "Pêche"
        31, 32 -> "Remorqueur"
        33 -> "Dragueur"
        35 -> "Militaire"
        36 -> "Voilier"
        37 -> "Plaisance"
        in 40..49 -> "Grande vitesse"
        50 -> "Pilote"
        51 -> "Sauvetage"
        52 -> "Remorqueur"
        53 -> "Ravitailleur portuaire"
        55 -> "Autorités"
        58 -> "Médical"
        in 60..69 -> "Passagers"
        in 70..79 -> "Cargo"
        in 80..89 -> "Pétrolier"
        in 90..99 -> "Autre"
        else -> "Type $code"
    }
}
