package dev.infusion.core.playback.api

enum class SegmentType {
    INTRO,
    RECAP,
    CREDITS,
    PREVIEW,
    MID_CREDITS_SCENE,
    POST_CREDITS_SCENE,
    CHAPTER,
}

enum class SegmentProvenance {

    JELLYFIN,

    CHAPTERS,

    THEINTRODB,

    INTRODB,

    COMMUNITY,

    INFERRED,

    MANUAL,
}

sealed interface SegmentEvidence {

    data class Chapter(val index: Int, val title: String? = null) : SegmentEvidence

    data class Provider(
        val provider: String,
        val field: String,
        val providerRef: String? = null,
    ) : SegmentEvidence

    data class Server(val itemRef: String, val kind: String) : SegmentEvidence

    data class Heuristic(val rule: String) : SegmentEvidence

    data class Manual(val settingKey: String) : SegmentEvidence
}

data class PlaybackSegment(
    val type: SegmentType,
    val startMs: Long,
    val endMs: Long?,
    val provenance: SegmentProvenance,
    val confidence: Double? = null,
    val evidence: SegmentEvidence,
) {

    val isClosedRange: Boolean get() = endMs != null && endMs > startMs

    val isOpenEnded: Boolean get() = endMs == null

    fun endOr(start: Long = startMs): Long = endMs ?: start
}

data class PlaybackSegmentTimeline(
    val durationMs: Long?,
    val segments: List<PlaybackSegment>,
) {
    val isEmpty: Boolean get() = segments.isEmpty()

    fun ofType(type: SegmentType): List<PlaybackSegment> = segments.filter { it.type == type }

    companion object {
        val EMPTY: PlaybackSegmentTimeline = PlaybackSegmentTimeline(durationMs = null, segments = emptyList())
    }
}

data class ChapterMark(
    val startMs: Long,
    val title: String? = null,
    val index: Int = -1,
)

data class SegmentLookupIdentity(
    val tmdbId: Long? = null,

    val imdbId: String? = null,

    val showImdbId: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val isMovie: Boolean = false,
) {

    val hasTheIntroDbCoordinates: Boolean
        get() = seasonNumber != null && episodeNumber != null && (tmdbId != null || imdbId != null)

    val hasIntroDbAppCoordinates: Boolean
        get() = seasonNumber != null && episodeNumber != null && !showImdbId.isNullOrBlank()
}

data class SegmentEvidenceSettings(
    val onlineIntroMetadataPriority: Boolean,
    val manualFallbackEnabled: Boolean,
    val manualFallbackStartMs: Long,
    val manualFallbackEndMs: Long,

    val enableSkipIntro: Boolean = SegmentEvidencePreferences.DEFAULT.enableSkipIntro,

    val autoSkipIntro: Boolean = SegmentEvidencePreferences.DEFAULT.autoSkipIntro,

    val autoSkipDelaySeconds: Int = SegmentEvidencePreferences.DEFAULT.autoSkipDelaySeconds,

    val persistentSkipButton: Boolean = SegmentEvidencePreferences.DEFAULT.persistentSkipButton,

    val enableStingerAlerts: Boolean = SegmentEvidencePreferences.DEFAULT.enableStingerAlerts,

    val stingerTriggerPercent: Int = SegmentEvidencePreferences.DEFAULT.stingerTriggerPercent,

    val stingerEarlyNotifySeconds: Int = SegmentEvidencePreferences.DEFAULT.stingerEarlyNotifySeconds,

    val stingerToastDurationSeconds: Int =
        SegmentEvidencePreferences.DEFAULT.stingerToastDurationSeconds,
) {
    companion object {

        val DEFAULT: SegmentEvidenceSettings = SegmentEvidencePreferences.DEFAULT.toSettings()

        const val AUTO_SKIP_DELAY_MIN: Int = 0
        const val AUTO_SKIP_DELAY_MAX: Int = 10

        const val STINGER_PERCENT_MIN: Int = 50
        const val STINGER_PERCENT_MAX: Int = 99

        const val STINGER_EARLY_MIN: Int = 0
        const val STINGER_EARLY_MAX: Int = 60

        const val STINGER_TOAST_MIN: Int = 1
        const val STINGER_TOAST_MAX: Int = 30
    }
}

data class SegmentEvidenceInput(
    val identity: SegmentLookupIdentity,
    val durationMs: Long?,
    val chapters: List<ChapterMark>,
    val settings: SegmentEvidenceSettings,

    val serverSegments: List<PlaybackSegment> = emptyList(),
)

data class SegmentAttemptEvidence(
    val identity: SegmentLookupIdentity = SegmentLookupIdentity(),
    val chapters: List<ChapterMark> = emptyList(),

    val serverSegments: List<PlaybackSegment> = emptyList(),

    val stingerIdentity: StingerLookupIdentity = StingerLookupIdentity(),
)

sealed interface SegmentSourceOutcome {

    data class Evidence(val segments: List<PlaybackSegment>) : SegmentSourceOutcome

    data object Absent : SegmentSourceOutcome

    data class Failed(val reason: String) : SegmentSourceOutcome
}

interface SegmentEvidenceSource {

    val id: String

    suspend fun fetch(identity: SegmentLookupIdentity): SegmentSourceOutcome
}
