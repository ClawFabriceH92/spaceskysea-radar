package com.fabrice.spaceskysea

import com.fabrice.spaceskysea.data.AirlineTable
import com.fabrice.spaceskysea.data.AirportTable
import com.fabrice.spaceskysea.data.GeoUtils
import com.fabrice.spaceskysea.data.SpeedSmoother
import com.fabrice.spaceskysea.data.StarCatalog
import com.fabrice.spaceskysea.data.ais.AisParser
import com.fabrice.spaceskysea.data.ais.AisUpdate
import com.fabrice.spaceskysea.data.opensky.OpenSkyParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundingBoxTest {

    @Test
    fun `bbox 50km autour de Paris donne ~0 45 degre de delta lat`() {
        val bb = GeoUtils.boundingBox(48.85, 2.35, 50.0)
        assertEquals(2 * 50.0 / 110.574, bb.latMax - bb.latMin, 0.02)
        assertTrue(bb.latMin < 48.85 && bb.latMax > 48.85)
        assertTrue(bb.lonMin < 2.35 && bb.lonMax > 2.35)
    }

    @Test
    fun `bbox rayon 0 donne des bornes egales`() {
        val bb = GeoUtils.boundingBox(48.0, 2.0, 0.0)
        assertEquals(48.0, bb.latMin, 1e-9)
        assertEquals(48.0, bb.latMax, 1e-9)
    }

    @Test
    fun `distance haversine Paris-Lyon environ 390 km`() {
        val d = GeoUtils.distanceKm(48.85, 2.35, 45.76, 4.84)
        assertTrue(d in 380.0..410.0)
    }
}

class GeoUtilsAngleTest {

    @Test
    fun `bearingTo vers l'est environ 90 degres`() {
        val b = GeoUtils.bearingTo(48.0, 2.0, 48.0, 3.0)
        assertTrue("attendu ~90, obtenu $b", b in 88f..92f)
    }

    @Test
    fun `bearingTo vers le nord environ 0 degres`() {
        val b = GeoUtils.bearingTo(48.0, 2.0, 49.0, 2.0)
        assertTrue("attendu ~0, obtenu $b", b < 1f || b > 359f)
    }

    @Test
    fun `angularDiff est toujours positif et minimal`() {
        assertEquals(20f, GeoUtils.angularDiff(10f, 350f), 0.01f)
        assertEquals(20f, GeoUtils.angularDiff(350f, 10f), 0.01f)
        assertEquals(180f, GeoUtils.angularDiff(0f, 180f), 0.01f)
        assertEquals(20f, GeoUtils.angularDiff(170f, 190f), 0.01f)
        assertEquals(40f, GeoUtils.angularDiff(10f, 50f), 0.01f) // régression : renvoyait -40
    }

    @Test
    fun `signedAngleDelta donne le sens du plus court chemin`() {
        assertEquals(20f, GeoUtils.signedAngleDelta(10f, 350f), 0.01f)   // 350°→10° = +20 (droite)
        assertEquals(-20f, GeoUtils.signedAngleDelta(350f, 10f), 0.01f)  // 10°→350° = −20 (gauche)
        assertEquals(-180f, GeoUtils.signedAngleDelta(180f, 0f), 0.01f)
    }
}

class SpeedSmootherTest {

    @Test
    fun `EMA alpha 03 lisse les valeurs`() {
        val smoother = SpeedSmoother()
        smoother.update(10f)
        val second = smoother.update(10f)
        assertEquals(10f, second, 0.01f)
        smoother.reset()
        smoother.update(0f)
        val smooth = smoother.update(20f) // 0.3*20 + 0.7*0 = 6
        assertEquals(6f, smooth, 0.01f)
    }

    @Test
    fun `deux instances sont independantes`() {
        val a = SpeedSmoother()
        val b = SpeedSmoother()
        a.update(100f)
        assertEquals(5f, b.update(5f), 0.01f)
    }
}

class AirlineTableTest {

    @Test
    fun `Air France AF1234 devient AFR1234`() {
        assertEquals("AFR1234", AirlineTable.resolveCallsign("Air France AF1234"))
    }

    @Test
    fun `callsign deja ICAO inchange`() {
        assertEquals("EZY1234", AirlineTable.resolveCallsign("easyJet EZY1234"))
        assertEquals("AFR1234", AirlineTable.resolveCallsign("AFR 1234"))
    }

