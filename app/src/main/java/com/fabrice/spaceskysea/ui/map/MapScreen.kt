package com.fabrice.spaceskysea.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.preference.PreferenceManager
import com.fabrice.spaceskysea.R
import com.fabrice.spaceskysea.data.Aircraft
import com.fabrice.spaceskysea.data.RadarState
import com.fabrice.spaceskysea.data.SettingsStore
import com.fabrice.spaceskysea.data.UserPosition
import com.fabrice.spaceskysea.data.Vessel
import com.fabrice.spaceskysea.ui.theme.SkyBlue
import com.fabrice.spaceskysea.ui.theme.VesselOrange
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker

/**
 * Tuiles CartoDB Voyager en 512 px (@2x) : carte NETTE sur écrans haute densité
 * (contrairement aux tuiles OSM standard 256 px qui paraissent pixélisées).
 * Style moderne et lisible, gratuit, sans clé.
 */
private class CartoVoyagerSource : XYTileSource(
    "CartoVoyager", 0, 20, 512, ".png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/rastertiles/voyager/",
        "https://b.basemaps.cartocdn.com/rastertiles/voyager/",
        "https://c.basemaps.cartocdn.com/rastertiles/voyager/",
    ),
    "© OpenStreetMap contributors © CARTO",
) {
    override fun getTileURLString(tile: Long): String {
        val z = MapTileIndex.getZoom(tile)
        val x = MapTileIndex.getX(tile)
        val y = MapTileIndex.getY(tile)
        val sub = "abc"[(x + y) % 3]
        return "https://$sub.basemaps.cartocdn.com/rastertiles/voyager/$z/$x/$y@2x.png"
    }
}

/** État des overlays déjà rendus : évite de reconstruire les marqueurs à chaque frame. */
private class OverlayHolder {
    var aircraft: List<Aircraft>? = null
    var vessels: List<Vessel>? = null
    var aircraftLayer = true
    var vesselLayer = true
    val trafficMarkers = mutableListOf<Marker>()
    var userMarker: Marker? = null
    val iconCache = HashMap<Long, Drawable>()
}

