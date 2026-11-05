package dev.infusion.source.jellyfin

import dev.infusion.core.network.CanonicalUrl
import dev.infusion.core.network.CorrelationIds
import dev.infusion.core.network.DiagnosticReason
import dev.infusion.core.network.DiagnosticResource
import dev.infusion.core.network.LanTrustGrant
import dev.infusion.core.network.LanTrustRegistry
import dev.infusion.core.network.NetworkLimits
import dev.infusion.core.network.OkHttpHardenedClient
import dev.infusion.core.network.RedactedDiagnostic
import dev.infusion.core.network.Redactor
import dev.infusion.core.network.StrictNetworkPolicy
import dev.infusion.core.network.TrustClass
import dev.infusion.core.network.UrlParser
import dev.infusion.source.jellyfin.auth.JellyfinAuthenticator
import dev.infusion.source.jellyfin.auth.JellyfinSession
import dev.infusion.source.jellyfin.client.ApiResult
import dev.infusion.source.jellyfin.client.FailureKind
import dev.infusion.source.jellyfin.client.JellyfinClient
import dev.infusion.source.jellyfin.client.JellyfinFailure
import dev.infusion.source.jellyfin.policy.JellyfinVersionPolicy

data class JellyfinServerConfig(
    val origin: CanonicalUrl,
    val username: String,
) {
    init {
        require(username.isNotBlank()) { "username must not be blank" }
    }

    val host: String get() = origin.host

    val sourceKey: String get() = "${origin.origin}|$username"

    companion object {
        fun parse(rawUrl: String, username: String): JellyfinServerConfig {
            val url = try {
                UrlParser.parse(rawUrl.trim())
            } catch (e: Exception) {
                throw IllegalArgumentException("invalid server url")
            }
            if (url.scheme != "http" && url.scheme != "https") {
                throw IllegalArgumentException("only http/https server urls are supported")
            }
            return JellyfinServerConfig(origin = url, username = username)
        }
    }
}

