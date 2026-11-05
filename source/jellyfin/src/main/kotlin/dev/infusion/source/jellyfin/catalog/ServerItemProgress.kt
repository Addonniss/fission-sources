package dev.infusion.source.jellyfin.catalog

import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TitleKind

data class ServerItemProgress(
    val nativeId: String,
    val titleId: TitleId?,
    val kind: TitleKind,

    val positionMs: Long,

    val runtimeMs: Long?,

    val observedAt: java.time.Instant? = null,
)

data class PostPlaybackItemState(
    val nativeId: String,

    val positionMs: Long,

    val runtimeMs: Long?,

    val lastPlayedAt: java.time.Instant?,

    val played: Boolean,
)

data class ServerProgressBatch(
    val sourceId: String,
    val userId: String,
    val items: List<ServerItemProgress>,

    val requestedCount: Int,
)
