package com.fabrice.spaceskysea

import com.fabrice.spaceskysea.data.AirlineTable
import com.fabrice.spaceskysea.data.GeoUtils
import com.fabrice.spaceskysea.data.SpeedSmoother
import com.fabrice.spaceskysea.data.ais.AisParser
import com.fabrice.spaceskysea.data.opensky.OpenSkyParser
import org.junit.Assert.assertEquals
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

class SpeedSmootherTest {

    @Test
    fun `EMA alpha 03 lisse les valeurs`() {
        SpeedSmoother.reset()
        SpeedSmoother.update(10f)
        val second = SpeedSmoother.update(10f)
        assertEquals(10f, second, 0.01f)
        SpeedSmoother.reset()
        SpeedSmoother.update(0f)
        val smooth = SpeedSmoother.update(20f) // 0.3*20 + 0.7*0 = 6
        assertEquals(6f, smooth, 0.01f)
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
    fun `parse PositionReport`() {
        val body = """{"MetaData":{"MMSI":227006760,"ShipName":"LE PECHEUR","time_utc":"2026-08-20 10:00:00"},"MessageType":"PositionReport","Message":{"ShipType":"Fishing","CourseOverGround":120.5,"SpeedOverGround":5.2,"Latitude":48.3,"Longitude":-4.5,"Destination":"BREST","ETA":"2026-08-21 08:00"}}"""
        val v = AisParser.parsePositionReport(body)
        assertNotNull(v)
        assertEquals(227006760L, v!!.mmsi)
        assertEquals("LE PECHEUR", v.name)
        assertEquals(48.3, v.latitude, 1e-6)
        assertEquals(-4.5, v.longitude, 1e-6)
        assertEquals(5.2f, v.speedKnots, 0.01f)
        assertEquals("BREST", v.destination)
    }

    @Test
    fun `message non PositionReport ignore`() {
        assertNull(AisParser.parsePositionReport("""{"MessageType":"HeartBeat"}"""))
        assertNull(AisParser.parsePositionReport("pas du json"))
    }
}
