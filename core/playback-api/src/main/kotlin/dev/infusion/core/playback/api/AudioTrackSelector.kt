package dev.infusion.core.playback.api

object AudioTrackSelector {

    fun decide(
        inventory: TrackInventory,
        preferences: AudioLanguagePreferences,
        systemLanguages: List<String> = emptyList(),
    ): AudioSelectionDecision {

        val eligible = inventory.audioTracks
            .withIndex()
            .filter { (_, track) -> track.supported != false }
        if (eligible.isEmpty()) return AudioSelectionDecision.LeaveToEngine

        val primaryCandidates = candidatesOf(preferences.primary, systemLanguages)
        val secondaryCandidates = candidatesOf(preferences.secondary, systemLanguages)
            .filterNot { candidate -> candidate in primaryCandidates.toSet() }

        val tiers = listOf(
            Tier(primaryCandidates, AudioMatchReason.PRIMARY_EXACT, AudioMatchReason.PRIMARY_BASE),
            Tier(secondaryCandidates, AudioMatchReason.SECONDARY_EXACT, AudioMatchReason.SECONDARY_BASE),
        )
        for (tier in tiers) {
            if (tier.candidates.isEmpty()) continue
            matchInTier(eligible, tier.candidates, exact = true)
                ?.let { return AudioSelectionDecision.Select(it.id, tier.exactReason) }
            matchInTier(eligible, tier.candidates, exact = false)
                ?.let { return AudioSelectionDecision.Select(it.id, tier.baseReason) }
        }
        return AudioSelectionDecision.LeaveToEngine
    }

    private class Tier(
        val candidates: List<String>,
        val exactReason: AudioMatchReason,
        val baseReason: AudioMatchReason,
    )

    internal fun candidateTagsOf(
        preference: AudioLanguagePreference,
        systemLanguages: List<String>,
    ): List<String> = when (preference) {
        AudioLanguagePreference.None -> emptyList()
        AudioLanguagePreference.SystemLanguage ->
            systemLanguages.mapNotNull { AudioLanguageTag.canonicalize(it) }.distinct()
        is AudioLanguagePreference.Tag -> listOf(preference.value)
    }

    private fun candidatesOf(
        preference: AudioLanguagePreference,
        systemLanguages: List<String>,
    ): List<String> = candidateTagsOf(preference, systemLanguages)

    private fun matchInTier(
        eligible: List<IndexedValue<AudioTrack>>,
        candidates: List<String>,
        exact: Boolean,
    ): AudioTrack? {
        val ordinary = eligible.filter { (_, track) ->
            track.roles.none { role -> role == TrackRole.COMMENTARY || role == TrackRole.DESCRIPTIVE }
        }
        for (candidate in candidates) {
            val matches = ordinary.filter { (_, track) ->
                if (exact) {
                    AudioLanguageTag.exactEquals(candidate, track.language)
                } else {
                    AudioLanguageTag.baseEquals(candidate, track.language)
                }
            }
            if (matches.isNotEmpty()) return tieBreak(matches)
        }
        return null
    }

    private fun tieBreak(candidates: List<IndexedValue<AudioTrack>>): AudioTrack =
        candidates.minWith(
            compareBy(
                { indexed -> if (indexed.value.isDefault == true) 0 else 1 },
                { indexed -> if (indexed.value.supported == true) 0 else 1 },
                { indexed -> -(indexed.value.channelCount ?: UNKNOWN_CHANNEL_COUNT) },
                { indexed -> indexed.index },
            ),
        ).value

    private const val UNKNOWN_CHANNEL_COUNT = -1
}
