package dev.infusion.source.addon.lifecycle

import dev.infusion.core.database.Clock
import dev.infusion.core.domain.profile.ProfileScope
import dev.infusion.core.network.DiagnosticReason
import dev.infusion.core.network.LanTrustGrant
import dev.infusion.core.network.LanTrustRegistry
import dev.infusion.core.network.RedactedDiagnostic
import dev.infusion.core.network.TrustClass
import dev.infusion.source.addon.AddonDisclosure
import dev.infusion.source.addon.AddonManifestSnapshot
import dev.infusion.source.addon.AddonManifestSnapshotCodec
import dev.infusion.source.addon.AddonTrustPolicy
import dev.infusion.source.addon.InstalledAddon
import dev.infusion.source.addon.repository.AddonRepository
import dev.infusion.source.addon.repository.AddonRepositoryState
import dev.infusion.source.addon.repository.InstallRequest
import dev.infusion.source.addon.repository.InstalledAddonRuntime
import dev.infusion.source.stremio.AddonClientFactory
import dev.infusion.source.stremio.StremioClient
import dev.infusion.source.stremio.StremioClientFactory
import dev.infusion.source.stremio.StremioFailure
import dev.infusion.source.stremio.StremioResult
import dev.infusion.source.stremio.url.AddonUrlInputResult
import dev.infusion.source.stremio.url.AddonUrlNormalizer
import dev.infusion.source.stremio.url.ConfiguredAddonUrl
import dev.infusion.source.stremio.url.InvalidReason
import kotlinx.coroutines.flow.StateFlow

