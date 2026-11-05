package dev.infusion.core.playback.api

object SegmentReconciliation {

    const val MIN_INTRO_TARGET_MS: Long = 20_000L

    const val MAX_PERCENT: Int = 50

    const val MIN_GAP_MS: Long = 20_000L

    const val START_FLOOR_MS: Long = 1_000L

    fun reconcile(
        input: SegmentEvidenceInput,
        providerSegments: List<PlaybackSegment>,
    ): PlaybackSegmentTimeline {
        val duration = input.durationMs?.takeIf { it > 0L }
        val validated = providerSegments.mapNotNull { validateProviderSegment(it, duration) }
        val serverSegments = input.serverSegments.mapNotNull { validateProviderSegment(it, duration) }
        val chapters = input.chapters
            .filter { it.startMs >= 0L }
            .distinctBy { it.startMs }
            .sortedBy { it.startMs }

        val onlineOpening = selectProviderOpening(validated, SegmentProvenance.THEINTRODB)
            ?: selectProviderOpening(validated, SegmentProvenance.INTRODB)

        val chapterOpening = chapterHeuristicOpening(chapters, duration)
        val serverOpening = selectProviderOpening(serverSegments, SegmentProvenance.JELLYFIN)
        val manualOpening = manualFallbackOpening(input.settings, duration)

        val effectiveOpening = if (input.identity.isMovie) {
            null
        } else {
            when {
                input.settings.onlineIntroMetadataPriority ->
                    onlineOpening ?: chapterOpening ?: serverOpening ?: manualOpening
                else ->
                    chapterOpening ?: serverOpening ?: onlineOpening ?: manualOpening
            }
        }

        val segments = mutableListOf<PlaybackSegment>()

        chapters.forEach { chapter ->
            segments += PlaybackSegment(
                type = SegmentType.CHAPTER,
                startMs = chapter.startMs,
                endMs = null,
                provenance = SegmentProvenance.CHAPTERS,
                evidence = SegmentEvidence.Chapter(index = chapter.index, title = chapter.title),
            )
        }

        validated
            .filter { it.type != SegmentType.INTRO && it.type != SegmentType.RECAP }
            .forEach { segments += it }

        serverSegments
            .filter { it.type != SegmentType.INTRO && it.type != SegmentType.RECAP }
            .forEach { segments += it }
        if (effectiveOpening != null) segments += effectiveOpening

        val ordered = segments.sortedWith(
            compareBy({ it.startMs }, { it.type.ordinal }, { it.provenance.ordinal }),
        )
        return PlaybackSegmentTimeline(durationMs = duration, segments = ordered)
    }

    internal fun chapterHeuristicOpening(
        chapters: List<ChapterMark>,
        durationMs: Long?,
    ): PlaybackSegment? {
        if (durationMs == null || durationMs <= 0L) return null
        val maxStart = durationMs * MAX_PERCENT / 100
        val candidates = mutableListOf<Long>()
        var previous: Long? = null
        for (chapter in chapters) {
            val start = chapter.startMs
            if (start <= MIN_INTRO_TARGET_MS) continue
            if (start > maxStart) continue
            if (previous != null && start - previous < MIN_GAP_MS) continue
            candidates += start
            previous = start
        }
        if (candidates.isEmpty()) return null
        val (start, end) = if (candidates.size >= 2) {
            candidates[0] to candidates[1]
        } else {
            START_FLOOR_MS to candidates[0]
        }
        if (end <= start) return null
        return PlaybackSegment(
            type = SegmentType.INTRO,
            startMs = start,
            endMs = end,
            provenance = SegmentProvenance.INFERRED,
            confidence = null,
            evidence = SegmentEvidence.Heuristic("chapters"),
        )
    }

    internal fun selectProviderOpening(
        segments: List<PlaybackSegment>,
        provenance: SegmentProvenance,
    ): PlaybackSegment? {
        val fromProvider = segments.filter { it.provenance == provenance && it.isClosedRange }
        if (fromProvider.isEmpty()) return null
        val recaps = fromProvider.filter { it.type == SegmentType.RECAP }
        val pool = recaps.ifEmpty { fromProvider.filter { it.type == SegmentType.INTRO } }
        return pool.minByOrNull { it.endMs ?: Long.MAX_VALUE }
    }

    private fun manualFallbackOpening(
        settings: SegmentEvidenceSettings,
        durationMs: Long?,
    ): PlaybackSegment? {
        if (!settings.manualFallbackEnabled) return null
        val start = settings.manualFallbackStartMs
        val end = settings.manualFallbackEndMs
        if (start < 0L || end <= start) return null
        if (durationMs != null && end > durationMs) return null
        return PlaybackSegment(
            type = SegmentType.INTRO,
            startMs = start,
            endMs = end,
            provenance = SegmentProvenance.MANUAL,
            confidence = null,
            evidence = SegmentEvidence.Manual("segment_intro_manual_fallback"),
        )
    }

    internal fun validateProviderSegment(
        segment: PlaybackSegment,
        durationMs: Long?,
    ): PlaybackSegment? {
        val start = segment.startMs
        if (start < 0L) return null
        val end = segment.endMs
        if (end == null) {

            if (start <= 0L) return null
            if (durationMs != null && start >= durationMs) return null
            return segment
        }
        if (end <= start) return null
        if (start < START_FLOOR_MS) return null
        if (durationMs != null && end > durationMs) return null
        return segment
    }
}
