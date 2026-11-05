package dev.infusion.core.playback.media3

import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.Chapter
import dev.infusion.core.playback.api.ChapterMark

@UnstableApi
internal fun mapEmbeddedChapters(formats: Iterable<Format>): List<ChapterMark> =
    formats
        .mapNotNull { it.metadata }
        .flatMap { metadata -> (0 until metadata.length()).mapNotNull { metadata.get(it) } }
        .filterIsInstance<Chapter>()
        .sortedBy { it.startTimeMs }
        .distinctBy { it.startTimeMs }
        .mapIndexed { index, chapter ->
            ChapterMark(
                startMs = chapter.startTimeMs,
                title = chapter.title?.value,
                index = index,
            )
        }

@UnstableApi
internal fun mapEmbeddedChapters(tracks: Tracks): List<ChapterMark> = mapEmbeddedChapters(
    tracks.groups.flatMap { group -> (0 until group.length).map { group.getTrackFormat(it) } },
)
