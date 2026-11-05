package dev.infusion.tv.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import dev.infusion.core.playback.api.PlaybackUiDolbyVisionLuminance

internal val LocalPlaybackUiColourScale: ProvidableCompositionLocal<Float> =
    compositionLocalOf { PlaybackUiDolbyVisionLuminance.IDENTITY_SCALE }

internal val LocalPlaybackUiColourApplied: ProvidableCompositionLocal<Float> =
    compositionLocalOf { PlaybackUiDolbyVisionLuminance.IDENTITY_SCALE }

internal fun nextAppliedScale(policy: Float, alreadyApplied: Float, active: Boolean): Float {
    if (isApplicableUiLuminanceScale(alreadyApplied)) return PlaybackUiDolbyVisionLuminance.IDENTITY_SCALE
    return if (active) policy else PlaybackUiDolbyVisionLuminance.IDENTITY_SCALE
}

@Composable
internal fun PlaybackUiColourProvider(scale: Float, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPlaybackUiColourScale provides scale, content = content)
}

@Composable
internal fun PlaybackUiColourHost(
    modifier: Modifier = Modifier.fillMaxSize(),
    active: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val policy = LocalPlaybackUiColourScale.current
    val alreadyApplied = LocalPlaybackUiColourApplied.current
    val applied = nextAppliedScale(policy, alreadyApplied, active)

    val carried = if (isApplicableUiLuminanceScale(applied)) applied else alreadyApplied
    CompositionLocalProvider(LocalPlaybackUiColourApplied provides carried) {
        Box(
            modifier = modifier.playbackUiDolbyVisionLuminance(applied),
            content = content,
        )
    }
}

@Composable
internal fun PlaybackUiColourWindow(
    modifier: Modifier = Modifier.fillMaxSize(),
    active: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    CompositionLocalProvider(
        LocalPlaybackUiColourApplied provides PlaybackUiDolbyVisionLuminance.IDENTITY_SCALE,
    ) {
        PlaybackUiColourHost(modifier = modifier, active = active, content = content)
    }
}
