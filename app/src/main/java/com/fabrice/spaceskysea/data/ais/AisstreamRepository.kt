package com.fabrice.spaceskysea.data.ais

import com.fabrice.spaceskysea.data.GeoUtils
import com.fabrice.spaceskysea.data.SettingsStore
import com.fabrice.spaceskysea.data.Vessel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Repository AISstream.io (WebSocket poussé).
 * - clé requise : sans clé, aucune connexion (bandeau affiché dans l'UI)
 * - reconnexion automatique avec backoff 3s → 30s
 */
class AisstreamRepository(private val settings: SettingsStore) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var onVessels: ((List<Vessel>) -> Unit)? = null
    private var lat = 48.85
    private var lon = 2.35
    private var radiusKm = 25.0
    private var backoffSec = 3L

    private val json = Json { ignoreUnknownKeys = true }

    fun start(lat: Double, lon: Double, radiusKm: Double, onVessels: (List<Vessel>) -> Unit) {
        this.lat = lat
        this.lon = lon
        this.radiusKm = radiusKm
        this.onVessels = onVessels
        if (!settings.hasAisstreamKey) return // bandeau UI : clé manquante
        connect()
    }

    fun updateBoundingBox(lat: Double, lon: Double, radiusKm: Double) {
        this.lat = lat
        this.lon = lon
        this.radiusKm = radiusKm
        sendSubscription()
    }

    private fun connect() {
        val request = Request.Builder()
            .url("wss://stream.aisstream.io/v0/stream")
            .build()
        socket = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
            .newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            backoffSec = 3L
            sendSubscription()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val vessel = AisParser.parsePositionReport(text) ?: return
            onVessels?.invoke(listOf(vessel))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!scope.isActive) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(backoffSec * 1000)
            if (!settings.hasAisstreamKey) return@launch
            backoffSec = (backoffSec * 2).coerceAtMost(30)
            connect()
        }
    }

    private fun sendSubscription() {
        if (!settings.hasAisstreamKey) return
        val bb = GeoUtils.boundingBox(lat, lon, radiusKm)
        val payload = buildString {
            append("{\"APIKey\":\"${settings.aisstreamKey}\",\"BoundingBoxes\":[[[")
            append(bb.latMin).append(',').append(bb.lonMin).append("],[")
            append(bb.latMax).append(',').append(bb.lonMax).append("]]]}")
        }
        socket?.send(payload)
    }

    fun stop() {
        reconnectJob?.cancel()
        socket?.close(1000, "stop")
        socket = null
    }

}
