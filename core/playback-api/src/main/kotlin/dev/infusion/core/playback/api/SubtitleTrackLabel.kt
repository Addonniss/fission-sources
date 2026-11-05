package dev.infusion.core.playback.api

object SubtitleTrackLabel {

    const val NEUTRAL_EXTERNAL: String = "External subtitle"

    const val NEUTRAL_EMBEDDED: String = "Subtitle"

    fun primaries(tracks: List<SubtitleTrack>): List<String> {
        val undeclared = tracks.filter { declared(it) == null }
        val totals = undeclared.groupingBy { neutralLabel(it) }.eachCount()
        val seen = HashMap<String, Int>()
        return tracks.map { track ->
            val declared = declared(track)
            if (declared != null) {
                declared
            } else {
                val label = neutralLabel(track)
                val ordinal = (seen[label] ?: 0) + 1
                seen[label] = ordinal
                if ((totals[label] ?: 0) > 1) "$label $ordinal" else label
            }
        }
    }

    fun single(track: SubtitleTrack): String = primaries(listOf(track)).first()

    private fun declared(track: SubtitleTrack): String? =
        track.label?.takeIf { it.isNotBlank() } ?: track.language?.takeIf { it.isNotBlank() }

    private fun neutralLabel(track: SubtitleTrack): String =
        if (track.provenance == SubtitleProvenance.SIDECAR) NEUTRAL_EXTERNAL else NEUTRAL_EMBEDDED
}
