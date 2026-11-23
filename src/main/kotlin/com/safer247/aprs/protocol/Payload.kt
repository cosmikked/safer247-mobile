package org.example.com.safer247.aprs.protocol

/**
 * SAFER 247 Layer 4 payload types.
 *
 * Represents only the tag + body (`<SAFER247_TAG><BODY>`).
 * GPS coordinates and UTC timestamps live on the APRS Layer 3 envelope in [org.example.com.safer247.aprs.AprsTx].
 */
sealed class Payload {
    abstract val sender: String

    /** Location beacon: `[BCN|<SENDER>]` */
    data class Beacon(
        override val sender: String
    ) : Payload()

    /** Quick text message: `[QM|<SENDER>]<TEXT>` */
    data class QuickMessage(
        override val sender: String,
        val message: String
    ) : Payload()

    /** Quick photo start bracket: `[IMG|<SENDER>]<CAPTION>` */
    data class QuickPhoto(
        override val sender: String,
        val caption: String = ""
    ) : Payload()

    /** Incident report (with or without photo): `[IR|<SENDER>|<TYPE>|<RPT_ID>(|IMG)]<DESC>` */
    data class IncidentReport(
        override val sender: String,
        val incidentType: IncidentType,
        val reportId: String,
        val description: String,
        val hasImage: Boolean = false
    ) : Payload()

    /** SSTV end bracket trailer: `[IMGEND|<SENDER>]` or `[IMGEND|<SENDER>|<RPT_ID>]` */
    data class ImageEnd(
        override val sender: String,
        val reportId: String? = null
    ) : Payload()
}

data class GeoPoint(
    val latitude: Double,
    val longitude: Double
)

/**
 * Output of the TX encoder at every layer:
 * - [infoField]: APRS Layer 3 info field (position report or `:ALL      :` fallback)
 * - [tnc2]: Full TNC2 monitoring string (`SOURCE>DEST[,DIGIS]:info`)
 * - [ax25Bytes]: Raw AX.25 UI frame without CRC/FCS (ready for `javAX25` `Packet(ax25Bytes)`)
 */
data class EncodedTxPacket(
    val infoField: String,
    val tnc2: String,
    val ax25Bytes: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncodedTxPacket) return false
        return infoField == other.infoField &&
            tnc2 == other.tnc2 &&
            ax25Bytes.contentEquals(other.ax25Bytes)
    }

    override fun hashCode(): Int {
        var result = infoField.hashCode()
        result = 31 * result + tnc2.hashCode()
        result = 31 * result + ax25Bytes.contentHashCode()
        return result
    }
}
