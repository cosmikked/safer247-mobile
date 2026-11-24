package com.safer247.aprs.protocol

enum class IncidentType(
    val code: String,
    val displayName: String
) {
    FIRE("FRE", "Fire"),
    FLOOD("FLD", "Flood"),
    VEHICULAR("VEH", "Vehicular Accident"),
    MEDICAL("MED", "Medical"),
    LANDSLIDE("LND", "Landslide");

    companion object {
        private val byCode = entries.associateBy { it.code }

        /** Parses a 3-character wire code (case-insensitive) back into an IncidentType. */
        fun fromCode(code: String): IncidentType? =
            byCode[code.trim().uppercase()]
    }
}
