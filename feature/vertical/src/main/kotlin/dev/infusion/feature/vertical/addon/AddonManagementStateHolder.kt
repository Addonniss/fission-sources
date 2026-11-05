package dev.infusion.feature.vertical.addon

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AddonManagementStateHolder(
    private val port: AddonManagementPort,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val _state = MutableStateFlow(AddonManagementUiState())
    val state: StateFlow<AddonManagementUiState> = _state.asStateFlow()

    init {

        scope.launch(ioDispatcher) {
            port.repositoryStateFlow().collect { repositoryState ->
                val viewer = port.viewerProfile()
                val primary = port.primaryProfile()
                val catalog = port.catalogEntries(viewer, primary)
                _state.update { current ->
                    current.copy(
                        viewerProfileId = viewer.profileId,
                        repositoryState = repositoryState,
                        catalog = catalog,
                    )
                }
            }
        }
        refreshList()
    }

    fun refreshList() {
        scope.launch(ioDispatcher) {
            val viewer = port.viewerProfile()
            val primary = port.primaryProfile()
            val repositoryState = readyMapped(viewer, primary)
            val catalog = port.catalogEntries(viewer, primary)
            _state.update { current ->
                current.copy(
                    viewerProfileId = viewer.profileId,
                    repositoryState = repositoryState,
                    catalog = catalog,
                )
            }
        }
    }

    fun onManifestUrlChanged(url: String) {

        pendingReviewUrl = null
        _state.update { it.copy(manifestUrl = url) }
    }

    fun onLanReviewApprovedChanged(approved: Boolean) {
        _state.update { it.copy(lanReviewApproved = approved) }
    }

    private var pendingReviewUrl: String? = null

    fun startReview() {

        val url = pendingReviewUrl?.takeIf { it.isNotBlank() }
            ?: _state.value.manifestUrl.trim()
        if (url.isBlank()) {
            _state.update { it.copy(installPhase = AddonInstallPhase.ReviewInvalid("Enter a manifest URL")) }
            return
        }
        startReviewFor(url, lanReviewApproved = _state.value.lanReviewApproved)
    }

    fun startInstall() {
        val url = pendingReviewUrl?.takeIf { it.isNotBlank() } ?: _state.value.manifestUrl.trim()
        if (url.isBlank()) {
            _state.update { it.copy(installPhase = AddonInstallPhase.ReviewInvalid("Enter a manifest URL")) }
            return
        }

        val lanApproved = _state.value.lanReviewApproved
        pendingReviewUrl = url
        _state.update { it.copy(installPhase = AddonInstallPhase.Reviewing, manifestUrl = redactManifestUrl(url)) }
        scope.launch(ioDispatcher) {
            val first = port.review(url, lanReviewApproved = lanApproved)
            val reviewed = if (first is AddonReviewOutcome.LanConsentRequired && !lanApproved) {
                port.review(url, lanReviewApproved = true)
            } else first
            when (reviewed) {
                is AddonReviewOutcome.Ready -> {
                    _state.update { it.copy(installPhase = AddonInstallPhase.Installing) }
                    installReviewed(reviewed.review)
                }
                is AddonReviewOutcome.Invalid -> _state.update { it.copy(installPhase = AddonInstallPhase.ReviewInvalid(reviewed.reason)) }
                is AddonReviewOutcome.Failed -> _state.update { it.copy(installPhase = AddonInstallPhase.ReviewFailed(reviewed.reason)) }
                is AddonReviewOutcome.LanConsentRequired -> _state.update { it.copy(installPhase = AddonInstallPhase.ReviewFailed("LAN access approval failed")) }
            }
        }
    }

    fun startReviewFor(url: String, lanReviewApproved: Boolean = false) {
        val normalized = AddonDeepLink.parse(url)?.addonUrl
        if (normalized == null) {
            _state.update {
                it.copy(
                    installPhase = AddonInstallPhase.ReviewInvalid("Not an add-on URL"),
                    manifestUrl = "",
                )
            }
            return
        }

        pendingReviewUrl = normalized
        _state.update {
            it.copy(
                installPhase = AddonInstallPhase.Reviewing,
                manifestUrl = redactManifestUrl(normalized),
                lanReviewApproved = lanReviewApproved,
            )
        }
        scope.launch(ioDispatcher) {
            val outcome = port.review(normalized, lanReviewApproved)
            _state.update { current ->
                when (outcome) {
                    is AddonReviewOutcome.Ready -> current.copy(installPhase = AddonInstallPhase.ReviewReady(outcome.review))
                    is AddonReviewOutcome.LanConsentRequired -> current.copy(installPhase = AddonInstallPhase.LanConsentRequired(outcome.host))
                    is AddonReviewOutcome.Invalid -> current.copy(installPhase = AddonInstallPhase.ReviewInvalid(outcome.reason))
                    is AddonReviewOutcome.Failed -> current.copy(installPhase = AddonInstallPhase.ReviewFailed(outcome.reason))
                }
            }
        }
    }

    private fun redactManifestUrl(url: String): String {
        val withoutQuery = url.substringBefore('?')
        val hasQuery = url.length != withoutQuery.length
        return withoutQuery + if (hasQuery) "?configured" else ""
    }

    fun cancelInstall() {
        pendingReviewUrl = null
        _state.update {
            it.copy(
                installPhase = AddonInstallPhase.Idle,
                manifestUrl = "",
                lanReviewApproved = false,
            )
        }
    }

    fun acceptConsent(consent: AddonConsentInput) {
        val review = (_state.value.installPhase as? AddonInstallPhase.ReviewReady)?.review ?: return
        if (!consentMatchesReview(consent, review)) {
            _state.update { it.copy(installPhase = AddonInstallPhase.InstallConsentDenied("Consent does not match the reviewed disclosure")) }
            return
        }
        _state.update { it.copy(installPhase = AddonInstallPhase.Installing) }
        scope.launch(ioDispatcher) {
            val outcome = port.install(consent)
            when (outcome) {
                is AddonInstallOutcome.Installed -> refreshAfterInstall(AddonInstallPhase.Installed)
                is AddonInstallOutcome.AlreadyInstalled -> refreshAfterInstall(AddonInstallPhase.AlreadyInstalled)
                is AddonInstallOutcome.ConsentDenied -> _state.update { it.copy(installPhase = AddonInstallPhase.InstallConsentDenied(outcome.reason)) }
                is AddonInstallOutcome.Invalid -> _state.update { it.copy(installPhase = AddonInstallPhase.InstallFailed(outcome.reason)) }
                is AddonInstallOutcome.Failed -> _state.update { it.copy(installPhase = AddonInstallPhase.InstallFailed(outcome.reason)) }
            }
        }
    }

    private suspend fun installReviewed(review: AddonReviewUi) {
        when (val outcome = port.install(
            AddonConsentInput(
                endpointAccepted = true,
                resourceTypesAccepted = true,
                catalogVisibilityAccepted = true,
                networkDisclosureAccepted = true,
                credentialUseNoneAccepted = true,
                lanGranted = review.lanRequested,
            ),
        )) {
            is AddonInstallOutcome.Installed -> refreshAfterInstall(AddonInstallPhase.Installed)
            is AddonInstallOutcome.AlreadyInstalled -> refreshAfterInstall(AddonInstallPhase.AlreadyInstalled)
            is AddonInstallOutcome.ConsentDenied -> _state.update { it.copy(installPhase = AddonInstallPhase.InstallConsentDenied(outcome.reason)) }
            is AddonInstallOutcome.Invalid -> _state.update { it.copy(installPhase = AddonInstallPhase.InstallFailed(outcome.reason)) }
            is AddonInstallOutcome.Failed -> _state.update { it.copy(installPhase = AddonInstallPhase.InstallFailed(outcome.reason)) }
        }
    }

    private fun refreshAfterInstall(phase: AddonInstallPhase) {
        val viewer = port.viewerProfile()
        val primary = port.primaryProfile()

        val repositoryState = AddonManagementRepositoryState.Ready(port.listVisibleAddons(viewer, primary))
        val catalog = port.catalogEntries(viewer, primary)
        _state.update { current ->
            current.copy(
                installPhase = phase,
                repositoryState = repositoryState,
                catalog = catalog,
            )
        }
    }

    fun enable(addonId: String) {
        val item = _state.value.installed.firstOrNull { it.addonId == addonId } ?: return

        if (!item.canManage) return
        if (_state.value.isBusy(addonId)) return
        _state.update { current ->
            current.copy(itemOperations = current.itemOperations + (addonId to AddonItemOperation.Enabling))
        }
        scope.launch(ioDispatcher) {
            val ok = port.enable(addonId)
            refreshList(if (ok) "Enabled" else "Enable failed", operation = addonId to (if (ok) AddonItemOperation.Idle else AddonItemOperation.Failed("Enable failed")))
        }
    }

    fun disable(addonId: String) {
        val item = _state.value.installed.firstOrNull { it.addonId == addonId } ?: return
        if (!item.canManage) return
        if (_state.value.isBusy(addonId)) return
        _state.update { current ->
            current.copy(itemOperations = current.itemOperations + (addonId to AddonItemOperation.Disabling))
        }
        scope.launch(ioDispatcher) {
            val ok = port.disable(addonId)
            refreshList(if (ok) "Disabled" else "Disable failed", operation = addonId to (if (ok) AddonItemOperation.Idle else AddonItemOperation.Failed("Disable failed")))
        }
    }

    fun rename(addonId: String, displayName: String) {
        val item = _state.value.installed.firstOrNull { it.addonId == addonId } ?: return
        if (!item.canManage) return
        if (_state.value.isBusy(addonId)) return
        val normalized = normalizedAddonDisplayName(displayName) ?: return
        scope.launch(ioDispatcher) {
            val message = when (port.rename(addonId, normalized)) {
                is AddonRenameOutcome.Applied -> "Renamed"
                is AddonRenameOutcome.Invalid -> "Rename failed — enter a name"
                is AddonRenameOutcome.Denied -> "Rename denied"
                is AddonRenameOutcome.Failed -> "Rename failed"
            }
            refreshList(message)
        }
    }

    fun remove(addonId: String) {
        val item = _state.value.installed.firstOrNull { it.addonId == addonId } ?: return
        if (!item.canManage) return
        if (_state.value.isBusy(addonId)) return
        _state.update { current ->
            current.copy(itemOperations = current.itemOperations + (addonId to AddonItemOperation.Removing))
        }
        scope.launch(ioDispatcher) {
            val ok = port.remove(addonId)
            refreshList(if (ok) "Removed" else "Remove failed", operation = addonId to (if (ok) AddonItemOperation.Idle else AddonItemOperation.Failed("Remove failed")))
        }
    }

    fun refresh(addonId: String) {
        val item = _state.value.installed.firstOrNull { it.addonId == addonId } ?: return
        if (!item.canManage) return
        if (_state.value.isBusy(addonId)) return
        _state.update { current ->
            current.copy(itemOperations = current.itemOperations + (addonId to AddonItemOperation.Refreshing))
        }
        scope.launch(ioDispatcher) {
            val outcome = port.refresh(addonId)
            val (message, operation) = when (outcome) {
                is AddonRefreshOutcome.Refreshed -> "Refreshed" to AddonItemOperation.Idle
                is AddonRefreshOutcome.Fresh -> "Already up to date" to AddonItemOperation.Idle
                is AddonRefreshOutcome.Disabled -> "Disabled — enable to refresh" to AddonItemOperation.Idle
                is AddonRefreshOutcome.Failed -> "Refresh failed — last validated snapshot kept" to AddonItemOperation.Failed("Refresh failed")
            }
            refreshList(message, operation = addonId to operation)
        }
    }

    fun inspectHealth(addonId: String) {
        scope.launch(ioDispatcher) {
            val history = port.healthHistory(addonId)
            _state.update { it.copy(healthHistory = history, healthHistoryAddonId = addonId) }
        }
    }

    fun moveUpInstalled(addonId: String) = moveInstalled(addonId, delta = -1)

    fun moveDownInstalled(addonId: String) = moveInstalled(addonId, delta = +1)

    private fun moveInstalled(addonId: String, delta: Int) {
        val installed = _state.value.installed
        val index = installed.indexOfFirst { it.addonId == addonId }
        if (index < 0) return
        val targetIndex = index + delta
        if (targetIndex !in installed.indices) return
        val item = installed[index]

        if (!item.canManage) return
        if (_state.value.isBusy(addonId)) return
        val adjacent = installed[targetIndex]
        _state.update { current ->
            current.copy(itemOperations = current.itemOperations + (addonId to AddonItemOperation.Moving))
        }
        scope.launch(ioDispatcher) {
            val outcome = port.moveInstalled(
                addonId = addonId,
                adjacentVisibleAddonId = adjacent.addonId,
                viewerProfileId = _state.value.viewerProfileId,
            )
            val (message, operation) = when (outcome) {
                is AddonMoveInstalledOutcome.Applied -> "Order updated" to AddonItemOperation.Idle
                is AddonMoveInstalledOutcome.NoOp -> "Already at the edge" to AddonItemOperation.Idle
                is AddonMoveInstalledOutcome.Denied -> "Order update denied" to AddonItemOperation.Failed("Order update denied")
                is AddonMoveInstalledOutcome.Failed -> "Order update failed" to AddonItemOperation.Failed("Order update failed")
            }
            refreshList(message, operation = addonId to operation)
        }
    }

    fun moveUp(catalogKey: AddonManagementCatalogKey) = reorderCatalog(catalogKey, -1)

    fun moveDown(catalogKey: AddonManagementCatalogKey) = reorderCatalog(catalogKey, +1)

    private fun reorderCatalog(catalogKey: AddonManagementCatalogKey, delta: Int) {
        val catalog = _state.value.catalog
        val index = catalog.indexOfFirst {
            it.addonId == catalogKey.addonId &&
                it.catalogType == catalogKey.catalogType &&
                it.catalogId == catalogKey.catalogId
        }
        if (index < 0) return

        val firstIndex = catalog.indexOfFirst { it.addonId == catalogKey.addonId }
        val lastIndex = catalog.indexOfLast { it.addonId == catalogKey.addonId }
        val target = index + delta
        if (target !in firstIndex..lastIndex) return
        val reordered = catalog.toMutableList()
        val moved = reordered.removeAt(index)
        reordered.add(target, moved)

        val slots = (firstIndex..lastIndex).map { position ->
            catalog[position].sortOrder ?: (position - firstIndex)
        }.sorted()
        scope.launch(ioDispatcher) {
            val viewer = port.viewerProfile()
            slots.forEachIndexed { slotIndex, slot ->
                val entry = reordered[firstIndex + slotIndex]
                port.setCatalogPreference(
                    profileId = viewer.profileId,
                    addonId = entry.addonId,
                    catalogType = entry.catalogType,
                    catalogId = entry.catalogId,
                    sortOrder = slot,
                    visibleInDiscover = entry.visibleInDiscover,
                )
            }
            refreshList()
        }
    }

    fun toggleCatalogVisibility(catalogKey: AddonManagementCatalogKey) {
        val catalog = _state.value.catalog
        val entry = catalog.firstOrNull {
            it.addonId == catalogKey.addonId &&
                it.catalogType == catalogKey.catalogType &&
                it.catalogId == catalogKey.catalogId
        } ?: return

        val firstIndex = catalog.indexOfFirst { it.addonId == entry.addonId }
        val localRank = entry.sortOrder ?: (catalog.indexOf(entry) - firstIndex).coerceAtLeast(0)
        scope.launch(ioDispatcher) {
            val viewer = port.viewerProfile()
            port.setCatalogPreference(
                profileId = viewer.profileId,
                addonId = entry.addonId,
                catalogType = entry.catalogType,
                catalogId = entry.catalogId,
                sortOrder = localRank,
                visibleInDiscover = !entry.visibleInDiscover,
            )
            refreshList()
        }
    }

    private fun consentMatchesReview(consent: AddonConsentInput, review: AddonReviewUi): Boolean =
        consent.endpointAccepted &&
            consent.resourceTypesAccepted &&
            consent.catalogVisibilityAccepted &&
            consent.networkDisclosureAccepted &&
            consent.credentialUseNoneAccepted &&
            (consent.lanGranted == review.lanRequested)

    private fun refreshList(actionMessage: String? = null, operation: Pair<String, AddonItemOperation>? = null) {
        scope.launch(ioDispatcher) {
            val viewer = port.viewerProfile()
            val primary = port.primaryProfile()
            val repositoryState = readyMapped(viewer, primary)
            val catalog = port.catalogEntries(viewer, primary)
            _state.update { current ->
                current.copy(
                    viewerProfileId = viewer.profileId,
                    repositoryState = repositoryState,
                    catalog = catalog,
                    actionMessage = actionMessage,
                    itemOperations = operation?.let { (id, op) ->
                        current.itemOperations + (id to op)
                    } ?: current.itemOperations,
                )
            }
        }
    }

    private fun readyMapped(
        viewer: dev.infusion.core.model.profile.Profile,
        primary: dev.infusion.core.model.profile.Profile,
    ): AddonManagementRepositoryState {
        val current = _state.value.repositoryState
        return when (current) {
            is AddonManagementRepositoryState.Ready -> AddonManagementRepositoryState.Ready(
                port.listVisibleAddons(viewer, primary),
            )
            else -> current
        }
    }
}

data class AddonManagementCatalogKey(
    val addonId: String,
    val catalogType: String,
    val catalogId: String,
)
