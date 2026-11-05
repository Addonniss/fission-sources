package dev.infusion.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import dev.infusion.core.artworkimage.MediaArtworkImage
import dev.infusion.core.model.MediaArtworkRef
import dev.infusion.feature.vertical.details.EpisodeDetailsStateHolder
import dev.infusion.tv.ui.focus.RequestFocusOnKey
import dev.infusion.tv.ui.focus.rememberFocusRequesters
import dev.infusion.tv.ui.presentation.artworkTargetPx
import dev.infusion.tv.ui.theme.InfusionTvDetailsMetrics
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration

@Composable
fun TvEpisodeDetailsScreen(
    stateHolder: EpisodeDetailsStateHolder,
    onPrimaryAction: () -> Unit,
    onPlayFromBeginning: () -> Unit,
    onChooseSource: () -> Unit,
    onMarkWatched: () -> Unit,
    onMarkUnwatched: () -> Unit,
    onBack: () -> Unit,
) {
    val state by stateHolder.state.collectAsStateWithLifecycle()
    val requesters = rememberFocusRequesters()
    RequestFocusOnKey(key = state.initialFocusKey, requesters = requesters)

    Box(modifier = Modifier.fillMaxSize()) {
        TvDetailsBackdropCanvas(backdropLocator = state.parentShowBackdropLocator)

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {

            val contentMinHeight = (maxHeight - InfusionTvDetailsMetrics.PageHorizontalPadding * 2)
                .coerceAtLeast(0.dp)
            Column(
                modifier = Modifier
                    .fillMaxSize()

                    .verticalScroll(rememberScrollState())

                    .padding(InfusionTvDetailsMetrics.PageHorizontalPadding)
                    .heightIn(min = contentMinHeight),
                verticalArrangement = Arrangement.Bottom,
            ) {

                Column(verticalArrangement = Arrangement.spacedBy(EpisodeDetailsGroupGap)) {

                    EpisodeStillForeground(ref = state.episodeStillRef)

                    Text(
                        state.seriesTitle?.let { "$it — S${state.seasonNumber}E${state.episodeNumber}" }
                            ?: "S${state.seasonNumber}E${state.episodeNumber}",
                        style = InfusionTvScaleCalibration.Type.Body,
                    )

                    state.displayTitle?.let {
                        Text(it, style = InfusionTvScaleCalibration.Type.Body)
                    }

                    state.selectedEditionLabel?.let {
                        Text("Selected: $it", style = InfusionTvScaleCalibration.Type.Body)
                    }
                    state.synopsis?.let {
                        Text(it, style = InfusionTvScaleCalibration.Type.Body, maxLines = 6)
                    }
                    state.unplayableReason?.let {
                        Text(it, style = InfusionTvScaleCalibration.Type.Body)
                    }
                }

                DetailsStageOneActionLine(
                    items = episodeStageActions(state),
                    lineTag = EpisodeActionLineTag,
                    requesters = requesters,
                    onPrimaryAction = onPrimaryAction,
                    onRestart = onPlayFromBeginning,
                    onSeen = onMarkWatched,
                    onNotSeen = onMarkUnwatched,
                    onSources = onChooseSource,

                    onTrailer = {},
                )
            }
        }
    }
}

private val EpisodeDetailsGroupGap = 10.dp

internal const val EpisodeStillTag = "episode:still"

@Composable
private fun EpisodeStillForeground(ref: MediaArtworkRef?) {
    Box(
        modifier = Modifier
            .size(InfusionTvDetailsMetrics.EpisodeStillSize)
            .testTag(EpisodeStillTag),
    ) {
        MediaArtworkImage(
            ref = ref,
            targetSize = artworkTargetPx(InfusionTvDetailsMetrics.EpisodeStillSize),
            contentDescription = "Episode still",
            modifier = Modifier.fillMaxSize(),
        )
    }
}
