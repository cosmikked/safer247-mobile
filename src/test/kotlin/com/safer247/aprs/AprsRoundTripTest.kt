package org.example.com.safer247.aprs

import org.example.com.safer247.aprs.model.AprsPacket
import org.example.com.safer247.aprs.protocol.GeoPoint
import org.example.com.safer247.aprs.protocol.IncidentType
import org.example.com.safer247.aprs.protocol.Payload
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AprsRoundTripTest {

    private val tx = AprsTx()
    private val rx = AprsRx()
    private val fixedTime = Instant.parse("2026-11-24T06:43:00Z")
    private val bukidnon = GeoPoint(latitude = 7.9186, longitude = 125.3286)

    @Test
    fun `round-trip location beacon with GPS`() {
        val payload = Payload.Beacon(sender = "Responder Alpha")
        val encoded = tx.encodePacket(payload, location = bukidnon, timestamp = fixedTime)

        val decoded = assertIs<AprsPacket.Safer247>(rx.decodeAx25(encoded.ax25Bytes))
        assertEquals(payload, decoded.payload)
        assertEquals("064300h", decoded.timestamp)
        assertEquals("/>", decoded.symbol)
        assertEquals(encoded.tnc2, decoded.tnc2)
        assertLocationNear(bukidnon, decoded.location!!)
    }

    @Test
    fun `round-trip quick message with GPS and no-GPS fallback`() {
        val payload = Payload.QuickMessage(sender = "Command Center", message = "Ambulance en route.")

        // With GPS
        val withGps = tx.encodePacket(payload, location = bukidnon, timestamp = fixedTime)
        val decodedGps = assertIs<AprsPacket.Safer247>(rx.decodeAx25(withGps.ax25Bytes))
        assertEquals(payload, decodedGps.payload)
        assertEquals("064300h", decodedGps.timestamp)
        assertLocationNear(bukidnon, decodedGps.location!!)

        // Without GPS (:ALL      : fallback)
        val noGps = tx.encodePacket(payload, location = null, timestamp = fixedTime)
        val decodedNoGps = assertIs<AprsPacket.Safer247>(rx.decodeAx25(noGps.ax25Bytes))
        assertEquals(payload, decodedNoGps.payload)
        assertNull(decodedNoGps.location)
        assertNull(decodedNoGps.timestamp)
    }

    @Test
    fun `round-trip quick photo with and without caption`() {
        for (caption in listOf("Bridge flood water level", "")) {
            val payload = Payload.QuickPhoto(sender = "Responder Alpha", caption = caption)
            val encoded = tx.encodePacket(payload, location = bukidnon, timestamp = fixedTime)

            val decoded = assertIs<AprsPacket.Safer247>(rx.decodeAx25(encoded.ax25Bytes))
            assertEquals(payload, decoded.payload)
        }
    }

    @Test
    fun `round-trip incident report across all incident types and image flags`() {
        for (type in IncidentType.entries) {
            for (hasImage in listOf(false, true)) {
                val payload = Payload.IncidentReport(
                    sender = "Responder Alpha",
                    incidentType = type,
                    reportId = "RPT-4A9K2M",
                    description = "Incident report for ${type.displayName}",
                    hasImage = hasImage
                )
                val encoded = tx.encodePacket(payload, location = bukidnon, timestamp = fixedTime)
                val decoded = assertIs<AprsPacket.Safer247>(rx.decodeAx25(encoded.ax25Bytes))
                assertEquals(payload, decoded.payload)
                assertEquals(decoded, rx.decodeTnc2(encoded.tnc2))
            }
        }
    }

    @Test
    fun `round-trip image end with and without reportId`() {
        for (reportId in listOf("RPT-4A9K2M", null)) {
            val payload = Payload.ImageEnd(sender = "Responder Alpha", reportId = reportId)
            val encoded = tx.encodePacket(payload, location = bukidnon, timestamp = fixedTime)

            val decoded = assertIs<AprsPacket.Safer247>(rx.decodeAx25(encoded.ax25Bytes))
            assertEquals(payload, decoded.payload)
        }
    }

    @Test
    fun `non-APRS destination is not classified as Safer247`() {
        val otherTx = AprsTx(destination = "BEACON")
        val encoded = otherTx.encodePacket(
            Payload.QuickMessage(sender = "Responder Alpha", message = "Hello"),
            location = bukidnon,
            timestamp = fixedTime
        )
        val decoded = rx.decodeAx25(encoded.ax25Bytes)
        assertIs<AprsPacket.Position>(decoded)
    }

    @Test
    fun `unknown tag and invalid incident code fall back to generic Position`() {
        val unknownTag = rx.decodeTnc2("S247>APRS:/064300h0755.12N/12519.72E>[FOO|Alpha]Hello")
        assertIs<AprsPacket.Position>(unknownTag)

        val badType = rx.decodeTnc2("S247>APRS:/064300h0755.12N/12519.72E>[IR|Alpha|ZZZ|RPT-4A9K2M]Hello")
        assertIs<AprsPacket.Position>(badType)
    }

    @Test
    fun `beacon without GPS over ALL fallback is rejected as Safer247`() {
        val decoded = rx.decodeTnc2("S247>APRS::ALL      :[BCN|Responder Alpha]")
        assertIs<AprsPacket.TextMessage>(decoded)
    }

    private fun assertLocationNear(expected: GeoPoint, actual: GeoPoint, toleranceDeg: Double = 0.0002) {
        assertTrue(
            abs(expected.latitude - actual.latitude) <= toleranceDeg,
            "Latitude mismatch: expected=${expected.latitude}, actual=${actual.latitude}"
        )
        assertTrue(
            abs(expected.longitude - actual.longitude) <= toleranceDeg,
            "Longitude mismatch: expected=${expected.longitude}, actual=${actual.longitude}"
        )
    }
}
