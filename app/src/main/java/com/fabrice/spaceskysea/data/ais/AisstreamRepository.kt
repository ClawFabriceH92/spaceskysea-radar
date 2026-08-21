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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
 * - fusionne PositionReport + ShipStaticData par MMSI et pousse la liste
 *   complète des navires vus récemment (purge après 15 min sans nouvelle)
 */
class AisstreamRepository(private val settings: SettingsStore) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var onVessels: ((List<Vessel>) -> Unit)? = null
    private var lat = 48.85
    private var lon = 2.35
    private var radiusKm = 25.0
    private var backoffSec = 3L

    @Volatile
    private var stopped = false
    @Volatile
    var connected = false
        private set

    // MMSI → (navire fusionné, dernier message en ms)
    private val vessels = LinkedHashMap<Long, Pair<Vessel, Long>>()

    fun start(lat: Double, lon: Double, radiusKm: Double, onVessels: (List<Vessel>) -> Unit) {
        this.lat = lat
        this.lon = lon
        this.radiusKm = radiusKm
        this.onVessels = onVessels
        stopped = false
        if (!settings.hasAisstreamKey) return // bandeau UI : clé manquante
        if (socket == null) connect()
    }

    /** (Re)connecte si une clé est disponible et qu'aucune socket n'est ouverte. */
    fun ensureConnected(lat: Double, lon: Double, radiusKm: Double) {
        if (stopped || !settings.hasAisstreamKey) return
        val moved = GeoUtils.distanceKm(this.lat, this.lon, lat, lon) > radiusKm * 0.25
        val radiusChanged = radiusKm != this.radiusKm
        this.lat = lat
        this.lon = lon
        this.radiusKm = radiusKm
        when {
            socket == null -> connect()
            moved || radiusChanged -> sendSubscription()
        }
    }

    private fun connect() {
        val request = Request.Builder()
            .url("wss://stream.aisstream.io/v0/stream")
            .build()
        socket = client.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            backoffSec = 3L
            connected = true
            sendSubscription()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val update = AisParser.parse(text) ?: return
            val list = synchronized(vessels) {
                merge(update)
                prune()
                vessels.values.map { it.first }
            }
            onVessels?.invoke(list)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            connected = false
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            connected = false
            scheduleReconnect()
        }
    }

    private fun merge(update: AisUpdate) {
        val now = System.currentTimeMillis()
        when (update) {
            is AisUpdate.Position -> {
                val prev = vessels[update.mmsi]?.first
                vessels[update.mmsi] = Vessel(
                    mmsi = update.mmsi,
                    name = update.name ?: prev?.name ?: "Navire ${update.mmsi}",
                    latitude = update.latitude,
                    longitude = update.longitude,
                    speedKnots = update.speedKnots,
                    course = update.course,
                    type = prev?.type ?: "",
                    destination = prev?.destination,
                    eta = prev?.eta,
                ) to now
            }
            is AisUpdate.Static -> {
                // Pas de position dans ce message : enrichit un navire déjà vu
                val prev = vessels[update.mmsi] ?: return
                vessels[update.mmsi] = prev.first.copy(
                    name = update.name ?: prev.first.name,
                    type = update.typeLabel.ifBlank { prev.first.type },
                    destination = update.destination ?: prev.first.destination,
                    eta = update.eta ?: prev.first.eta,
                ) to prev.second
            }
        }
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - 15 * 60_000L
        vessels.entries.removeAll { (_, value) -> value.second < cutoff }
    }

    private fun scheduleReconnect() {
        if (stopped || !scope.isActive) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(backoffSec * 1000)
            if (stopped || !settings.hasAisstreamKey) return@launch
            backoffSec = (backoffSec * 2).coerceAtMost(30)
            connect()
        }
    }

    private fun sendSubscription() {
        if (!settings.hasAisstreamKey) return
        val bb = GeoUtils.boundingBox(lat, lon, radiusKm)
        val payload = buildJsonObject {
            put("APIKey", settings.aisstreamKey)
            put("BoundingBoxes", buildJsonArray {
                addJsonArray {
                    addJsonArray { add(bb.latMin); add(bb.lonMin) }
                    addJsonArray { add(bb.latMax); add(bb.lonMax) }
                }
            })
            put("FilterMessageTypes", buildJsonArray {
                add("PositionReport")
                add("ShipStaticData")
            })
        }
        socket?.send(payload.toString())
    }

    fun stop() {
        stopped = true
        reconnectJob?.cancel()
        socket?.close(1000, "stop")
        socket = null
        connected = false
    }
}