    @Test
    fun `compagnie inconnue retourne null`() {
        assertNull(AirlineTable.resolveCallsign("Zoubida Airlines ZB1234"))
    }

    @Test
    fun `code court accepte`() {
        assertEquals("KLM456", AirlineTable.resolveCallsign("KLM 456"))
    }

    @Test
    fun `compagnies francaises ajoutees`() {
        assertEquals("CRL731", AirlineTable.resolveCallsign("Corsair SS731"))
        assertEquals("FBU720", AirlineTable.resolveCallsign("French Bee BF720"))
    }
}

class OpenSkyParsingTest {

    @Test
    fun `parse states avec nulls`() {
        val body = """{"time":1720000000,"states":[
            ["abc123","AFR1234","France",1720000000,1720000000,2.35,48.85,10000,false,250,90,null,null,null,null,null,null],
            ["def456",null,"Germany",null,null,null,null,null,null,null,null,null,null,null,null,null,null],
            null
        ]}"""
        val aircraft = OpenSkyParser.parseStates(body)
        assertEquals(2, aircraft.size)
        val first = aircraft[0]
        assertEquals("abc123", first.icao24)
        assertEquals("AFR1234", first.callsign)
        assertEquals(48.85, first.latitude!!, 1e-6)
        assertEquals(2.35, first.longitude!!, 1e-6)
        assertEquals(10000f, first.altitudeMeters!!, 0.1f)
        assertEquals(250f, first.velocityMs!!, 0.1f)
        assertEquals(90f, first.heading!!, 0.1f)
        assertFalse(first.onGround)
        val second = aircraft[1]
        assertEquals("", second.callsign)
        assertNull(second.latitude)
    }

    @Test
    fun `parse body vide ou sans states`() {
        assertEquals(0, OpenSkyParser.parseStates("""{"time":0}""").size)
        assertEquals(0, OpenSkyParser.parseStates("""{"time":0,"states":[]}""").size)
    }
}

class AisParsingTest {

    @Test
    fun `parse PositionReport au format reel AISstream`() {
        // Format réel : données imbriquées sous Message.PositionReport, champs Sog/Cog
        val body = """{"Message":{"PositionReport":{"Cog":120.5,"Latitude":48.3,"Longitude":-4.5,"MessageID":1,"Sog":5.2,"TrueHeading":118,"UserID":227006760}},"MessageType":"PositionReport","MetaData":{"MMSI":227006760,"ShipName":"LE PECHEUR ","latitude":48.3,"longitude":-4.5,"time_utc":"2026-08-20 10:00:00"}}"""
        val u = AisParser.parse(body)
        assertNotNull(u)
        val pos = u as AisUpdate.Position
        assertEquals(227006760L, pos.mmsi)
        assertEquals("LE PECHEUR", pos.name)
        assertEquals(48.3, pos.latitude, 1e-6)
        assertEquals(-4.5, pos.longitude, 1e-6)
        assertEquals(5.2f, pos.speedKnots, 0.01f)
        assertEquals(120.5f, pos.course, 0.01f)
    }

    @Test
    fun `parse ShipStaticData enrichit type destination eta`() {
        val body = """{"Message":{"ShipStaticData":{"Destination":"BREST","Eta":{"Month":8,"Day":21,"Hour":8,"Minute":0},"Name":"LE PECHEUR","Type":30}},"MessageType":"ShipStaticData","MetaData":{"MMSI":227006760,"ShipName":"LE PECHEUR"}}"""
        val u = AisParser.parse(body)
        assertNotNull(u)
        val st = u as AisUpdate.Static
        assertEquals(227006760L, st.mmsi)
        assertEquals("Pêche", st.typeLabel)
        assertEquals("BREST", st.destination)
        assertEquals("21/08 08:00", st.eta)
    }

    @Test
    fun `types de navires mappes`() {
        assertEquals("Cargo", AisParser.shipTypeLabel(70))
        assertEquals("Pétrolier", AisParser.shipTypeLabel(84))
        assertEquals("Passagers", AisParser.shipTypeLabel(60))
        assertEquals("Voilier", AisParser.shipTypeLabel(36))
        assertEquals("", AisParser.shipTypeLabel(null))
    }