@Composable
fun MapScreen(modifier: Modifier = Modifier, vm: MapViewModel = viewModel()) {
    val pos by vm.userPosition.collectAsState()
    val radar by vm.radar.collectAsState()
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    val holder = remember { OverlayHolder() }

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var selectedAircraft by remember { mutableStateOf<Aircraft?>(null) }
    var selectedVessel by remember { mutableStateOf<Vessel?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            mapView?.onDetach()
        }
    }

    // Recentrage automatique sur la position GPS dès le premier fix
    LaunchedEffect(pos.hasFix) {
        if (pos.hasFix) {
            mapView?.controller?.animateTo(GeoPoint(pos.latitude, pos.longitude), 13.0, 600L)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                Configuration.getInstance().load(
                    ctx,
                    PreferenceManager.getDefaultSharedPreferences(ctx)
                )
                Configuration.getInstance().userAgentValue = "SpaceSkySeaRadar/1.0"
                val mv = MapView(ctx)
                mv.setTileSource(CartoVoyagerSource())
                mv.controller.setZoom(11.0)
                mv.setMultiTouchControls(true)
                mv.overlays.add(CopyrightOverlay(ctx))
                mv.onResume()
                mapView = mv
                mv
            },
            update = { mv ->
                val aircraftLayer = settings.aircraftLayerEnabled
                val vesselLayer = settings.vesselLayerEnabled
                val trafficChanged = holder.aircraft !== radar.aircraft ||
                    holder.vessels !== radar.vessels ||
                    holder.aircraftLayer != aircraftLayer ||
                    holder.vesselLayer != vesselLayer
                if (trafficChanged) {
                    holder.aircraft = radar.aircraft
                    holder.vessels = radar.vessels
                    holder.aircraftLayer = aircraftLayer
                    holder.vesselLayer = vesselLayer
                    mv.overlays.removeAll(holder.trafficMarkers)
                    holder.trafficMarkers.clear()
                    if (aircraftLayer) {
                        radar.aircraft.forEach { ac ->
                            val lat = ac.latitude ?: return@forEach
                            val lon = ac.longitude ?: return@forEach
                            // Couleur = tendance : bleu monte / rouge descend / gris niveau
                            val color = when {
                                (ac.verticalRateMs ?: 0f) > 1f -> Color(0xFF1E88E5)
                                (ac.verticalRateMs ?: 0f) < -1f -> Color(0xFFD32F2F)
                                else -> Color(0xFF616161)
                            }
                            val m = Marker(mv)
                            m.position = GeoPoint(lat, lon)
                            // osmdroid tourne les icônes en sens antihoraire :
                            // le cap (horaire) doit être inversé.
                            m.rotation = -(ac.heading ?: 0f)
                            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            m.icon = holder.aircraftIcon(mv.context, sizePx(sizeFromSpeed(ac.velocityMs)), color)
                            m.title = ac.callsign.ifEmpty { ac.icao24 }
                            m.setOnMarkerClickListener { _, _ ->
                                selectedAircraft = ac
                                selectedVessel = null
                                true
                            }
                            holder.trafficMarkers.add(m)
                        }
                    }
                    if (vesselLayer) {
                        radar.vessels.forEach { v ->
                            val m = Marker(mv)
                            m.position = GeoPoint(v.latitude, v.longitude)
                            m.rotation = -v.course
                            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            m.icon = holder.vesselIcon(mv.context, sizePx(20))
                            m.title = v.name
                            m.setOnMarkerClickListener { _, _ ->
                                selectedVessel = v
                                selectedAircraft = null
                                true
                            }
                            holder.trafficMarkers.add(m)
                        }
                    }
                    mv.overlays.addAll(holder.trafficMarkers)
                    mv.invalidate()
                }
                if (pos.hasFix) {
                    val me = holder.userMarker ?: Marker(mv).also {
                        it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        it.icon = drawUserDot(mv.context, sizePx(16), SkyBlue)
                        it.setOnMarkerClickListener { _, _ -> true }
                        holder.userMarker = it
                        mv.overlays.add(it)
                    }
                    val p = GeoPoint(pos.latitude, pos.longitude)
                    if (me.position != p) {
                        me.position = p
                        mv.invalidate()
                    }
                }
            }
        )

        // Indicateur d'état (mise à jour / à jour / requêtes restantes)
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color.White.copy(alpha = 0.92f),
        ) {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val blocked = radar.blockedUntilMs > System.currentTimeMillis()
                when {
                    blocked -> {
                        Text(
                            "⏳ Quota OpenSky — reprise ${retryDelayText(radar.blockedUntilMs)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFE65100),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    radar.authFailed -> {
                        Text(
                            "⚠️ Clé OpenSky refusée — requêtes anonymes",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFC62828),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    radar.loading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Mise à jour…",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF455A64),
                        )
                    }
                    radar.lastUpdateMs > 0L -> {
                        Text(
                            "✓ À jour",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF1B5E20),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                if (!blocked) radar.rateLimitRemaining?.let { rl ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "· $rl req",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (rl < 100) Color(0xFFE65100) else Color(0xFF546E7A),
                    )
                }
            }
        }

        // Bandeau vitesse
        SpeedBanner(
            pos = pos,
            radar = radar,
            settings = settings,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
        )

        // Recentrer
        FloatingActionButton(
            onClick = {
                mapView?.controller?.animateTo(
                    GeoPoint(pos.latitude, pos.longitude), 13.0, 500L
                )
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
        ) {
            Icon(Icons.Filled.MyLocation, contentDescription = "Recentrer")
        }

        // Fiche détail avion (avec indicateur de chargement pendant la requête itinéraire)
        selectedAircraft?.let { ac ->
            val route by vm.selectedRoute.collectAsState()
            val routeLoading by vm.routeLoading.collectAsState()
            LaunchedEffect(ac.icao24) {
                vm.loadAircraftRoute(ac.icao24)
            }
            AlertDialog(
                onDismissRequest = { selectedAircraft = null; vm.clearSelectedRoute() },
                title = { Text("✈️ ${ac.callsign.ifEmpty { ac.icao24 }}") },
                text = {
                    Column {
                        Text("Pays : ${ac.originCountry.ifEmpty { "?" }}")
                        Text(
                            "Altitude : " + if (ac.onGround) "0 m (au sol)"
                            else "${ac.altitudeMeters?.let { "${it.toInt()} m" } ?: "?"}"
                        )
                        Text("Vitesse : ${ac.velocityMs?.let { "${(it * 3.6).toInt()} km/h" } ?: "?"}")
                        Text("Cap : ${ac.heading?.toInt() ?: "?"}°")
                        Text(
                            "Tendance : " + when {
                                (ac.verticalRateMs ?: 0f) > 1f -> "▲ monte"
                                (ac.verticalRateMs ?: 0f) < -1f -> "▼ descend"
                                else -> "▶ niveau"
                            }
                        )
                        ac.squawk?.let { Text("Squawk : $it") }
                        Spacer(Modifier.height(10.dp))
                        when {
                            routeLoading -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(22.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text("Recherche de l'itinéraire…", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            route?.first == "LIMIT" -> {
                                Text(
                                    "⏳ Limite OpenSky sur l'itinéraire épuisée — le serveur bloque cette API (souvent ~22 h). " +
                                        "Le radar avions continue normalement.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFE65100),
                                )
                            }
                            route != null -> {
                                Text(
                                    "🛫 Itinéraire : ${route!!.first} → ${route!!.second}",
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            else -> {
                                Text(
                                    if (settings.hasOpenSkyCredentials)
                                        "🛫 Itinéraire inconnu pour ce vol (pas d'estimation OpenSky)"
                                    else
                                        "🛫 Itinéraire : non disponible (importez credentials.json dans Paramètres pour l'activer)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { selectedAircraft = null; vm.clearSelectedRoute() }) { Text("Fermer") }
                },
            )
        }

        // Fiche détail navire
        selectedVessel?.let { v ->
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("🚢 ${v.name}", style = MaterialTheme.typography.titleMedium)
                    Text("MMSI : ${v.mmsi}")
                    if (v.type.isNotEmpty()) Text("Type : ${v.type}")
                    Text("Vitesse : ${"%.1f".format(v.speedKnots)} nœuds (${(v.speedKnots * 1.852f).toInt()} km/h)")
                    Text("Cap : ${v.course.toInt()}°")
                    v.destination?.let { Text("Destination : $it") }
                    v.eta?.let { Text("ETA : $it") }
                    TextButton(onClick = { selectedVessel = null }) { Text("Fermer") }
                }
            }
        }
    }
}

@Composable
private fun SpeedBanner(
    pos: UserPosition,
    radar: RadarState,
    settings: SettingsStore,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Color(0xCC1B468A))) {
        Row(
            Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "🚀 ${settings.formatSpeed(pos.speedMs)} ${settings.speedUnitLabel}",
                color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(12.dp))
            Text("Max ${settings.formatSpeed(pos.maxSpeedMs)}", color = Color(0xFFD6E3FF), fontSize = 14.sp)
            Spacer(Modifier.width(12.dp))
            Text("Cap ${pos.bearing.toInt()}°", color = Color(0xFFD6E3FF), fontSize = 14.sp)
        }
        Row(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp, top = 2.dp)) {
            Text("✈️ ${radar.aircraftCount}", color = Color(0xFFFFCDD2), fontSize = 13.sp)
            Spacer(Modifier.width(10.dp))
            Text("🚢 ${radar.vesselCount}", color = Color(0xFFFFE0B2), fontSize = 13.sp)
            if (!settings.hasAisstreamKey && settings.vesselLayerEnabled) {
                Spacer(Modifier.width(10.dp))
                Text("⚠️ Clé AISstream manquante", color = Color(0xFFFFEB3B), fontSize = 12.sp)
            }
        }
    }
}

/** "dans X min" / "dans X h" / "dans <1 min" pour un instant futur. */
private fun retryDelayText(untilMs: Long): String {
    val min = ((untilMs - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
    return when {
        min >= 120 -> "dans ${min / 60} h"
        min >= 1 -> "dans $min min"
        else -> "dans <1 min"
    }
}

/** Taille de l'icône selon la taille de l'avion (approximée par la vitesse). */
private fun sizeFromSpeed(velocityMs: Float?): Int = when {
    velocityMs == null -> 18
    velocityMs < 80f -> 18     // lent (< 290 km/h) : petit appareil
    velocityMs < 200f -> 28    // moyen (290-720 km/h)
    else -> 38                 // rapide (> 720 km/h) : gros appareil
}

private fun sizePx(dp: Int): Int = (dp * 2.75f).toInt()

/** Point bleu pour la position de l'utilisateur (cercle plein + liseré blanc). */
private fun drawUserDot(context: Context, size: Int, color: Color): Drawable {
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bmp)
    val outer = Paint(Paint.ANTI_ALIAS_FLAG)
    outer.color = color.toArgb()
    val white = Paint(Paint.ANTI_ALIAS_FLAG)
    white.color = android.graphics.Color.WHITE
    val cx = size / 2f
    val r = size / 2f - 3f
    canvas.drawCircle(cx, cx, r + 2f, white)
    canvas.drawCircle(cx, cx, r, outer)
    return BitmapDrawable(context.resources, bmp)
}

/** Icône avion teintée, avec cache (clé = taille + couleur). */
private fun OverlayHolder.aircraftIcon(context: Context, size: Int, color: Color): Drawable =
    iconCache.getOrPut(size.toLong() shl 32 or (color.toArgb().toLong() and 0xFFFFFFFFL)) {
        drawAircraftBitmap(context, size, color)
    }

private fun OverlayHolder.vesselIcon(context: Context, size: Int): Drawable =
    iconCache.getOrPut(size.toLong() shl 32 or 1L) { drawVesselBitmap(context, size) }

private fun drawAircraftBitmap(context: Context, size: Int, color: Color): Drawable {
    // Emoji ✈️ (Twemoji) — teinté selon la tendance (bleu monte / rouge descend / gris niveau)
    val drawable = ContextCompat.getDrawable(context, R.drawable.ic_plane)?.mutate()
    drawable?.setTint(color.toArgb())
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bmp)
    // L'emoji pointe vers le haut-droit : rotation -45° pour viser le haut,
    // puis le Marker applique le cap réel.
    val matrix = Matrix()
    matrix.setRotate(-45f, size / 2f, size / 2f)
    canvas.save()
    canvas.concat(matrix)
    drawable?.setBounds(0, 0, size, size)
    drawable?.draw(canvas)
    canvas.restore()
    return BitmapDrawable(context.resources, bmp)
}

/** Silhouette de navire (coque + proue) pointant vers le haut, liseré blanc. */
private fun drawVesselBitmap(context: Context, size: Int): Drawable {
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bmp)
    val w = size.toFloat()
    val h = size.toFloat()
    val path = Path().apply {
        moveTo(w * 0.50f, h * 0.04f)   // proue
        lineTo(w * 0.80f, h * 0.38f)
        lineTo(w * 0.80f, h * 0.90f)   // poupe droite
        lineTo(w * 0.20f, h * 0.90f)   // poupe gauche
        lineTo(w * 0.20f, h * 0.38f)
        close()
    }
    val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = w * 0.10f
        strokeJoin = Paint.Join.ROUND
    }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = VesselOrange.toArgb()
        style = Paint.Style.FILL
    }
    canvas.drawPath(path, outline)
    canvas.drawPath(path, fill)
    return BitmapDrawable(context.resources, bmp)
}
