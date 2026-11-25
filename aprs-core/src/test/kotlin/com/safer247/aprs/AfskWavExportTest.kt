package com.safer247.aprs

import com.safer247.aprs.afsk.AfskDemodulator
import com.safer247.aprs.afsk.AfskModulator
import com.safer247.aprs.model.AprsPacket
import com.safer247.aprs.protocol.GeoPoint
import com.safer247.aprs.protocol.IncidentType
import com.safer247.aprs.protocol.Payload
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AfskWavExportTest {

    private val tx = AprsTx()
    private val rx = AprsRx()
    private val modulator = AfskModulator()
    private val fixedTime = Instant.parse("2026-11-24T06:43:00Z")
    private val bukidnon = GeoPoint(latitude = 7.9186, longitude = 125.3286)
    private val outDir = File("build/test-wavs")

    @Test
    fun `export wavs for direwolf and verify javAX25 loopback`() {
        val cases = listOf(
            Triple(
                "qm_gps.wav",
                Payload.QuickMessage(sender = "Responder Alpha", message = "Hello from SAFER 247"),
                bukidnon
            ),
            Triple(
                "qm_nogps.wav",
                Payload.QuickMessage(sender = "Responder Alpha", message = "Indoors no GPS fix"),
                null
            ),
            Triple(
                "ir_gps.wav",
                Payload.IncidentReport(
                    sender = "Responder Alpha",
                    incidentType = IncidentType.FIRE,
                    reportId = "RPT-4A9K2M",
                    description = "Residential fire near public market.",
                    hasImage = false
                ),
                bukidnon
            ),
            Triple(
                "bcn_gps.wav",
                Payload.Beacon(sender = "Responder Alpha"),
                bukidnon
            )
        )

        for ((fileName, payload, location) in cases) {
            val encoded = tx.encodePacket(payload, location = location, timestamp = fixedTime)
            val pcm16 = modulator.modulateToPcm16(encoded)

            // 1. Export 48 kHz mono PCM16 WAV for Direwolf (atest.exe) cross-verification
            writePcm16Wav(File(outDir, fileName), pcm16)

            // 2. Verify pure-JVM software audio loopback through javAX25 AfskDemodulator -> AprsRx
            val received = mutableListOf<ByteArray>()
            val demodulator = AfskDemodulator { frame -> received.add(frame) }
            demodulator.feedPcm16(pcm16)
            demodulator.feedPcm16(ShortArray(2400)) // 50 ms trailing silence to flush FIR filters

            assertEquals(1, received.size, "Expected 1 decoded frame for $fileName")
            val decoded = assertIs<AprsPacket.Safer247>(rx.decodeAx25(received.first()))
            assertEquals(payload, decoded.payload, "Payload mismatch for $fileName")
            assertEquals(encoded.tnc2, decoded.tnc2, "TNC2 mismatch for $fileName")
        }
    }
}
