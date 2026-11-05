package dev.infusion.core.domain.upnext

import dev.infusion.core.domain.tracking.TrackingExternalIds
import dev.infusion.core.domain.tracking.TrackingInboundOutcome
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate

data class UpNextTrackerRecord(
    val kind: UpNextTrackerKind,
    val externalIds: TrackingExternalIds,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val seriesTitle: String? = null,
    val year: Int? = null,

    val activityAt: Instant? = null,

    val airedAt: LocalDate? = null,

    val airedAtProvenAt: Instant? = null,
    val providerRecordId: String? = null,
) {
    init {
        require(!externalIds.isEmpty) {
            "an Up Next record requires at least one proven external identifier to be matchable"
        }
        if (kind == UpNextTrackerKind.EPISODE) {
            require(seasonNumber != null && seasonNumber > 0) {
                "an episode Up Next record requires a positive season number"
            }
            require(episodeNumber != null && episodeNumber > 0) {
                "an episode Up Next record requires a positive episode number"
            }
        }
    }
}

enum class UpNextTrackerKind { EPISODE, MOVIE }

data class UpNextTrackerPage(
    val records: List<UpNextTrackerRecord>,
    val malformed: Int = 0,
    val skipped: Int = 0,
    val truncated: Boolean = false,
)

data class UpNextReadRequest(
    val page: Int = 1,
    val limit: Int = DEFAULT_LIMIT,
) {
    init {
        require(page >= 1) { "page must be >= 1" }
        require(limit in 1..MAX_LIMIT) { "limit must be in 1..$MAX_LIMIT" }
    }

    companion object {
        const val DEFAULT_LIMIT: Int = 20

        const val MAX_LIMIT: Int = 100
    }
}

interface UpNextReadPort {

    val providerId: String

    suspend fun readUpNext(request: UpNextReadRequest): TrackingInboundOutcome<UpNextTrackerPage>
}

object UpNextLimits {

    const val MAX_CACHED_CANDIDATES: Int = 200

    const val MAX_TITLE_LENGTH: Int = 200

    const val MAX_ID_LENGTH: Int = 200
}

interface UpNextSelectionStore {

    fun read(profileId: String): UpNextSelection

    fun write(profileId: String, selection: UpNextSelection)

    fun clearProfile(profileId: String)
}

interface UpNextPresentationStore {

    fun showUpNext(profileId: String): Boolean

    fun setShowUpNext(profileId: String, show: Boolean)
}

data class UpNextCachedAnswer(
    val profileId: String,
    val trackerId: String,
    val accountLabel: String?,
    val fetchedAt: Instant,
    val candidates: List<UpNextTrackerCandidate>,
)

interface UpNextCacheStore {

    fun read(profileId: String, trackerId: String, accountLabel: String?): UpNextCachedAnswer?

    fun write(answer: UpNextCachedAnswer)

    fun clearAccount(profileId: String, trackerId: String, accountLabel: String?)

    fun clearOtherAccounts(profileId: String, trackerId: String, accountLabel: String?)

    fun clearProfile(profileId: String)
}

object UpNextCodec {

    const val SELECTION_VERSION_TAG: String = "un1"
    const val CACHE_VERSION_TAG: String = "uc2"

    const val CACHE_VERSION_TAG_V1: String = "uc1"

    private const val FIELD_SEPARATOR = '|'
    private const val RECORD_SEPARATOR = ';'

    private const val FIELD_COUNT = 10
    private const val V1_FIELD_COUNT = 8

    private const val LOCAL_TOKEN = "local"
    private const val TRACKER_TOKEN_PREFIX = "tracker:"

    private const val UNRESERVED: String =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._-"

    private const val HEX: String = "0123456789ABCDEF"

    fun encodeSelection(selection: UpNextSelection): String? {
        val source = selection.source ?: return null
        val token = when (source) {
            UpNextSource.LocalOnly -> LOCAL_TOKEN
            is UpNextSource.Tracker -> TRACKER_TOKEN_PREFIX + source.trackerId
        }
        return "$SELECTION_VERSION_TAG$RECORD_SEPARATOR${escape(token)}"
    }

