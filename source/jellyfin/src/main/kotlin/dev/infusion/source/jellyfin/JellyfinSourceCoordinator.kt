package dev.infusion.source.jellyfin

import dev.infusion.core.database.Clock
import dev.infusion.core.database.dao.AvailabilityDao
import dev.infusion.core.database.dao.ExternalIdDao
import dev.infusion.core.database.dao.JellyfinSourceStateDao
import dev.infusion.core.database.dao.ServerItemDao
import dev.infusion.core.database.dao.ServerMediaVersionDao
import dev.infusion.core.database.dao.TitleDao
import dev.infusion.core.database.entity.JellyfinSourceStateEntity
import dev.infusion.core.database.model.RemovalScope
import dev.infusion.core.database.sources.SourceRemoval
import dev.infusion.core.database.sources.SourceRemovalReport
import dev.infusion.core.database.store.RoomPlaybackSessionContextStore
import dev.infusion.core.domain.sync.PlaybackSessionContextStore
import dev.infusion.core.domain.sync.RetirementIntentStore
import dev.infusion.core.domain.sync.WatchOutbox
import dev.infusion.core.network.CanonicalUrl
import dev.infusion.core.network.LanTrustRegistry
import dev.infusion.core.network.NetworkLimits
import dev.infusion.core.network.OkHttpHardenedClient
import dev.infusion.core.network.UrlParser
import dev.infusion.core.security.SecretReference
import dev.infusion.core.security.SecretStore
import dev.infusion.source.jellyfin.auth.AuthOutcome
import dev.infusion.source.jellyfin.auth.JellyfinSession
import dev.infusion.source.jellyfin.client.ApiResult
import dev.infusion.source.jellyfin.client.FailureKind
import dev.infusion.source.jellyfin.client.JellyfinFailure
import dev.infusion.source.jellyfin.policy.JellyfinVersionPolicy
import dev.infusion.source.jellyfin.removal.JellyfinSourceRemoval
import dev.infusion.source.jellyfin.wire.UserInfo
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class JellyfinSourceCoordinator(
    private val lanTrust: LanTrustRegistry,
    private val secretStore: SecretStore,
    private val stateDao: JellyfinSourceStateDao,
    private val sourceDao: dev.infusion.core.database.dao.SourceDao,
    private val secretDao: dev.infusion.core.database.dao.SecretReferenceDao,
    private val serverItemDao: ServerItemDao,
    private val serverMediaVersionDao: ServerMediaVersionDao,
    private val titleDao: TitleDao,
    private val externalIdDao: ExternalIdDao,
    private val availabilityDao: AvailabilityDao,
    private val outbox: WatchOutbox,
    private val playbackContextStore: PlaybackSessionContextStore,

    private val videoPlaybackActive: () -> Boolean = { false },
    private val sourceRemoval: SourceRemoval,
    private val scope: CoroutineScope,
    private val transaction: (block: () -> Unit) -> Unit,

    private val limits: NetworkLimits = JellyfinSource.jsonLimits,
    private val clock: Clock = Clock.SYSTEM,

    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,

    private val profileScope: dev.infusion.core.domain.profile.ProfileScope,

    private val transportFactory: (() -> OkHttpHardenedClient)? = null,

    private val checkpointStore: dev.infusion.core.domain.sync.ConnectorCheckpointStore = dev.infusion.core.sync.store.InMemoryConnectorCheckpointStore(),

    private val retirementIntentStore: RetirementIntentStore = dev.infusion.core.sync.store.InMemoryRetirementIntentStore(),

    private val convergence: dev.infusion.core.domain.identity.CanonicalConvergenceStore? = null,

    private val ratingsEvidence: dev.infusion.core.domain.ratings.JellyfinRatingsEvidenceStore? = null,
) {

    companion object {

        @Deprecated("Use ProfilePolicy.DEFAULT_PROFILE_ID")
        const val DEFAULT_PROFILE_ID: String = "local-default"

        @Deprecated("Retirement intents now live in the source-global retirement_intents table (MIGRATION_9_10)")
        const val RETIREMENT_INTENT_PREFIX: String = "jellyfin-retire:"

        const val QUICK_CONNECT_POLL_INTERVAL_MS: Long = 3_000L

        const val QUICK_CONNECT_OVERALL_TIMEOUT_MS: Long = 5 * 60_000L
    }

    suspend fun setup(
        serverUrl: String,
        username: String,
        password: String,
        deviceId: String? = null,
        consentLan: Boolean,
    ): SetupResult = withContext(ioDispatcher) {
        setupOn(serverUrl, username, password, deviceId, consentLan)
    }

    private suspend fun setupOn(
        serverUrl: String,
        username: String,
        password: String,
        deviceId: String?,
        consentLan: Boolean,
    ): SetupResult {
        val config = try {
            JellyfinServerConfig.parse(serverUrl, username)
        } catch (_: IllegalArgumentException) {
            return SetupResult.Invalid("invalid server url")
        }

        val stableDeviceId = deviceId?.takeIf { it.isNotBlank() }
            ?: "infusion-" + UUID.randomUUID().toString().substring(0, 8)
        val source = JellyfinSource(config, lanTrust, secretStore, limits, stableDeviceId, transportFactory)
        if (consentLan) source.consentLan()
        return when (val outcome = source.connect(password)) {
            is AuthConnectResult.Failed -> {
                source.close()
                SetupResult.Failed(outcome.failure)
            }
            is AuthConnectResult.Ok -> commitAuthenticated(source, outcome.session, stableDeviceId, consentLan)
        }
    }

    private suspend fun commitAuthenticated(
        source: JellyfinSource,
        session: JellyfinSession,
        deviceId: String,
        consentLan: Boolean,
    ): SetupResult {
        val graph = buildGraph(source)

        source.restoreSession(session)

        val priorState = stateDao.getActive()
        val retirement = snapshotPriorRetirement(priorState, graph.sourceId.value.toString())
        try {

            persistAndRetire(source, graph, session, deviceId, retirement)
        } catch (e: Exception) {

            source.close()
            try {
                secretStore.delete(session.tokenRef)
            } catch (_: Exception) {

            }
            return SetupResult.Failed(
                JellyfinFailure(
                    FailureKind.UNKNOWN,
                    null,
                    "failed to persist source configuration",
                    retryable = false,
                ),
            )
        }

        retireExternalAfterCommit(retirement)

        if (consentLan) source.consentLan()
        return SetupResult.Ok(graph)
    }

    sealed interface ConnectionTestResult {

        data class Ok(
            val userId: String,
            val userName: String?,
            val avatarTag: String?,
            val serverVersion: String?,
            val qualificationLabel: String?,
        ) : ConnectionTestResult

        data class Failed(val kind: String, val message: String) : ConnectionTestResult

        data class Invalid(val reason: String) : ConnectionTestResult
    }

    data class JellyfinUserRef(
        val userId: String,
        val name: String?,
        val avatarTag: String?,
        val disabled: Boolean,
        val hidden: Boolean,
    )

    sealed interface UserEnumerationResult {
        data class Ok(val users: List<JellyfinUserRef>) : UserEnumerationResult
        data class Failed(val kind: String, val message: String) : UserEnumerationResult

        data class Unsupported(val reason: String) : UserEnumerationResult
    }

    suspend fun testPassword(
        serverUrl: String,
        username: String,
        password: String,
        consentLan: Boolean,
    ): ConnectionTestResult = withContext(ioDispatcher) {
        val config = try {
            JellyfinServerConfig.parse(serverUrl, username)
        } catch (_: IllegalArgumentException) {
            return@withContext ConnectionTestResult.Invalid("invalid server url")
        }
        val testDeviceId = "infusion-test-" + UUID.randomUUID().toString().substring(0, 8)
        val source = JellyfinSource(config, lanTrust, secretStore, limits, testDeviceId, transportFactory)
        if (consentLan) source.consentLan()
        try {
            when (val outcome = source.connect(password)) {
                is AuthConnectResult.Failed ->
                    ConnectionTestResult.Failed(outcome.failure.kind.name, outcome.failure.message)
                is AuthConnectResult.Ok -> {
                    val session = outcome.session

                    try {
                        secretStore.delete(session.tokenRef)
                    } catch (_: Exception) {

                    }
                    ConnectionTestResult.Ok(
                        userId = session.userId,
                        userName = session.userName,
                        avatarTag = session.avatarTag,
                        serverVersion = source.serverVersion,
                        qualificationLabel = source.versionQualification?.name,
                    )
                }
            }
        } finally {
            source.close()
        }
    }

    suspend fun testApiKey(
        serverUrl: String,
        apiKey: String,
        targetUserId: String?,
        consentLan: Boolean,
    ): ConnectionTestResult = withContext(ioDispatcher) {
        val origin = parseOriginOrNull(serverUrl)
            ?: return@withContext ConnectionTestResult.Invalid("invalid server url")
        val probe = newSource(origin, probeUsername(targetUserId), deviceId = null, consentLan = consentLan)
        try {
            probe.probeVersionGate()?.let {
                return@withContext ConnectionTestResult.Failed(it.kind.name, it.message)
            }

            when (val sys = probe.client.systemInfo(apiKey, limits)) {
                is ApiResult.Err ->
                    return@withContext ConnectionTestResult.Failed(sys.failure.kind.name, sys.failure.message)
                is ApiResult.Ok -> Unit
            }
            when (val resolved = resolveUser(probe, apiKey, targetUserId)) {
                is ResolveResult.Refused ->
                    return@withContext ConnectionTestResult.Failed(resolved.kind.name, resolved.message)
                is ResolveResult.Found -> ConnectionTestResult.Ok(
                    userId = resolved.user.userId,
                    userName = resolved.user.name,
                    avatarTag = resolved.user.avatarTag,
                    serverVersion = probe.serverVersion,
                    qualificationLabel = probe.versionQualification?.name,
                )
            }
        } finally {
            probe.close()
        }
    }

    suspend fun enumerateUsers(
        serverUrl: String,
        apiKey: String,
        consentLan: Boolean,
    ): UserEnumerationResult = withContext(ioDispatcher) {
        val origin = parseOriginOrNull(serverUrl)
            ?: return@withContext UserEnumerationResult.Unsupported("invalid server url")
        val probe = newSource(origin, "jellyfin-api-key", deviceId = null, consentLan = consentLan)
        try {
            probe.probeVersionGate()?.let {
                return@withContext UserEnumerationResult.Unsupported(it.message)
            }
            when (val result = probe.client.users(apiKey, limits)) {
                is ApiResult.Err -> when (result.failure.kind) {
                    FailureKind.UNAUTHORIZED, FailureKind.FORBIDDEN, FailureKind.POLICY ->
                        UserEnumerationResult.Unsupported(result.failure.message)
                    else -> UserEnumerationResult.Failed(result.failure.kind.name, result.failure.message)
                }
                is ApiResult.Ok -> UserEnumerationResult.Ok(result.value.mapNotNull { it.toUserRef() })
            }
        } finally {
            probe.close()
        }
    }

    suspend fun setupWithApiKey(
        serverUrl: String,
        apiKey: String,
        targetUserId: String?,
        consentLan: Boolean,
    ): SetupResult = withContext(ioDispatcher) {
        val origin = parseOriginOrNull(serverUrl)
            ?: return@withContext SetupResult.Invalid("invalid server url")
        val probe = newSource(origin, probeUsername(targetUserId), deviceId = null, consentLan = consentLan)
        val user: JellyfinUserRef
        val serverId: String
        val observedVersion: String?
        val observedQualification: JellyfinVersionPolicy.VersionQualification?
        try {
            probe.probeVersionGate()?.let { return@withContext SetupResult.Failed(it) }
            observedVersion = probe.serverVersion
            observedQualification = probe.versionQualification
            serverId = when (val sys = probe.client.systemInfo(apiKey, limits)) {
                is ApiResult.Err -> return@withContext SetupResult.Failed(sys.failure)
                is ApiResult.Ok -> sys.value.id ?: ""
            }
            user = when (val r = resolveUser(probe, apiKey, targetUserId)) {
                is ResolveResult.Refused ->
                    return@withContext SetupResult.Failed(
                        JellyfinFailure(r.kind, null, r.message, retryable = false),
                    )
                is ResolveResult.Found -> r.user
            }
        } finally {
            probe.close()
        }

        val stableDeviceId = "infusion-" + UUID.randomUUID().toString().substring(0, 8)
        val username = user.name?.takeIf { it.isNotBlank() } ?: user.userId
        val source = newSource(origin, username, stableDeviceId, consentLan)

        source.adoptObservedVersion(observedVersion, observedQualification)
        val session = when (
            val adopted = source.authenticator.authenticateWithApiKey(
                sourceId = source.sourceKey,
                apiKey = apiKey,
                userId = user.userId,
                userName = user.name,
                serverId = serverId,
                avatarTag = user.avatarTag,
            )
        ) {
            is AuthOutcome.Failed -> {
                source.close()
                return@withContext SetupResult.Failed(adopted.failure)
            }
            is AuthOutcome.Authenticated -> adopted.session
        }
        commitAuthenticated(source, session, stableDeviceId, consentLan)
    }

    suspend fun connectQuickConnect(
        serverUrl: String,
        consentLan: Boolean,
        onChallenge: (code: String) -> Unit,
        shouldCancel: () -> Boolean,
        pollIntervalMs: Long = QUICK_CONNECT_POLL_INTERVAL_MS,
        overallTimeoutMs: Long = QUICK_CONNECT_OVERALL_TIMEOUT_MS,
    ): SetupResult = withContext(ioDispatcher) {
        val origin = parseOriginOrNull(serverUrl)
            ?: return@withContext SetupResult.Invalid("invalid server url")
        val probe = newSource(origin, probeUsername(null), deviceId = null, consentLan = consentLan)
        try {

            probe.probeVersionGate()?.let { return@withContext SetupResult.Failed(it) }
            val observedVersion = probe.serverVersion
            val observedQualification = probe.versionQualification

            when (val enabled = probe.client.quickConnectEnabled(limits)) {
                is ApiResult.Err -> return@withContext SetupResult.Failed(enabled.failure)
                is ApiResult.Ok -> if (!enabled.value) {
                    return@withContext SetupResult.Failed(
                        JellyfinFailure(FailureKind.POLICY, null, "Quick Connect is disabled on this server", retryable = false),
                    )
                }
            }

            val challenge = when (val initiated = probe.client.quickConnectInitiate(limits)) {
                is ApiResult.Err -> return@withContext SetupResult.Failed(initiated.failure)
                is ApiResult.Ok -> initiated.value
            }
            val secret = challenge.secret
            if (secret.isBlank()) {
                return@withContext SetupResult.Failed(
                    JellyfinFailure(FailureKind.UNKNOWN, null, "Quick Connect initiate returned no secret", retryable = false),
                )
            }
            onChallenge(challenge.code)

            val maxAttempts = maxOf(1L, overallTimeoutMs / maxOf(1L, pollIntervalMs))
            var approved = false
            var attempt = 0L
            while (attempt < maxAttempts) {
                if (shouldCancel()) return@withContext quickConnectCancelled()
                when (val poll = probe.client.quickConnectConnect(secret, limits)) {
                    is ApiResult.Err -> return@withContext SetupResult.Failed(poll.failure)
                    is ApiResult.Ok -> if (poll.value.authenticated) approved = true
                }
                if (approved) break
                attempt++
                if (attempt < maxAttempts) delay(pollIntervalMs)
            }
            if (!approved) {
                return@withContext SetupResult.Failed(
                    JellyfinFailure(FailureKind.TIMEOUT, null, "Quick Connect timed out waiting for approval", retryable = true),
                )
            }

            if (shouldCancel()) return@withContext quickConnectCancelled()

            val auth = when (val exchanged = probe.client.authenticateWithQuickConnect(secret, limits)) {
                is ApiResult.Err -> return@withContext SetupResult.Failed(exchanged.failure)
                is ApiResult.Ok -> exchanged
            }
            val user = auth.value.user
            val userId = user?.id?.takeIf { it.isNotBlank() }
                ?: return@withContext SetupResult.Failed(
                    JellyfinFailure(FailureKind.UNKNOWN, auth.status, "Quick Connect response missing user", retryable = false),
                )
            val accessToken = auth.value.accessToken?.takeIf { it.isNotBlank() }
                ?: return@withContext SetupResult.Failed(
                    JellyfinFailure(FailureKind.UNKNOWN, auth.status, "Quick Connect response missing access token", retryable = false),
                )

            if (shouldCancel()) return@withContext quickConnectCancelled()

            val stableDeviceId = "infusion-" + UUID.randomUUID().toString().substring(0, 8)
            val username = user.name?.takeIf { it.isNotBlank() } ?: userId
            val source = newSource(origin, username, stableDeviceId, consentLan)
            source.adoptObservedVersion(observedVersion, observedQualification)
            val session = source.authenticator.adoptAccessToken(
                sourceId = source.sourceKey,
                accessToken = accessToken,
                userId = userId,
                userName = user.name,
                serverId = auth.value.serverId ?: "",
                avatarTag = user.primaryImageTag,
            )
            commitAuthenticated(source, session, stableDeviceId, consentLan)
        } finally {
            probe.close()
        }
    }

    private fun quickConnectCancelled(): SetupResult = SetupResult.Failed(
        JellyfinFailure(FailureKind.POLICY, null, "Quick Connect cancelled", retryable = false),
    )

    private fun parseOriginOrNull(rawUrl: String): CanonicalUrl? {
        val url = try {
            UrlParser.parse(rawUrl.trim())
        } catch (_: Exception) {
            return null
        }
        if (url.scheme != "http" && url.scheme != "https") return null
        return url
    }

    private fun newSource(
        origin: CanonicalUrl,
        username: String,
        deviceId: String?,
        consentLan: Boolean,
    ): JellyfinSource {
        val source = JellyfinSource(
            config = JellyfinServerConfig(origin, username),
            lanTrust = lanTrust,
            secretStore = secretStore,
            limits = limits,
            deviceId = deviceId,
            transportFactory = transportFactory,
        )
        if (consentLan) source.consentLan()
        return source
    }

    private fun probeUsername(targetUserId: String?): String =
        targetUserId?.takeIf { it.isNotBlank() } ?: "jellyfin-user"

    private sealed interface ResolveResult {
        data class Found(val user: JellyfinUserRef) : ResolveResult
        data class Refused(val kind: FailureKind, val message: String) : ResolveResult
    }

    private suspend fun resolveUser(
        source: JellyfinSource,
        apiKey: String,
        targetUserId: String?,
    ): ResolveResult {
        val explicit = targetUserId?.takeIf { it.isNotBlank() }
        if (explicit != null) {
            return when (val result = source.client.user(explicit, apiKey, limits)) {
                is ApiResult.Err -> ResolveResult.Refused(result.failure.kind, result.failure.message)
                is ApiResult.Ok -> {
                    val ref = result.value.toUserRef()
                        ?: return ResolveResult.Refused(FailureKind.NOT_FOUND, "target user has no id")
                    ResolveResult.Found(ref)
                }
            }
        }
        return when (val result = source.client.users(apiKey, limits)) {
            is ApiResult.Err -> ResolveResult.Refused(
                result.failure.kind,
                "server refused user enumeration (${result.failure.kind.name}); supply an explicit user id",
            )
            is ApiResult.Ok -> {
                val visible = result.value.mapNotNull { it.toUserRef() }
                    .firstOrNull { !it.disabled && !it.hidden }
                if (visible == null) {
                    ResolveResult.Refused(FailureKind.NOT_FOUND, "no visible non-disabled Jellyfin user was found")
                } else {
                    ResolveResult.Found(visible)
                }
            }
        }
    }

    private fun UserInfo.toUserRef(): JellyfinUserRef? {
        val id = id?.takeIf { it.isNotBlank() } ?: return null
        return JellyfinUserRef(
            userId = id,
            name = name,
            avatarTag = primaryImageTag,
            disabled = disabled == true,
            hidden = hidden == true,
        )
    }

    fun restore(): JellyfinSourceGraph? {

        val state = stateDao.getActive() ?: return null
        val qualification = state.versionQualification?.let { raw ->
            JellyfinVersionPolicy.VersionQualification.entries.firstOrNull { it.name == raw }
        }

        if (qualification != JellyfinVersionPolicy.VersionQualification.QUALIFIED &&
            qualification != JellyfinVersionPolicy.VersionQualification.OBSERVED
        ) {
            return null
        }
        if (!state.lanConsented) return null
        val origin = try {
            UrlParser.parse(state.origin)
        } catch (_: Exception) {
            return null
        }
        val source = JellyfinSource(
            config = JellyfinServerConfig(origin, state.username),
            lanTrust = lanTrust,
            secretStore = secretStore,
            limits = limits,
            deviceId = state.deviceId,
            transportFactory = transportFactory,
        )

        source.consentLan()
        val graph = buildGraph(source)
        val tokenRefId = state.tokenRefId
        if (tokenRefId != null) {

            if (!isLiveSecretBinding(tokenRefId, state.sourceId)) {
                source.close()
                return null
            }
            graph.restoreSession(
                JellyfinSession(
                    sourceId = state.sourceId,
                    userId = state.userId,
                    serverId = state.serverId,
                    userName = state.username,
                    tokenRef = SecretReference(tokenRefId),

                    avatarTag = state.userAvatarTag,
                ),
            )
        } else {

            source.close()
            return null
        }
        return graph
    }

    suspend fun refreshConnectedUserAvatarEvidence(): Boolean = withContext(ioDispatcher) {

        fun evidence(step: String, detail: String = "") {
            dev.infusion.source.jellyfin.session.JellyfinForensicsLog.event(
                stage = "avatar-evidence-$step",
                fields = detail,
            )
        }
        val state = stateDao.getActive() ?: run { evidence("no-row"); return@withContext false }
        if (!state.userAvatarTag.isNullOrBlank()) {
            evidence("tag-present")
            return@withContext true
        }
        val tokenRefId = state.tokenRefId ?: run { evidence("no-token-ref"); return@withContext false }
        if (!isLiveSecretBinding(tokenRefId, state.sourceId)) {
            evidence("binding-not-live")
            return@withContext false
        }
        val origin = try {
            UrlParser.parse(state.origin)
        } catch (_: Exception) {
            evidence("bad-origin")
            return@withContext false
        }
        val source = JellyfinSource(
            config = JellyfinServerConfig(origin, state.username),
            lanTrust = lanTrust,
            secretStore = secretStore,
            limits = limits,
            deviceId = state.deviceId,
            transportFactory = transportFactory,
        )
        try {

            source.consentLan()
            val session = JellyfinSession(
                sourceId = state.sourceId,
                userId = state.userId,
                serverId = state.serverId,
                userName = state.username,
                tokenRef = SecretReference(tokenRefId),
                avatarTag = state.userAvatarTag,
            )
            source.restoreSession(session)
            val token = source.authenticator.token(session) ?: run {
                evidence("no-token")
                return@withContext false
            }
            val user = when (val result = source.client.user(state.userId, token, limits)) {
                is ApiResult.Ok -> result.value
                is ApiResult.Err -> {
                    evidence(
                        "user-read-err",
                        "kind=${result.failure.kind} status=${result.failure.status ?: "none"}",
                    )
                    return@withContext false
                }
            }
            val tag = user.primaryImageTag?.takeIf { it.isNotBlank() } ?: run {
                evidence("server-no-tag")
                return@withContext false
            }
            stateDao.upsert(
                state.copy(
                    username = user.name?.takeIf { it.isNotBlank() } ?: state.username,
                    userAvatarTag = tag,
                    updatedAtMs = System.currentTimeMillis(),
                ),
            )
            evidence("recovered")
            true
        } finally {
            source.close()
        }
    }

    private fun isLiveSecretBinding(tokenRefId: String, sourceId: String): Boolean {
        val row = secretDao.getByAlias(tokenRefId) ?: return false
        if (row.sourceId != sourceId) return false
        if (row.kind != dev.infusion.core.database.model.SecretKind.ACCESS_TOKEN) return false
        if (row.scope != dev.infusion.source.jellyfin.auth.JellyfinAuthenticator.PURPOSE_ACCESS_TOKEN) return false
        if (row.revokedAtMs != null) return false
        return secretStore.exists(SecretReference(tokenRefId))
    }

    suspend fun repairPersistedCredentialBinding(): Boolean = withContext(ioDispatcher) {
        val state = stateDao.getActive() ?: return@withContext false
        val tokenRefId = state.tokenRefId ?: return@withContext false

        if (secretDao.getByAlias(tokenRefId) != null) return@withContext false

        if (!secretStore.exists(SecretReference(tokenRefId))) return@withContext false
        val now = clock.nowMs()
        transaction {

            secretDao.hardDeleteBySourceKindScope(
                sourceId = state.sourceId,
                kind = dev.infusion.core.database.model.SecretKind.ACCESS_TOKEN,
                scope = dev.infusion.source.jellyfin.auth.JellyfinAuthenticator.PURPOSE_ACCESS_TOKEN,
            )
            secretDao.insert(
                dev.infusion.core.database.entity.SecretReferenceEntity(
                    secretRefId = tokenRefId,
                    sourceId = state.sourceId,
                    kind = dev.infusion.core.database.model.SecretKind.ACCESS_TOKEN,
                    keyAlias = tokenRefId,
                    scope = dev.infusion.source.jellyfin.auth.JellyfinAuthenticator.PURPOSE_ACCESS_TOKEN,
                    createdAtMs = now,
                    expiresAtMs = null,
                    revokedAtMs = null,
                ),
            )
        }
        true
    }

    private fun snapshotPriorRetirement(
        priorState: dev.infusion.core.database.entity.JellyfinSourceStateEntity?,
        newSourceId: String,
    ): PriorRetirement? {
        val state = priorState ?: return null
        val oldSourceId = state.sourceId
        val sameIdentity = oldSourceId == newSourceId
        val oldTokenRefId = state.tokenRefId
        val logoutAndDestroy = oldTokenRefId != null && isLiveSecretBinding(oldTokenRefId, oldSourceId)
        return PriorRetirement(
            state = state,
            oldSourceId = oldSourceId,
            oldTokenRefId = oldTokenRefId,
            sameIdentity = sameIdentity,
            logoutAndDestroy = logoutAndDestroy,
        )
    }

    private suspend fun retireExternalAfterCommit(retirement: PriorRetirement?) {
        val plan = retirement ?: return
        val state = plan.state

        var externalCompleted = false
        try {
            val origin = UrlParser.parse(state.origin)
            val oldSource = JellyfinSource(
                config = JellyfinServerConfig(origin, state.username),
                lanTrust = lanTrust,
                secretStore = secretStore,
                limits = limits,
                deviceId = state.deviceId,
                transportFactory = transportFactory,
            )
            val graph = buildGraph(oldSource)

            graph.watchConnector.stopPinging()

            oldSource.revokeLan()
            if (plan.logoutAndDestroy) {
                val tokenRefId = plan.oldTokenRefId
                if (tokenRefId != null) {
                    graph.restoreSession(
                        JellyfinSession(
                            sourceId = plan.oldSourceId,
                            userId = state.userId,
                            serverId = state.serverId,
                            userName = state.username,
                            tokenRef = SecretReference(tokenRefId),
                        ),
                    )
                    try {

                        val logoutResult = oldSource.logout()
                        if (logoutResult is ApiResult.Err) {
                            oldSource.close()
                            return
                        }
                    } catch (_: Exception) {

                        oldSource.close()
                        return
                    }

                    secretStore.delete(SecretReference(tokenRefId))
                }
            }
            oldSource.close()
            externalCompleted = true
        } catch (_: Exception) {

        }
        if (externalCompleted) {

            retirementIntentStore.clear(plan.oldSourceId)
        }
    }

    suspend fun drainPendingExternalRetirements() {
        val sourceIds = retirementIntentStore.pendingSourceIds()
        for (sourceId in sourceIds) {
            val payload = retirementIntentStore.payloadFor(sourceId)?.let(::decodeRetirementIntent)
            if (payload == null) {

                retirementIntentStore.clear(sourceId)
                continue
            }
            var completed = false
            try {
                val origin = UrlParser.parse(payload.origin)
                val oldSource = JellyfinSource(
                    config = JellyfinServerConfig(origin, payload.username),
                    lanTrust = lanTrust,
                    secretStore = secretStore,
                    limits = limits,
                    deviceId = payload.deviceId,
                    transportFactory = transportFactory,
                )
                val graph = buildGraph(oldSource)
                graph.watchConnector.stopPinging()
                oldSource.revokeLan()
                if (payload.logoutAndDestroy && payload.tokenRefId != null) {
                    graph.restoreSession(
                        JellyfinSession(
                            sourceId = payload.sourceId,
                            userId = payload.userId,
                            serverId = payload.serverId,
                            userName = payload.username,
                            tokenRef = SecretReference(payload.tokenRefId),
                        ),
                    )
                    try {

                        val logoutResult = oldSource.logout()
                        if (logoutResult is ApiResult.Err) {
                            oldSource.close()
                            return@drainPendingExternalRetirements
                        }
                    } catch (_: Exception) {

                        return@drainPendingExternalRetirements
                    }

                    secretStore.delete(SecretReference(payload.tokenRefId))
                }
                oldSource.close()
                completed = true
            } catch (_: Exception) {

                continue
            }
            if (completed) {

                retirementIntentStore.clear(sourceId)
            }
        }
    }

    private fun encodeRetirementIntent(plan: PriorRetirement): String =
        listOf(
            plan.oldSourceId,
            plan.state.origin,
            plan.state.username,
            plan.state.userId,
            plan.state.serverId,
            plan.state.deviceId,
            plan.oldTokenRefId ?: "",
            plan.logoutAndDestroy.toString(),
        ).joinToString("\u0000")

    private fun decodeRetirementIntent(raw: String): RetirementIntentPayload? {
        val parts = raw.split("\u0000")
        if (parts.size != 8) return null
        if (parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) return null
        return RetirementIntentPayload(
            sourceId = parts[0],
            origin = parts[1],
            username = parts[2],
            userId = parts[3],
            serverId = parts[4],
            deviceId = parts[5],
            tokenRefId = parts[6].takeIf { it.isNotBlank() },
            logoutAndDestroy = parts[7].toBooleanStrictOrNull() ?: false,
        )
    }

    private data class RetirementIntentPayload(
        val sourceId: String,
        val origin: String,
        val username: String,
        val userId: String,
        val serverId: String,
        val deviceId: String,
        val tokenRefId: String?,
        val logoutAndDestroy: Boolean,
    )

    suspend fun remove(graph: JellyfinSourceGraph, removalScope: RemovalScope): SourceRemovalReport =
        withContext(ioDispatcher) {
            val jellyfinRemoval = graph.removal(sourceRemoval, scope)
            val report = jellyfinRemoval.remove(graph.sourceId.value.toString(), removalScope)
            stateDao.delete(graph.sourceId.value.toString())
            report
        }

    private fun buildGraph(source: JellyfinSource): JellyfinSourceGraph = JellyfinSourceGraph(
        source = source,
        serverItemDao = serverItemDao,
        serverMediaVersionDao = serverMediaVersionDao,
        titleDao = titleDao,
        externalIdDao = externalIdDao,
        availabilityDao = availabilityDao,
        outbox = outbox,
        secretStore = secretStore,
        lanTrust = lanTrust,
        scope = scope,
        limits = limits,
        transaction = transaction,
        playbackContextStore = playbackContextStore,

        checkpointStore = checkpointStore,
        convergence = convergence,
        ratingsEvidence = ratingsEvidence,

        videoPlaybackActive = videoPlaybackActive,
    )

    private fun persistAndRetire(
        source: JellyfinSource,
        graph: JellyfinSourceGraph,
        session: JellyfinSession,
        deviceId: String,
        retirement: PriorRetirement?,
    ) {
        val now = clock.nowMs()
        val sourceId = graph.sourceId.value.toString()

        val ownerProfileId = profileScope.activeProfileId
        val existing = stateDao.getById(sourceId)
        transaction {

            sourceDao.upsert(
                dev.infusion.core.database.entity.SourceEntity(
                    sourceId = sourceId,
                    type = dev.infusion.core.database.model.SourceType.JELLYFIN,
                    ownerProfileId = ownerProfileId,
                    displayName = graph.canonicalSource.displayName,
                    origin = source.config.origin.toString(),
                    trustClass = dev.infusion.core.database.model.TrustClass.PERSONAL_SERVER,
                    enabled = true,
                    healthState = dev.infusion.core.database.model.HealthState.UNKNOWN,
                    lastHealthCheckMs = null,
                    createdAtMs = now,
                    removedAtMs = null,
                ),
            )

            secretDao.hardDeleteBySourceKindScope(
                sourceId = sourceId,
                kind = dev.infusion.core.database.model.SecretKind.ACCESS_TOKEN,
                scope = dev.infusion.source.jellyfin.auth.JellyfinAuthenticator.PURPOSE_ACCESS_TOKEN,
            )

            secretDao.insert(
                dev.infusion.core.database.entity.SecretReferenceEntity(
                    secretRefId = session.tokenRef.id,
                    sourceId = sourceId,
                    kind = dev.infusion.core.database.model.SecretKind.ACCESS_TOKEN,
                    keyAlias = session.tokenRef.id,
                    scope = dev.infusion.source.jellyfin.auth.JellyfinAuthenticator.PURPOSE_ACCESS_TOKEN,
                    createdAtMs = now,
                    expiresAtMs = null,
                    revokedAtMs = null,
                ),
            )
            stateDao.upsert(
                JellyfinSourceStateEntity(
                    sourceId = sourceId,
                    origin = source.config.origin.toString(),
                    username = source.config.username,
                    userId = session.userId,
                    serverId = session.serverId,
                    deviceId = deviceId,
                    tokenRefId = session.tokenRef.id,
                    serverVersion = source.serverVersion,
                    versionQualification = source.versionQualification?.name,
                    userAvatarTag = session.avatarTag,
                    lanConsented = source.isLanConsented,
                    createdAtMs = existing?.createdAtMs ?: now,
                    updatedAtMs = now,
                ),
            )

            val plan = retirement
            if (plan != null) {
                if (plan.sameIdentity) {

                    plan.oldTokenRefId?.let { secretDao.hardDeleteByAlias(it) }
                } else {

                    sourceDao.tombstone(plan.oldSourceId, now)
                    secretDao.hardDeleteBySource(plan.oldSourceId)
                    stateDao.delete(plan.oldSourceId)
                    playbackContextStore.deleteBySource(plan.oldSourceId)
                    outbox.deleteByConnector("jellyfin:${plan.oldSourceId}")
                    checkpointStore.deleteAllByConnectorPrefix("jellyfin:${plan.oldSourceId}")

                    retirementIntentStore.save(
                        sourceId = plan.oldSourceId,
                        payload = encodeRetirementIntent(plan),
                        createdAtMs = now,
                    )
                }
            }
        }
    }
}

private data class PriorRetirement(
    val state: dev.infusion.core.database.entity.JellyfinSourceStateEntity,
    val oldSourceId: String,
    val oldTokenRefId: String?,
    val sameIdentity: Boolean,
    val logoutAndDestroy: Boolean,
)

sealed interface SetupResult {
    data class Ok(val graph: JellyfinSourceGraph) : SetupResult
    data class Failed(val failure: JellyfinFailure) : SetupResult
    data class Invalid(val reason: String) : SetupResult
}
