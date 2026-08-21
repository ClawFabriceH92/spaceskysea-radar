package com.fabrice.spaceskysea

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Résultat d'une vérification manuelle de mise à jour. */
sealed class UpdateCheck {
    data class UpToDate(val current: String) : UpdateCheck()
    data class Downloading(val version: String) : UpdateCheck()
    data class Failed(val message: String) : UpdateCheck()
}

/**
 * Auto-update via GitHub Releases : au lancement, lit version.json de la
 * release "latest" ; si une version STRICTEMENT plus récente existe (et n'a
 * pas déjà été téléchargée), télécharge l'APK (DownloadManager) et notifie.
 * [checkNow] permet une vérification manuelle depuis Paramètres.
 */
class UpdateManager(private val context: Context) {

    private val repo = "ClawFabriceH92/spaceskysea-radar"
    private val versionUrl = "https://github.com/$repo/releases/latest/download/version.json"
    private val prefs = context.getSharedPreferences("spaceskysea_update", Context.MODE_PRIVATE)

    /** Vérification silencieuse au lancement (jamais deux téléchargements). */
    fun checkForUpdates() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val current = currentVersion()
                val remote = fetchRemoteVersion() ?: return@launch
                val alreadyDownloaded = prefs.getString("last_downloaded", null)
                if (VersionUtils.isNewer(remote.version, current) &&
                    VersionUtils.normalize(remote.version) != alreadyDownloaded
                ) {
                    downloadApk(remote.apkUrl, remote.version)
                }
            } catch (_: Exception) {
                // Silencieux : l'auto-update ne doit jamais bloquer l'app
            }
        }
    }

    /**
     * Vérification MANUELLE (bouton Paramètres) : relance le téléchargement
     * même si la version avait déjà été téléchargée, et rend compte.
     */
    suspend fun checkNow(): UpdateCheck = try {
        val current = currentVersion()
        val remote = fetchRemoteVersion()
        when {
            remote == null || remote.version.isBlank() ->
                UpdateCheck.Failed("Impossible de lire la dernière release GitHub")
            VersionUtils.isNewer(remote.version, current) -> {
                downloadApk(remote.apkUrl, remote.version)
                UpdateCheck.Downloading(VersionUtils.normalize(remote.version))
            }
            else -> UpdateCheck.UpToDate(VersionUtils.normalize(current))
        }
    } catch (e: Exception) {
        UpdateCheck.Failed("Erreur réseau : ${e.message ?: "inconnue"}")
    }

    private fun currentVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (_: Exception) {
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

    private fun downloadApk(apkUrl: String, version: String) {
        if (apkUrl.isBlank()) return
        val normalized = VersionUtils.normalize(version)
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("SpaceSkySea Radar — mise à jour $normalized")
            .setDescription("Téléchargement de la nouvelle version…")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "spaceskysea-radar-$normalized.apk"
            )
        dm.enqueue(request)
        prefs.edit().putString("last_downloaded", normalized).apply()
    }

    private data class RemoteVersion(val version: String, val apkUrl: String)
}
