package com.fabrice.spaceskysea.data.opensky

import com.fabrice.spaceskysea.data.Aircraft
import com.fabrice.spaceskysea.data.GeoUtils
import com.fabrice.spaceskysea.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
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

    // Token OAuth2 en cache (Keycloak OpenSky)
    private var cachedToken: String? = null
    private var tokenExpiresAtMs: Long = 0L

    // Cooldown global sur l'API itinéraire (flights/aircraft) après un 429 :
    // ne plus réessayer pendant 10 min pour ne pas marteler le serveur
    private var flightRouteCooldownUntilMs: Long = 0L

    /** Header Authorization : Bearer si credentials OAuth2 configurés. */
    private fun authHeader(): String? {
        if (!settings.hasOpenSkyCredentials) return null
        val token = getToken() ?: return null
        return "Bearer $token"
    }

    /** Obtient (ou rafraîchit) le token OAuth2 via Keycloak OpenSky. */
    private fun getToken(): String? {
        val now = System.currentTimeMillis()
        if (!cachedToken.isNullOrBlank() && now < tokenExpiresAtMs - 60_000) return cachedToken
        return try {
            val form = "grant_type=client_credentials" +
                "&client_id=${settings.openskyClientId}" +
                "&client_secret=${settings.openskyClientSecret}"
            val req = Request.Builder()
                .url("https://auth.opensky-network.org/auth/realms/opensky-network/protocol/openid-connect/token")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .post(okhttp3.RequestBody.create(
                    "application/x-www-form-urlencoded".toMediaType(), form
                ))
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.code != 200) return null
                val body = resp.body?.string() ?: return null
                val obj = json.parseToJsonElement(body).jsonObject
                val token = obj["access_token"]?.jsonPrimitive?.contentOrNull ?: return null
                cachedToken = token
                tokenExpiresAtMs = now + (obj["expires_in"]?.jsonPrimitive?.longOrNull ?: 86400L) * 1000
                token
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun fetchAircrafts(lat: Double, lon: Double, radiusKm: Double): OpenSkyResult {
        val bb = GeoUtils.boundingBox(lat, lon, radiusKm)
        return fetchBoundingBox(bb.latMin, bb.lonMin, bb.latMax, bb.lonMax)
    }

    /** Exécute une requête avec Bearer ; en cas de 401 (token expiré), refetch le token et retente une fois. */
    private suspend fun executeAuth(builder: Request.Builder): okhttp3.Response {
        authHeader()?.let { builder.header("Authorization", it) }
        var resp = client.newCall(builder.build()).execute()
        if (resp.code == 401 && settings.hasOpenSkyCredentials) {
            resp.close()
            cachedToken = null // token expiré/invalidé → force le refetch
            val retryAuth = authHeader()
            if (retryAuth != null) {
                builder.header("Authorization", retryAuth)
                resp = client.newCall(builder.build()).execute()
            }
        }
        return resp
    }

    /** Requête OpenSky sur une bounding box explicite (utilisé aussi par le suivi de vol). */
    suspend fun fetchBoundingBox(
        latMin: Double, lonMin: Double, latMax: Double, lonMax: Double
    ): OpenSkyResult = withContext(Dispatchers.IO) {
        val url = "https://opensky-network.org/api/states/all" +
            "?lamin=$latMin&lomin=$lonMin&lamax=$latMax&lomax=$lonMax"
        val builder = Request.Builder().url(url).header("User-Agent", "SpaceSkySeaRadar/1.0")
        val response = executeAuth(builder)
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

    /** Vérifie que les credentials OpenSky fonctionnent : token + 1 requête de test.
     *  Retourne null si OK, sinon un message d'erreur. */
    suspend fun testCredentials(): String? = withContext(Dispatchers.IO) {
        if (!settings.hasOpenSkyCredentials) {
            return@withContext "Credentials manquants (importez credentials.json)"
        }
        val token = getToken()
        if (token == null) {
            return@withContext "❌ Token refusé : clientId/clientSecret invalides"
        }
        try {
            val req = Request.Builder()
                .url("https://opensky-network.org/api/states/all?lamin=48&lomin=2&lamax=49&lomax=3")
                .header("User-Agent", "SpaceSkySeaRadar/1.0")
                .header("Authorization", "Bearer $token")
                .build()
            client.newCall(req).execute().use { resp ->
                when (resp.code) {
                    200 -> null
                    401, 403 -> "❌ Token refusé par l'API (code ${resp.code})"
                    else -> "❌ Erreur HTTP ${resp.code}"
                }
            }
        } catch (e: Exception) {
            "❌ Erreur réseau : ${e.message}"
        }
    }

    /** Route d'un vol : [origine, destination] (codes IATA 3 lettres, ex. CDG/ORY).
     *  Utilise /api/flights/aircraft (vols d'un avion sur 24 h) — plus fiable que /api/routes. */
    suspend fun fetchFlightRoute(icao24: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        // Cooldown après un 429 : retourne le marqueur LIMIT sans requête
        if (System.currentTimeMillis() < flightRouteCooldownUntilMs) {
            return@withContext "LIMIT" to "LIMIT"
        }
        try {
            val now = System.currentTimeMillis() / 1000
            val begin = now - 3 * 3600 // 3 h suffisent pour le départ (24 h = trop de crédits OpenSky)
            val url = "https://opensky-network.org/api/flights/aircraft" +
                "?icao24=$icao24&begin=$begin&end=$now"
            val builder = Request.Builder().url(url).header("User-Agent", "SpaceSkySeaRadar/1.0")
            val resp = executeAuth(builder)
            try {
                when (resp.code) {
                    200 -> {
                        val body = resp.body?.string() ?: return@withContext null
                        val arr = json.parseToJsonElement(body).jsonArray
                        for (el in arr) {
                            val obj = el.jsonObject
                            val origin = obj["estDepartureAirport"]?.jsonPrimitive?.contentOrNull
                            val dest = obj["estArrivalAirport"]?.jsonPrimitive?.contentOrNull
                            // IMPORTANT : un avion EN VOL a souvent l'arrivée encore vide (None).
                            // On retourne dès que le DÉPART est connu ; l'arrivée s'affiche quand dispo.
                            if (origin != null && origin.length == 3) {
                                return@withContext origin to (dest?.takeIf { it.length == 3 } ?: "?")
                            }
                            if (dest != null && dest.length == 3) {
                                return@withContext "?" to dest
                            }
                        }
                        null
                    }
                    429 -> {
                        flightRouteCooldownUntilMs = System.currentTimeMillis() + 10 * 60_000
                        return@withContext "LIMIT" to "LIMIT" // marqueur : quota/rate limit
                    }
                    else -> null
                }
            } finally {
                resp.close()
            }
        } catch (_: Exception) {
            null
        }
    }

}
