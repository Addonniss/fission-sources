package dev.infusion.core.playback.api

object SubtitleTrackSelector {

    fun decide(
        inventory: TrackInventory,
        preferences: SubtitlePreferences,
        selectedAudioLanguage: String? = null,
    ): SubtitleSelectionDecision {
        if (preferences.mode == SubtitleModePreference.OFF) return SubtitleSelectionDecision.Off

        val permitted = inventory.subtitleTracks.withIndex().filter { (_, track) ->
            isPermitted(track, preferences, selectedAudioLanguage)
        }

        val tiers = listOf(
            Tier(primaryCandidates(preferences.primary), SubtitleMatchReason.PRIMARY_EXACT, SubtitleMatchReason.PRIMARY_BASE),
            Tier(primaryCandidates(preferences.secondary), SubtitleMatchReason.SECONDARY_EXACT, SubtitleMatchReason.SECONDARY_BASE),
        )
        for (tier in tiers) {
            if (tier.candidates.isEmpty()) continue
            matchInTier(permitted, tier.candidates, exact = true)
                ?.let { return SubtitleSelectionDecision.Select(it.id, tier.exactReason) }
            matchInTier(permitted, tier.candidates, exact = false)
                ?.let { return SubtitleSelectionDecision.Select(it.id, tier.baseReason) }
        }
        return if (preferences.isRestrictive) {
            SubtitleSelectionDecision.NoEligible
        } else {
            SubtitleSelectionDecision.LeaveToEngine
        }
    }

    private class Tier(
        val candidates: List<String>,
        val exactReason: SubtitleMatchReason,
        val baseReason: SubtitleMatchReason,
    )

    private fun primaryCandidates(preference: SubtitleLanguagePreference): List<String> = when (preference) {
        SubtitleLanguagePreference.None -> emptyList()
        is SubtitleLanguagePreference.Tag -> listOf(preference.value)
    }

    private fun isPermitted(
        track: SubtitleTrack,
        preferences: SubtitlePreferences,
        selectedAudioLanguage: String?,
    ): Boolean {
        if (track.supported == false) return false

        if (SubtitleForcedRule.isRejectedForced(track, selectedAudioLanguage)) return false

        if (selectedAudioLanguage != null &&
            AudioLanguageTag.baseEquals(track.language, selectedAudioLanguage)
        ) {
            when (preferences.sameLanguage) {
                SubtitleSameLanguagePolicy.OFF -> return false
                SubtitleSameLanguagePolicy.FORCED_ONLY -> if (track.isForced != true) return false
                SubtitleSameLanguagePolicy.FULL -> Unit
            }
        }

        val type = track.typeOf
        return when (preferences.type) {
            SubtitleTypePreference.AUTOMATIC -> true
            SubtitleTypePreference.STANDARD -> type == SubtitleTrackType.OTHER
            SubtitleTypePreference.SDH -> type == SubtitleTrackType.SDH
            SubtitleTypePreference.FORCED_ONLY -> type == SubtitleTrackType.FORCED
        }
    }

    private fun matchInTier(
        permitted: List<IndexedValue<SubtitleTrack>>,
        candidates: List<String>,
        exact: Boolean,
    ): SubtitleTrack? {
        for (candidate in candidates) {
            val matches = permitted.filter { (_, track) ->
                if (exact) {
                    AudioLanguageTag.exactEquals(candidate, track.language)
                } else {
                    AudioLanguageTag.baseEquals(candidate, track.language)
                }
            }
            if (matches.isNotEmpty()) return bestSubtitleCandidate(matches)
        }
        return null
    }
}

internal fun bestSubtitleCandidate(candidates: List<IndexedValue<SubtitleTrack>>): SubtitleTrack? =
    candidates.minWithOrNull(
        compareBy(
            { indexed -> rankSubtitleType(indexed.value.typeOf) },
            { indexed -> rankSubtitleProvenance(indexed.value.provenance) },
            { indexed -> if (indexed.value.isDefault == true) 0 else 1 },
            { indexed -> if (indexed.value.supported == true) 0 else 1 },
            { indexed -> indexed.index },
        ),
    )?.value

internal fun rankSubtitleType(type: SubtitleTrackType): Int = when (type) {
    SubtitleTrackType.OTHER -> 0
    SubtitleTrackType.SDH -> 1
    SubtitleTrackType.FORCED -> 2
}

internal fun rankSubtitleProvenance(provenance: SubtitleProvenance?): Int = when (provenance) {
    SubtitleProvenance.EMBEDDED -> 0
    null -> 1
    SubtitleProvenance.SIDECAR -> 2
}