    fun decodeSelection(stored: String?): UpNextSelection {
        if (stored == null) return UpNextSelection.Unset
        val prefix = SELECTION_VERSION_TAG + RECORD_SEPARATOR
        if (!stored.startsWith(prefix)) return UpNextSelection.Unset
        val token = unescape(stored.substring(prefix.length)) ?: return UpNextSelection.Unset
        return when {
            token == LOCAL_TOKEN -> UpNextSelection.localOnly()
            token.startsWith(TRACKER_TOKEN_PREFIX) -> {
                val id = token.substring(TRACKER_TOKEN_PREFIX.length)
                if (id.isBlank()) UpNextSelection.Unset else UpNextSelection.tracker(id)
            }
            else -> UpNextSelection.Unset
        }
    }

    fun encodeCache(answer: UpNextCachedAnswer): String {
        require(answer.candidates.size <= UpNextLimits.MAX_CACHED_CANDIDATES) {
            "a cached Up Next answer holds at most ${UpNextLimits.MAX_CACHED_CANDIDATES} candidates"
        }
        require(answer.candidates.all(::withinBounds)) {
            "a cached Up Next candidate is outside the storage bounds"
        }
        val records = answer.candidates.map { candidate ->
            listOf(
                candidate.seriesTitleId.value.toString(),
                candidate.seasonNumber.toString(),
                candidate.episodeNumber.toString(),
                candidate.episodeTitleId?.value?.toString() ?: "",
                candidate.episodeTitle ?: "",
                candidate.seriesTitle ?: "",
                candidate.trackerId,
                candidate.activityAt?.toEpochMilli()?.toString() ?: "",

                candidate.airedAt?.toEpochDay()?.toString() ?: "",
                candidate.airedAtProvenAt?.toEpochMilli()?.toString() ?: "",
            ).joinToString(FIELD_SEPARATOR.toString()) { escape(it) }
        }
        return listOf(
            CACHE_VERSION_TAG,

            escape(answer.trackerId),
            answer.fetchedAt.toEpochMilli().toString(),
        ).joinToString(RECORD_SEPARATOR.toString()) +
            if (records.isEmpty()) "" else RECORD_SEPARATOR + records.joinToString(RECORD_SEPARATOR.toString())
    }

    fun decodeCache(
        stored: String?,
        profileId: String,
        trackerId: String,
        accountLabel: String?,
    ): UpNextCachedAnswer? {
        if (stored == null) return null
        val version = when {
            stored.startsWith(CACHE_VERSION_TAG + RECORD_SEPARATOR) -> CACHE_VERSION_TAG
            stored.startsWith(CACHE_VERSION_TAG_V1 + RECORD_SEPARATOR) -> CACHE_VERSION_TAG_V1
            else -> return null
        }
        val prefix = version + RECORD_SEPARATOR
        val parts = stored.split(RECORD_SEPARATOR)
        val payloadTrackerId = parts.getOrNull(1)?.let { unescape(it) } ?: return null
        if (payloadTrackerId != trackerId) return null
        val fetchedAt = parts.getOrNull(2)?.toLongOrNull()?.let { Instant.ofEpochMilli(it) } ?: return null
        val candidates = ArrayList<UpNextTrackerCandidate>()
        for (index in 3 until parts.size) {
            val candidate = decodeCandidate(parts[index], trackerId, version) ?: continue
            candidates += candidate
        }
        return UpNextCachedAnswer(
            profileId = profileId,
            trackerId = trackerId,
            accountLabel = accountLabel,
            fetchedAt = fetchedAt,
            candidates = candidates,
        )
    }

