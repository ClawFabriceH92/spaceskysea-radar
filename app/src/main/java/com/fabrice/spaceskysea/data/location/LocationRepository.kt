package com.fabrice.spaceskysea.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
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
    private var lastFixMs = 0L
    private var maxSpeed = 0f

    fun start(onUpdate: (UserPosition) -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        manager = lm
        SpeedSmoother.reset()
        maxSpeed = 0f
        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                lastFixMs = System.currentTimeMillis()
                val smoothed = SpeedSmoother.update(location.speed)
                if (smoothed > maxSpeed) maxSpeed = smoothed
                onUpdate(
                    UserPosition(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        speedKmh = smoothed,
                        maxSpeedKmh = maxSpeed,
                        bearing = location.bearing,
                        hasFix = true,
                    )
                )
            }

            @Deprecated("Deprecated in API 29")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
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
