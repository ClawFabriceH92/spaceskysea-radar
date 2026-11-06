package com.fabrice.spaceskysea

import android.app.Application

/**
 * Application déclarée dans le manifeste uniquement pour pouvoir installer le
 * journal de plantage AVANT toute activité : un plantage au démarrage laisse
 * ainsi une trace exploitable.
 */
class SkyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.installer(this)
    }
}
