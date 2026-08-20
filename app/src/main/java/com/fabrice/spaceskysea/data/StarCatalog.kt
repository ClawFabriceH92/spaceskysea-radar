package com.fabrice.spaceskysea.data

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Catalogue des étoiles brillantes + calcul de leur position (azimut, altitude)
 * pour une date/heure/lieu donnés (formules d'astronomie de base).
 * Permet la « carte du ciel » des jumelles gyroscopiques.
 */
object StarCatalog {

    data class Star(
        val name: String,
        val raHours: Double,   // ascension droite en heures
        val decDeg: Double,    // déclinaison en degrés
        val magnitude: Double,
    )

    // Les étoiles les plus brillantes (visibles à l'œil nu)
    val STARS: List<Star> = listOf(
        Star("Sirius", 6.7525, -16.7161, -1.46),
        Star("Canopus", 6.3996, -52.6956, -0.74),
        Star("Arcturus", 14.2617, 19.1870, -0.05),
        Star("Vega", 18.6156, 38.7837, 0.03),
        Star("Capella", 5.2783, 45.9980, 0.08),
        Star("Rigel", 5.2423, -8.2016, 0.13),
        Star("Procyon", 7.6550, 5.2250, 0.34),
        Star("Betelgeuse", 5.9195, 7.4071, 0.42),
        Star("Achernar", 1.6291, -57.2367, 0.46),
        Star("Hadar", 14.0643, -60.3730, 0.61),
        Star("Altair", 19.8464, 8.8683, 0.76),
        Star("Aldebaran", 4.5986, 16.5093, 0.86),
        Star("Antares", 16.4901, -26.4320, 1.06),
        Star("Spica", 13.4203, -11.1613, 0.98),
        Star("Pollux", 7.7553, 28.0262, 1.14),
        Star("Fomalhaut", 22.9610, -29.6222, 1.16),
        Star("Deneb", 20.6911, 45.2803, 1.25),
        Star("Regulus", 10.1395, 11.9672, 1.36),
        Star("Castor", 7.5762, 31.8884, 1.58),
        Star("Polaris", 2.5303, 89.2641, 1.98),
        Star("Mizar", 13.3996, 54.9253, 2.04),
        Star("Alcor", 13.4178, 54.9857, 3.99),
        Star("Schedar", 0.6741, 56.5373, 2.24),
        Star("Caph", 0.1583, 59.1495, 2.28),
        Star("Mirach", 1.1706, 35.6206, 2.05),
        Star("Alpheratz", 0.1393, 29.0904, 2.06),
        Star("Markab", 23.0800, 15.2053, 2.49),
        Star("Enif", 21.7381, 9.8750, 2.39),
        Star("Hamal", 2.1193, 23.4624, 2.00),
        Star("Algol", 3.0796, 40.9556, 2.12),
        Star("Mirfak", 3.4058, 49.8610, 1.79),
        Star("Almach", 2.0603, 42.3297, 2.26),
        Star("Alcyone", 3.7885, 24.1050, 2.87),
        Star("Elnath", 5.4346, 28.6074, 1.65),
        Star("Alnilam", 5.6039, -1.2019, 1.69),
        Star("Alnitak", 5.6786, -1.9426, 1.74),
        Star("Mintaka", 5.5321, -0.2991, 2.23),
        Star("Saiph", 5.8071, -9.6696, 2.07),
        Star("Bellatrix", 5.4180, 6.3497, 1.64),
        Star("Adhara", 6.9759, -28.9721, 1.50),
        Star("Sirius B", 6.7525, -16.7161, 8.4),
        Star("Acrux", 12.4435, -63.0991, 0.77),
        Star("Gacrux", 12.5288, -57.1132, 1.64),
        Star("Mimosa", 12.7062, -59.6888, 1.25),
    )

    data class SkyPosition(val azimuthDeg: Double, val altitudeDeg: Double)

    /**
     * Position d'une étoile (azimut 0=N, altitude 0=horizon) pour
     * (date, latitude, longitude). Formules simplifiées (précision ~1°).
     */
    fun position(star: Star, date: java.util.Date, latDeg: Double, lonDeg: Double): SkyPosition {
        val lst = localSiderealTime(date, lonDeg)
        val raRad = Math.toRadians(star.raHours * 15.0)
        val decRad = Math.toRadians(star.decDeg)
        val latRad = Math.toRadians(latDeg)
        val hRad = Math.toRadians(lst * 15.0) - raRad // angle horaire

        val altRad = Math.asin(
            sin(latRad) * sin(decRad) + cos(latRad) * cos(decRad) * cos(hRad)
        )
        val azRad = Math.atan2(
            sin(hRad),
            cos(hRad) * sin(latRad) - Math.tan(decRad) * cos(latRad)
        )
        var az = Math.toDegrees(azRad)
        if (az < 0) az += 360.0
        return SkyPosition(az, Math.toDegrees(altRad))
    }

    /** Temps sidéral local en heures (approximation). */
    fun localSiderealTime(date: java.util.Date, lonDeg: Double): Double {
        val d = date.time / 86400000.0 + 2440587.5 // jour julien
        val t = (d - 2451545.0) / 36525.0
        val gst = 280.46061837 + 360.98564736629 * (d - 2451545.0) +
                0.000387933 * t * t - t * t * t / 38710000.0
        var lst = (gst + lonDeg) % 360.0
        if (lst < 0) lst += 360.0
        return lst / 15.0
    }

    /** Étoiles visibles (au-dessus de l'horizon) pour une position donnée. */
    fun visibleStars(date: java.util.Date, latDeg: Double, lonDeg: Double): List<Pair<Star, SkyPosition>> =
        STARS.mapNotNull { s ->
            val p = position(s, date, latDeg, lonDeg)
            if (p.altitudeDeg > 0.0) s to p else null
        }
}
