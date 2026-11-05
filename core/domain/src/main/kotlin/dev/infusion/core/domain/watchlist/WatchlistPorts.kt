package dev.infusion.core.domain.watchlist

import dev.infusion.core.domain.tracking.TrackingExternalIds
import dev.infusion.core.domain.tracking.TrackingInboundOutcome
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TitleKind
import dev.infusion.core.model.watch.WatchKey

data class WatchlistMembershipRef(
    val kind: WatchlistKind,
    val externalIds: TrackingExternalIds,
    val remoteKind: WatchlistRemoteKind,
    val remoteRef: String? = null,
    val title: String? = null,
    val year: Int? = null,

    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
) {
    init {
        require(!externalIds.isEmpty) {
            "a watchlist membership reference requires at least one proven external identifier"
        }
    }
}

sealed interface MembershipWriteResult {
    data object Delivered : MembershipWriteResult

    data class Retryable(val reason: String) : MembershipWriteResult

    data class Rejected(val reason: String) : MembershipWriteResult

    data class Unauthorized(val reason: String) : MembershipWriteResult

    data class Unsupported(val reason: String) : MembershipWriteResult
}

data class WatchlistReadRequest(
    val page: Int = 1,
    val cursor: String? = null,
    val limit: Int = DEFAULT_LIMIT,
) {
    init {
        require(page >= 1) { "page must be >= 1" }
        require(limit in 1..MAX_LIMIT) { "limit must be in 1..$MAX_LIMIT" }
    }

    companion object {
        const val DEFAULT_LIMIT: Int = 100
        const val MAX_LIMIT: Int = 200
    }
}

data class MembershipCapabilities(
    val add: Boolean,
    val remove: Boolean,
    val progressClear: Boolean,
    val writeKinds: Set<WatchlistRemoteKind>,
) {
    companion object {
        val None: MembershipCapabilities = MembershipCapabilities(false, false, false, emptySet())
    }
}

interface WatchlistMembershipPort {

    val providerId: String

    fun capabilities(): MembershipCapabilities

    fun accountConnected(): Boolean

    suspend fun read(request: WatchlistReadRequest): TrackingInboundOutcome<WatchlistRemotePage>

    suspend fun add(ref: WatchlistMembershipRef): MembershipWriteResult

    suspend fun remove(ref: WatchlistMembershipRef): MembershipWriteResult

    suspend fun clearResumeProgress(ref: WatchlistMembershipRef): MembershipWriteResult
}

interface WatchlistIdentityResolver {

    fun resolve(record: WatchlistRemoteRecord): ResolvedRemote?

    fun aliasesFor(titleId: TitleId, kind: WatchlistKind): WatchlistAliasResolution?
}

interface WatchlistIdentityCompletion {

    suspend fun complete(record: WatchlistRemoteRecord): ResolvedRemote?
}

data class WatchlistAliasResolution(
    val titleId: TitleId,
    val kind: WatchlistKind,
    val externalIds: TrackingExternalIds,
    val title: String?,
    val year: Int?,
)

interface WatchlistStore {
    fun read(profileId: String): WatchlistSnapshot
    fun write(profileId: String, snapshot: WatchlistSnapshot)
    fun clearProfile(profileId: String)
}

enum class CardActionSurface { BROWSE, DETAILS, WATCHLIST }

enum class CardActionId {
    ADD_TO_WATCHLIST,
    REMOVE_FROM_WATCHLIST,
    MARK_WATCHED,
    MARK_UNWATCHED,
    RESET_RESUME,

    ADD_TO_JELLYFIN,

    SET_RATING,

    PLAY_FROM_BEGINNING,

    BROWSE_SHOW,
}

data class CardActionRequest(
    val titleId: TitleId?,
    val kind: TitleKind?,
    val watchKey: WatchKey?,
    val surface: CardActionSurface,
    val displayTitle: String,
    val year: Int? = null,

    val artwork: WatchlistArtwork? = null,

    val seasonNumber: Int? = null,

    val playableTitleId: TitleId? = null,
) {

    val provenShowId: TitleId?
        get() = watchKey?.seriesId
            ?: titleId?.takeIf { kind == TitleKind.SERIES || kind == TitleKind.SEASON }

    val provenMovieId: TitleId? get() = titleId?.takeIf { kind == TitleKind.MOVIE }

    val localUnit: WatchKey?
        get() = watchKey
            ?: titleId?.takeIf { kind == TitleKind.MOVIE }?.let { WatchKey.movie(it) }

    val resumeUnit: WatchKey? get() = localUnit

    val watchedUnit: WatchKey? get() = localUnit
}

sealed interface CardActionAvailability {
    data object Enabled : CardActionAvailability
    data class Disabled(val reason: String) : CardActionAvailability
}

data class CardAction(
    val id: CardActionId,
    val label: String,
    val availability: CardActionAvailability = CardActionAvailability.Enabled,

    val detail: String? = null,
)

sealed interface RemoteOutcome {

    data object None : RemoteOutcome
    data object Pending : RemoteOutcome
    data object Delivered : RemoteOutcome
    data class Failed(val reason: String) : RemoteOutcome

    data class Unsupported(val reason: String) : RemoteOutcome
}

sealed interface CardActionOutcome {
    data object Done : CardActionOutcome

    data class Local(val remote: RemoteOutcome) : CardActionOutcome

    data class Refused(val reason: String) : CardActionOutcome

    data class Failed(val reason: String) : CardActionOutcome
}

interface CardActionOwner {

    fun actionsFor(request: CardActionRequest): List<CardAction>

    fun warm(request: CardActionRequest) {}

    suspend fun perform(action: CardAction, request: CardActionRequest): CardActionOutcome
}
