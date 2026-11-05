package dev.infusion.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.infusion.core.model.PlayableCandidate
import dev.infusion.feature.vertical.sourcepicker.PlaybackStartPhase
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration

internal const val PlaybackPreparationTag = "playback-preparation"

internal const val PlaybackPreparationSourceTag = "playback-preparation-source"

private const val PreparationScrimAlpha = 0.55f

data class PlaybackPreparationPresentation(
    val backdropLocator: String? = null,
    val logoLocator: String? = null,
    val title: String? = null,
    val providerLabel: String,

    val sourceLabel: String? = null,
)

@Composable
fun TvPlaybackPreparationScreen(
    phase: PlaybackStartPhase,
    backdropLocator: String?,
    logoLocator: String?,
    title: String?,
    providerLabel: (PlayableCandidate) -> String,

    fileLabel: (PlayableCandidate) -> String?,
    onBack: () -> Unit,
) {
    val selected = phase as? PlaybackStartPhase.Selected
    PreparationSurface(
        backdropLocator = backdropLocator,
        logoLocator = logoLocator,
        title = title,
        providerLabel = selected?.let { providerLabel(it.candidate) },
        sourceLabel = selected?.candidate?.let(fileLabel),
        onBack = onBack,
    )
}

@Composable
fun TvPlaybackPreparationOverlay(presentation: PlaybackPreparationPresentation) {
    PreparationSurface(
        backdropLocator = presentation.backdropLocator,
        logoLocator = presentation.logoLocator,
        title = presentation.title,
        providerLabel = presentation.providerLabel,
        sourceLabel = presentation.sourceLabel,
        onBack = null,
    )
}

@Composable
private fun PreparationSurface(
    backdropLocator: String?,
    logoLocator: String?,
    title: String?,
    providerLabel: String?,
    sourceLabel: String?,
    onBack: (() -> Unit)?,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(PlaybackPreparationTag)

            .onPreviewKeyEvent { event ->
                if (onBack == null) return@onPreviewKeyEvent false
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (event.key != Key.Back && event.key != Key.Escape) return@onPreviewKeyEvent false
                onBack()
                true
            },
    ) {

        TvDetailsBackdropCanvas(backdropLocator = backdropLocator, modifier = Modifier.fillMaxSize())

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = PreparationScrimAlpha)),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .padding(horizontal = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {

            if (logoLocator != null) {
                TvCanonicalLogoHeader(logoLocator = logoLocator, modifier = Modifier.fillMaxWidth())
            } else if (!title.isNullOrBlank()) {
                Text(
                    title,
                    style = InfusionTvScaleCalibration.Type.Headline,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (providerLabel != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(PlaybackPreparationSourceTag),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(providerLabel, style = InfusionTvScaleCalibration.Type.Emphasis)
                    if (sourceLabel != null) {
                        Text(
                            sourceLabel,
                            style = InfusionTvScaleCalibration.Type.Body,
                            color = Color.White.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
