package dev.infusion.feature.vertical.addon

import dev.infusion.core.model.profile.Profile
import kotlinx.coroutines.flow.Flow

interface AddonManagementPort {

    fun viewerProfile(): Profile

    fun primaryProfile(): Profile

    fun repositoryStateFlow(): Flow<AddonManagementRepositoryState>

    fun listVisibleAddons(viewer: Profile, primary: Profile): List<AddonManagementItemUi>

    fun catalogEntries(viewer: Profile, primary: Profile): List<AddonCatalogEntryUi>

    suspend fun review(manifestUrl: String, lanReviewApproved: Boolean): AddonReviewOutcome

    suspend fun install(consent: AddonConsentInput): AddonInstallOutcome

    suspend fun enable(addonId: String): Boolean

    suspend fun disable(addonId: String): Boolean

    suspend fun remove(addonId: String): Boolean

    suspend fun rename(addonId: String, displayName: String): AddonRenameOutcome

    suspend fun setInstalledOrder(orderedIds: List<String>): Boolean

    suspend fun moveInstalled(
        addonId: String,
        adjacentVisibleAddonId: String,
        viewerProfileId: String,
    ): AddonMoveInstalledOutcome

    suspend fun refresh(addonId: String): AddonRefreshOutcome

    fun healthHistory(addonId: String): List<AddonHealthObservationUi>

    fun setCatalogPreference(
        profileId: String,
        addonId: String,
        catalogType: String,
        catalogId: String,
        sortOrder: Int,
        visibleInDiscover: Boolean = true,
    )
}
