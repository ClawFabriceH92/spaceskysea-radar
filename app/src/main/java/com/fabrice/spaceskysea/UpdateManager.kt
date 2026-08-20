package com.fabrice.spaceskysea

import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Auto-update via GitHub Releases : au lancement, lit version.json de la
 * release "latest" ; si une version plus récente existe, télécharge l'APK
 * (DownloadManager) et notifie l'utilisateur.
 */
class UpdateManager(private val context: Context) {

    private val repo = "ClawFabriceH92/spaceskysea-radar"
    private val versionUrl = "https://github.com/$repo/releases/latest/download/version.json"

    fun checkForUpdates() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val current = currentVersion()
                val remote = fetchRemoteVersion() ?: return@launch
                if (remote.version != current && remote.version.isNotBlank()) {
                    downloadApk(remote.apkUrl)
                }
            } catch (_: Exception) {
                // Silencieux : l'auto-update ne doit jamais bloquer l'app
            }
        }
    }

    private fun currentVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (e: PackageManager.NameNotFoundException) {
        ""
    }

    private suspend fun fetchRemoteVersion(): RemoteVersion? = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(versionUrl).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val body = resp.body?.string() ?: return@use null
            val json = JSONObject(body)
            RemoteVersion(
                version = json.optString("version"),
                apkUrl = json.optString("apk_url"),
            )
        }
    }

    private fun downloadApk(apkUrl: String) {
        if (apkUrl.isBlank()) return
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("SpaceSkySea Radar — mise à jour disponible")
            .setDescription("Téléchargement de la nouvelle version…")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "spaceskysea-radar-update.apk"
            )
        dm.enqueue(request)
    }

    private data class RemoteVersion(val version: String, val apkUrl: String)
}
