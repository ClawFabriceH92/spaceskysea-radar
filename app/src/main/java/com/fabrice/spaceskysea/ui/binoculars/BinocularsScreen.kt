package com.fabrice.spaceskysea.ui.binoculars

import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.fabrice.spaceskysea.data.StarCatalog
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Mode Jumelles : boussole + cibles dans le cône de visée.
 * Deux vues : Horizontal (boussole + liste) et Vertical (profil du ciel
 * distance × altitude, rendu 2.5D).
 */
@Composable
fun BinocularsScreen(modifier: Modifier = Modifier, vm: BinocularsViewModel = viewModel()) {
    val s by vm.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    var mode by remember { mutableIntStateOf(0) } // 0 = horizontal, 1 = vertical

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
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Jumelles", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Pointez le téléphone — avions dans votre visée (±45°)",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == 0,
                onClick = { mode = 0 },
                label = { Text("Horizontal") },
            )
            FilterChip(
                selected = mode == 1,
                onClick = { mode = 1 },
                label = { Text("Vertical (ciel)") },
            )
            FilterChip(
                selected = mode == 2,
                onClick = { mode = 2 },
                label = { Text("Contrôleur") },
            )
            FilterChip(
                selected = mode == 3,
                onClick = { mode = 3 },
                label = { Text("Ciel") },
            )
        }

        when (mode) {
            0 -> HorizontalView(s)
            1 -> VerticalSkyView(s)
            2 -> ControllerView(s)
            else -> SkyView(s)
        }

        if (s.apiBlocked) {
            Text(
                "Quota OpenSky dépassé — réessai automatique dans 60 s",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun HorizontalView(s: BinocularsState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Compass(heading = s.heading, modifier = Modifier.size(170.dp))
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
                modifier = Modifier.padding(top = 12.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(s.targets) { t ->
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
        vr < -1f -> Color(0xFFD32F2F)
        else -> Color(0xFF546E7A)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.9f)),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("✈️ ${t.label}", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("${t.bearing.roundToInt()}° · ${t.distanceKm.roundToInt()} km")
        }
        Row(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
            if (t.status.isNotBlank()) {
                Text("${t.status} · ", style = MaterialTheme.typography.bodySmall, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            }
            t.altitudeMeters?.let { Text("Alt ${it.toInt()} m · ", style = MaterialTheme.typography.bodySmall) }
            t.speedKmh?.let { Text("${it.roundToInt()} km/h · ", style = MaterialTheme.typography.bodySmall) }
            if (t.country.isNotBlank()) {
                Text("🌍 ${t.country} · ", style = MaterialTheme.typography.bodySmall)
            }
            Text(trend, style = MaterialTheme.typography.bodySmall, color = trendColor)
            t.approaching?.let { app ->
                if (app) {
                    Text(" · ⟶ se rapproche", style = MaterialTheme.typography.bodySmall, color = Color(0xFF2E7D32))
                } else {
                    Text(" · ⟵ s'éloigne", style = MaterialTheme.typography.bodySmall, color = Color(0xFFE65100))
                }
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
                modifier = Modifier.padding(top = 12.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(s.allAircraft) { t ->
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
        (t.verticalRateMs ?: 0f) > 1f -> "▲"
        (t.verticalRateMs ?: 0f) < -1f -> "▼"
        else -> "▶"
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.92f)),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(Modifier.size(12.dp)) { drawCircle(altColor) }
            Spacer(Modifier.width(8.dp))
            Text(
                "${t.label} $trend",
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${t.altitudeMeters?.let { "${it.toInt()} m" } ?: "?"}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "${t.distanceKm.roundToInt()} km",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp)) {
            Text("Cap ${t.bearing.roundToInt()}° · ", style = MaterialTheme.typography.bodySmall)
            t.speedKmh?.let { Text("${it.roundToInt()} km/h · ", style = MaterialTheme.typography.bodySmall) }
            if (t.country.isNotBlank()) {
                Text("🌍 ${t.country} · ", style = MaterialTheme.typography.bodySmall)
            }
            t.geoAltitudeMeters?.let { Text("GPS ${it.toInt()} m · ", style = MaterialTheme.typography.bodySmall) }
            t.squawk?.let { Text("Squawk $it · ", style = MaterialTheme.typography.bodySmall) }
            Text("${t.icao24.uppercase()}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            (t.verticalRateMs ?: 0f).let { vr ->
                if (vr != 0f) {
                    Text(
                        if (vr > 0) "▲ ${(vr * 196.85f).roundToInt()} ft/min" else "▼ ${(-vr * 196.85f).roundToInt()} ft/min",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (vr > 0) Color(0xFF2E7D32) else Color(0xFFD32F2F),
                    )
                }
            }
        }
    }
}

/**
 * Vue verticale : profil du ciel devant l'utilisateur.
 * X = distance au sol (km), Y = altitude (m). Rend une impression 3D :
 * on voit les avions « posés » dans le ciel au-dessus de la direction visée.
 */
@Composable
private fun VerticalSkyView(s: BinocularsState) {
    val maxDist = s.maxDistanceKm.coerceAtLeast(10)
    val maxAlt = 12_000f
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 9.sp, color = Color(0xFF1B468A))

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
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

            // Cône vertical (limite de la visée)
            drawLine(
                Color(0x44F57C00), Offset(margin, h - 8f),
                Offset(margin + (h - 20f) * 0.9f, 20f), strokeWidth = 2f,
            )

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
            "Distance → · Altitude ↑ (rayon ${s.maxDistanceKm} km)",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF1B468A),
        )
    }
}

