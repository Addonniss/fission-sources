package dev.infusion.core.playback.api

object SelectedTrackResolution {

    fun resolvedAudioTrack(inventory: TrackInventory?, assertedTrackId: TrackId?): AudioTrack? {
        val tracks = inventory?.audioTracks.orEmpty()
        if (assertedTrackId != null) {
            tracks.firstOrNull { it.id == assertedTrackId }?.let { return it }
        }
        return tracks.firstOrNull { it.isSelected == true }
    }

    fun resolvedVideoTrack(inventory: TrackInventory?, assertedTrackId: TrackId? = null): VideoTrack? {
        val tracks = inventory?.videoTracks.orEmpty()
        if (assertedTrackId != null) {
            tracks.firstOrNull { it.id == assertedTrackId }?.let { return it }
        }
        return tracks.singleOrNull() ?: tracks.firstOrNull()
    }

    fun resolvedSubtitleTrack(inventory: TrackInventory?, assertedTrackId: TrackId?): SubtitleTrack? {
        val tracks = inventory?.subtitleTracks.orEmpty()
        if (assertedTrackId != null) {
            tracks.firstOrNull { it.id == assertedTrackId }?.let { return it }
        }
        return tracks.firstOrNull { it.isSelected == true }
    }

    fun selectedAudioLanguage(
        inventory: TrackInventory?,
        assertedAudioTrackId: TrackId?,
    ): String? = resolvedAudioTrack(inventory, assertedAudioTrackId)?.language
}
