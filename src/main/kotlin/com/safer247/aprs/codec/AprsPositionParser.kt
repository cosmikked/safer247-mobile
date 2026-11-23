package org.example.com.safer247.aprs.codec

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/*
 * RadioChat APRS — Android APRS client for KISS BLE radios,
 * DireWolf (KISS TCP) and APRS-IS.
 * Copyright (C) 2026 Luis Almonte (HI3LAG)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */


/**
 * APRS position parser:
 * - Plain text: `!` / `=` / `/` / `@` + DDMM.mmN/S + symbol + DDDMM.mmE/W + symbol + comment
 * - Basic compressed (not Mic-E; only base91 `/` or `*` after an optional timestamp — compressed `!` format)
 *
 * Extracts: lat/lon as Double, symbol (table+code) and comment.
 */
object AprsPositionParser {

    // !4903.50N/07201.75W-Test comment
    private val uncompressedRegex = Regex(
        """^([!=\/@])(\d{4}\.\d{2})([NS])(.)(\d{5}\.\d{2})([EW])(.)(.*)"""
    )

    // Compressed format: ! / symbolTable + 4 lat + 4 lon + symbol + csT + comment
    // Typical example after '!': /YYYYXXXX$csTcomment  (base91)
    private val compressedRegex = Regex(
        """^([!=])([\\\/])(.{4})(.{4})(.)(.)(.)(.)(.*)"""
    )

    private val utcHmsFormatter = DateTimeFormatter
        .ofPattern("HHmmss")
        .withZone(ZoneOffset.UTC)


    /**
     * @param latitude
     * @param longitude
     * @param symbol mandatory icon code for APRS ("/>" - vehicle/mobile responder for safer247)
    * @param comment the message that a responder sends
    * */
    data class ParsedPosition(
        val latitude: Double,
        val longitude: Double,
        val symbol: String,
        val comment: String,
        val timestamp: String? = null
    )

    // TODO: consider using compressed timestamp format in safer247

    /**
     * Parses an APRS position from the info field of a packet.
     * @param infoField the info field of an APRS packet (after the colon)
     * @return ParsedPosition if successful, null otherwise
     * */
    fun parse(infoField: String): ParsedPosition? {
        val info = infoField.trimEnd('\r', '\n')
        if (info.isEmpty()) return null

        // Some beacons carry a timestamp before the position (/HHMMSSz or @DDHHMMz)
        val (ts, withoutTs) = stripLeadingTimestamp(info)

        parseUncompressed(withoutTs)?.let { return it.copy(timestamp = ts) }
        parseCompressed(withoutTs)?.let { return it.copy(timestamp = ts) }

        return null
    }

    /**
     * Strips the timestamp from the latitude and longitude if present.
     * @param info the info field of an APRS packet (after the colon)
     * @return a Pair of the timestamp (if present) and the remaining info field.
     *
     * For ex: "/064215h0755.12N/12519.72E>Hi"
     * ts = "064215h", info = "!0755.12N/12519.72E>Hi"
     * */
    private fun stripLeadingTimestamp(info: String): Pair<String?, String> {
        // /, @ + 7 timestamp chars + the rest of the position
        if (info.length > 8 && (info[0] == '/' || info[0] == '@')) {
            val ts = info.substring(1, 8)
            if (ts.substring(0,6).all { it.isDigit() } && ts[6] in setOf('z', 'h', '/')) {
                return ts to ("!" + info.substring(8))
            }
        }
        return null to info
    }

    private fun parseUncompressed(info: String): ParsedPosition? {
        val m = uncompressedRegex.matchEntire(info) ?: return null
        val lat = dmToDecimal(m.groupValues[2], m.groupValues[3])
        val lon = dmToDecimal(m.groupValues[5], m.groupValues[6])
        val table = m.groupValues[4]
        val code = m.groupValues[7]
        val comment = m.groupValues[8].trim()
        return ParsedPosition(lat, lon, table + code, comment)
    }

    private fun parseCompressed(info: String): ParsedPosition? {
        val m = compressedRegex.matchEntire(info) ?: return null
        val table = m.groupValues[2]
        val latEnc = m.groupValues[3]
        val lonEnc = m.groupValues[4]
        val code = m.groupValues[5]
        val comment = m.groupValues[9].trim()

        val lat = decodeBase91Lat(latEnc) ?: return null
        val lon = decodeBase91Lon(lonEnc) ?: return null
        return ParsedPosition(lat, lon, table + code, comment)
    }

    /** DDMM.mm + hemisphere → decimal degrees. */
    private fun dmToDecimal(dm: String, hemisphere: String): Double {
        val degDigits = if (dm.length >= 8) 3 else 2 // lon=DDDMM.mm, lat=DDMM.mm
        val degrees = dm.substring(0, degDigits).toInt()
        val minutes = dm.substring(degDigits).toDouble()
        var dec = degrees + minutes / 60.0
        if (hemisphere.equals("S", true) || hemisphere.equals("W", true)) {
            dec = -dec
        }
        return dec
    }

    private fun decodeBase91Lat(y: String): Double? {
        if (y.length != 4 || y.any { it.code < 33 || it.code > 123 }) return null
        var v = 0
        for (c in y) v = v * 91 + (c.code - 33)
        return 90.0 - v / 380926.0
    }

    private fun decodeBase91Lon(x: String): Double? {
        if (x.length != 4 || x.any { it.code < 33 || it.code > 123 }) return null
        var v = 0
        for (c in x) v = v * 91 + (c.code - 33)
        return -180.0 + v / 190463.0
    }



    /** Encodes timestamp and lat/lon as APRS plain text.
     *
     * @param timestamp defaults to now (UTC)
     * @param latitude in decimal degrees
     * @param longitude in decimal degrees
     * @param symbol APRS symbol (table+code, e.g., "/>")
     * @param comment optional comment to include after the position | message body in safer247
     *
     * Returns a string in the format: `/HHMMSShDDMM.mmN/Ssymbol[0]DDDMM.mmE/Wsymbol[1]comment`
     * */
    fun formatUncompressed(
        latitude: Double,
        longitude: Double,
        symbol: String = "/>",
        comment: String = "",
        timestamp: Instant = Instant.now()
    ): String {
        val table = symbol.getOrElse(0) { '/' }
        val code = symbol.getOrElse(1) { '>' }
        val lat = decimalToDm(latitude, isLat = true)
        val lon = decimalToDm(longitude, isLat = false)
        val ts = "${utcHmsFormatter.format(timestamp)}h"

        // TODO: consider if comment here will be truncated
        return "/$ts$lat$table$lon$code${comment}"
    }

    private fun decimalToDm(value: Double, isLat: Boolean): String {
        val hemi = when {
            isLat && value >= 0 -> 'N'
            isLat -> 'S'
            value >= 0 -> 'E'
            else -> 'W'
        }
        val abs = kotlin.math.abs(value)
        val deg = abs.toInt()
        val minutes = (abs - deg) * 60.0
        return if (isLat) {
            String.format(java.util.Locale.US, "%02d%05.2f%c", deg, minutes, hemi)
        } else {
            String.format(java.util.Locale.US, "%03d%05.2f%c", deg, minutes, hemi)
        }
    }
}
