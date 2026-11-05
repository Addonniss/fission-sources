package dev.infusion.core.playback.api

object AudioRouteFallback {

    data class RouteKey(
        val language: String? = null,
        val codec: String? = null,
        val channelCount: Int? = null,
        val sampleRate: Int? = null,
        val roles: Set<TrackRole> = emptySet(),
    )

    data class Plan(
        val order: List<RouteKey>,
        val attempted: Set<RouteKey> = emptySet(),
    ) {

        val remaining: Int get() = order.count { it !in attempted }
    }

    fun keyOf(track: AudioTrack): RouteKey = RouteKey(
        language = AudioLanguageTag.canonicalize(track.language),
        codec = track.codec?.takeIf { it.isNotBlank() },
        channelCount = track.channelCount,
        sampleRate = track.sampleRate,
        roles = track.roles,
    )

    fun keyOf(inventory: TrackInventory, trackId: TrackId?): RouteKey? =
        trackId?.let { id -> inventory.audioTracks.firstOrNull { it.id == id } }?.let(::keyOf)

    fun involvesAudioRoute(failure: PlaybackFailure, progress: AudioOutputProgress): Boolean =
        failure.category == FailureCategory.AUDIO_OUTPUT ||
            (
                failure.stall == PlaybackStall.NO_PROGRESS &&
                    progress.routeStarted &&
                    (!progress.outputAdvanced || progress.underruns > 0)
                )

    fun plan(
        inventory: TrackInventory,
        failedTrackId: TrackId?,
        preferences: AudioLanguagePreferences,
        systemLanguages: List<String> = emptyList(),
    ): Plan {
        val failed = failedTrackId?.let { id -> inventory.audioTracks.firstOrNull { it.id == id } }
        val failedKey = failed?.let(::keyOf)
        val failedLanguage = failed?.language
        val eligible = inventory.audioTracks
            .withIndex()
            .filter { (_, track) ->
                track.supported != false &&
                    track.roles.none { it == TrackRole.COMMENTARY || it == TrackRole.DESCRIPTIVE } &&
                    keyOf(track) != failedKey
            }
        val primary = AudioTrackSelector.candidateTagsOf(preferences.primary, systemLanguages)
        val secondary = AudioTrackSelector.candidateTagsOf(preferences.secondary, systemLanguages)
            .filterNot { candidate -> candidate in primary.toSet() }
        val ordered = eligible.sortedWith(
            compareBy(
                { (_, track) -> rank(track, failedLanguage, primary, secondary) },
                { (index, _) -> index },
            ),
        )
        return Plan(order = ordered.map { (_, track) -> keyOf(track) })
    }

    fun nextAlternative(inventory: TrackInventory, plan: Plan): RouteKey? =
        plan.order.firstOrNull { key -> key !in plan.attempted && resolve(inventory, key) != null }

    fun resolve(inventory: TrackInventory, key: RouteKey): TrackId? =
        inventory.audioTracks.firstOrNull { track -> keyOf(track) == key }?.id

    fun describe(key: RouteKey): String = buildList {
        key.codec?.let { add(it) }
        key.channelCount?.let { add("${it}ch") }
        key.sampleRate?.let { add("${it}Hz") }
        key.language?.let { add(it) }
    }.joinToString(" ").ifEmpty { "unknown" }

    private fun rank(
        track: AudioTrack,
        failedLanguage: String?,
        primaryCandidates: List<String>,
        secondaryCandidates: List<String>,
    ): Int = when {
        failedLanguage != null && AudioLanguageTag.baseEquals(failedLanguage, track.language) -> 0
        primaryCandidates.any { candidate -> AudioLanguageTag.exactEquals(candidate, track.language) } -> 1
        primaryCandidates.any { candidate -> AudioLanguageTag.baseEquals(candidate, track.language) } -> 2
        secondaryCandidates.any { candidate -> AudioLanguageTag.exactEquals(candidate, track.language) } -> 3
        secondaryCandidates.any { candidate -> AudioLanguageTag.baseEquals(candidate, track.language) } -> 4
        else -> UNRANKED
    }

    private const val UNRANKED = 5
}
