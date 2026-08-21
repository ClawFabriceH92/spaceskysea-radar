package com.fabrice.spaceskysea.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.fabrice.spaceskysea.data.SpeedSmoother
import com.fabrice.spaceskysea.data.UserPosition

/**
 * GPS via LocationManager (aucune dépendance Google Play Services).
 * Vitesse = location.speed (m/s) avec lissage EMA, cap = bearing, vitesse max de session.
 */
class LocationRepository(private val context: Context) {

    private var manager: LocationManager? = null
    private var listener: LocationListener? = null
    private val smoother = SpeedSmoother()
    private var lastFixMs = 0L
    private var maxSpeed = 0f

    var lastPosition: UserPosition? = null
        private set

    fun start(onUpdate: (UserPosition) -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        manager = lm
        smoother.reset()
        maxSpeed = 0f
        // Objet explicite (pas de lambda SAM) : avant l'API 30, les méthodes
        // onStatusChanged/onProvider* n'ont pas d'implémentation par défaut
        // côté appareil et leur absence provoque un AbstractMethodError.
        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                lastFixMs = System.currentTimeMillis()
                val smoothed = smoother.update(location.speed)
                if (smoothed > maxSpeed) maxSpeed = smoothed
                val pos = UserPosition(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    speedMs = smoothed,
                    maxSpeedMs = maxSpeed,
                    bearing = location.bearing,
                    hasFix = true,
                )
                lastPosition = pos
                onUpdate(pos)
            }

            @Deprecated("Deprecated in API 29")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener!!)
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 0f, listener!!)
            }
        } catch (_: SecurityException) {
        }
    }

    fun stop() {
        listener?.let { manager?.removeUpdates(it) }
        listener = null
    }

    fun hasRecentFix(): Boolean = System.currentTimeMillis() - lastFixMs < 10_000
}
