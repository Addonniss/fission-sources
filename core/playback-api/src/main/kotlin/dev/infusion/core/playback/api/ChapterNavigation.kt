package dev.infusion.core.playback.api

object ChapterNavigation {

    const val FALLBACK_STEP_MS: Long = 5 * 60_000L

    data class ChapterBoundary(val startMs: Long, val title: String?)

    data class Destination(
        val positionMs: Long,
        val forward: Boolean,
        val chapterNumber: Int?,
        val chapterTitle: String?,
        val fallback: Boolean,
    )

    fun boundaries(timeline: PlaybackSegmentTimeline?): List<ChapterBoundary> =
        timeline?.segments
            .orEmpty()
            .filter { it.type == SegmentType.CHAPTER }
            .map { segment ->
                ChapterBoundary(
                    startMs = segment.startMs,
                    title = (segment.evidence as? SegmentEvidence.Chapter)?.title,
                )
            }
            .filter { it.startMs >= 0L }
            .sortedBy { it.startMs }
            .distinctBy { it.startMs }
            .filter { boundary ->
                val duration = timeline?.durationMs
                duration == null || boundary.startMs < duration
            }

    fun next(
        timeline: PlaybackSegmentTimeline?,
        positionMs: Long,
        durationMs: Long?,
    ): Destination? = navigate(timeline, positionMs, durationMs, forward = true)

    fun previous(
        timeline: PlaybackSegmentTimeline?,
        positionMs: Long,
        durationMs: Long?,
    ): Destination? = navigate(timeline, positionMs, durationMs, forward = false)

    private fun navigate(
        timeline: PlaybackSegmentTimeline?,
        positionMs: Long,
        durationMs: Long?,
        forward: Boolean,
    ): Destination? {
        val chapters = boundaries(timeline)
        if (chapters.isNotEmpty()) {

            val index = if (forward) {
                chapters.indexOfFirst { it.startMs > positionMs }
            } else {
                chapters.indexOfLast { it.startMs < positionMs }
            }
            if (index < 0) return null
            val chapter = chapters[index]
            return Destination(
                positionMs = chapter.startMs,
                forward = forward,
                chapterNumber = index + 1,
                chapterTitle = chapter.title,
                fallback = false,
            )
        }

        val raw = if (forward) {
            positionMs + FALLBACK_STEP_MS
        } else {
            positionMs - FALLBACK_STEP_MS
        }
        val target = when {
            raw < 0L -> 0L
            durationMs != null && durationMs > 0L && raw > durationMs -> durationMs
            else -> raw
        }
        if (target == positionMs) return null
        return Destination(
            positionMs = target,
            forward = forward,
            chapterNumber = null,
            chapterTitle = null,
            fallback = true,
        )
    }
}
