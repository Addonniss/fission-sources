package dev.infusion.tv.di

import dev.infusion.core.model.ArtworkLocator
import dev.infusion.core.network.NetworkLimits

internal object CollectionArtworkTransport {

    const val STILL_MAX_RESPONSE_BYTES: Long = 6L * 1024 * 1024

    const val FOCUS_MAX_RESPONSE_BYTES: Long = 12L * 1024 * 1024

    val stillLimits: NetworkLimits = limits(STILL_MAX_RESPONSE_BYTES)

    val focusLimits: NetworkLimits = limits(FOCUS_MAX_RESPONSE_BYTES)

    fun limitsForSlot(slot: String): NetworkLimits =
        if (slot == ArtworkLocator.COLLECTION_SLOT_FOCUS) focusLimits else stillLimits

    private fun limits(maxResponseBytes: Long): NetworkLimits = NetworkLimits(
        maxResponseBytes = maxResponseBytes,
        maxDecompressedBytes = maxResponseBytes,
        maxDecompressionRatio = 8,
        connectTimeoutMs = 10_000,
        readTimeoutMs = 20_000,
        totalTimeoutMs = 30_000,
        maxRedirects = 2,
    )
}
