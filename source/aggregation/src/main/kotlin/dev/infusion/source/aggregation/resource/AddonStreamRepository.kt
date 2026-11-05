package dev.infusion.source.aggregation.resource

import dev.infusion.source.stremio.StremioResult
import dev.infusion.source.stremio.policy.StremioContentType
import dev.infusion.source.stremio.wire.Resource
import dev.infusion.source.stremio.wire.StremioStreamResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface AddonStreamRepository {

    fun streams(type: StremioContentType, id: String): Flow<StreamBatch>

    suspend fun streamsFrom(addonId: String, type: StremioContentType, id: String): StremioResult<StremioStreamResponse>

    val states: StateFlow<Map<String, ProviderResourceState<StremioStreamResponse>>>
}

data class StreamBatch(
    val addonId: String,
    val addonDisplayName: String,
    val state: ProviderResourceState<StremioStreamResponse>,
) {

    val response: StremioStreamResponse? get() = (state as? ProviderResourceState.Loaded)?.value
}
