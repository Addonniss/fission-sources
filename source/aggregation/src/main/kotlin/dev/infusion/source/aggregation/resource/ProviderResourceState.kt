package dev.infusion.source.aggregation.resource

import dev.infusion.source.stremio.StremioFailure

sealed interface ProviderResourceState<out T> {
    data object Pending : ProviderResourceState<Nothing>
    data class Loaded<T>(val value: T, val status: Int) : ProviderResourceState<T>
    data class Failed(val failure: StremioFailure) : ProviderResourceState<Nothing>
    data object Skipped : ProviderResourceState<Nothing>
}
