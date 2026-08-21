package com.fabrice.spaceskysea.ui.binoculars

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fabrice.spaceskysea.data.GeoUtils
import com.fabrice.spaceskysea.data.StarCatalog
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Mode Jumelles : boussole + cibles dans le cône de visée.
 * Quatre vues : Horizontal (boussole + liste), Vertical (profil du ciel),
 * Contrôleur (tout le trafic) et Ciel (réalité augmentée caméra).
 */
@Composable
fun BinocularsScreen(modifier: Modifier = Modifier, vm: BinocularsViewModel = viewModel()) {
    val s by vm.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    var mode by rememberSaveable { mutableIntStateOf(0) } // 0=horizontal, 1=vertical, 2=contrôleur, 3=ciel

    // Permission caméra demandée à l'entrée du mode Ciel
    val context = LocalContext.current
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(mode) {
        if (mode == 3 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    var selectedTarget by remember { mutableStateOf<Target?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> vm.onResume()
                Lifecycle.Event.ON_PAUSE -> vm.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.onPause()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Jumelles", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Pointez le téléphone — avions dans votre visée (±45°)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            listOf("Horizontal", "Vertical", "Contrôleur", "Ciel 📷").forEachIndexed { i, label ->
                FilterChip(
                    selected = mode == i,
                    onClick = { mode = i },
                    label = { Text(label) },
                )
            }
        }

        when (mode) {
            0 -> HorizontalView(s)
            1 -> VerticalSkyView(s, onTap = { selectedTarget = it })
            2 -> ControllerView(s)
            else -> SkyView(s, onTap = { selectedTarget = it })
        }

        if (s.apiBlocked) {
            Text(
                "Quota OpenSky dépassé — réessai automatique dans 60 s",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // Fiche détail au tap sur un avion (mode Ciel / Vertical)
        selectedTarget?.let { t ->
            AlertDialog(
                onDismissRequest = { selectedTarget = null },
                title = { Text("✈️ ${t.label}") },
                text = {
                    Column {
                        InfoLine("Statut", t.status.ifEmpty { "En vol" })
                        InfoLine("Pays", t.country.ifEmpty { "?" })
                        InfoLine("Altitude", altitudeText(t) +
                            (t.geoAltitudeMeters?.let { " (GPS ${it.toInt()} m)" } ?: ""))
                        InfoLine("Vitesse", t.speedKmh?.let { "${it.roundToInt()} km/h" } ?: "?")
                        t.headingDeg?.let { InfoLine("Cap avion", "${it.roundToInt()}°") }
                        InfoLine("Direction", "${t.bearing.roundToInt()}° · ${t.distanceKm.roundToInt()} km")
                        InfoLine("Tendance", when {
                            (t.verticalRateMs ?: 0f) > 1f -> "▲ monte"
                            (t.verticalRateMs ?: 0f) < -1f -> "▼ descend"
                            else -> "▶ niveau"
                        })
                        t.approaching?.let { app ->
                            InfoLine("Évolution", if (app) "⟶ se rapproche" else "⟵ s'éloigne")
                        }
                        if (t.originAirport != null || t.destinationAirport != null) {
                            InfoLine("Itinéraire", "${t.originAirport ?: "?"} → ${t.destinationAirport ?: "?"}")
                        }
                        t.squawk?.let { InfoLine("Squawk", it) }
                        InfoLine("ICAO24", t.icao24.uppercase())
                    }
                },
                confirmButton = {
                    TextButton(onClick = { selectedTarget = null }) { Text("Fermer") }
                },
            )
        }
    }
}

@Composable
private fun HorizontalView(s: BinocularsState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Compass(heading = s.heading, modifier = Modifier.size(170.dp).padding(top = 4.dp))
        Text(
            "Cap ${s.heading.roundToInt()}°",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        Text(
            "Cibles : ${s.targets.size} / ${s.totalAircraft} avions détectés",
            style = MaterialTheme.typography.titleSmall,
        )
        if (s.targets.isEmpty()) {
            Text(
                "Aucun avion dans la visée — tournez-vous lentement",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(s.targets, key = { it.icao24 }) { t ->
                    TargetCard(t)
                }
            }
        }
    }
}

@Composable
private fun TargetCard(t: Target) {
    val vr = t.verticalRateMs ?: 0f
    val trend = when {
        vr > 1f -> "▲ monte"
        vr < -1f -> "▼ descend"
        else -> "▶ niveau"
    }
    val trendColor = when {
        vr > 1f -> Color(0xFF2E7D32)
        vr < -1f -> Color(0xFFC62828)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✈️ ${t.label}", fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(
                    t.status.ifEmpty { "En vol" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            InfoLine("Altitude", altitudeText(t))
            InfoLine("Vitesse", t.speedKmh?.let { "${it.roundToInt()} km/h" } ?: "?")
            InfoLine("Direction", "${t.bearing.roundToInt()}° · ${t.distanceKm.roundToInt()} km")
            InfoLine("Pays", t.country.ifEmpty { "?" })
            InfoLine("Tendance", trend, color = trendColor)
            t.approaching?.let { app ->
                InfoLine(
                    "Distance",
                    if (app) "⟶ se rapproche" else "⟵ s'éloigne",
                    color = if (app) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (t.originAirport != null || t.destinationAirport != null) {
                InfoLine("Itinéraire", "${t.originAirport ?: "?"} → ${t.destinationAirport ?: "?"}")
            }
        }
    }
}

/**
 * Mode Contrôleur aérien : TOUT le trafic autour, trié par distance,
 * code couleur par altitude, cap + montée/descente visibles.
 */
@Composable
private fun ControllerView(s: BinocularsState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "🛩️ Trafic aérien — ${s.allAircraft.size} avions dans ${s.maxDistanceKm} km",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        Row(Modifier.padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LegendDot(Color(0xFF2E7D32), "Bas")
            LegendDot(Color(0xFFF57C00), "Moyen")
            LegendDot(Color(0xFFD32F2F), "Haut")
        }
        if (s.allAircraft.isEmpty()) {
            Text(
                "Aucun avion détecté dans le rayon",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(s.allAircraft, key = { it.icao24 }) { t ->
                    ControllerRow(t)
                }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(10.dp)) {
            drawCircle(color)
        }
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ControllerRow(t: Target) {
    val altColor = when {
        (t.altitudeMeters ?: 0f) < 3000f -> Color(0xFF2E7D32)
        (t.altitudeMeters ?: 0f) < 9000f -> Color(0xFFF57C00)
        else -> Color(0xFFD32F2F)
    }
    val trend = when {
        (t.verticalRateMs ?: 0f) > 1f -> "▲ monte"
        (t.verticalRateMs ?: 0f) < -1f -> "▼ descend"
        else -> "▶ niveau"
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(12.dp)) { drawCircle(altColor) }
                Spacer(Modifier.width(8.dp))
                Text(
                    t.label,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    t.status.ifEmpty { "En vol" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            InfoLine("Altitude", altitudeText(t) +
                (t.geoAltitudeMeters?.let { " (GPS ${it.toInt()} m)" } ?: ""))
            InfoLine("Vitesse", t.speedKmh?.let { "${it.roundToInt()} km/h" } ?: "?")
            InfoLine("Direction", "${t.bearing.roundToInt()}° · ${t.distanceKm.roundToInt()} km · $trend")
            InfoLine("Pays", t.country.ifEmpty { "?" })
            t.squawk?.let { InfoLine("Squawk", it) }
            InfoLine("ICAO24", t.icao24.uppercase())
            if (t.originAirport != null || t.destinationAirport != null) {
                InfoLine("✈️ Itinéraire", "${t.originAirport ?: "?"} → ${t.destinationAirport ?: "?"}")
            }
            t.approaching?.let { app ->
                InfoLine(
                    "Distance",
                    if (app) "⟶ se rapproche" else "⟵ s'éloigne",
                    color = if (app) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Altitude affichée : 0 m si l'avion est au sol (stationnement/roulage). */
private fun altitudeText(t: Target): String =
    if (t.status == "Stationnement" || t.status == "Au sol (roulage)") "0 m"
    else "${t.altitudeMeters?.toInt() ?: "?"} m"

@Composable
private fun InfoLine(label: String, value: String, color: Color = Color.Unspecified) {
    Row(Modifier.padding(top = 2.dp)) {
        Text(
            "$label : ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Vue verticale : profil du ciel devant l'utilisateur.
 * X = distance au sol (km), Y = altitude (m). Rend une impression 3D :
 * on voit les avions « posés » dans le ciel au-dessus de la direction visée.
 */
@Composable
private fun VerticalSkyView(s: BinocularsState, onTap: (Target) -> Unit) {
    val maxDist = s.maxDistanceKm.coerceAtLeast(10)
    val maxAlt = 12_000f
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 9.sp, color = Color(0xFF1B468A))

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .pointerInput(s.targets, maxDist) {
                    detectTapGestures { offset ->
                        val margin = 24.dp.toPx()
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val hit = s.targets.minByOrNull { t ->
                            val x = margin + (t.distanceKm / maxDist) * (w - margin * 2)
                            val y = h - 10f - ((t.altitudeMeters ?: 1000f) / maxAlt) * (h - 30f)
                            val dx = x - offset.x
                            val dy = y - offset.y
                            dx * dx + dy * dy
                        }
                        if (hit != null) onTap(hit)
                    }
                }
        ) {
            val w = size.width
            val h = size.height
            val margin = 24.dp.toPx()

            fun xOf(distanceKm: Float) = margin + (distanceKm / maxDist) * (w - margin * 2)
            fun yOf(alt: Float) = h - 10f - (alt / maxAlt) * (h - 30f)

            // Ciel (dégradé)
            drawRect(
                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(Color(0xFFBBDEFB), Color(0xFFE3F2FD))
                ),
                size = size,
            )

            // Lignes d'altitude
            listOf(3000f, 6000f, 9000f, 12000f).forEach { alt ->
                val y = yOf(alt)
                drawLine(
                    Color(0x66FFFFFF), Offset(0f, y), Offset(w, y), strokeWidth = 1f,
                )
                drawText(
                    textMeasurer.measure("${alt.toInt() / 1000} km", labelStyle),
                    topLeft = Offset(2f, y - 8f),
                )
            }

            // Lignes de distance
            val step = if (maxDist <= 25) 5 else 10
            for (d in step..maxDist step step) {
                val x = xOf(d.toFloat())
                drawLine(
                    Color(0x44FFFFFF), Offset(x, 0f), Offset(x, h), strokeWidth = 1f,
                )
                drawText(
                    textMeasurer.measure("${d}km", labelStyle),
                    topLeft = Offset(x - 10f, h - 14f),
                )
            }

            // Ligne du sol
            drawLine(Color(0xFF1B468A), Offset(0f, h - 8f), Offset(w, h - 8f), strokeWidth = 3f)

            // Utilisateur (en bas à gauche)
            drawCircle(Color(0xFF1B468A), radius = 7f, center = Offset(margin, h - 8f))

            // Avions : position (distance, altitude) + taille selon altitude
            s.targets.forEach { t ->
                val x = xOf(t.distanceKm)
                val y = yOf(t.altitudeMeters ?: 1000f)
                val r = when {
                    (t.altitudeMeters ?: 0f) < 3000f -> 8f
                    (t.altitudeMeters ?: 0f) < 9000f -> 11f
                    else -> 14f
                }
                drawCircle(Color(0xFFD32F2F), radius = r, center = Offset(x, y))
                drawCircle(Color.White, radius = 2.5f, center = Offset(x, y))
                drawText(
                    textMeasurer.measure(t.label.take(6), labelStyle),
                    topLeft = Offset(x - 18f, y + 8f),
                )
                // Flèche de montée/descente (▲ / ▼)
                val vr = t.verticalRateMs ?: 0f
                if (vr > 1f) {
                    drawText(
                        textMeasurer.measure("▲", TextStyle(fontSize = 12.sp, color = Color(0xFF2E7D32))),
                        topLeft = Offset(x + 8f, y - 16f),
                    )
                } else if (vr < -1f) {
                    drawText(
                        textMeasurer.measure("▼", TextStyle(fontSize = 12.sp, color = Color(0xFFD32F2F))),
                        topLeft = Offset(x + 8f, y + 6f),
                    )
                }
            }

            if (s.targets.isEmpty()) {
                drawText(
                    textMeasurer.measure("Aucun avion dans la visée", TextStyle(fontSize = 14.sp, color = Color(0xFF1B468A))),
                    topLeft = Offset(w / 2 - 90f, h / 2 - 8f),
                )
            }
        }

        Text(
            "Distance → · Altitude ↑ (rayon ${s.maxDistanceKm} km) — touchez un avion",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Mode Ciel : carte du ciel en réalité augmentée. Levez le téléphone — la
 * CAMÉRA s'affiche en fond, avec les étoiles réelles (position astronomique
 * calculée) et les avions en surimpression, alignés sur la direction visée
 * par la caméra arrière (azimut + élévation).
 */
@Composable
private fun SkyView(s: BinocularsState, onTap: (Target) -> Unit) {
    val textMeasurer = rememberTextMeasurer()
    val starLabel = TextStyle(fontSize = 9.sp, color = Color(0xFFFFF59D))
    val planeLabel = TextStyle(fontSize = 10.sp, color = Color(0xFFFFCDD2))
    val fovAz = 32f   // demi-champ en azimut (≈ caméra grand angle)
    val fovAlt = 42f  // demi-champ en élévation
    var showStars by rememberSaveable { mutableStateOf(true) }
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    // Le gestionnaire de tap vit plus longtemps qu'une recomposition : il doit
    // lire l'état et le zoom COURANTS, pas ceux capturés à sa création.
    val currentS by rememberUpdatedState(s)
    val currentZoom by rememberUpdatedState(zoom)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(400.dp)
                .clipToBounds()
        ) {
            CameraPreview(Modifier.fillMaxSize())
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            // Conversion écran → (azimut, élévation) avec le zoom
                            val st = currentS
                            val fovAzE = fovAz / currentZoom
                            val fovAltE = fovAlt / currentZoom
                            val tapAz = st.cameraAzimuth +
                                (offset.x / size.width - 0.5f) * 2f * fovAzE
                            val tapElev = st.cameraElevation +
                                (0.5f - offset.y / size.height) * 2f * fovAltE
                            // L'avion le plus proche du point touché (< 12°)
                            val hit = st.allAircraft.minByOrNull { t ->
                                val dAz = GeoUtils.angularDiff(t.bearing, tapAz)
                                val dAlt = abs(t.elevationDeg - tapElev)
                                dAz * dAz + dAlt * dAlt
                            }
                            if (hit != null) {
                                val dAz = GeoUtils.angularDiff(hit.bearing, tapAz)
                                val dAlt = abs(hit.elevationDeg - tapElev)
                                if (dAz * dAz + dAlt * dAlt <= 12f * 12f) onTap(hit)
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoomChange, _ ->
                            zoom = (zoom * zoomChange).coerceIn(1f, 5f)
                        }
                    }
            ) {
                val w = size.width
                val h = size.height
                val fovAzE = fovAz / zoom
                val fovAltE = fovAlt / zoom

                // Position écran d'un point du ciel, relative à la visée caméra
                fun xOf(azDeg: Float) =
                    w / 2f + (GeoUtils.signedAngleDelta(azDeg, s.cameraAzimuth) / fovAzE) * (w / 2f)
                fun yOf(elevDeg: Float) =
                    h / 2f - ((elevDeg - s.cameraElevation) / fovAltE) * (h / 2f)
                fun visible(azDeg: Float, elevDeg: Float): Boolean =
                    GeoUtils.angularDiff(azDeg, s.cameraAzimuth) <= fovAzE &&
                        abs(elevDeg - s.cameraElevation) <= fovAltE + 5f

                // Voile sombre semi-transparent pour la lisibilité (la caméra reste visible)
                drawRect(
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color(0x33010A18), Color(0x22010A18))
                    ),
                    size = size,
                )

                // Horizon
                if (abs(s.cameraElevation) <= fovAltE + 2f) {
                    val horizonY = yOf(0f)
                    drawLine(Color(0x6600BFFF), Offset(0f, horizonY), Offset(w, horizonY), strokeWidth = 2f)
                    drawText(
                        textMeasurer.measure("Horizon", TextStyle(fontSize = 8.sp, color = Color(0x9900BFFF))),
                        topLeft = Offset(4f, horizonY + 2f),
                    )
                }

                // Étoiles (option) — position astronomique réelle
                if (showStars) {
                    val now = java.util.Date()
                    val stars = StarCatalog.visibleStars(now, s.position.latitude, s.position.longitude)
                    stars.forEach { (star, pos) ->
                        val az = pos.azimuthDeg.toFloat()
                        val elev = pos.altitudeDeg.toFloat()
                        if (visible(az, elev)) {
                            val x = xOf(az)
                            val y = yOf(elev)
                            val r = (4.5f - star.magnitude.toFloat()).coerceIn(1.5f, 4.5f)
                            drawCircle(Color(0xFFFFEB3B), radius = r, center = Offset(x, y))
                            drawText(textMeasurer.measure(star.name, starLabel), topLeft = Offset(x + 5f, y - 6f))
                        }
                    }
                }

                // Avions dans le ciel (azimut + élévation) — tout le trafic du rayon
                s.allAircraft.forEach { t ->
                    if (visible(t.bearing, t.elevationDeg)) {
                        val x = xOf(t.bearing)
                        val y = yOf(t.elevationDeg)
                        drawCircle(Color(0xFFD32F2F), radius = 6f, center = Offset(x, y))
                        drawCircle(Color.White, radius = 2f, center = Offset(x, y))
                        drawText(textMeasurer.measure(t.label.take(7), planeLabel), topLeft = Offset(x + 7f, y - 7f))
                        val vr = t.verticalRateMs ?: 0f
                        if (vr > 1f) {
                            drawText(textMeasurer.measure("▲", TextStyle(fontSize = 12.sp, color = Color(0xFF69F0AE))), topLeft = Offset(x + 6f, y - 18f))
                        } else if (vr < -1f) {
                            drawText(textMeasurer.measure("▼", TextStyle(fontSize = 12.sp, color = Color(0xFFEF9A9A))), topLeft = Offset(x + 6f, y + 6f))
                        }
                    }
                }
            }
            // Toggle Étoiles en OVERLAY (au-dessus du canvas pour rester cliquable)
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                shape = RoundedCornerShape(20.dp),
                color = Color(0xCCFFFFFF),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("⭐ Étoiles", style = MaterialTheme.typography.bodySmall, color = Color(0xFF1B468A))
                    Spacer(Modifier.width(4.dp))
                    Switch(
                        checked = showStars,
                        onCheckedChange = { showStars = it },
                        modifier = Modifier.scale(0.7f),
                    )
                }
            }
            // Boutons de zoom (+/-)
            Column(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
            ) {
                FloatingActionButton(
                    onClick = { zoom = (zoom * 1.5f).coerceIn(1f, 5f) },
                    modifier = Modifier.size(40.dp),
                    containerColor = Color(0xCCFFFFFF),
                    contentColor = Color(0xFF1B468A),
                ) { Text("+", fontSize = 18.sp) }
                Spacer(Modifier.height(6.dp))
                FloatingActionButton(
                    onClick = { zoom = (zoom / 1.5f).coerceIn(1f, 5f) },
                    modifier = Modifier.size(40.dp),
                    containerColor = Color(0xCCFFFFFF),
                    contentColor = Color(0xFF1B468A),
                ) { Text("−", fontSize = 18.sp) }
            }
        }

        Text(
            "Visée ${s.cameraAzimuth.roundToInt()}° · Élévation ${s.cameraElevation.roundToInt()}° — levez le téléphone",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Caméra + étoiles réelles + ✈️ avions — touchez un avion pour sa fiche",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Aperçu caméra CameraX (fond du mode Ciel), libéré en quittant le mode. */
@Composable
private fun CameraPreview(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(Unit) {
        onDispose {
            // Libère la caméra quand on quitte le mode Ciel (sinon elle reste
            // ouverte tant que l'activité vit : batterie + voyant caméra)
            try {
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            } catch (_: Exception) {
            }
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                try {
                    val provider = future.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview
                    )
                } catch (_: Exception) {
                    // Caméra indisponible : le mode fonctionne sans fond caméra
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

@Composable
private fun Compass(heading: Float, modifier: Modifier = Modifier) {
    val textMeasurer = rememberTextMeasurer()
    val cardinalStyle = TextStyle(
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Bold,
    )
    Canvas(modifier = modifier) {
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        val rad = Math.toRadians((heading - 90f).toDouble())

        drawCircle(Color(0xFF1B468A), radius = r * 0.92f, style = Stroke(width = 3f), center = c)

        // Points cardinaux (fixes : le téléphone tourne, pas la rose)
        listOf("N" to 0f, "E" to 90f, "S" to 180f, "O" to 270f).forEach { (label, deg) ->
            val a = Math.toRadians((deg - 90f).toDouble())
            val measured = textMeasurer.measure(label, cardinalStyle)
            drawText(
                measured,
                topLeft = Offset(
                    c.x + r * 0.99f * cos(a).toFloat() - measured.size.width / 2f,
                    c.y + r * 0.99f * sin(a).toFloat() - measured.size.height / 2f,
                ),
            )
        }

        val tip = Offset(
            c.x + r * 0.70f * cos(rad).toFloat(),
            c.y + r * 0.70f * sin(rad).toFloat(),
        )
        val tail = Offset(
            c.x - r * 0.70f * cos(rad).toFloat(),
            c.y - r * 0.70f * sin(rad).toFloat(),
        )
        drawLine(Color(0xFFD32F2F), tail, tip, strokeWidth = 8f)
        drawCircle(Color(0xFFD32F2F), radius = 6f, center = c)

        val cone1 = Math.toRadians((heading - BinocularsViewModel.CONE_HALF_ANGLE - 90f).toDouble())
        val cone2 = Math.toRadians((heading + BinocularsViewModel.CONE_HALF_ANGLE - 90f).toDouble())
        drawLine(
            Color(0x88F57C00), c,
            Offset(c.x + r * 0.92f * cos(cone1).toFloat(), c.y + r * 0.92f * sin(cone1).toFloat()),
            strokeWidth = 3f,
        )
        drawLine(
            Color(0x88F57C00), c,
            Offset(c.x + r * 0.92f * cos(cone2).toFloat(), c.y + r * 0.92f * sin(cone2).toFloat()),
            strokeWidth = 3f,
        )
    }
}
