package dev.infusion.core.playback.api

object NextEpisodeTrigger {

    const val CHAPTER_TAIL_GUARD_MS: Long = 5_000L

    fun triggerMs(
        priorityOnline: Boolean,
        credits: List<PlaybackSegment>,
        chapters: List<ChapterMark>,
        durationMs: Long?,
        fallbackPercent: Int,
    ): Long? {
        val duration = durationMs?.takeIf { it > 0L }

        val remoteCredits = earliestStart(credits.filter { it.provenance == SegmentProvenance.THEINTRODB })
            ?: earliestStart(credits.filter { it.provenance == SegmentProvenance.INTRODB })

        val chapterTrigger = lastChapterBefore(duration, chapters)
        val jellyfinCredits = earliestStart(credits.filter { it.provenance == SegmentProvenance.JELLYFIN })
        val otherCredits = earliestStart(
            credits.filter {
                it.provenance != SegmentProvenance.THEINTRODB &&
                    it.provenance != SegmentProvenance.INTRODB &&
                    it.provenance != SegmentProvenance.JELLYFIN
            },
        )
        val onlineTier = listOfNotNull(remoteCredits, otherCredits)
        val localTier = listOfNotNull(chapterTrigger, jellyfinCredits)

        val ordered = if (priorityOnline) onlineTier + localTier else localTier + onlineTier
        ordered.firstOrNull { isUsableTrigger(it, duration) }?.let { return it }

        val percent = duration?.let { percentOf(it, fallbackPercent) }
        return percent?.takeIf { isUsableTrigger(it, duration) }
    }

    private fun earliestStart(segments: List<PlaybackSegment>): Long? =
        segments.asSequence()
            .map { it.startMs }
            .filter { it > 0L }
            .minOrNull()

    private fun lastChapterBefore(durationMs: Long?, chapters: List<ChapterMark>): Long? {
        val duration = durationMs?.takeIf { it > 0L } ?: return null
        val bound = duration - CHAPTER_TAIL_GUARD_MS
        if (bound <= 0L) return null
        return chapters.asSequence()
            .map { it.startMs }
            .filter { it > 0L && it < bound }
            .maxOrNull()
    }

    private fun percentOf(durationMs: Long, percent: Int): Long {
        val clamped = percent.coerceIn(
            NextEpisodeSettings.FALLBACK_PERCENT_MIN,
            NextEpisodeSettings.FALLBACK_PERCENT_MAX,
        )
        return durationMs * clamped / 100L
    }

    private fun isUsableTrigger(triggerMs: Long, durationMs: Long?): Boolean = when {
        triggerMs <= 0L -> false
        durationMs != null && durationMs > 0L -> triggerMs < durationMs
        else -> true
    }
}
