package com.safer247.mobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safer247.aprs.AprsTx
import com.safer247.aprs.afsk.AfskAudioTransmitter
import com.safer247.aprs.afsk.AfskConfig
import com.safer247.aprs.protocol.GeoPoint
import com.safer247.aprs.protocol.IncidentType
import com.safer247.aprs.protocol.Payload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PayloadMode(val label: String) {
    QUICK_MESSAGE("Quick Msg"),
    INCIDENT_REPORT("Incident"),
    BEACON("Beacon")
}

sealed interface TxStatus {
    data object Idle : TxStatus
    data object Sending : TxStatus
    data object AudioComplete : TxStatus
    data class Failed(val error: String) : TxStatus
}

data class TxUiState(
    val mode: PayloadMode = PayloadMode.QUICK_MESSAGE,
    val body: String = "",
    val incidentType: IncidentType = IncidentType.FIRE,
    val includeGps: Boolean = true,
    val status: TxStatus = TxStatus.Idle,
    val lastTnc2: String? = null,
    val lastFrameBytes: Int? = null,
    val sampleRateHz: Int = AfskConfig.SAMPLE_RATE_HZ,
    val txDelayMs: Int = AfskConfig.TX_DELAY_UNITS * 10
) {
    /** Location Beacon always requires GPS coordinates. */
    val effectiveIncludeGps: Boolean
        get() = mode == PayloadMode.BEACON || includeGps

    fun buildPayload(): Payload = when (mode) {
        PayloadMode.QUICK_MESSAGE -> Payload.QuickMessage(
            sender = TxViewModel.TEST_SENDER,
            message = body
        )

        PayloadMode.INCIDENT_REPORT -> Payload.IncidentReport(
            sender = TxViewModel.TEST_SENDER,
            incidentType = incidentType,
            reportId = TxViewModel.TEST_REPORT_ID,
            description = body,
            hasImage = false
        )

        PayloadMode.BEACON -> Payload.Beacon(
            sender = TxViewModel.TEST_SENDER
        )
    }

    val previewTnc2: String
        get() {
            val tagAndBody = when (mode) {
                PayloadMode.QUICK_MESSAGE ->
                    "[QM|${TxViewModel.TEST_SENDER}]$body"

                PayloadMode.INCIDENT_REPORT ->
                    "[IR|${TxViewModel.TEST_SENDER}|${incidentType.code}|${TxViewModel.TEST_REPORT_ID}]$body"

                PayloadMode.BEACON ->
                    "[BCN|${TxViewModel.TEST_SENDER}]"
            }
            val prefix = if (effectiveIncludeGps) {
                "/HHMMSSh${TxViewModel.TEST_APRS_COORDS}>"
            } else {
                ":ALL      :"
            }
            return "${AprsTx.DEFAULT_SOURCE}>${AprsTx.DEFAULT_DESTINATION}:$prefix$tagAndBody"
        }
}

class TxViewModel : ViewModel() {

    companion object {
        const val TEST_SENDER: String = "Responder Alpha"
        const val TEST_REPORT_ID: String = "RPT-4A9K2M"
        const val TEST_APRS_COORDS: String = "0755.12N/12519.72E"
        val TEST_LOCATION: GeoPoint = GeoPoint(latitude = 7.9186, longitude = 125.3286)
    }

    private val transmitter = AfskAudioTransmitter()

    private val _uiState = MutableStateFlow(TxUiState())
    val uiState: StateFlow<TxUiState> = _uiState.asStateFlow()

    fun onModeChange(newMode: PayloadMode) {
        _uiState.update { state ->
            state.copy(
                mode = newMode,
                status = if (state.status is TxStatus.Failed) TxStatus.Idle else state.status
            )
        }
    }

    fun onBodyChange(newText: String) {
        _uiState.update { state ->
            state.copy(
                body = newText,
                status = if (state.status is TxStatus.Failed) TxStatus.Idle else state.status
            )
        }
    }

    fun onIncidentTypeChange(newType: IncidentType) {
        _uiState.update { state ->
            state.copy(
                incidentType = newType,
                status = if (state.status is TxStatus.Failed) TxStatus.Idle else state.status
            )
        }
    }

    fun onIncludeGpsChange(enabled: Boolean) {
        _uiState.update { state ->
            state.copy(
                includeGps = enabled,
                status = if (state.status is TxStatus.Failed) TxStatus.Idle else state.status
            )
        }
    }

    fun transmit() {
        val current = _uiState.value
        if (current.status is TxStatus.Sending) return

        val payload = current.buildPayload()
        val location = if (current.effectiveIncludeGps) TEST_LOCATION else null

        viewModelScope.launch {
            _uiState.update { it.copy(status = TxStatus.Sending) }
            try {
                val encoded = transmitter.transmitPayload(
                    payload = payload,
                    location = location
                )
                _uiState.update {
                    it.copy(
                        status = TxStatus.AudioComplete,
                        lastTnc2 = encoded.tnc2,
                        lastFrameBytes = encoded.ax25Bytes.size
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        status = TxStatus.Failed(e.message ?: "Transmission failed.")
                    )
                }
            }
        }
    }
}
