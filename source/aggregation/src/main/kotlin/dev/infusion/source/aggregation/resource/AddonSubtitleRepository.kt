package dev.infusion.source.aggregation.resource

import dev.infusion.source.stremio.StremioResult
import dev.infusion.source.stremio.policy.StremioContentType
import dev.infusion.source.stremio.wire.Resource
import dev.infusion.source.stremio.wire.StremioSubtitlesResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface AddonSubtitleRepository {

    fun subtitles(
        type: StremioContentType,
        id: String,
        videoId: String? = null,
        hash: String? = null,
        size: Long? = null,
        filename: String? = null,
    ): Flow<SubtitleBatch>

    val states: StateFlow<SubtitleQueryState?>
}

data class SubtitleQueryState(
    val type: StremioContentType,
    val id: String,
    val providers: List<SubtitleBatch>,
)

data class SubtitleBatch(
    val addonId: String,
    val addonDisplayName: String,
    val state: ProviderResourceState<StremioSubtitlesResponse>,
) {

    val response: StremioSubtitlesResponse? get() = (state as? ProviderResourceState.Loaded)?.value
}
