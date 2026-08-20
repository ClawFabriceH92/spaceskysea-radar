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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.fabrice.spaceskysea.data.Aircraft
import com.fabrice.spaceskysea.data.RadarState
import com.fabrice.spaceskysea.data.SettingsStore
import com.fabrice.spaceskysea.data.UserPosition
import com.fabrice.spaceskysea.data.Vessel
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

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
                mv.setTileSource(
                    if (settings.useOpenTopoMap) TileSourceFactory.USGS_TOPO
                    else TileSourceFactory.MAPNIK
                )
                mv.controller.setZoom(11.0)
                mv.setMultiTouchControls(true)
                mv.setTilesScaledToDpi(true)
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
                        val m = Marker(mv)
                        m.position = GeoPoint(lat, lon)
                        m.rotation = ac.heading ?: 0f
                        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        m.icon = BitmapDrawable(mv.context.resources, drawAircraftBitmap(
                            sizePx(altitudeSize(ac.altitudeMeters)),
                            Color(0xFFD32F2F)
                        ))
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
                        m.icon = BitmapDrawable(mv.context.resources, drawAircraftBitmap(sizePx(18), Color(0xFFF57C00)))
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
                    me.icon = BitmapDrawable(mv.context.resources, drawAircraftBitmap(sizePx(26), Color(0xFF1B468A)))
                    mv.overlays.add(me)
                }
            }
        )

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

    // Fiche détail avion
    selectedAircraft?.let { ac ->
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
                .fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.95f)),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("✈️ ${ac.callsign.ifEmpty { ac.icao24 }}", style = MaterialTheme.typography.titleMedium)
                Text("Pays : ${ac.originCountry.ifEmpty { "?" }}")
                Text("Altitude : ${ac.altitudeMeters?.let { "${it.toInt()} m" } ?: "?"}")
                Text("Vitesse : ${ac.velocityMs?.let { "${(it * 3.6).toInt()} km/h" } ?: "?"}")
                Text("Cap : ${ac.heading?.toInt() ?: "?"}°")
                TextButton(onClick = { selectedAircraft = null }) { Text("Fermer") }
            }
        }
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
            if (!settings.hasAisstreamKey) {
                Spacer(Modifier.width(10.dp))
                Text("⚠️ Clé AISstream manquante", color = Color(0xFFFFEB3B), fontSize = 12.sp)
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

private fun sizePx(dp: Int): Int = (dp * 2.75f).toInt()

private fun drawAircraftBitmap(size: Int, color: Color): android.graphics.Bitmap {
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    paint.color = color.toArgb()
    val cx = size / 2f
    val r = size / 2f - 1f
    // Avion stylisé vu du dessus, pointant vers le haut (rotation par le Marker)
    val path = android.graphics.Path()
    path.moveTo(cx, r * 0.12f)             // nez
    path.lineTo(cx - r * 0.32f, r * 0.58f) // côté fuselage gauche
    path.lineTo(cx - r * 0.95f, r * 0.72f) // bout aile gauche
    path.lineTo(cx - r * 0.30f, r * 0.62f) // rentrant
    path.lineTo(cx - r * 0.26f, r * 0.95f) // arrière gauche
    path.lineTo(cx, r * 0.82f)             // queue (milieu)
    path.lineTo(cx + r * 0.26f, r * 0.95f) // arrière droite
    path.lineTo(cx + r * 0.30f, r * 0.62f) // rentrant
    path.lineTo(cx + r * 0.95f, r * 0.72f) // bout aile droite
    path.lineTo(cx + r * 0.32f, r * 0.58f) // côté fuselage droit
    path.close()
    canvas.drawPath(path, paint)
    return bmp
}
