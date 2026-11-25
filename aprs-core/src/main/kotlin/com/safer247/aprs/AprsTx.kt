package com.safer247.aprs

import com.safer247.aprs.ax25.Ax25Frame
import com.safer247.aprs.codec.AprsMessageCodec
import com.safer247.aprs.codec.AprsPositionParser
import com.safer247.aprs.codec.Tnc2Codec
import com.safer247.aprs.protocol.EncodedTxPacket
import com.safer247.aprs.protocol.GeoPoint
import com.safer247.aprs.protocol.Payload
import java.security.SecureRandom
import java.time.Instant

/**
 * APRS TX pipeline orchestrator.
 *
 * Encodes a typed [Payload] through:
 *   1. tag + body ([encodeTag])
 *   2. APRS Layer 3 info field ([encodeInfoField]) — timestamped position report or `:ALL      :`  -> no-GPS fallback
 *   3. TNC2 string + raw AX.25 UI frame without FCS ([encodePacket]) — ready for `javAX25`
 */
class AprsTx(
    private val source: String = DEFAULT_SOURCE,
    private val destination: String = DEFAULT_DESTINATION,
    private val digipeaters: List<String> = emptyList(),
    private val symbol: String = DEFAULT_SYMBOL
) {

    companion object {
        const val DEFAULT_SOURCE = "S247"
        const val DEFAULT_DESTINATION = "APRS"
        const val DEFAULT_SYMBOL = "/>"
        const val FALLBACK_ADDRESSEE = "ALL"
        const val MAX_INFO_BYTES = 256
        const val MAX_PLAIN_MESSAGE_LENGTH = MAX_INFO_BYTES - 11 // ":ALL      :" is 11 bytes
        const val MAX_BODY_LENGTH = 175
        const val MAX_SENDER_LENGTH = 24
        const val MAX_REPORT_ID_LENGTH = 12

        private val FORBIDDEN_SENDER_CHARS = setOf('|', '[', ']', '\r', '\n')
        private const val REPORT_ID_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        private val secureRandom = SecureRandom()

        /** Generates a compact report ID like `RPT-4A9K2M`. */
        fun generateReportId(): String = buildString(MAX_REPORT_ID_LENGTH) {
            append("RPT-")
            repeat(6) {
                append(REPORT_ID_ALPHABET[secureRandom.nextInt(REPORT_ID_ALPHABET.length)])
            }
        }
    }

    /** Builds `<TAG><BODY>` from a typed [Payload]. */
    fun encodeTag(payload: Payload): String {
        validateSender(payload.sender)

        return when (payload) {
            is Payload.Beacon ->
                "[BCN|${payload.sender}]"

            is Payload.QuickMessage ->
                "[QM|${payload.sender}]${payload.message}"

            is Payload.QuickPhoto ->
                "[IMG|${payload.sender}]${payload.caption}"

            is Payload.IncidentReport -> {
                validateReportId(payload.reportId)
                val imgFlag = if (payload.hasImage) "|IMG" else ""
                "[IR|${payload.sender}|${payload.incidentType.code}|${payload.reportId}$imgFlag]${payload.description}"
            }

            is Payload.ImageEnd -> {
                val rpt = payload.reportId?.let { id ->
                    validateReportId(id)
                    "|$id"
                }.orEmpty()
                "[IMGEND|${payload.sender}$rpt]"
            }
        }
    }

    /**
     * Builds the APRS Layer 3 info field:
     * - With [location]: `/<HHMMSS>h<DDMM.mm><N|S>/<DDDMM.mm><E|W>><TAG><BODY>`
     * - Without [location]: `:ALL      :<TAG><BODY>` (not allowed for [Payload.Beacon])
     */
    fun encodeInfoField(
        payload: Payload,
        location: GeoPoint? = null,
        timestamp: Instant = Instant.now()
    ): String {
        val taggedPayload = encodeTag(payload)

        val infoField = if (location != null) {
            AprsPositionParser.formatUncompressed(
                timestamp = timestamp,
                latitude = location.latitude,
                longitude = location.longitude,
                symbol = symbol,
                comment = taggedPayload
            )
        } else {
            require(payload !is Payload.Beacon) {
                "LocationBeacon requires a valid GPS GeoPoint."
            }
            AprsMessageCodec.build(
                addressee = FALLBACK_ADDRESSEE,
                text = taggedPayload
            )
        }

        // TODO: Finalize body sanitization (newlines/non-ASCII) vs. truncation strategy when wiring UI
        require(infoField.length <= MAX_INFO_BYTES) {
            "APRS info field exceeds $MAX_INFO_BYTES bytes (actual: ${infoField.length})."
        }

        return infoField
    }

    /**
     * Builds the complete TX bundle: APRS info field, TNC2 monitoring string,
     * and raw AX.25 UI frame bytes (without CRC/FCS — `javAX25` appends FCS).
     */
    fun encodePacket(
        payload: Payload,
        location: GeoPoint? = null,
        timestamp: Instant = Instant.now()
    ): EncodedTxPacket {
        val infoField = encodeInfoField(
            payload = payload,
            location = location,
            timestamp = timestamp
        )
        val tnc2 = Tnc2Codec.format(
            source = source,
            destination = destination,
            digipeaters = digipeaters,
            info = infoField
        )
        val ax25Bytes = Ax25Frame.buildUiFrame(
            destination = destination,
            source = source,
            info = infoField.toByteArray(Charsets.ISO_8859_1),
            digipeaters = digipeaters
        )
        return EncodedTxPacket(
            infoField = infoField,
            tnc2 = tnc2,
            ax25Bytes = ax25Bytes
        )
    }

    /**
     * Builds a plain APRS addressed message packet (`SOURCE>DEST::ADDRESSEE:message`)
     * without SAFER 247 `[TAG|...]` framing. Useful for basic TX testing before full
     * SAFER 247 payloads are wired.
     */
    fun encodePlainMessage(
        message: String,
        addressee: String = FALLBACK_ADDRESSEE
    ): EncodedTxPacket {
        require(message.isNotBlank()) { "Message must not be blank." }
        require(message.all { it.code in 0x20..0x7E }) {
            "Message must be single-line printable ASCII (0x20..0x7E)."
        }

        val infoField = AprsMessageCodec.build(
            addressee = addressee,
            text = message
        )
        require(infoField.length <= MAX_INFO_BYTES) {
            "APRS info field exceeds $MAX_INFO_BYTES bytes (actual: ${infoField.length})."
        }

        val tnc2 = Tnc2Codec.format(
            source = source,
            destination = destination,
            digipeaters = digipeaters,
            info = infoField
        )
        val ax25Bytes = Ax25Frame.buildUiFrame(
            destination = destination,
            source = source,
            info = infoField.toByteArray(Charsets.ISO_8859_1),
            digipeaters = digipeaters
        )
        return EncodedTxPacket(
            infoField = infoField,
            tnc2 = tnc2,
            ax25Bytes = ax25Bytes
        )
    }

    private fun validateSender(sender: String) {
        require(sender.isNotBlank() && sender.length <= MAX_SENDER_LENGTH) {
            "Sender must be $MAX_SENDER_LENGTH characters."
        }
        require(sender.all { it.code in 0x20..0x7E && it !in FORBIDDEN_SENDER_CHARS }) {
            "Sender must be printable ASCII excluding |, [, ], CR, and LF."
        }
    }

    private fun validateReportId(reportId: String) {
        require(reportId.isNotBlank() && reportId.length <= MAX_REPORT_ID_LENGTH) {
            "Report ID must be $MAX_REPORT_ID_LENGTH characters."
        }
        require(reportId.all { it.code in 0x20..0x7E && it !in FORBIDDEN_SENDER_CHARS }) {
            "Report ID contains invalid characters."
        }
    }


}
