package dev.infusion.tv.music

import dev.infusion.core.domain.music.MusicQueueEntry
import dev.infusion.core.domain.music.QueueEntryId
import dev.infusion.feature.vertical.jellyfin.JellyfinTabRowItem

fun JellyfinTabRowItem.asQueueOccurrence(): MusicQueueEntry = MusicQueueEntry(
    occurrenceId = QueueEntryId("browse:$nativeItemId"),
    trackNativeId = nativeItemId,
    albumNativeId = parentNativeId?.takeIf { it.isNotBlank() },
    albumArtistNativeId = albumArtistNativeId?.takeIf { it.isNotBlank() },
    title = displayTitle,
    artist = albumArtistName?.takeIf { it.isNotBlank() },
    albumName = albumName?.takeIf { it.isNotBlank() },

    durationMs = runtimeTicks?.div(10_000L)?.takeIf { it > 0L },
    membershipId = playlistItemId?.takeIf { it.isNotBlank() },
)

fun JellyfinTabRowItem.isSongBrowsingRow(): Boolean =
    nativeType == "Audio" && !unavailable
