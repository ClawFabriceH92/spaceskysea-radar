package com.fabrice.spaceskysea.data.opensky

import com.fabrice.spaceskysea.data.Aircraft
import com.fabrice.spaceskysea.data.GeoUtils
import com.fabrice.spaceskysea.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class OpenSkyResult {
    data class Success(val aircraft: List<Aircraft>) : OpenSkyResult()
    data object QuotaExceeded : OpenSkyResult()
    data class Error(val message: String) : OpenSkyResult()
}

/**
 * Repository OpenSky Network (REST).
 * - anonyme par défaut, Basic Auth si les credentials sont configurés
 * - HTTP 429 → QuotaExceeded (pop-up + repli)
 */
class OpenSkyRepository(private val settings: SettingsStore) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchAircrafts(lat: Double, lon: Double, radiusKm: Double): OpenSkyResult {
        val bb = GeoUtils.boundingBox(lat, lon, radiusKm)
        return fetchBoundingBox(bb.latMin, bb.lonMin, bb.latMax, bb.lonMax)
    }

    /** Requête OpenSky sur une bounding box explicite (utilisé aussi par le suivi de vol). */
    suspend fun fetchBoundingBox(
        latMin: Double, lonMin: Double, latMax: Double, lonMax: Double
    ): OpenSkyResult = withContext(Dispatchers.IO) {
        val url = "https://opensky-network.org/api/states/all" +
            "?lamin=$latMin&lomin=$lonMin&lamax=$latMax&lomax=$lonMax"
        val builder = Request.Builder().url(url).header("User-Agent", "SpaceSkySeaRadar/1.0")
        if (settings.hasOpenSkyCredentials) {
            builder.header(
                "Authorization",
                Credentials.basic(settings.openskyUsername, settings.openskyPassword)
            )
        }
        val response = client.newCall(builder.build()).execute()
        try {
            when (response.code) {
                200 -> {
                    val body = response.body?.string() ?: return@withContext OpenSkyResult.Error("Réponse vide")
                    OpenSkyResult.Success(OpenSkyParser.parseStates(body))
                }
                429 -> OpenSkyResult.QuotaExceeded
                else -> OpenSkyResult.Error("Erreur HTTP ${response.code}")
            }
        } catch (e: IOException) {
            OpenSkyResult.Error(e.message ?: "Erreur réseau")
        } finally {
            response.close()
        }
    }

    /** Route d'un vol : [origine, destination] (codes ICAO 4 lettres, ex. LFPG/LIRF). */
    suspend fun fetchRoute(callsign: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        try {
            val url = "https://opensky-network.org/api/routes?callsign=$callsign"
            val builder = Request.Builder().url(url).header("User-Agent", "SpaceSkySeaRadar/1.0")
            if (settings.hasOpenSkyCredentials) {
                builder.header(
                    "Authorization",
                    Credentials.basic(settings.openskyUsername, settings.openskyPassword)
                )
            }
            val resp = client.newCall(builder.build()).execute()
            try {
                if (resp.code != 200) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                val arr = json.parseToJsonElement(body).jsonArray
                if (arr.size >= 2) {
                    val origin = arr[0].jsonPrimitive.contentOrNull
                    val dest = arr[1].jsonPrimitive.contentOrNull
                    if (origin != null && dest != null && origin.length == 4 && dest.length == 4) {
                        return@withContext origin to dest
                    }
                }
                null
            } finally {
                resp.close()
            }
        } catch (_: Exception) {
            null
        }
    }

}