class AddonLifecycleManager(

    val repository: AddonRepository,
    private val lanTrust: LanTrustRegistry,
    private val profileScope: ProfileScope,
    private val sessionStore: AddonReviewSessionStore = AddonReviewSessionStore(),
    private val clock: Clock = Clock.SYSTEM,

    private val clientFactory: AddonClientFactory = { configured, trust ->
        StremioClientFactory.create(configured, trust, lanTrust)
    },
) {

    val installed: StateFlow<AddonRepositoryState> = repository.installed

    fun installedAddonIds(): List<String> = when (val state = repository.installed.value) {
        is AddonRepositoryState.Ready -> state.installed.map { it.addonId }
        else -> emptyList()
    }

    suspend fun review(rawUrl: String, lanReviewApproved: Boolean): LifecycleReviewResult {
        val url = when (val result = AddonUrlNormalizer.normalizeInput(rawUrl)) {
            is AddonUrlInputResult.Valid -> result.configured
            is AddonUrlInputResult.Invalid -> return LifecycleReviewResult.Invalid(
                when (result.reason) {
                    InvalidReason.EMPTY -> "enter a URL"
                    InvalidReason.UNSUPPORTED_SCHEME -> "only https (or stremio://) add-on URLs are supported"
                    InvalidReason.UNPARSEABLE -> "invalid add-on url"
                },
            )
        }

        val trust = AddonTrustPolicy.classify(url.publicBase, lanApproved = lanReviewApproved)
        if (trust == TrustClass.LAN_TRUSTED && !lanReviewApproved) {
            return LifecycleReviewResult.LanConsentRequired(url.host)
        }

        var lanReviewGrantPreExisted = false
        if (trust == TrustClass.LAN_TRUSTED) {

            lanReviewGrantPreExisted = lanTrust.isApproved(url.host)
            lanTrust.approve(LanTrustGrant(url.host))
        }
        return try {
            val client = try {
                clientFactory(url, trust)
            } catch (e: IllegalArgumentException) {
                return LifecycleReviewResult.Invalid("lan trust not granted for ${url.host}")
            }
            try {
                when (val result = client.fetchManifest()) {
                    is StremioResult.Ok -> {
                        val snapshot = AddonManifestSnapshotCodec.fromManifest(result.value)
                        val lanRequested = trust == TrustClass.LAN_TRUSTED
                        val record = AddonReviewRecord(
                            configured = url,
                            trust = trust,
                            manifest = result.value,
                            snapshot = snapshot,
                            lanRequested = lanRequested,
                            disclosure = AddonDisclosure(
                                endpoint = url.origin,
                                requestedResourceTypes = snapshot.requestedResourceTypes,
                                catalogVisibility = snapshot.catalogVisibility,
                                networkDisclosure = networkDisclosure(url, lanRequested),
                                credentialUse = AddonDisclosure.CREDENTIAL_USE_NONE,
                                lanGranted = lanRequested,
                                disclosedAtMs = clock.nowMs(),
                            ),
                        )
                        val token = sessionStore.create(record)
                        LifecycleReviewResult.Ready(token = token, record = record)
                    }
                    is StremioResult.Err -> {

                        if (trust == TrustClass.PUBLIC &&
                            result.failure.reason == DiagnosticReason.PRIVATE_DESTINATION
                        ) {
                            LifecycleReviewResult.LanConsentRequired(url.host)
                        } else {
                            LifecycleReviewResult.Failed(result.failure)
                        }
                    }
                }
            } finally {
                client.close()
            }
        } finally {
            if (trust == TrustClass.LAN_TRUSTED && !lanReviewGrantPreExisted) {
                lanTrust.revoke(url.host)
            }
        }
    }

    suspend fun install(token: String, consent: LifecycleInstallConsent): LifecycleInstallResult {
        val record = sessionStore.consume(token)
            ?: return LifecycleInstallResult.Invalid("review session expired or already used")

        if (!consent.endpointAccepted) return LifecycleInstallResult.ConsentDenied("endpoint not accepted")
        if (!consent.resourceTypesAccepted) return LifecycleInstallResult.ConsentDenied("resource types not accepted")
        if (!consent.catalogVisibilityAccepted) return LifecycleInstallResult.ConsentDenied("catalog visibility not accepted")
        if (!consent.networkDisclosureAccepted) return LifecycleInstallResult.ConsentDenied("network disclosure not accepted")
        if (!consent.credentialUseNoneAccepted) return LifecycleInstallResult.ConsentDenied("credential/header use must be none")
        if (record.lanRequested && !consent.lanGranted) return LifecycleInstallResult.ConsentDenied("LAN access not granted")

        var lanInstallGrantPreExisted = false
        if (record.lanRequested) {
            lanInstallGrantPreExisted = lanTrust.isApproved(record.configured.host)
            lanTrust.approve(LanTrustGrant(record.configured.host))
        }
        fun rollbackLan() {
            if (record.lanRequested && !lanInstallGrantPreExisted) lanTrust.revoke(record.configured.host)
        }

        val validated = when (val outcome = refetch(record)) {
            is ReFetchOutcome.Ok -> outcome.snapshot
            is ReFetchOutcome.Fail -> {
                rollbackLan()
                return LifecycleInstallResult.Failed(outcome.failure)
            }
            is ReFetchOutcome.Changed -> {
                rollbackLan()
                return LifecycleInstallResult.Invalid("manifest changed since review")
            }
        }

        return when (val result = repository.add(
            InstallRequest(
                configured = record.configured,
                ownerProfileId = profileScope.activeProfileId,
                lanConsented = record.lanRequested,
                lanHost = if (record.lanRequested) record.configured.host else null,
                validatedSnapshot = validated,
            ),
        )) {
            is AddonRepository.AddonAddResult.Ok -> LifecycleInstallResult.Installed(result.addonId)
            is AddonRepository.AddonAddResult.AlreadyInstalled -> {
                rollbackLan()
                LifecycleInstallResult.AlreadyInstalled(result.addonId)
            }
            is AddonRepository.AddonAddResult.FetchFailed -> {
                rollbackLan()
                LifecycleInstallResult.Failed(result.failure)
            }
            is AddonRepository.AddonAddResult.Invalid -> {
                rollbackLan()
                LifecycleInstallResult.Invalid(result.reason)
            }
        }
    }

    private sealed interface ReFetchOutcome {
        data class Ok(val snapshot: AddonManifestSnapshot) : ReFetchOutcome
        data class Changed(val snapshot: AddonManifestSnapshot) : ReFetchOutcome
        data class Fail(val failure: StremioFailure) : ReFetchOutcome
    }

    private suspend fun refetch(record: AddonReviewRecord): ReFetchOutcome {
        val client = try {

            clientFactory(record.configured, record.trust)
        } catch (e: IllegalArgumentException) {
            return ReFetchOutcome.Fail(
                StremioFailure(
                    reason = DiagnosticReason.INTERNAL,
                    status = null,
                    message = "lan trust not granted for ${record.configured.host}",
                    retryable = false,
                    diagnostic = RedactedDiagnostic(
                        correlationId = dev.infusion.core.network.CorrelationIds.newId(),
                        reason = DiagnosticReason.INTERNAL,
                        origin = record.configured.origin,
                    ),
                ),
            )
        }
        return try {
            when (val result = client.fetchManifest()) {
                is StremioResult.Ok -> {
                    val snapshot = AddonManifestSnapshotCodec.fromManifest(result.value)
                    if (snapshot == record.snapshot) ReFetchOutcome.Ok(snapshot) else ReFetchOutcome.Changed(snapshot)
                }
                is StremioResult.Err -> ReFetchOutcome.Fail(result.failure)
            }
        } finally {
            client.close()
        }
    }

    suspend fun setEnabled(addonId: String, enabled: Boolean): AddonRepository.SetEnabledResult =
        repository.setEnabled(addonId, enabled)

    suspend fun setDisplayName(addonId: String, displayName: String?): AddonRepository.SetDisplayNameResult =
        repository.setDisplayName(addonId, displayName)

    suspend fun setOrder(orderedIds: List<String>): AddonRepository.AddonOrderResult =
        repository.setOrder(orderedIds)

    suspend fun refresh(addonId: String): AddonRepository.RefreshResult =
        repository.refresh(addonId)

    suspend fun remove(
        addonId: String,
        removalScope: dev.infusion.core.database.model.RemovalScope =
            dev.infusion.core.database.model.RemovalScope.INCLUDING_CACHED_DATA,
    ): AddonRepository.AddonRemoveResult = repository.remove(addonId, removalScope)

    suspend fun restore(): AddonRepositoryState = repository.restore()

    fun runtimeFor(addonId: String): InstalledAddonRuntime? = repository.runtimeFor(addonId)

    fun close() = repository.close()

    private fun networkDisclosure(url: ConfiguredAddonUrl, lanRequested: Boolean): String =
        if (lanRequested) {
            "HTTPS/HTTP to ${url.origin} (approved LAN host); no credentials; no cookies; no headers; bounded responses"
        } else {
            "HTTPS to ${url.origin}; no credentials; no cookies; no headers; bounded responses"
        }
}

sealed interface LifecycleReviewResult {

    data class Ready(val token: String, val record: AddonReviewRecord) : LifecycleReviewResult
    data class LanConsentRequired(val host: String) : LifecycleReviewResult
    data class Invalid(val reason: String) : LifecycleReviewResult
    data class Failed(val failure: StremioFailure) : LifecycleReviewResult
}

data class LifecycleInstallConsent(
    val endpointAccepted: Boolean,
    val resourceTypesAccepted: Boolean,
    val catalogVisibilityAccepted: Boolean,
    val networkDisclosureAccepted: Boolean,
    val credentialUseNoneAccepted: Boolean,
    val lanGranted: Boolean,
)

sealed interface LifecycleInstallResult {
    data class Installed(val addonId: String) : LifecycleInstallResult
    data class AlreadyInstalled(val addonId: String) : LifecycleInstallResult
    data class ConsentDenied(val reason: String) : LifecycleInstallResult
    data class Invalid(val reason: String) : LifecycleInstallResult
    data class Failed(val failure: StremioFailure) : LifecycleInstallResult
}