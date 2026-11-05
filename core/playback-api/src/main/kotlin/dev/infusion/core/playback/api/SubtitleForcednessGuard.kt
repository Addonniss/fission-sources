package dev.infusion.core.playback.api

object SubtitleForcedRule {

    fun matchesSelectedAudioLanguage(track: SubtitleTrack, selectedAudioLanguage: String?): Boolean =
        AudioLanguageTag.baseEquals(track.language, selectedAudioLanguage)

    fun isRejectedForced(track: SubtitleTrack, selectedAudioLanguage: String?): Boolean =
        track.isForced == true && !matchesSelectedAudioLanguage(track, selectedAudioLanguage)
}

sealed interface SubtitleForcednessVerdict {

    data object EngineDefaultStands : SubtitleForcednessVerdict

    data class Assert(val trackId: TrackId) : SubtitleForcednessVerdict

    data object DisableText : SubtitleForcednessVerdict
}

object SubtitleForcednessGuard {

    fun engineCouldReach(track: SubtitleTrack, captioningLanguage: String?): Boolean =
        track.isDefault == true || AudioLanguageTag.baseEquals(track.language, captioningLanguage)

    fun reconcile(
        inventory: TrackInventory,
        selectedAudioLanguage: String?,
        captioningLanguage: String?,
    ): SubtitleForcednessVerdict {
        val rejectedAndReachable = inventory.subtitleTracks.withIndex().any { (_, track) ->
            track.supported != false &&
                SubtitleForcedRule.isRejectedForced(track, selectedAudioLanguage) &&
                engineCouldReach(track, captioningLanguage)
        }
        if (!rejectedAndReachable) return SubtitleForcednessVerdict.EngineDefaultStands

        val permitted = inventory.subtitleTracks.withIndex().filter { (_, track) ->
            track.supported != false &&
                !SubtitleForcedRule.isRejectedForced(track, selectedAudioLanguage) &&
                engineCouldReach(track, captioningLanguage)
        }
        val best = bestSubtitleCandidate(permitted)
        return if (best == null) {
            SubtitleForcednessVerdict.DisableText
        } else {
            SubtitleForcednessVerdict.Assert(best.id)
        }
    }
}
