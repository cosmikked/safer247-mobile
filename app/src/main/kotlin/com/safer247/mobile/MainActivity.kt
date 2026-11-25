package com.safer247.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.safer247.aprs.AprsTx
import com.safer247.aprs.protocol.IncidentType

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Safer247TxScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun Safer247TxScreen(
    viewModel: TxViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val isSending = state.status is TxStatus.Sending

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("SAFER 247 — APRS TX") }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "AFSK-1200 Audio Pipeline",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${state.sampleRateHz} Hz · Mono PCM16 · TXDELAY ${state.txDelayMs} ms",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "Sender: ${TxViewModel.TEST_SENDER} · Report ID: ${TxViewModel.TEST_REPORT_ID}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                PayloadMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = state.mode == mode,
                        onClick = { viewModel.onModeChange(mode) },
                        enabled = !isSending,
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = PayloadMode.entries.size
                        ),
                        label = { Text(mode.label) }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Include GPS (${TxViewModel.TEST_LOCATION.latitude}, ${TxViewModel.TEST_LOCATION.longitude})",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (state.mode == PayloadMode.BEACON) {
                            "Required for Location Beacon"
                        } else if (state.effectiveIncludeGps) {
                            "Position envelope (/<HHMMSS>h...)"
                        } else {
                            "No-GPS fallback (:ALL      :...)"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = state.effectiveIncludeGps,
                    onCheckedChange = viewModel::onIncludeGpsChange,
                    enabled = !isSending && state.mode != PayloadMode.BEACON
                )
            }

            when (state.mode) {
                PayloadMode.QUICK_MESSAGE -> {
                    OutlinedTextField(
                        value = state.body,
                        onValueChange = viewModel::onBodyChange,
                        label = { Text("Quick Message") },
                        placeholder = { Text("Enter quick message text...") },
                        singleLine = true,
                        enabled = !isSending,
                        supportingText = {
                            Text("${state.body.length} / ${AprsTx.MAX_BODY_LENGTH} chars")
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                PayloadMode.INCIDENT_REPORT -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Incident Type",
                            style = MaterialTheme.typography.labelLarge
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            IncidentType.entries.forEach { type ->
                                FilterChip(
                                    selected = state.incidentType == type,
                                    onClick = { viewModel.onIncidentTypeChange(type) },
                                    enabled = !isSending,
                                    label = { Text("${type.displayName} (${type.code})") }
                                )
                            }
                        }
                        OutlinedTextField(
                            value = state.body,
                            onValueChange = viewModel::onBodyChange,
                            label = { Text("Incident Description") },
                            placeholder = { Text("Describe the incident...") },
                            singleLine = true,
                            enabled = !isSending,
                            supportingText = {
                                Text("${state.body.length} / ${AprsTx.MAX_BODY_LENGTH} chars · hasImage = false")
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                PayloadMode.BEACON -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(
                            text = "Location Beacon transmits GPS coordinates, UTC timestamp, and sender tag [BCN|${TxViewModel.TEST_SENDER}] with no message body.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Wire Preview (TNC2)",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = state.previewTnc2,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Button(
                onClick = viewModel::transmit,
                enabled = !isSending,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isSending) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Transmitting AFSK Audio...")
                    }
                } else {
                    Text("Transmit ${state.mode.label}")
                }
            }

            StatusCard(state = state)
        }
    }
}

@Composable
private fun StatusCard(state: TxUiState) {
    val (label, containerColor) = when (val status = state.status) {
        TxStatus.Idle -> "Idle" to MaterialTheme.colorScheme.surfaceVariant
        TxStatus.Sending -> "Sending (playing out speaker)..." to MaterialTheme.colorScheme.primaryContainer
        TxStatus.AudioComplete -> "Audio Complete" to MaterialTheme.colorScheme.secondaryContainer
        is TxStatus.Failed -> "Failed: ${status.error}" to MaterialTheme.colorScheme.errorContainer
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Status: $label",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            state.lastTnc2?.let { tnc2 ->
                Text(
                    text = "Last TX: $tnc2",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            state.lastFrameBytes?.let { bytes ->
                Text(
                    text = "AX.25 frame: $bytes bytes (+ 2B CRC in javAX25)",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