    private fun decodeCandidate(raw: String, expectedTrackerId: String, version: String): UpNextTrackerCandidate? {
        val fields = raw.split(FIELD_SEPARATOR)
        val expectedFields = if (version == CACHE_VERSION_TAG_V1) V1_FIELD_COUNT else FIELD_COUNT
        if (fields.size != expectedFields) return null
        val seriesId = unescape(fields[0])?.takeIf { it.isNotBlank() } ?: return null
        val season = fields[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val episode = fields[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val episodeTitleId = unescape(fields[3])?.takeIf { it.isNotBlank() }
        val episodeTitle = unescape(fields[4])?.takeIf { it.isNotBlank() && it.length <= UpNextLimits.MAX_TITLE_LENGTH }
        val seriesTitle = unescape(fields[5])?.takeIf { it.isNotBlank() && it.length <= UpNextLimits.MAX_TITLE_LENGTH }
        val trackerId = unescape(fields[6])?.takeIf { it.isNotBlank() } ?: return null
        if (!trackerId.equals(expectedTrackerId, ignoreCase = false)) return null
        val activity = fields[7].toLongOrNull()?.let { Instant.ofEpochMilli(it) }

        val airedAt = if (fields.size > V1_FIELD_COUNT) {
            fields[8].toLongOrNull()?.let { java.time.LocalDate.ofEpochDay(it) }
        } else {
            null
        }
        val airedAtProvenAt = if (fields.size > V1_FIELD_COUNT) {
            fields[9].toLongOrNull()?.let { Instant.ofEpochMilli(it) }
        } else {
            null
        }
        if (seriesId.length > UpNextLimits.MAX_ID_LENGTH) return null
        if (episodeTitleId != null && episodeTitleId.length > UpNextLimits.MAX_ID_LENGTH) return null

        val seriesTitleId = runCatching { dev.infusion.core.model.TitleId.fromString(seriesId) }.getOrNull()
            ?: return null
        val episodeTitleKey = episodeTitleId
            ?.let { raw -> runCatching { dev.infusion.core.model.TitleId.fromString(raw) }.getOrNull() }
        return UpNextTrackerCandidate(
            trackerId = trackerId,
            seriesTitleId = seriesTitleId,
            seasonNumber = season,
            episodeNumber = episode,
            episodeTitleId = episodeTitleKey,
            episodeTitle = episodeTitle,
            seriesTitle = seriesTitle,
            activityAt = activity,
            airedAt = airedAt,
            airedAtProvenAt = airedAtProvenAt,
        )
    }

    private fun withinBounds(candidate: UpNextTrackerCandidate): Boolean =
        candidate.seriesTitleId.value.toString().length <= UpNextLimits.MAX_ID_LENGTH &&
            candidate.seasonNumber > 0 &&
            candidate.episodeNumber > 0 &&
            candidate.trackerId.isNotBlank() &&
            (candidate.episodeTitleId?.value?.toString()?.length ?: 0) <= UpNextLimits.MAX_ID_LENGTH &&
            (candidate.episodeTitle?.length ?: 0) <= UpNextLimits.MAX_TITLE_LENGTH &&
            (candidate.seriesTitle?.length ?: 0) <= UpNextLimits.MAX_TITLE_LENGTH

    private fun escape(value: String): String {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        val out = StringBuilder(bytes.size)
        bytes.forEach { byte ->
            val char = byte.toInt().toChar()
            if (byte >= 0 && char in UNRESERVED) {
                out.append(char)
            } else {
                val unsigned = byte.toInt() and 0xFF
                out.append('%').append(HEX[unsigned shr 4]).append(HEX[unsigned and 0x0F])
            }
        }
        return out.toString()
    }

    private fun unescape(value: String): String? {
        if ('%' !in value) return value
        val out = java.io.ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char != '%') {
                if (char.code > 0x7F) return null
                out.write(char.code)
                index += 1
                continue
            }
            if (index + 3 > value.length) return null
            val high = Character.digit(value[index + 1], 16)
            val low = Character.digit(value[index + 2], 16)
            if (high < 0 || low < 0) return null
            out.write((high shl 4) or low)
            index += 3
        }
        return String(out.toByteArray(), StandardCharsets.UTF_8)
    }
}
