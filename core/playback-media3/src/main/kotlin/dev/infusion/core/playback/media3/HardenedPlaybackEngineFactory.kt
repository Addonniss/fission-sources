package dev.infusion.core.playback.media3

import android.content.Context
import dev.infusion.core.network.HardenedHttpClient
import dev.infusion.core.network.NetworkLimits
import dev.infusion.core.playback.api.DolbyVisionProfile7Fallback
import dev.infusion.core.playback.api.PlaybackEngine

object HardenedPlaybackEngineFactory {

    fun create(
        context: Context,
        transport: () -> HardenedHttpClient,
        sensitiveHeaders: Set<String>,
        limits: NetworkLimits = NetworkLimits.media(),

        dolbyVisionProfile7Fallback: () -> DolbyVisionProfile7Fallback = {
            DolbyVisionProfile7Fallback.DEFAULT
        },
    ): PlaybackEngine = Media3PlaybackEngine(
        context,
        httpDataSourceFactory = {
            HardenedHttpDataSourceFactory(
                transport = transport,
                limits = limits,
                sensitiveHeaders = sensitiveHeaders,
            )
        },
        dolbyVisionProfile7Fallback = dolbyVisionProfile7Fallback,
    )
}
