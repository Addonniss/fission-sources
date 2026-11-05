package dev.infusion.feature.vertical.addon

sealed interface AddonManagementRepositoryState {

    data object Restoring : AddonManagementRepositoryState

    data class Ready(val installed: List<AddonManagementItemUi>) : AddonManagementRepositoryState

    data class Failed(val issues: List<String>) : AddonManagementRepositoryState
}

data class AddonManagementItemUi(
    val addonId: String,
    val displayName: String,

    val origin: String,
    val enabled: Boolean,

    val healthLabel: String,
    val ownerProfileId: String,

    val ownedByViewer: Boolean,
    val installedAtMs: Long,

    val requestedResourceTypes: List<String>,

    val catalogVisibility: List<String>,

    val networkDisclosure: String,

    val credentialUse: String,
    val lanGranted: Boolean,

    val refreshFailed: Boolean = false,

    val refreshFailureReason: String? = null,

    val canManage: Boolean = true,
)

data class AddonReviewUi(

    val manifestUrl: String,
    val addonName: String,
    val addonVersion: String,
    val description: String,
    val endpoint: String,

    val trustLabel: String,
    val requestedResourceTypes: List<String>,
    val catalogVisibility: List<String>,
    val networkDisclosure: String,
    val credentialUse: String,
    val lanRequested: Boolean,
)

data class AddonConsentInput(
    val endpointAccepted: Boolean,
    val resourceTypesAccepted: Boolean,
    val catalogVisibilityAccepted: Boolean,
    val networkDisclosureAccepted: Boolean,
    val credentialUseNoneAccepted: Boolean,
    val lanGranted: Boolean,
)

sealed interface AddonReviewOutcome {
    data class Ready(val review: AddonReviewUi) : AddonReviewOutcome
    data class LanConsentRequired(val host: String) : AddonReviewOutcome
    data class Invalid(val reason: String) : AddonReviewOutcome
    data class Failed(val reason: String) : AddonReviewOutcome
}

sealed interface AddonInstallOutcome {
    data object Installed : AddonInstallOutcome
    data object AlreadyInstalled : AddonInstallOutcome
    data class ConsentDenied(val reason: String) : AddonInstallOutcome
    data class Invalid(val reason: String) : AddonInstallOutcome
    data class Failed(val reason: String) : AddonInstallOutcome
}

data class AddonCatalogEntryUi(
    val addonId: String,

    val catalogType: String,

    val catalogId: String,
    val displayName: String,
    val sortOrder: Int?,
    val ownerProfileId: String,

    val visibleInDiscover: Boolean = true,
)

sealed interface AddonRefreshOutcome {

    data object Refreshed : AddonRefreshOutcome

    data object Fresh : AddonRefreshOutcome

    data object Disabled : AddonRefreshOutcome

    data class Failed(val reason: String) : AddonRefreshOutcome
}

data class AddonHealthObservationUi(
    val observedAtMs: Long,
    val healthy: Boolean,
    val reason: String?,
    val status: Int?,
)

sealed interface AddonItemOperation {
    data object Idle : AddonItemOperation
    data object Enabling : AddonItemOperation
    data object Disabling : AddonItemOperation
    data object Refreshing : AddonItemOperation
    data object Moving : AddonItemOperation
    data object Removing : AddonItemOperation

    data class Failed(val message: String) : AddonItemOperation
}

sealed interface AddonMoveInstalledOutcome {

    data class Applied(val orderedIds: List<String>) : AddonMoveInstalledOutcome

    data object NoOp : AddonMoveInstalledOutcome

    data class Denied(val reason: String) : AddonMoveInstalledOutcome

    data class Failed(val reason: String) : AddonMoveInstalledOutcome
}

sealed interface AddonRenameOutcome {

    data object Applied : AddonRenameOutcome

    data object Invalid : AddonRenameOutcome

    data class Denied(val reason: String) : AddonRenameOutcome

    data class Failed(val reason: String) : AddonRenameOutcome
}

fun normalizedAddonDisplayName(input: String): String? = input.trim().takeIf { it.isNotEmpty() }

sealed interface AddonInstallPhase {
    data object Idle : AddonInstallPhase
    data object Reviewing : AddonInstallPhase
    data class ReviewReady(val review: AddonReviewUi) : AddonInstallPhase
    data class LanConsentRequired(val host: String) : AddonInstallPhase
    data class ReviewInvalid(val reason: String) : AddonInstallPhase
    data class ReviewFailed(val reason: String) : AddonInstallPhase
    data object Installing : AddonInstallPhase
    data class InstallFailed(val reason: String) : AddonInstallPhase
    data class InstallConsentDenied(val reason: String) : AddonInstallPhase
    data object Installed : AddonInstallPhase
    data object AlreadyInstalled : AddonInstallPhase
}

data class AddonManagementUiState(

    val repositoryState: AddonManagementRepositoryState = AddonManagementRepositoryState.Restoring,

    val catalog: List<AddonCatalogEntryUi> = emptyList(),
    val installPhase: AddonInstallPhase = AddonInstallPhase.Idle,
    val manifestUrl: String = "",
    val lanReviewApproved: Boolean = false,

    val actionMessage: String? = null,

    val itemOperations: Map<String, AddonItemOperation> = emptyMap(),

    val viewerProfileId: String = "",

    val healthHistory: List<AddonHealthObservationUi> = emptyList(),
    val healthHistoryAddonId: String? = null,
) {

    val installed: List<AddonManagementItemUi>
        get() = (repositoryState as? AddonManagementRepositoryState.Ready)?.installed ?: emptyList()

    val repositoryRestoring: Boolean get() = repositoryState is AddonManagementRepositoryState.Restoring
    val repositoryFailed: Boolean get() = repositoryState is AddonManagementRepositoryState.Failed

    fun canMoveUpInstalled(addonId: String): Boolean =
        installed.indexOfFirst { it.addonId == addonId }.let { it > 0 }

    fun canMoveDownInstalled(addonId: String): Boolean =
        installed.indexOfFirst { it.addonId == addonId }.let { it >= 0 && it < installed.lastIndex }

    fun operationFor(addonId: String): AddonItemOperation =
        itemOperations[addonId] ?: AddonItemOperation.Idle

    fun isBusy(addonId: String): Boolean =
        operationFor(addonId) !is AddonItemOperation.Idle &&
            operationFor(addonId) !is AddonItemOperation.Failed
}
