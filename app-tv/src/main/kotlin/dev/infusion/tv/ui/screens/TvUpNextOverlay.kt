package dev.infusion.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.infusion.tv.ui.theme.InfusionTvButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.infusion.feature.vertical.model.PlayerUiState
import dev.infusion.feature.vertical.model.UpNextAvailability
import dev.infusion.tv.ui.focus.RequestFocusOnKey
import dev.infusion.tv.ui.focus.rememberFocusRequesters
import dev.infusion.tv.ui.focus.stableFocusModifier
import dev.infusion.tv.ui.theme.InfusionTvScaleCalibration

@Composable
fun TvUpNextOverlay(
    state: PlayerUiState,
    onPlayNext: () -> Unit,
    onDismiss: () -> Unit,
) {
    val requesters = rememberFocusRequesters()
    val upNext = state.upNext ?: return

    val actionable = upNext.nextEpisode != null &&
        upNext.availability == UpNextAvailability.AVAILABLE

    BackHandler {

        onDismiss()
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(48.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Up Next", style = InfusionTvScaleCalibration.Type.Headline)
            upNext.nextEpisode?.let {
                Text(
                    upNext.identityLabel,
                    style = InfusionTvScaleCalibration.Type.Emphasis,
                )
            } ?: Text(
                "No next episode is available.",
                style = InfusionTvScaleCalibration.Type.Body,
            )

            if (upNext.nextEpisode != null && upNext.availability == UpNextAvailability.NOT_YET_AIRED) {
                Text(
                    "This episode has not aired yet.",
                    style = InfusionTvScaleCalibration.Type.Body,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            ) {

                if (actionable) {
                    InfusionTvButton(
                        onClick = onPlayNext,
                        modifier = stableFocusModifier("play-next", requesters) {},
                    ) { Text("Play Next") }
                }
                InfusionTvButton(
                    onClick = onDismiss,
                    modifier = stableFocusModifier("dismiss", requesters) {},
                ) { Text("Dismiss / Back to Details") }
            }
        }
    }

    RequestFocusOnKey(
        key = if (actionable) "play-next" else "dismiss",
        requesters = requesters,
    )
}
