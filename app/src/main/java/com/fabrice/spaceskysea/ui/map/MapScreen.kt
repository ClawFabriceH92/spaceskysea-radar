package com.fabrice.spaceskysea.ui.map

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.preference.PreferenceManager
import androidx.core.content.ContextCompat
import com.fabrice.spaceskysea.R
import com.fabrice.spaceskysea.data.Aircraft
import com.fabrice.spaceskysea.data.RadarState
import com.fabrice.spaceskysea.data.SettingsStore
import com.fabrice.spaceskysea.data.UserPosition
import com.fabrice.spaceskysea.data.Vessel
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * Tuiles CartoDB Voyager en 512 px (@2x) : carte NETTE sur écrans haute densité
 * (contrairement aux tuiles OSM standard 256 px qui paraissent pixélisées).
 * Style moderne et lisible, gratuit, sans clé.
 */
private class CartoVoyagerSource : XYTileSource(
    "CartoVoyager", 0, 20, 512, ".png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}{r}.png",
        "https://b.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}{r}.png",
        "https://c.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}{r}.png",
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

@Composable
fun MapScreen(modifier: Modifier = Modifier, vm: MapViewModel = viewModel()) {
    val pos by vm.userPosition.collectAsState()
    val radar by vm.radar.collectAsState()
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var selectedAircraft by remember { mutableStateOf<Aircraft?>(null) }
    var selectedVessel by remember { mutableStateOf<Vessel?>(null) }

    // Cycle de vie osmdroid : onResume/onPause/onDestroy sont OBLIGATOIRES
    // pour que le chargement des tuiles démarre (piège classique).
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
                mv.onResume()
                mapView = mv
                mv
            },
            update = { mv ->
                // Marqueurs avions
                mv.overlays.removeAll { it is Marker || it is Polyline }
                if (settings.aircraftLayerEnabled) {
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
                        m.rotation = ac.heading ?: 0f
                        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        m.icon = drawAircraftBitmap(mv.context, sizePx(sizeFromSpeed(ac.velocityMs)), color)
                        m.title = ac.callsign.ifEmpty { ac.icao24 }
                        m.setOnMarkerClickListener { _, _ ->
                            selectedAircraft = ac
                            selectedVessel = null
                            true
                        }
                        mv.overlays.add(m)
                    }
                }
                if (settings.vesselLayerEnabled) {
                    radar.vessels.forEach { v ->
                        val m = Marker(mv)
                        m.position = GeoPoint(v.latitude, v.longitude)
                        m.rotation = v.course
                        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        m.icon = drawAircraftBitmap(mv.context, sizePx(18), Color(0xFFF57C00))
                        m.title = v.name
                        m.setOnMarkerClickListener { _, _ ->
                            selectedVessel = v
                            selectedAircraft = null
                            true
                        }
                        mv.overlays.add(m)
                    }
                }
                if (pos.hasFix) {
                    val me = Marker(mv)
                    me.position = GeoPoint(pos.latitude, pos.longitude)
                    me.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    me.icon = drawUserDot(mv.context, sizePx(16), Color(0xFF1B468A))
                    mv.overlays.add(me)
                }
            }
        )

        // Indicateur de chargement (sablier) en haut à droite — APRÈS la carte pour être visible
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            color = Color.White.copy(alpha = 0.92f),
        ) {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (radar.loading) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Mise à jour…",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF455A64),
                    )
                } else if (radar.lastUpdateMs > 0L) {
                    Text(
                        "✓",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF1B5E20),
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "À jour",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF1B5E20),
                    )
                }
                radar.rateLimitRemaining?.let { rl ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "· $rl req restantes",
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

        // Pop-up quota API
    if (radar.apiBlocked) {
        AlertDialog(
            onDismissRequest = { vm.dismissApiBlocked() },
            title = { Text("Quota API dépassé") },
            text = { Text("L'application va réessayer automatiquement dans 60 s. Configurez vos clés API dans Paramètres pour augmenter le quota.") },
            confirmButton = {
                TextButton(onClick = { vm.dismissApiBlocked() }) { Text("OK") }
            }
        )
    }

    // Fiche détail avion (pop-up avec indicateur de chargement pendant la requête itinéraire)
    selectedAircraft?.let { ac ->
        val route by vm.selectedRoute.collectAsState()
        val routeLoading by vm.routeLoading.collectAsState()
        LaunchedEffect(ac.icao24) {
            vm.loadAircraftRoute(ac.icao24, ac.callsign)
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { selectedAircraft = null; vm.clearSelectedRoute() },
            title = { Text("✈️ ${ac.callsign.ifEmpty { ac.icao24 }}") },
            text = {
                Column {
                    Text("Pays : ${ac.originCountry.ifEmpty { "?" }}")
                    Text(
                        "Altitude : " + if (ac.onGround == true) "0 m (au sol)"
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
                    Spacer(Modifier.height(10.dp))
                    when {
                        routeLoading -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(10.dp))
                                Text("Recherche de l'itinéraire…", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        route != null && route!!.first == "LIMIT" -> {
                            Text(
                                "⏳ Quota OpenSky atteint (trop de requêtes) — attendez quelques secondes puis réessayez",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFE65100),
                            )
                        }
                        route != null -> {
                            Text(
                                "🛫 Itinéraire : ${route!!.first} → ${route!!.second}",
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            )
                        }
                        else -> {
                            Text(
                                "🛫 Itinéraire : non disponible (importez credentials.json dans Paramètres pour l'activer)",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF78909C),
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
            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.95f)),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("🚢 ${v.name}", style = MaterialTheme.typography.titleMedium)
                Text("MMSI : ${v.mmsi}")
                Text("Type : ${v.type.ifEmpty { "?" }}")
                Text("Vitesse : ${v.speedKnots} nœuds")
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
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "🚀 ${settings.formatSpeed(pos.speedKmh)}",
                color = Color.White, fontSize = 24.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
            Spacer(Modifier.width(12.dp))
            Text("Max ${settings.formatSpeed(pos.maxSpeedKmh)}", color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.width(12.dp))
            Text("Cap ${pos.bearing.toInt()}°", color = Color.White, fontSize = 14.sp)
        }
        Row(Modifier.padding(horizontal = 14.dp, vertical = 0.dp)) {
            Text("✈️ ${radar.aircraftCount}", color = Color(0xFFFFCDD2), fontSize = 13.sp)
            Spacer(Modifier.width(10.dp))
            Text("🚢 ${radar.vesselCount}", color = Color(0xFFFFE0B2), fontSize = 13.sp)
            if (!settings.hasAisstreamKey && settings.vesselLayerEnabled) {
                Spacer(Modifier.width(10.dp))
                Text("⚠️ Clé AISstream manquante (Paramètres)", color = Color(0xFFFFEB3B), fontSize = 12.sp)
            }
        }
    }
}

private fun altitudeSize(altitude: Float?): Int = when {
    altitude == null -> 16
    altitude < 3000f -> 18
    altitude < 6000f -> 26
    altitude < 9000f -> 32
    else -> 40
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
private fun drawUserDot(context: Context, size: Int, color: Color): android.graphics.drawable.Drawable {
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val outer = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    outer.color = color.toArgb()
    val white = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    white.color = android.graphics.Color.WHITE
    val cx = size / 2f
    val r = size / 2f - 1f
    canvas.drawCircle(cx, cx, r + 2f, white)
    canvas.drawCircle(cx, cx, r, outer)
    return android.graphics.drawable.BitmapDrawable(context.resources, bmp)
}

private fun drawAircraftBitmap(context: Context, size: Int, color: Color): android.graphics.drawable.Drawable {
    // Emoji ✈️ (Twemoji) choisi par Fabrice — teinté selon la tendance (bleu monte / rouge descend / gris niveau)
    val drawable = ContextCompat.getDrawable(context, R.drawable.ic_plane)?.mutate()
    drawable?.setTint(color.toArgb())
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    // L'emoji pointe vers le haut-droit : rotation -45° pour viser le haut,
    // puis le Marker applique le cap réel.
    val matrix = android.graphics.Matrix()
    matrix.setRotate(-45f, size / 2f, size / 2f)
    canvas.save()
    canvas.concat(matrix)
    drawable?.setBounds(0, 0, size, size)
    drawable?.draw(canvas)
    canvas.restore()
    return android.graphics.drawable.BitmapDrawable(context.resources, bmp)
}
