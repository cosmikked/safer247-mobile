package com.safer247.aprs.codec

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
 * APRS messaging format (addressed text).
 *
 * Strict send:
 *   ':' + addressee (exactly 9 chars, space padded) + ':' + text
 * Example: ":EA1ABC   :Hello"
 */
object AprsMessageCodec {

    /**
     * Builds the Info Field of an APRS message.
     * @param addressee destination callsign (SSID not required; padded to 9 chars)
     * @param text message body (max. ~67 chars recommended on RF)
     * @param messageId optional, to request an ACK (`{01`)
     */
    fun build(addressee: String, text: String): String {
        val padded = padAddressee(addressee)
        return ":$padded:$text"
    }

    /**
     * APRS message Info Field: `:ADDRESSEE:text`
     *
     * The spec asks for a 9-character space-padded addressee; DireWolf and many
     * TNCs send it unpadded (`:HI3LAG-7:Hello`). Both are accepted.
     */
    fun parse(infoField: String): ParsedMessage? {
        val cleaned = infoField.trimStart()
            .trimEnd('\u0000', '\r', '\n')
            .takeWhile { it.code in 32..126 }
        if (cleaned.isEmpty() || cleaned[0] != ':') return null
        val rest = cleaned.substring(1)
        val colon = rest.indexOf(':')
        if (colon < 1 || colon > 9) return null
        val addressee = rest.substring(0, colon).trim()
        if (addressee.isEmpty()) return null
        var body = rest.substring(colon + 1)
        return ParsedMessage(addressee = addressee, text = body)
    }

    /** Pads/truncates the addressee to exactly 9 characters. */
    private fun padAddressee(callsign: String): String {
        return callsign.uppercase().take(9).padEnd(9, ' ')
    }


    data class ParsedMessage(
        val addressee: String,
        val text: String
    )

}
