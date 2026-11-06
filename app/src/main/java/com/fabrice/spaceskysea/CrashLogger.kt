package com.fabrice.spaceskysea

import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Journal de plantage embarqué.
 *
 * Pourquoi : sans câble USB, un plantage sur le téléphone ne laisse aucune trace
 * exploitable. On installe donc un handler global qui écrit la pile d'appels sur
 * disque, avec les dernières actions de l'utilisateur (« miettes » de pain).
 * L'écran Réglages permet ensuite de partager ce fichier en un geste.
 *
 * Aucune donnée personnelle n'est enregistrée : ni position, ni identifiants,
 * seulement le type d'action et l'écran concerné.
 */
object CrashLogger {

    private const val DOSSIER = "journal"
    private const val FICHIER_PLANTAGE = "dernier_plantage.txt"
    private const val FICHIER_MIETTES = "miettes.txt"
    private const val MAX_MIETTES = 200

    @Volatile
    private var contexte: Context? = null

    @Volatile
    private var installe = false

    /** À appeler en tout premier dans Application.onCreate(). */
    fun installer(context: Context) {
        contexte = context.applicationContext
        if (installe) return
        installe = true
        miette("démarrage v${BuildConfig.VERSION_NAME}")

        val ancien = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { fil, erreur ->
            try {
                ecrirePile(erreur)
            } catch (_: Throwable) {
                // On ne doit jamais masquer le plantage d'origine.
            }
            ancien?.uncaughtException(fil, erreur)
        }
    }

    /** Note une étape récente (écran ouvert, action lancée). */
    fun miette(etape: String) {
        val ctx = contexte ?: return
        try {
            val f = fichier(ctx, FICHIER_MIETTES)
            val horodatage = SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRANCE).format(Date())
            val lignes = (if (f.exists()) f.readLines() else emptyList()) + "$horodatage · $etape"
            f.writeText(lignes.takeLast(MAX_MIETTES).joinToString("\n"))
        } catch (_: Throwable) {
        }
    }

    /** Dernier plantage enregistré, ou null. */
    fun dernierPlantage(context: Context): String? {
        val f = fichier(context, FICHIER_PLANTAGE)
        return if (f.exists() && f.length() > 0) f.readText() else null
    }

    fun effacer(context: Context) {
        fichier(context, FICHIER_PLANTAGE).delete()
        fichier(context, FICHIER_MIETTES).delete()
    }

    /** Intent de partage du journal complet (plantage + miettes). */
    fun intentionPartage(context: Context): Intent {
        val plantage = dernierPlantage(context) ?: "Aucun plantage enregistré."
        val miettes = try {
            fichier(context, FICHIER_MIETTES).takeIf { it.exists() }?.readText().orEmpty()
        } catch (_: Throwable) {
            ""
        }
        val contenu = buildString {
            appendLine("SpaceSkySea Radar v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
            appendLine("Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine()
            appendLine(plantage)
            appendLine()
            appendLine("--- actions récentes ---")
            append(miettes)
        }
        val envoi = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "SpaceSkySea — journal de plantage")
            putExtra(Intent.EXTRA_TEXT, contenu)
        }
        return Intent.createChooser(envoi, "Envoyer le journal")
    }

    private fun ecrirePile(erreur: Throwable) {
        val ctx = contexte ?: return
        val horodatage = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.FRANCE).format(Date())
        val miettes = try {
            fichier(ctx, FICHIER_MIETTES).takeIf { it.exists() }?.readLines()?.takeLast(25)
                ?.joinToString("\n").orEmpty()
        } catch (_: Throwable) {
            ""
        }
        val contenu = buildString {
            appendLine("=== Plantage du $horodatage ===")
            appendLine("Version : ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Appareil : ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine()
            appendLine(erreur.stackTraceToString())
            appendLine()
            appendLine("--- actions récentes ---")
            append(miettes)
        }
        fichier(ctx, FICHIER_PLANTAGE).writeText(contenu)
    }

    private fun fichier(context: Context, nom: String): File {
        val dossier = File(context.filesDir, DOSSIER)
        if (!dossier.exists()) dossier.mkdirs()
        return File(dossier, nom)
    }
}
