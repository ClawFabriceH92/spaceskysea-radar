package com.fabrice.spaceskysea.data.opensky

import com.fabrice.spaceskysea.data.Aircraft
import com.fabrice.spaceskysea.data.GeoUtils
import com.fabrice.spaceskysea.data.SettingsStore
import kotlinx.serialization.json.Json
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
    ): OpenSkyResult {
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
        return try {
            when (response.code) {
                200 -> {
                    val body = response.body?.string() ?: return OpenSkyResult.Error("Réponse vide")
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

}
