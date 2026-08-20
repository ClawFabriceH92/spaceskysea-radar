package com.fabrice.spaceskysea

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import com.fabrice.spaceskysea.ui.binoculars.BinocularsScreen
import com.fabrice.spaceskysea.ui.flight.FlightScreen
import com.fabrice.spaceskysea.ui.map.MapScreen
import com.fabrice.spaceskysea.ui.settings.SettingsScreen
import com.fabrice.spaceskysea.ui.settings.SettingsViewModel

class MainActivity : ComponentActivity() {

    private val settingsViewModel: SettingsViewModel by viewModels()

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestLocationPermissionsIfNeeded()
        UpdateManager(this).checkForUpdates()
        setContent {
            SpaceSkySeaApp(settingsViewModel) {
                requestBackgroundLocationIfNeeded()
            }
        }
    }

    private fun requestLocationPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (needed.isNotEmpty()) {
            locationPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    fun requestBackgroundLocationIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        }
    }
}

data class TabItem(val label: String, val icon: ImageVector)

@Composable
fun SpaceSkySeaApp(
    settingsViewModel: SettingsViewModel,
    onRequestBackgroundLocation: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        TabItem("Carte", Icons.Filled.Map),
        TabItem("Jumelles", Icons.Filled.Visibility),
        TabItem("Vol", Icons.Filled.Flight),
        TabItem("Paramètres", Icons.Filled.Settings),
    )
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        when (selectedTab) {
            0 -> MapScreen(Modifier.padding(padding))
            1 -> BinocularsScreen(Modifier.padding(padding))
            2 -> FlightScreen(Modifier.padding(padding))
            3 -> SettingsScreen(
                settingsViewModel,
                Modifier.padding(padding),
                onBackgroundTrackingChanged = { enabled ->
                    if (enabled) {
                        onRequestBackgroundLocation()
                        com.fabrice.spaceskysea.service.TrackingService.start(context)
                    } else {
                        com.fabrice.spaceskysea.service.TrackingService.stop(context)
                    }
                }
            )
        }
    }
}