class JellyfinSource(
    val config: JellyfinServerConfig,
    private val lanTrust: LanTrustRegistry,
    secretStore: dev.infusion.core.security.SecretStore,

    limits: NetworkLimits = jsonLimits,
    deviceId: String? = null,

    transportFactory: (() -> OkHttpHardenedClient)? = null,
) {

    val sourceKey: String = config.sourceKey

    val client: JellyfinClient
    val authenticator: JellyfinAuthenticator

    @Volatile
    private var currentSession: JellyfinSession? = null

    @Volatile
    var serverVersion: String? = null
        private set

    @Volatile
    var versionQualification: JellyfinVersionPolicy.VersionQualification? = null
        private set

    private val diagnosticHistory = java.util.concurrent.ConcurrentLinkedDeque<RedactedDiagnostic>()

    init {

        val transport = transportFactory?.invoke() ?: OkHttpHardenedClient(
            trust = TrustClass.LAN_TRUSTED,
            approvedLanHost = config.host,
            networkPolicy = StrictNetworkPolicy,
            limits = limits,
        )
        client = JellyfinClient(
            origin = config.origin,
            transport = transport,

            deviceId = deviceId?.takeIf { it.isNotBlank() },
        )
        authenticator = JellyfinAuthenticator(client, secretStore, limits)
    }

    fun recentDiagnostics(limit: Int = MAX_DIAGNOSTIC_HISTORY): List<RedactedDiagnostic> =
        diagnosticHistory.toList().take(limit)

    private fun recordDiagnostic(reason: DiagnosticReason, statusCode: Int? = null) {
        val diag = RedactedDiagnostic(
            correlationId = CorrelationIds.newId(),
            reason = reason,

            origin = config.origin.origin,
            resource = DiagnosticResource.HEALTH,

            statusCode = statusCode?.takeIf { it in 100..599 },
            elapsedMs = 0,
        )
        diagnosticHistory.addFirst(diag)
        while (diagnosticHistory.size > MAX_DIAGNOSTIC_HISTORY) {
            diagnosticHistory.removeLast()
        }
    }

    private fun reasonFor(kind: dev.infusion.source.jellyfin.client.FailureKind): DiagnosticReason = when (kind) {
        dev.infusion.source.jellyfin.client.FailureKind.UNAUTHORIZED -> DiagnosticReason.UNAUTHORIZED
        dev.infusion.source.jellyfin.client.FailureKind.FORBIDDEN -> DiagnosticReason.FORBIDDEN
        dev.infusion.source.jellyfin.client.FailureKind.NOT_FOUND -> DiagnosticReason.NOT_FOUND
        dev.infusion.source.jellyfin.client.FailureKind.RATE_LIMITED -> DiagnosticReason.RATE_LIMITED
        dev.infusion.source.jellyfin.client.FailureKind.SERVER_ERROR -> DiagnosticReason.SERVER_ERROR
        dev.infusion.source.jellyfin.client.FailureKind.TIMEOUT -> DiagnosticReason.TIMEOUT
        dev.infusion.source.jellyfin.client.FailureKind.LIMIT_EXCEEDED -> DiagnosticReason.LIMIT_EXCEEDED
        dev.infusion.source.jellyfin.client.FailureKind.DNS -> DiagnosticReason.DNS_FAILED
        dev.infusion.source.jellyfin.client.FailureKind.CLEARTEXT_BLOCKED -> DiagnosticReason.CLEARTEXT_BLOCKED
        dev.infusion.source.jellyfin.client.FailureKind.NETWORK -> DiagnosticReason.CONNECTION_FAILED
        else -> DiagnosticReason.INTERNAL
    }

    fun consentLan() {
        lanTrust.approve(LanTrustGrant(host = config.host))
    }

    fun revokeLan() {
        lanTrust.revoke(config.host)
    }

    val isLanConsented: Boolean get() = lanTrust.isApproved(config.host)

    suspend fun connect(password: String): AuthConnectResult {
        if (!isLanConsented) {
            recordDiagnostic(DiagnosticReason.SCHEME_DENIED)
            return AuthConnectResult.Failed(
                JellyfinFailure(
                    FailureKind.POLICY,
                    null,
                    "LAN host not consented; approve the server in setup before connecting",
                    retryable = false,
                ),
            )
        }

        probeVersionGate()?.let { return AuthConnectResult.Failed(it) }

        val outcome = authenticator.authenticate(
            sourceId = sourceKey,
            username = config.username,
            password = password,
        )
        return when (outcome) {
            is dev.infusion.source.jellyfin.auth.AuthOutcome.Failed -> {
                recordDiagnostic(reasonFor(outcome.failure.kind), statusCode = outcome.failure.status)
                AuthConnectResult.Failed(outcome.failure)
            }
            is dev.infusion.source.jellyfin.auth.AuthOutcome.Authenticated -> {
                currentSession = outcome.session
                AuthConnectResult.Ok(outcome.session)
            }
        }
    }

    suspend fun probeVersionGate(): JellyfinFailure? {
        when (val info = client.publicInfo()) {
            is ApiResult.Err -> {

                serverVersion = null
                versionQualification = JellyfinVersionPolicy.VersionQualification.UNPARSEABLE
                recordDiagnostic(reasonFor(info.failure.kind), statusCode = info.failure.status)
                return JellyfinFailure(
                    kind = info.failure.kind,
                    status = info.failure.status,
                    message = "server version could not be verified: ${info.failure.message}",
                    retryable = info.failure.retryable,
                )
            }
            is ApiResult.Ok -> {
                serverVersion = info.value.version
                versionQualification = JellyfinVersionPolicy.qualify(info.value.version)
            }
        }
        val qualification = versionQualification
            ?: return JellyfinFailure(FailureKind.POLICY, null, "server version unparseable", retryable = false)
                .also { recordDiagnostic(DiagnosticReason.SCHEMA_INVALID) }
        if (qualification == JellyfinVersionPolicy.VersionQualification.BELOW_FLOOR ||
            qualification == JellyfinVersionPolicy.VersionQualification.UNPARSEABLE
        ) {
            recordDiagnostic(DiagnosticReason.SCHEMA_INVALID)
            return JellyfinFailure(
                FailureKind.POLICY,
                null,
                "server version ${serverVersion ?: "unknown"} is not supported (floor 10.10.7)",
                retryable = false,
            )
        }
        return null
    }

    suspend fun validate(): ApiResult<JellyfinSession> {
        val session = currentSession
        if (session == null) {
            recordDiagnostic(DiagnosticReason.UNAUTHORIZED)
            return ApiResult.Err(
                JellyfinFailure(
                    FailureKind.UNAUTHORIZED,
                    null,
                    "no session",
                    retryable = false,
                ),
            )
        }
        val result = authenticator.validate(session)
        if (result is ApiResult.Err) {
            recordDiagnostic(reasonFor(result.failure.kind), statusCode = result.failure.status)
        }
        return result
    }

    suspend fun logout(): ApiResult<Unit> {
        val session = currentSession ?: return ApiResult.Ok(Unit, 204)
        val result = authenticator.logout(session)

        if (result is ApiResult.Ok) currentSession = null
        return result
    }

    fun session(): JellyfinSession? = currentSession

    fun restoreSession(session: JellyfinSession) {
        currentSession = session
    }

    fun adoptObservedVersion(
        version: String?,
        qualification: JellyfinVersionPolicy.VersionQualification?,
    ) {
        serverVersion = version
        versionQualification = qualification
    }

    val diagnosticOrigin: String get() = Redactor.url(config.origin)

    fun close() {
        client.close()
    }

    companion object {

        const val MAX_DIAGNOSTIC_HISTORY = 8

        val jsonLimits: NetworkLimits = NetworkLimits.DEFAULT.copy(
            retryOnConnectionFailure = true,
        )
    }
}

sealed interface AuthConnectResult {
    data class Ok(val session: JellyfinSession) : AuthConnectResult
    data class Failed(val failure: JellyfinFailure) : AuthConnectResult
}
