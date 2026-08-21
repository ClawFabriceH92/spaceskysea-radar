package com.fabrice.spaceskysea.data.routes

import com.fabrice.spaceskysea.data.opensky.OpenSkyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Itinéraires (départ → arrivée) avec deux sources :
 * 1. ADSBdb (api.adsbdb.com) — gratuit, sans clé, par indicatif, réponses
 *    « CDG Paris » directement ; source primaire.
 * 2. OpenSky /flights/aircraft — secours (budget serveur très limité).
 *
 * Le marqueur ("LIMIT","LIMIT") n'est renvoyé que si AUCUNE source n'est
 * disponible (les deux en cooldown) ; une réponse définitive « indicatif
 * inconnu » d'ADSBdb donne null, jamais LIMIT.
 */
class RouteProvider(private val opensky: OpenSkyRepository) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var adsbdbCooldownUntilMs = 0L

    /** true si les deux sources sont en cooldown (inutile d'insister). */
    val allSourcesBlocked: Boolean
        get() {
            val now = System.currentTimeMillis()
            return adsbdbCooldownUntilMs > now && opensky.routeCooldownUntilMs > now
        }

    /** Prochain instant où une source redevient disponible (0 = maintenant). */
    val retryAtMs: Long
        get() {
            val now = System.currentTimeMillis()
            if (!allSourcesBlocked) return 0L
            return minOf(adsbdbCooldownUntilMs, opensky.routeCooldownUntilMs)
        }

    suspend fun fetchRoute(callsign: String, icao24: String): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            var adsbdbAnswered = false
            if (callsign.isNotBlank() && System.currentTimeMillis() >= adsbdbCooldownUntilMs) {
                val (route, answered) = queryAdsbdb(callsign)
                adsbdbAnswered = answered
                if (route != null) return@withContext route
            }
            val openskyRoute = opensky.fetchFlightRoute(icao24)
            when {
                openskyRoute != null && openskyRoute.first != "LIMIT" -> openskyRoute
                adsbdbAnswered -> null // réponse définitive : route inconnue
                else -> openskyRoute   // null, ou ("LIMIT","LIMIT") si tout est bloqué
            }
        }

    /** (route, aRépondu) — aRépondu=false en cas d'erreur réseau/429 (cooldown posé). */
    private fun queryAdsbdb(callsign: String): Pair<Pair<String, String>?, Boolean> {
        return try {
            val req = Request.Builder()
                .url("https://api.adsbdb.com/v0/callsign/${callsign.trim().uppercase()}")
                .header("User-Agent", "SpaceSkySeaRadar/1.0")
                .build()
            client.newCall(req).execute().use { resp ->
                when (resp.code) {
                    // 404 = indicatif inconnu : réponse définitive, pas une panne
                    200, 404 -> AdsbdbParser.parseRoute(resp.body?.string() ?: "") to true
                    429 -> {
                        adsbdbCooldownUntilMs = System.currentTimeMillis() + 10 * 60_000L
                        null to false
                    }
                    else -> {
                        adsbdbCooldownUntilMs = System.currentTimeMillis() + 5 * 60_000L
                        null to false
                    }
                }
            }
        } catch (_: Exception) {
            adsbdbCooldownUntilMs = System.currentTimeMillis() + 2 * 60_000L
            null to false
        }
    }
}
