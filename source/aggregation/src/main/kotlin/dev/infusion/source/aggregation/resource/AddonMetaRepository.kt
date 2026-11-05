package dev.infusion.source.aggregation.resource

import dev.infusion.source.stremio.StremioFailure
import dev.infusion.source.stremio.StremioResult
import dev.infusion.source.stremio.policy.StremioContentType
import dev.infusion.source.stremio.wire.StremioMetaResponse

interface AddonMetaRepository {

    suspend fun specific(addonId: String, type: StremioContentType, id: String): StremioResult<StremioMetaResponse>

    suspend fun all(type: StremioContentType, id: String): StremioMetaQueryResult

    suspend fun primary(
        type: StremioContentType,
        id: String,
        preferredAddonId: String? = null,
        speculative: Boolean = false,
    ): StremioMetaQueryResult

    fun lastStates(): Map<String, ProviderResourceState<StremioMetaResponse>>

    fun cached(type: StremioContentType, id: String): StremioMetaResponse?

    fun clear()
}

data class StremioMetaQueryResult(
    val response: StremioMetaResponse?,
    val addonId: String?,
    val states: Map<String, ProviderResourceState<StremioMetaResponse>>,
) {
    val succeeded: Boolean get() = response != null
}
