package dev.infusion.core.domain.music

import dev.infusion.core.model.SourceId
import dev.infusion.core.playback.api.TransportRequest

@JvmInline
value class QueueEntryId(val value: String) {
    init {
        require(value.isNotBlank()) { "QueueEntryId must not be blank" }
    }
}

data class MusicQueueEntry(
    val occurrenceId: QueueEntryId,

    val trackNativeId: String,
    val albumNativeId: String? = null,

    val albumArtistNativeId: String? = null,
    val title: String,
    val artist: String? = null,
    val albumName: String? = null,
    val durationMs: Long? = null,
    val discNumber: Int? = null,
    val trackNumber: Int? = null,

    val membershipId: String? = null,
) {

    val secondaryLine: String?
        get() = listOfNotNull(artist?.takeIf { it.isNotBlank() }, albumName?.takeIf { it.isNotBlank() })
            .joinToString(" · ")
            .takeIf { it.isNotBlank() }
}

sealed interface MusicQueueContext {
    val sourceId: SourceId

    val label: String

    data class Album(
        override val sourceId: SourceId,
        val albumNativeId: String,
        override val label: String,
    ) : MusicQueueContext

    data class Playlist(
        override val sourceId: SourceId,
        val playlistNativeId: String,
        override val label: String,
    ) : MusicQueueContext

    data class Songs(
        override val sourceId: SourceId,
        val libraryNativeId: String,
        override val label: String,
        val sortBy: List<String>,
        val sortOrder: String,
        val filters: Map<String, String> = emptyMap(),
    ) : MusicQueueContext
}

sealed interface MusicQueueSlotId {
    data class Source(val absoluteIndex: Int) : MusicQueueSlotId

    data class Local(val occurrenceId: QueueEntryId) : MusicQueueSlotId
}

data class MusicQueueSourceIdentity(
    val context: MusicQueueContext,
    val profileId: String,
    val capturedAtEpochMs: Long,
)

data class MusicOccurrencePage(
    val offset: Int,
    val entries: List<MusicQueueEntry>,
    val total: Int? = null,

    val sourceChanged: Boolean = false,
)

enum class MusicPageFailure {

    TRANSPORT,

    SOURCE_GONE,

    DISCONNECTED,
}

sealed interface MusicPageResult {
    data class Ok(val page: MusicOccurrencePage) : MusicPageResult

    data class Failed(val message: String, val kind: MusicPageFailure) : MusicPageResult
}

data class MusicReportingIdentity(
    val itemId: String,
    val mediaSourceId: String,
    val playSessionId: String,
    val playMethod: String,
)

data class MusicTransport(
    val request: TransportRequest,
    val reporting: MusicReportingIdentity,
)

enum class MusicTransportFailure {

    TRANSPORT,

    UNAVAILABLE,

    SOURCE_GONE,

    DISCONNECTED,
}

sealed interface MusicTransportResult {
    data class Ok(val transport: MusicTransport) : MusicTransportResult

    data class Failed(
        val message: String,
        val kind: MusicTransportFailure,
    ) : MusicTransportResult
}

data class MusicLyricLine(val startMs: Long, val text: String)

sealed interface MusicLyrics {
    data class Synced(val lines: List<MusicLyricLine>) : MusicLyrics

    data class Plain(val lines: List<String>) : MusicLyrics

    data object None : MusicLyrics
}

sealed interface MusicLyricsResult {
    data class Ok(val lyrics: MusicLyrics) : MusicLyricsResult

    data class Failed(val message: String, val retryable: Boolean = true) : MusicLyricsResult
}

interface MusicQueuePort {

    suspend fun page(context: MusicQueueContext, offset: Int, limit: Int): MusicPageResult

    suspend fun transportFor(entry: MusicQueueEntry, requestId: String): MusicTransportResult

    suspend fun lyrics(trackNativeId: String): MusicLyricsResult
}

interface MusicAudioFocus {

    fun request(): Boolean

    fun abandon()

    fun setListener(listener: MusicAudioFocusListener?)
}

fun interface MusicAudioFocusListener {
    fun onAudioFocusChange(change: MusicAudioFocusChange)
}

enum class MusicAudioFocusChange {
    GAIN,

    LOSS_PERMANENT,

    LOSS_TRANSIENT,

    LOSS_TRANSIENT_CAN_DUCK,
}
