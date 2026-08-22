package com.fabrice.spaceskysea.data

import kotlin.math.asin
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Bounding box (lat/lon min/max) autour d'une position, en km. */
data class BoundingBox(
    val latMin: Double,
    val lonMin: Double,
    val latMax: Double,
    val lonMax: Double,
)

object GeoUtils {

    private const val EARTH_RADIUS_KM = 6371.0

    /** Calcule la bounding box d'un rayon (km) autour de (lat, lon). */
    fun boundingBox(lat: Double, lon: Double, radiusKm: Double): BoundingBox {
        val dLat = radiusKm / 110.574 // degrés latitude (≈ constant)
        val dLon = radiusKm / (111.320 * cos(Math.toRadians(lat)).coerceAtLeast(0.05))
        return BoundingBox(
            latMin = lat - dLat,
            lonMin = lon - dLon,
            latMax = lat + dLat,
            lonMax = lon + dLon,
        )
    }

    /** Distance haversine entre 2 points (km). */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_KM * asin(sqrt(a))
    }

    /** Relèvement (0=N, 90=E) depuis le point 1 vers le point 2. */
    fun bearingTo(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
                sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLon)
        val deg = Math.toDegrees(atan2(y, x)).toFloat()
        return if (deg < 0) deg + 360f else deg
    }

    /** Position atteinte depuis (lat, lon) en suivant [bearingDeg] sur [distanceMeters]. */
    fun offsetPosition(
        lat: Double, lon: Double, bearingDeg: Double, distanceMeters: Double
    ): Pair<Double, Double> {
        val rad = Math.toRadians(bearingDeg)
        val dLat = distanceMeters * cos(rad) / 111_320.0
        val dLon = distanceMeters * sin(rad) /
            (111_320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.05))
        return (lat + dLat) to (lon + dLon)
    }

    /** Écart angulaire signé b→a dans [-180, 180) : négatif = a à gauche de b. */
    fun signedAngleDelta(a: Float, b: Float): Float {
        var d = (a - b) % 360f
        if (d < -180f) d += 360f
        if (d >= 180f) d -= 360f
        return d
    }

    /** Écart angulaire absolu minimal entre deux caps, dans [0, 180]. */
    fun angularDiff(a: Float, b: Float): Float = abs(signedAngleDelta(a, b))
}

/** Lissage exponentiel (EMA) — une instance par flux de mesures. */
class SpeedSmoother(private val alpha: Float = 0.3f) {
    private var current: Float = 0f
    private var initialized = false

    fun reset() {
        current = 0f
        initialized = false
    }

    fun update(raw: Float): Float {
        current = if (!initialized) {
            initialized = true
            raw
        } else {
            alpha * raw + (1f - alpha) * current
        }
        return current
    }
}

/** Table compagnies aériennes → code ICAO + résolution numéro de vol. */
object AirlineTable {

    // Compagnie (recherche insensible à la casse, partielle) → code ICAO
    private val TABLE: Map<String, String> = mapOf(
        "air france" to "AFR", "klm" to "KLM", "lufthansa" to "DLH",
        "ryanair" to "RYR", "easyjet" to "EZY", "transavia" to "TRA",
        "vueling" to "VLG", "iberia" to "IBE", "british airways" to "BAW",
        "swiss" to "SWR", "emirates" to "UAE", "qatar" to "QTR",
        "turkish" to "THY", "american" to "AAL", "delta" to "DAL",
        "united" to "UAL", "air canada" to "ACA", "singapore" to "SIA",
        "cathay" to "CPA", "qantas" to "QFA", "lufthansa city" to "CLH",
        "brussels" to "BEL", "tap" to "TAP", "tap air portugal" to "TAP",
        "alitalia" to "AZA", "ita" to "ITA", "aer lingus" to "EIN",
        "finnair" to "FIN", "sas" to "SAS", "scandinavian" to "SAS",
        "lot" to "LOT", "austrian" to "AUA",
        "wizz" to "WZZ", "jet2" to "EXS", "pegasus" to "PGT",
        "aeroflot" to "AFL", "etihad" to "ETD", "kuwait" to "KAC",
        "air arabia" to "ABY", "norwegian" to "NAX", "icelandair" to "ICE",
        "air india" to "AIC", "japan airlines" to "JAL", "ana" to "ANA",
        "korean" to "KAL", "asiana" to "AAR", "air china" to "CCA",
        "china southern" to "CSN", "china eastern" to "CES", "thai" to "THA",
        "malaysia" to "MAS", "garuda" to "GIA", "ethiopian" to "ETH",
        "kenya" to "KQA", "south african" to "SAA", "air new zealand" to "ANZ",
        "latam" to "LAN", "tam" to "TAM", "avianca" to "AVA",
        "copa" to "CMP", "aeromexico" to "AMX", "westjet" to "WJA",
        "jetblue" to "JBU", "southwest" to "SWA", "spirit" to "NKS",
        "frontier" to "FFT", "alaska" to "ASA", "hawaiian" to "HAL",
        "virgin atlantic" to "VIR", "virgin" to "VIR",
        "corsair" to "CRL", "french bee" to "FBU", "air caraibes" to "FWI",
        "air caraïbes" to "FWI", "air corsica" to "CCM", "air austral" to "REU",
        "volotea" to "VOE", "luxair" to "LGL", "aegean" to "AEE",
        "ba" to "BAW", "af" to "AFR", "lh" to "DLH", "kl" to "KLM", "ey" to "ETD",
    )

    /**
     * Résout « Air France AF1234 » → « AFR1234 ».
     * Retourne null si la compagnie est inconnue. Un code déjà ICAO (3 lettres
     * + numéro, ex. EZY1234) est retourné inchangé.
     */
    fun resolveCallsign(input: String): String? {
        val clean = input.trim().uppercase().replace(Regex("\\s+"), " ")
        if (clean.isEmpty()) return null
        // Déjà un callsign ICAO ? ex. "AFR1234" ou "AF 1234"
        val already = Regex("^([A-Z]{3})\\s*([0-9]{1,4})$").find(clean)
        if (already != null) return "${already.groupValues[1]}${already.groupValues[2]}"

        val m = Regex("^([A-Z ]+?)\\s+([A-Z]{1,3})\\s*([0-9]{1,4})$").find(clean)
            ?: return null
        val company = m.groupValues[1].trim().lowercase()
        val code = m.groupValues[2]
        val number = m.groupValues[3]
        // Le code saisi est déjà ICAO (3 lettres) ?
        if (code.length == 3) return "$code$number"
        // Recherche compagnie (exacte puis partielle)
        TABLE[company]?.let { return "$it$number" }
        for ((key, icao) in TABLE) {
            if (company.contains(key) || key.contains(company)) return "$icao$number"
        }
        return null
    }
}