/**
 * Mode Ciel : carte du ciel en réalité augmentée. Levez le téléphone — la
 * CAMÉRA s'affiche en fond, avec les étoiles réelles (position astronomique
 * calculée) et les avions en surimpression (gyroscope : cap + inclinaison).
 */
@Composable
private fun SkyView(s: BinocularsState) {
    val textMeasurer = rememberTextMeasurer()
    val starLabel = TextStyle(fontSize = 9.sp, color = Color(0xFFFFF59D))
    val planeLabel = TextStyle(fontSize = 10.sp, color = Color(0xFFFFCDD2))
    val fovAz = 75f   // demi-champ en azimut
    val fovAlt = 55f  // demi-champ en altitude
    var showStars by remember { mutableStateOf(true) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Étoiles", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = showStars,
                onCheckedChange = { showStars = it },
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(400.dp)
        ) {
            CameraPreview(Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                fun xOf(azDeg: Float) = (azDeg - (s.heading - fovAz)) / (2 * fovAz) * w
                fun yOf(altDeg: Float) = (1f - (altDeg - (s.pitchDeg - fovAlt)) / (2 * fovAlt)) * h

                // Voile sombre semi-transparent pour la lisibilité (la caméra reste visible)
                drawRect(
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color(0x33010A18), Color(0x22010A18))
                    ),
                    size = size,
                )

                // Horizon
                val horizonY = yOf(0f)
                drawLine(Color(0x6600BFFF), Offset(0f, horizonY), Offset(w, horizonY), strokeWidth = 2f)
                drawText(textMeasurer.measure("Horizon", TextStyle(fontSize = 8.sp, color = Color(0x9900BFFF))), topLeft = Offset(4f, horizonY + 2f))

                // Étoiles (option) — position astronomique réelle
                if (showStars) {
                    val now = java.util.Date()
                    val stars = StarCatalog.visibleStars(now, s.position.latitude, s.position.longitude)
                    stars.forEach { (star, pos) ->
                        val dx = angularDiff(pos.azimuthDeg.toFloat(), s.heading)
                        val dy = pos.altitudeDeg.toFloat() - s.pitchDeg
                        if (dx <= fovAz && dy > -fovAlt && dy < fovAlt + 20f) {
                            val x = xOf(if (pos.azimuthDeg < 180) s.heading + dx else s.heading - dx)
                            val y = yOf(pos.altitudeDeg.toFloat())
                            val r = (4.5f - star.magnitude.toFloat()).coerceIn(1.5f, 4.5f)
                            drawCircle(Color(0xFFFFEB3B), radius = r, center = Offset(x, y))
                            drawText(textMeasurer.measure(star.name, starLabel), topLeft = Offset(x + 5f, y - 6f))
                        }
                    }
                }

                // Avions dans le ciel (azimut + élévation)
                s.targets.forEach { t ->
                    val dx = angularDiff(t.bearing, s.heading)
                    val dy = t.elevationDeg - s.pitchDeg
                    if (dx <= fovAz && dy > -fovAlt && dy < fovAlt + 20f) {
                        val x = xOf(if (t.bearing < 180) s.heading + dx else s.heading - dx)
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
        }

        Text(
            "Cap ${s.heading.roundToInt()}° · Inclinaison ${s.pitchDeg.roundToInt()}° — levez le téléphone",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF1B468A),
        )
        Text(
            "Caméra + étoiles réelles + ✈️ avions (gyroscope)",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF546E7A),
        )
    }
}

/** Aperçu caméra CameraX (fond du mode Ciel). */
@Composable
private fun CameraPreview(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
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
    Canvas(modifier = modifier) {
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        val rad = Math.toRadians((heading - 90f).toDouble())

        drawCircle(Color(0xFF1B468A), radius = r, style = Stroke(width = 3f))

        val tip = Offset(
            c.x + r * 0.75f * cos(rad).toFloat(),
            c.y + r * 0.75f * sin(rad).toFloat(),
        )
        val tail = Offset(
            c.x - r * 0.75f * cos(rad).toFloat(),
            c.y - r * 0.75f * sin(rad).toFloat(),
        )
        drawLine(Color(0xFFD32F2F), tail, tip, strokeWidth = 8f)
        drawCircle(Color(0xFFD32F2F), radius = 6f, center = c)

        val cone1 = Math.toRadians((heading - 45f - 90f).toDouble())
        val cone2 = Math.toRadians((heading + 45f - 90f).toDouble())
        drawLine(
            Color(0x88F57C00), c,
            Offset(c.x + r * cos(cone1).toFloat(), c.y + r * sin(cone1).toFloat()),
            strokeWidth = 3f,
        )
        drawLine(
            Color(0x88F57C00), c,
            Offset(c.x + r * cos(cone2).toFloat(), c.y + r * sin(cone2).toFloat()),
            strokeWidth = 3f,
        )
    }
}

/** Différence angulaire minimale entre deux caps [0..180]. */
private fun angularDiff(a: Float, b: Float): Float {
    val d = (a - b) % 360
    return if (d > 180) 360 - d else if (d < -180) d + 360 else d
}
