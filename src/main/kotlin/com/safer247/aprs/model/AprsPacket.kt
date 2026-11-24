package com.safer247.aprs.model

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

import com.safer247.aprs.protocol.GeoPoint
import com.safer247.aprs.protocol.Payload

/**
 * Result of parsing a received AX.25/APRS frame.
 */
sealed class AprsPacket {
    abstract val sourceCall: String
    abstract val destCall: String
    abstract val rawInfo: String

    /** Valid SAFER 247 packet (either GPS position envelope or `:ALL      :` no-GPS fallback). */
    data class Safer247(
        override val sourceCall: String,
        override val destCall: String,
        override val rawInfo: String,
        val digipeaters: List<String> = emptyList(),
        val tnc2: String,
        val payload: Payload,
        val location: GeoPoint? = null,
        val timestamp: String? = null,
        val symbol: String? = null
    ) : AprsPacket()

    data class TextMessage(
        override val sourceCall: String,
        override val destCall: String,
        override val rawInfo: String,
        val addressee: String,
        val messageText: String
    ) : AprsPacket()

    data class Position(
        override val sourceCall: String,
        override val destCall: String,
        override val rawInfo: String,
        val latitude: Double,
        val longitude: Double,
        val symbol: String,
        val comment: String,
        val timestamp: String? = null
    ) : AprsPacket()

    data class Other(
        override val sourceCall: String,
        override val destCall: String,
        override val rawInfo: String
    ) : AprsPacket()
}
