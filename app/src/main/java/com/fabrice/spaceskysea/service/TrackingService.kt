package com.fabrice.spaceskysea.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.fabrice.spaceskysea.MainActivity
import com.fabrice.spaceskysea.data.location.LocationRepository

/**
 * Foreground Service optionnel (désactivé par défaut) : garde le suivi GPS
 * actif quand l'app est en arrière-plan / écran éteint.
 */
class TrackingService : Service() {

    private var location: LocationRepository? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Android 14+ refuse un service de type "location" sans la permission :
        // on s'arrête proprement plutôt que de planter.
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return
        }
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (location == null) {
            location = LocationRepository(this)
            location?.start {
                // Le suivi continue en arrière-plan ; l'UI reprendra l'état au retour
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        location?.stop()
        super.onDestroy()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Suivi SpaceSkySea",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SpaceSkySea Radar")
            .setContentText("Suivi en arrière-plan actif")
            .setSmallIcon(com.fabrice.spaceskysea.R.drawable.ic_stat_plane)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "spaceskysea_tracking"
        private const val NOTIF_ID = 42

        fun start(context: Context) {
            val intent = Intent(context, TrackingService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackingService::class.java))
        }
    }
}
