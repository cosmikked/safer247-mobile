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

import com.safer247.aprs.ax25.Ax25Frame

/**
 * TNC2 / APRS-IS format: `SOURCE>DEST[,DIGI…]:info`
 */
object Tnc2Codec {

    data class Frame(
        val source: String,
        val destination: String,
        val digipeaters: List<String>,
        val info: String
    ) {
        val pathForGate: String
            get() = buildString {
                append(destination)
                digipeaters.forEach { d ->
                    append(',')
                    append(d.removeSuffix("*"))
                }
            }
    }

    fun format(source: String, destination: String, digipeaters: List<String>, info: String): String {
        val digis = digipeaters.joinToString("") { ",$it" }
        return "$source>$destination$digis:$info"
    }

    fun fromAx25(payload: ByteArray): Frame? {
        val ax = Ax25Frame.parse(payload) ?: return null
        return Frame(
            source = ax.source,
            destination = ax.destination,
            digipeaters = ax.digipeaters,
            info = ax.infoText
        )
    }

    fun parse(line: String): Frame? {
        val trimmed = line.trimEnd('\r', '\n')
        if (trimmed.isEmpty() || trimmed.startsWith('#')) return null
        val gt = trimmed.indexOf('>')
        val colon = trimmed.indexOf(':')
        if (gt <= 0 || colon <= gt) return null
        val source = trimmed.substring(0, gt).trim()
        val path = trimmed.substring(gt + 1, colon)
        val info = trimmed.substring(colon + 1)
        val parts = path.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        return Frame(
            source = source,
            destination = parts.first(),
            digipeaters = parts.drop(1),
            info = info
        )
    }
}
