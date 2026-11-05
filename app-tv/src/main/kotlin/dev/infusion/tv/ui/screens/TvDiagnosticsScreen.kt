package dev.infusion.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.infusion.tv.ui.theme.InfusionTvButton
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.infusion.feature.vertical.diagnostics.DiagnosticExportStateHolder
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration
import dev.infusion.tv.ui.theme.InfusionTvTheme

@Composable
fun TvDiagnosticsScreen(
    holder: DiagnosticExportStateHolder,
    onBack: () -> Unit,
    streamLimitObservations: List<String> = emptyList(),
) {
    val preview = remember(holder) { holder.preview() }
    var exportedJson by remember { mutableStateOf<String?>(null) }
    var exportedId by remember { mutableStateOf<String?>(null) }
    var exportedExpiry by remember { mutableStateOf(0L) }
    var refusedNotice by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Diagnostics", style = InfusionTvScaleCalibration.Type.Headline)
        Text("Preview of what an export contains (never secrets, URLs, tokens, filenames, LAN addresses or headers):")
        Text(preview, style = InfusionTvScaleCalibration.Type.Body)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InfusionTvButton(onClick = {
                refusedNotice = null
                val result = holder.requestExport()
                if (result != null) {
                    exportedId = result.bundleId
                    exportedExpiry = result.expiresAtMs
                    exportedJson = result.json
                } else {
                    exportedJson = null
                    exportedId = null
                    refusedNotice = "Export refused: this diagnostic bundle has expired locally. Generate a new one and try again."
                }
            }) {
                Text("Export diagnostics")
            }
            InfusionTvButton(onClick = onBack) {
                Text("Back")
            }
        }
        refusedNotice?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        if (exportedId != null) {
            Text("Exported bundle $exportedId", style = InfusionTvScaleCalibration.Type.Body)
            Text("Local expiry at $exportedExpiry, stored on this device only.", style = InfusionTvScaleCalibration.Type.Body)
            Text(
                text = exportedJson ?: "",
                style = InfusionTvScaleCalibration.Type.Body,
            )
        } else if (!holder.currentExportIsValid()) {
            Text("No active export.", style = InfusionTvScaleCalibration.Type.Body)
        }

        if (streamLimitObservations.isNotEmpty()) {
            Text("Stream resolution observations", style = InfusionTvScaleCalibration.Type.Emphasis)
            Text(
                text = streamLimitObservations.joinToString("\n"),
                style = InfusionTvScaleCalibration.Type.Body,
            )
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun TvDiagnosticsScreenPreview() {
    val holder = DiagnosticExportStateHolder(
        app = dev.infusion.core.network.DiagnosticAppDescriptor("Infusion TV", "0.8.0", 36),
        counts = { dev.infusion.core.network.DiagnosticCounts(1, 0, 0) },
        sourceDiagnostics = { emptyList() },
        playerDiagnostics = { emptyList() },
        nowMs = { 0L },
    )
    InfusionTvTheme {
        TvDiagnosticsScreen(holder = holder, onBack = {})
    }
}