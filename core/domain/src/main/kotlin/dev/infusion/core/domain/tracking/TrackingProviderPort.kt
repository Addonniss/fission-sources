package dev.infusion.core.domain.tracking

import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

data class TrackingProviderCapabilities(
    val providerId: String,
    val deviceAuthorization: Boolean = false,
    val scrobbling: Boolean = false,
    val historyWrite: Boolean = false,
    val historyImport: Boolean = false,
    val playbackImport: Boolean = false,
    val watchedImport: Boolean = false,
    val watchlist: Boolean = false,
    val ratings: Boolean = false,
    val lists: Boolean = false,

    val watchedHistory: Boolean = false,

    val personalRating: Set<dev.infusion.core.model.sync.PersonalRatingKind> = emptySet(),

    val upNext: Boolean = false,

    val reviews: Boolean = false,
) {
    init {
        require(providerId.isNotBlank()) { "providerId must not be blank" }
    }

    fun asConnectorRegistration(displayName: String): ConnectorRegistration = ConnectorRegistration(
        connectorId = providerId,
        displayName = displayName,
        capabilities = dev.infusion.core.model.sync.TrackingCapabilities(
            connectorId = providerId,
            historyImport = historyImport,
            scrobble = scrobbling,
            watchlist = watchlist,
            ratings = ratings,
            lists = lists,
            importExport = false,
        ),
    )
}

interface TrackingProviderPort {

    val providerId: String

    val displayName: String

    fun capabilities(): TrackingProviderCapabilities

    fun connectionState(): TrackingConnectionState

    suspend fun beginAuthorization(): TrackingDeviceAuthorization

    suspend fun pollAuthorization(): TrackingAuthorizationPoll

    fun cancelAuthorization()

    suspend fun disconnect()

    suspend fun deliver(event: TrackingScrobbleEvent): TrackingDeliveryResult
}

interface TrackingCredentialStore {

    fun credentials(providerId: String): StoredTrackingCredentials?

    fun save(
        providerId: String,
        accessToken: String,
        refreshToken: String?,
        accountLabel: String?,
        accessExpiresAt: Instant?,
        refreshExpiresAt: Instant?,
        scope: String?,
    )

    fun updateTokens(
        providerId: String,
        accessToken: String,
        refreshToken: String?,
        accessExpiresAt: Instant?,
        refreshExpiresAt: Instant?,
    )

    fun isConnected(providerId: String): Boolean = credentials(providerId) != null

    fun updateAccountId(providerId: String, accountId: String?)

    fun clear(providerId: String)

    fun tokens(providerId: String): TrackingTokenMaterial?

    fun pendingDeviceCode(providerId: String): String?

    fun clearAllProfiles(providerId: String)

    fun connectionChanges(): Flow<Unit> = emptyFlow()

    fun clearProfile(providerId: String, profileId: String)

    fun pendingAuthorization(providerId: String): StoredPendingAuthorization?

    fun savePendingAuthorization(
        providerId: String,
        pending: StoredPendingAuthorization,
        deviceCode: String,
    )

    fun clearPendingAuthorization(providerId: String)
}

data class StoredTrackingCredentials(
    val providerId: String,
    val accountLabel: String?,
    val accessExpiresAt: Instant?,
    val refreshExpiresAt: Instant?,
    val scope: String?,
    val secretReferenceId: String,

    val accountId: String? = null,
)

data class StoredPendingAuthorization(
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String?,
    val expiresAt: Instant,
    val intervalSeconds: Int,
)

data class TrackingTokenMaterial(
    val accessToken: String,
    val refreshToken: String?,
)