    @Test
    fun `message non gere ignore`() {
        assertNull(AisParser.parse("""{"MessageType":"HeartBeat"}"""))
        assertNull(AisParser.parse("pas du json"))
        // Ancien format plat (n'existe pas chez AISstream) : rejeté proprement
        assertNull(AisParser.parse("""{"MessageType":"PositionReport","MetaData":{"MMSI":1},"Message":{"Latitude":48.0,"Longitude":2.0}}"""))
    }
}

class FlightRouteParsingTest {

    @Test
    fun `parse route au format reel OpenSky (codes OACI 4 lettres)`() {
        // Régression : les codes OACI (4 lettres) étaient rejetés (filtre == 3 lettres)
        val body = """[
            {"icao24":"3944ef","firstSeen":1755700000,"estDepartureAirport":"LFPG","lastSeen":1755707200,"estArrivalAirport":"EGLL","callsign":"AFR1234"},
            {"icao24":"3944ef","firstSeen":1755760000,"estDepartureAirport":"LFPO","lastSeen":1755767200,"estArrivalAirport":null,"callsign":"AFR5678"}
        ]"""
        val route = OpenSkyParser.parseFlightRoute(body)
        assertNotNull(route)
        // Le vol le plus récent (firstSeen max) est retenu, arrivée inconnue = null
        assertEquals("LFPO", route!!.first)
        assertNull(route.second)
    }

    @Test
    fun `liste vide ou sans aeroports donne null`() {
        assertNull(OpenSkyParser.parseFlightRoute("[]"))
        assertNull(OpenSkyParser.parseFlightRoute("""[{"icao24":"abc","firstSeen":1,"estDepartureAirport":null,"estArrivalAirport":null}]"""))
    }

    @Test
    fun `AirportTable convertit OACI en libelle lisible`() {
        assertEquals("CDG Paris", AirportTable.display("LFPG"))
        assertEquals("ORY Paris-Orly", AirportTable.display("lfpo"))
        assertEquals("JFK New York", AirportTable.display("KJFK"))
        assertEquals("UUWW", AirportTable.display("UUWW")) // inconnu : tel quel
    }
}

class StarCatalogTest {

    @Test
    fun `Polaris est au nord et a une altitude proche de la latitude`() {
        val paris = 48.85 to 2.35
        val polaris = StarCatalog.STARS.first { it.name == "Polaris" }
        // Peu importe l'heure : Polaris reste à ~0,7° du pôle céleste
        val date = java.util.Date(1755772800000L) // 2025-08-21 10:40 UTC
        val pos = StarCatalog.position(polaris, date, paris.first, paris.second)
        assertTrue(
            "azimut attendu ~0° (nord), obtenu ${pos.azimuthDeg}",
            pos.azimuthDeg < 3.0 || pos.azimuthDeg > 357.0
        )
        assertEquals(paris.first, pos.altitudeDeg, 2.0)
    }

    @Test
    fun `une etoile sous l'horizon est exclue de visibleStars`() {
        val date = java.util.Date(1755772800000L)
        val visible = StarCatalog.visibleStars(date, 48.85, 2.35)
        assertTrue(visible.isNotEmpty())
        visible.forEach { (_, pos) -> assertTrue(pos.altitudeDeg > 0.0) }
    }
}

class VersionUtilsTest {

    @Test
    fun `normalize retire le prefixe v et le suffixe`() {
        assertEquals("1.2.3", VersionUtils.normalize("v1.2.3"))
        assertEquals("1.2.3", VersionUtils.normalize("1.2.3-debug"))
        assertEquals("1.2.3", VersionUtils.normalize(" V1.2.3 "))
    }

    @Test
    fun `isNewer compare numeriquement`() {
        assertTrue(VersionUtils.isNewer("v1.2.0", "1.1.7"))
        assertTrue(VersionUtils.isNewer("1.10.0", "1.9.9"))
        assertFalse(VersionUtils.isNewer("v1.1.7", "1.1.7")) // régression : re-téléchargeait à chaque lancement
        assertFalse(VersionUtils.isNewer("1.1.7", "1.2.0"))
        assertFalse(VersionUtils.isNewer("garbage", "1.0.0"))
    }
}
