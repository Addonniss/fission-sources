package dev.infusion.source.jellyfin.playback

import dev.infusion.core.network.NetworkLimits
import dev.infusion.core.playback.api.ChapterMark
import dev.infusion.core.playback.api.PlaybackSegment
import dev.infusion.core.playback.api.SegmentEvidence
import dev.infusion.core.playback.api.SegmentProvenance
import dev.infusion.core.playback.api.SegmentType
import dev.infusion.source.jellyfin.auth.JellyfinAuthenticator
import dev.infusion.source.jellyfin.auth.JellyfinSession
import dev.infusion.source.jellyfin.client.ApiResult
import dev.infusion.source.jellyfin.client.JellyfinClient
import dev.infusion.source.jellyfin.wire.MediaSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class JellyfinSegmentEvidence(
    private val client: JellyfinClient,
    private val authenticator: JellyfinAuthenticator,
    private val sessionProvider: () -> JellyfinSession?,
    private val limits: NetworkLimits = NetworkLimits.DEFAULT,
) {

    data class Result(
        val chapters: List<ChapterMark> = emptyList(),
        val segments: List<PlaybackSegment> = emptyList(),
    ) {
        companion object {
            val EMPTY: Result = Result()
        }
    }

    suspend fun evidence(itemId: String): Result = withContext(Dispatchers.IO) {
        if (itemId.isBlank()) return@withContext Result.EMPTY
        val session = sessionProvider() ?: return@withContext Result.EMPTY
        val token = authenticator.token(session) ?: return@withContext Result.EMPTY

        val chapters = when (val result = client.item(session.userId, itemId, token, limits)) {
            is ApiResult.Ok -> result.value.chapters.mapIndexedNotNull { index, chapter -> toChapterMark(index, chapter) }
            is ApiResult.Err -> emptyList()
        }
        val segments = when (val result = client.mediaSegments(itemId, token, limits)) {
            is ApiResult.Ok -> result.value.mapNotNull { toPlaybackSegment(itemId, it) }
            is ApiResult.Err -> emptyList()
        }
        Result(chapters = chapters, segments = segments)
    }

    companion object {

        const val TICKS_PER_MILLISECOND: Long = 10_000L

        internal fun toChapterMark(index: Int, chapter: dev.infusion.source.jellyfin.wire.ChapterInfo): ChapterMark? {
            val ticks = chapter.startPositionTicks ?: return null
            if (ticks < 0L) return null
            return ChapterMark(startMs = ticks / TICKS_PER_MILLISECOND, title = chapter.name, index = index)
        }

        internal fun toPlaybackSegment(itemId: String, segment: MediaSegment): PlaybackSegment? {
            val type = mapType(segment.type) ?: return null
            val startTicks = segment.startTicks ?: return null
            if (startTicks < 0L) return null
            val endMs = segment.endTicks?.takeIf { it > startTicks }?.div(TICKS_PER_MILLISECOND)
            return PlaybackSegment(
                type = type,
                startMs = startTicks / TICKS_PER_MILLISECOND,
                endMs = endMs,
                provenance = SegmentProvenance.JELLYFIN,

                confidence = null,
                evidence = SegmentEvidence.Server(itemRef = itemId, kind = segment.type ?: type.name),
            )
        }

        internal fun mapType(serverType: String?): SegmentType? = when (serverType?.trim()?.lowercase()) {
            "intro" -> SegmentType.INTRO
            "recap" -> SegmentType.RECAP

            "outro" -> SegmentType.CREDITS
            "preview" -> SegmentType.PREVIEW
            else -> null
        }
    }
}
