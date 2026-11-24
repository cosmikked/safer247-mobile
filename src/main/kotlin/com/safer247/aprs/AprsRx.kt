package com.safer247.aprs

import com.safer247.aprs.ax25.Ax25Frame
import com.safer247.aprs.codec.AprsMessageCodec
import com.safer247.aprs.codec.AprsPositionParser
import com.safer247.aprs.codec.Tnc2Codec
import com.safer247.aprs.model.AprsPacket
import com.safer247.aprs.protocol.GeoPoint
import com.safer247.aprs.protocol.IncidentType
import com.safer247.aprs.protocol.Payload

/**
 * SAFER 247 RX pipeline orchestrator (PROJECT_SPEC.md Section 4.1-4.3).
 *
 * Decodes a raw AX.25 UI frame (from `javAX25` `PacketHandler.handlePacket(bytesWithoutCRC)`)
 * or a TNC2 string into a typed [AprsPacket].
 */
class AprsRx(
    private val acceptedDestinations: Set<String> = DEFAULT_ACCEPTED_DESTINATIONS
) {

    companion object {
        val DEFAULT_ACCEPTED_DESTINATIONS: Set<String> = setOf("APRS")
    }

    /**
     * Decodes a raw AX.25 UI frame into an [AprsPacket].
     * Tolerates an optional trailing 2-byte CRC/FCS if present.
     *
     * @param ax25Bytes Raw AX.25 UI frame bytes (without CRC/FCS or with optional trailing 2-byte CRC/FCS).
     * @return Decoded [AprsPacket] or `null` if the frame is invalid or unrecognized.
     */
    fun decodeAx25(ax25Bytes: ByteArray): AprsPacket? {
        classifyAx25(ax25Bytes)?.let { packet ->
            if (packet !is AprsPacket.Other) return packet
        }
        // Tolerate optional trailing 2-byte FCS from external TNCs
        if (ax25Bytes.size > 18) {
            classifyAx25(ax25Bytes.copyOf(ax25Bytes.size - 2))?.let { packet ->
                if (packet !is AprsPacket.Other) return packet
            }
        }
        return classifyAx25(ax25Bytes)
    }

    /**
     * Decodes a TNC2 monitoring line (`SOURCE>DEST[,DIGIS]:info`) into an [AprsPacket].
     * Useful for Direwolf log replay and Golden Vector unit tests.
     */
    fun decodeTnc2(tnc2Line: String): AprsPacket? {
        val frame = Tnc2Codec.parse(tnc2Line) ?: return null
        return classifyFrame(
            source = frame.source,
            destination = frame.destination,
            digipeaters = frame.digipeaters,
            info = frame.info
        )
    }

    /**
     * Parses a SAFER 247 `<SAFER247_TAG><BODY>` string into a typed [Payload].
     * Returns `null` if the string does not match a valid SAFER 247 tag.
     */
    private fun decodeTag(taggedText: String): Payload? {
        val trimmed = taggedText.trimStart()
        if (!trimmed.startsWith('[')) return null
        val closeIdx = trimmed.indexOf(']')
        if (closeIdx <= 1) return null

        val header = trimmed.substring(1, closeIdx)
        val body = trimmed.substring(closeIdx + 1)
        val parts = header.split('|')
        if (parts.size < 2) return null

        val tag = parts[0].uppercase()
        val sender = parts[1].trim()
        if (sender.isEmpty()) return null

        return when (tag) {
            "BCN" -> {
                if (parts.size != 2) return null
                Payload.Beacon(sender = sender)
            }

            "QM" -> {
                if (parts.size != 2) return null
                Payload.QuickMessage(sender = sender, message = body)
            }

            "IMG" -> {
                if (parts.size != 2) return null
                Payload.QuickPhoto(sender = sender, caption = body)
            }

            "IR" -> {
                if (parts.size !in 4..5) return null
                val incidentType = IncidentType.fromCode(parts[2]) ?: return null
                val reportId = parts[3].trim()
                if (reportId.isEmpty()) return null
                val hasImage = when (parts.size) {
                    4 -> false
                    5 -> if (parts[4].equals("IMG", ignoreCase = true)) true else return null
                    else -> return null
                }
                Payload.IncidentReport(
                    sender = sender,
                    incidentType = incidentType,
                    reportId = reportId,
                    description = body,
                    hasImage = hasImage
                )
            }

            "IMGEND" -> {
                if (parts.size !in 2..3) return null
                val reportId = parts.getOrNull(2)?.trim()?.ifEmpty { null }
                Payload.ImageEnd(sender = sender, reportId = reportId)
            }

            else -> null
        }
    }

    private fun classifyAx25(ax25Bytes: ByteArray): AprsPacket? {
        val ax25 = Ax25Frame.parse(ax25Bytes) ?: return null
        return classifyFrame(
            source = ax25.source,
            destination = ax25.destination,
            digipeaters = ax25.digipeaters,
            info = ax25.infoText
        )
    }

    private fun classifyFrame(
        source: String,
        destination: String,
        digipeaters: List<String> = emptyList(),
        info: String
    ): AprsPacket? {
        if (info.isEmpty()) return null

        val destBase = destination.uppercase().trim().substringBefore('-')
        val isAcceptedDest = destBase in acceptedDestinations
        val tnc2 = Tnc2Codec.format(source, destination, digipeaters, info)

        // 1. Primary path: APRS Position Report (timestamped or untimestamped)
        AprsPositionParser.parse(info)?.let { pos ->
            if (isAcceptedDest) {
                decodeTag(pos.comment)?.let { payload ->
                    return AprsPacket.Safer247(
                        sourceCall = source,
                        destCall = destination,
                        rawInfo = info,
                        digipeaters = digipeaters,
                        tnc2 = tnc2,
                        payload = payload,
                        location = GeoPoint(pos.latitude, pos.longitude),
                        timestamp = pos.timestamp,
                        symbol = pos.symbol
                    )
                }
            }
            return AprsPacket.Position(
                sourceCall = source,
                destCall = destination,
                rawInfo = info,
                latitude = pos.latitude,
                longitude = pos.longitude,
                symbol = pos.symbol,
                comment = pos.comment,
                timestamp = pos.timestamp
            )
        }

        // 2. No-GPS fallback path: APRS Addressed Message (`:ALL      :<TAG><BODY>`)
        AprsMessageCodec.parse(info)?.let { msg ->
            if (isAcceptedDest) {
                decodeTag(msg.text)?.let { payload ->
                    // LocationBeacon is only valid with GPS coordinates
                    if (payload !is Payload.Beacon) {
                        return AprsPacket.Safer247(
                            sourceCall = source,
                            destCall = destination,
                            rawInfo = info,
                            digipeaters = digipeaters,
                            tnc2 = tnc2,
                            payload = payload,
                            location = null,
                            timestamp = null,
                            symbol = null
                        )
                    }
                }
            }
            return AprsPacket.TextMessage(
                sourceCall = source,
                destCall = destination,
                rawInfo = info,
                addressee = msg.addressee,
                messageText = msg.text
            )
        }

        // 3. Unrecognized or non-APRS frame
        return AprsPacket.Other(
            sourceCall = source,
            destCall = destination,
            rawInfo = info
        )
    }


}
