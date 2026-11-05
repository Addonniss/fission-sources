package dev.infusion.source.addon.repository

import dev.infusion.core.database.model.RemovalScope
import dev.infusion.core.database.sources.SourceRemovalReport
import dev.infusion.core.network.TrustClass
import dev.infusion.source.addon.AddonManifestSnapshot
import dev.infusion.source.addon.InstalledAddon
import dev.infusion.source.addon.model.AddonManifest
import dev.infusion.source.addon.store.AddonStore
import dev.infusion.source.stremio.StremioClient
import dev.infusion.source.stremio.StremioFailure
import dev.infusion.source.stremio.StremioProviderState
import dev.infusion.source.stremio.url.ConfiguredAddonUrl
import kotlinx.coroutines.flow.StateFlow

sealed interface AddonRepositoryState {

    data object Restoring : AddonRepositoryState

    data class Ready(val installed: List<InstalledAddon>) : AddonRepositoryState

    data class Failed(val issues: List<AddonStore.RestoreIssue>) : AddonRepositoryState
}

class InstalledAddonRuntime(
    private val client: StremioClient,
    val providerState: StremioProviderState,
    installed: InstalledAddon,
) : java.io.Closeable {

    @Volatile
    private var _installed = installed

    val addonId: String get() = _installed.addonId

    val installed: InstalledAddon get() = _installed

    internal fun replaceInstalled(updated: InstalledAddon) {
        _installed = updated
    }

    suspend fun <T> useClient(block: suspend (StremioClient) -> T): T = block(client)

    val hardenedTransport: dev.infusion.core.network.HardenedHttpClient get() = client.hardenedTransport

    override fun close() {
        providerState.disable()
        client.close()
    }
}

interface AddonRepository {

    val installed: StateFlow<AddonRepositoryState>

    val restoreIssues: StateFlow<List<AddonStore.RestoreIssue>>

    suspend fun fetchManifest(configured: ConfiguredAddonUrl, forceRefresh: Boolean): AddonFetchResult

    suspend fun add(request: InstallRequest): AddonAddResult

    suspend fun remove(addonId: String, removalScope: RemovalScope = RemovalScope.INCLUDING_CACHED_DATA): AddonRemoveResult

    suspend fun setEnabled(addonId: String, enabled: Boolean): SetEnabledResult

    suspend fun setDisplayName(addonId: String, displayName: String?): SetDisplayNameResult

    suspend fun setOrder(orderedIds: List<String>): AddonOrderResult

    suspend fun refresh(addonId: String): RefreshResult

    suspend fun restore(): AddonRepositoryState

    fun runtimeFor(addonId: String): InstalledAddonRuntime?

    fun close()

    sealed interface AddonFetchResult {
        data class Ok(val manifest: AddonManifest, val snapshot: AddonManifestSnapshot, val fromCache: Boolean) : AddonFetchResult
        data class Failed(val failure: StremioFailure) : AddonFetchResult
    }

    sealed interface AddonAddResult {
        data class Ok(val addonId: String) : AddonAddResult
        data class AlreadyInstalled(val addonId: String) : AddonAddResult

        data class FetchFailed(val failure: StremioFailure) : AddonAddResult
        data class Invalid(val reason: String) : AddonAddResult
    }

    sealed interface AddonRemoveResult {
        data class Ok(val report: SourceRemovalReport?) : AddonRemoveResult
        data object NotFound : AddonRemoveResult
    }

    sealed interface SetEnabledResult {
        data object Applied : SetEnabledResult
        data object NotFound : SetEnabledResult
    }

    sealed interface SetDisplayNameResult {

        data object Applied : SetDisplayNameResult
        data object NotFound : SetDisplayNameResult

        data object Invalid : SetDisplayNameResult
    }

    sealed interface AddonOrderResult {
        data class Applied(val orderedIds: List<String>) : AddonOrderResult
        data class Invalid(val reason: String) : AddonOrderResult
    }

    sealed interface RefreshResult {
        data class Ok(val manifest: AddonManifest) : RefreshResult

        data class StaleNotRefreshed(val failure: StremioFailure) : RefreshResult
        data object Fresh : RefreshResult
        data object Disabled : RefreshResult
        data object NotFound : RefreshResult
    }
}

data class InstallRequest(
    val configured: ConfiguredAddonUrl,
    val ownerProfileId: String,
    val lanConsented: Boolean,
    val lanHost: String? = if (lanConsented) configured.host else null,

    val validatedSnapshot: AddonManifestSnapshot? = null,
) {

    val trust: TrustClass
        get() = if (lanConsented && lanHost != null) TrustClass.LAN_TRUSTED else TrustClass.PUBLIC
}