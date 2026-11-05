package dev.infusion.source.addon.shells

import dev.infusion.core.database.InfusionDatabase
import dev.infusion.core.database.entity.AvailabilityEntity
import dev.infusion.core.database.entity.ExternalIdEntity
import dev.infusion.core.database.entity.HomePresentationPreferenceEntity
import dev.infusion.core.database.entity.HomeProviderPreferenceEntity
import dev.infusion.core.database.entity.SourceEntity
import dev.infusion.core.database.entity.TitleEntity
import dev.infusion.core.database.model.HealthState
import dev.infusion.core.domain.home.HomePresentationDefaults
import dev.infusion.core.model.Availability
import dev.infusion.core.model.AvailabilityId
import dev.infusion.core.model.AvailabilityKind
import dev.infusion.core.model.EditionId
import dev.infusion.core.model.Provenance
import dev.infusion.core.model.SourceId
import dev.infusion.core.model.Title
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TitleKind
import dev.infusion.core.model.TypedExternalId
import dev.infusion.core.model.profile.Profile
import dev.infusion.feature.vertical.addon.AddonCatalogEntryUi
import dev.infusion.feature.vertical.addon.AddonConsentInput
import dev.infusion.feature.vertical.addon.AddonHealthObservationUi
import dev.infusion.feature.vertical.addon.AddonInstallOutcome
import dev.infusion.feature.vertical.addon.AddonManagementItemUi
import dev.infusion.feature.vertical.addon.AddonManagementPort
import dev.infusion.feature.vertical.addon.AddonManagementRepositoryState
import dev.infusion.feature.vertical.addon.AddonMoveInstalledOutcome
import dev.infusion.feature.vertical.addon.AddonRefreshOutcome
import dev.infusion.feature.vertical.addon.AddonRenameOutcome
import dev.infusion.feature.vertical.addon.AddonReviewOutcome
import dev.infusion.feature.vertical.addon.AddonReviewUi
import dev.infusion.feature.vertical.discover.DiscoverPort
import dev.infusion.feature.vertical.home.HomeCatalogKey
import dev.infusion.feature.vertical.home.HomeCatalogOption
import dev.infusion.feature.vertical.home.HomeCatalogPort
import dev.infusion.feature.vertical.home.HomePresentationPreferences
import dev.infusion.feature.vertical.home.HomeSpotlightSource
import dev.infusion.feature.vertical.home.HomeSpotlightSourceCodec
import dev.infusion.feature.vertical.home.HomeProviderHomePreference
import dev.infusion.feature.vertical.home.homeProviderOrder
import dev.infusion.feature.vertical.home.orderHomeCatalogKeys
import dev.infusion.feature.vertical.search.AddonSearchPort
import dev.infusion.source.addon.AddonManifestSnapshot
import dev.infusion.source.addon.AddonProfileVisibility
import dev.infusion.source.addon.InstalledAddon
import dev.infusion.source.addon.lifecycle.AddonLifecycleManager
import dev.infusion.source.addon.lifecycle.AddonReviewRecord
import dev.infusion.source.addon.lifecycle.LifecycleInstallResult
import dev.infusion.source.addon.lifecycle.LifecycleReviewResult
import dev.infusion.source.addon.repository.AddonRepository
import dev.infusion.source.addon.repository.AddonRepositoryState
import dev.infusion.source.addon.store.AddonStore
import dev.infusion.source.aggregation.AddonAvailabilityStore
import dev.infusion.source.aggregation.AddonRegistryPort
import dev.infusion.source.aggregation.AddonRuntimeDescriptor
import dev.infusion.source.aggregation.CanonicalTitleLookup
import dev.infusion.source.aggregation.TitleAvailabilityLookup
import dev.infusion.source.aggregation.discover.DiscoverCatalogPlan
import dev.infusion.source.aggregation.discover.DiscoverService
import dev.infusion.source.aggregation.discover.DiscoverShelf
import dev.infusion.source.aggregation.discover.DiscoverShelfDeclaration
import dev.infusion.source.aggregation.discover.DiscoverShelfEmission
import dev.infusion.source.aggregation.match.AddonMetaItem
import dev.infusion.source.aggregation.match.CanonicalTitleMaterializer
import dev.infusion.source.aggregation.resource.DeclaredCatalog
import dev.infusion.source.aggregation.search.AddonGroupDestinations
import dev.infusion.source.aggregation.search.CanonicalSearchDiscovery
import dev.infusion.source.aggregation.search.PeopleSearchDiscovery
import dev.infusion.source.aggregation.search.SearchGroup
import dev.infusion.source.aggregation.search.SearchResults
import dev.infusion.source.aggregation.search.SearchResultsService
import dev.infusion.source.aggregation.search.SearchService
import dev.infusion.source.stremio.policy.StremioContentType
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.toList
import org.json.JSONObject

class AddonShellPorts(
    private val manager: AddonLifecycleManager,
    private val addonStore: AddonStore,
    private val db: InfusionDatabase,

    private val profileScope: dev.infusion.core.domain.profile.ProfileScope,
) {

    private val homeSelectionChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val titleSupersession =
        dev.infusion.core.database.store.TitleSupersession(db.titleDao())

    fun managementPort(): AddonManagementPort = object : AddonManagementPort {
        override fun viewerProfile(): Profile = activeViewer()
        override fun primaryProfile(): Profile = this@AddonShellPorts.primaryProfile()

        override fun repositoryStateFlow(): Flow<AddonManagementRepositoryState> =
            manager.installed
                .map { state -> repositoryStateUi(state, activeViewer(), primaryProfile()) }
                .distinctUntilChanged()

        override fun listVisibleAddons(viewer: Profile, primary: Profile): List<AddonManagementItemUi> =
            visibleInstalled(viewer, primary).map { it.toManagementItem(viewer) }

        override fun catalogEntries(viewer: Profile, primary: Profile): List<AddonCatalogEntryUi> =
            catalogFor(viewer, primary).flatMap { entry ->

                entry.catalogs.map { catalog ->
                    val pref = entry.catalogPreferences.firstOrNull {
                        it.catalogType == catalog.type.wire && it.catalogId == catalog.id
                    }
                    AddonCatalogEntryUi(
                        addonId = entry.addonId,
                        catalogType = catalog.type.wire,
                        catalogId = catalog.id,
                        displayName = catalog.name.ifBlank { entry.displayName },
                        sortOrder = pref?.sortOrder,
                        ownerProfileId = entry.ownerProfileId,

                        visibleInDiscover = pref?.visibleInDiscover ?: true,
                    )
                }
            }

        override suspend fun review(manifestUrl: String, lanReviewApproved: Boolean): AddonReviewOutcome =
            when (val result = manager.review(manifestUrl, lanReviewApproved)) {
                is LifecycleReviewResult.Ready -> {

                    pendingReviewToken = result.token
                    AddonReviewOutcome.Ready(result.record.toReviewUi())
                }
                is LifecycleReviewResult.LanConsentRequired -> AddonReviewOutcome.LanConsentRequired(result.host)
                is LifecycleReviewResult.Invalid -> AddonReviewOutcome.Invalid(result.reason)
                is LifecycleReviewResult.Failed -> AddonReviewOutcome.Failed(result.failure.message ?: result.failure.reason.name.lowercase())
            }

        override suspend fun install(consent: AddonConsentInput): AddonInstallOutcome {
            val token = pendingReviewToken ?: return AddonInstallOutcome.Invalid("no review record")
            val result = manager.install(
                token = token,
                consent = dev.infusion.source.addon.lifecycle.LifecycleInstallConsent(
                    endpointAccepted = consent.endpointAccepted,
                    resourceTypesAccepted = consent.resourceTypesAccepted,
                    catalogVisibilityAccepted = consent.catalogVisibilityAccepted,
                    networkDisclosureAccepted = consent.networkDisclosureAccepted,
                    credentialUseNoneAccepted = consent.credentialUseNoneAccepted,
                    lanGranted = consent.lanGranted,
                ),
            )

            pendingReviewToken = null
            return when (result) {
                is LifecycleInstallResult.Installed -> AddonInstallOutcome.Installed
                is LifecycleInstallResult.AlreadyInstalled -> AddonInstallOutcome.AlreadyInstalled
                is LifecycleInstallResult.ConsentDenied -> AddonInstallOutcome.ConsentDenied(result.reason)
                is LifecycleInstallResult.Invalid -> AddonInstallOutcome.Invalid(result.reason)
                is LifecycleInstallResult.Failed -> AddonInstallOutcome.Failed(result.failure.message ?: result.failure.reason.name.lowercase())
            }
        }

        override suspend fun enable(addonId: String): Boolean {
            if (!canMutate(addonId, activeViewer())) return false
            return manager.setEnabled(addonId, true) == AddonRepository.SetEnabledResult.Applied
        }

        override suspend fun disable(addonId: String): Boolean {
            if (!canMutate(addonId, activeViewer())) return false
            return manager.setEnabled(addonId, false) == AddonRepository.SetEnabledResult.Applied
        }

        override suspend fun remove(addonId: String): Boolean {
            if (!canMutate(addonId, activeViewer())) return false
            return manager.remove(addonId) is AddonRepository.AddonRemoveResult.Ok
        }

        override suspend fun rename(addonId: String, displayName: String): AddonRenameOutcome {

            if (!canMutate(addonId, activeViewer())) return AddonRenameOutcome.Denied("Read-only add-on")
            return when (manager.setDisplayName(addonId, displayName)) {
                AddonRepository.SetDisplayNameResult.Applied -> AddonRenameOutcome.Applied
                AddonRepository.SetDisplayNameResult.Invalid -> AddonRenameOutcome.Invalid
                AddonRepository.SetDisplayNameResult.NotFound -> AddonRenameOutcome.Denied("add-on not installed")
            }
        }

        override suspend fun setInstalledOrder(orderedIds: List<String>): Boolean {

            val viewer = activeViewer()
            if (orderedIds.any { id -> !canMutate(id, viewer) }) return false
            return manager.setOrder(orderedIds) is AddonRepository.AddonOrderResult.Applied
        }

        override suspend fun moveInstalled(
            addonId: String,
            adjacentVisibleAddonId: String,
            viewerProfileId: String,
        ): AddonMoveInstalledOutcome {

            val installed = when (val state = manager.installed.value) {
                is AddonRepositoryState.Ready -> state.installed
                else -> return AddonMoveInstalledOutcome.Denied("repository not ready")
            }
            val viewer = activeViewer()
            val primary = primaryProfile()
            if (addonId == adjacentVisibleAddonId) return AddonMoveInstalledOutcome.NoOp
            val moved = installed.firstOrNull { it.addonId == addonId }
                ?: return AddonMoveInstalledOutcome.Denied("add-on not installed")
            val anchor = installed.firstOrNull { it.addonId == adjacentVisibleAddonId }
                ?: return AddonMoveInstalledOutcome.Denied("adjacent add-on not installed")

            if (!AddonProfileVisibility.isVisible(moved.ownerProfileId, viewer, primary)) {
                return AddonMoveInstalledOutcome.Denied("add-on not visible")
            }
            if (!AddonProfileVisibility.isVisible(anchor.ownerProfileId, viewer, primary)) {
                return AddonMoveInstalledOutcome.Denied("adjacent add-on not visible")
            }
            if (moved.ownerProfileId != viewer.profileId) {
                return AddonMoveInstalledOutcome.Denied("add-on is read-only for this profile")
            }

            val reordered = installed.toMutableList()
            val from = reordered.indexOfFirst { it.addonId == addonId }
            val to = reordered.indexOfFirst { it.addonId == adjacentVisibleAddonId }
            if (from < 0 || to < 0) return AddonMoveInstalledOutcome.NoOp
            val item = reordered.removeAt(from)
            reordered.add(to, item)
            return when (val result = manager.setOrder(reordered.map { it.addonId })) {
                is AddonRepository.AddonOrderResult.Applied ->
                    AddonMoveInstalledOutcome.Applied(result.orderedIds)
                is AddonRepository.AddonOrderResult.Invalid ->
                    AddonMoveInstalledOutcome.Failed(result.reason)
            }
        }

        override suspend fun refresh(addonId: String): AddonRefreshOutcome {
            if (!canMutate(addonId, activeViewer())) return AddonRefreshOutcome.Failed("read-only for this profile")
            return when (val result = manager.refresh(addonId)) {
                is AddonRepository.RefreshResult.Ok -> AddonRefreshOutcome.Refreshed
                is AddonRepository.RefreshResult.Fresh -> AddonRefreshOutcome.Fresh
                is AddonRepository.RefreshResult.Disabled -> AddonRefreshOutcome.Disabled
                is AddonRepository.RefreshResult.NotFound -> AddonRefreshOutcome.Failed("not found")
                is AddonRepository.RefreshResult.StaleNotRefreshed -> AddonRefreshOutcome.Failed(
                    result.failure.reason.name.lowercase(),
                )
            }
        }

        override fun healthHistory(addonId: String): List<AddonHealthObservationUi> =
            addonStore.recentHealthObservations(addonId, MAX_HEALTH_HISTORY).map { observation ->
                AddonHealthObservationUi(
                    observedAtMs = observation.observedAtMs,
                    healthy = observation.healthy,
                    reason = observation.reason,
                    status = observation.status,
                )
            }

        override fun setCatalogPreference(
            profileId: String,
            addonId: String,
            catalogType: String,
            catalogId: String,
            sortOrder: Int,
            visibleInDiscover: Boolean,
        ) {

            addonStore.setCatalogPreference(
                dev.infusion.core.database.entity.AddonCatalogPreferenceEntity(
                    profileId = profileId,
                    addonId = addonId,
                    catalogType = catalogType,
                    catalogId = catalogId,
                    sortOrder = sortOrder,
                    hidden = false,
                    discoverVisible = visibleInDiscover,
                ),
            )
        }
    }

    @Volatile
    private var pendingReviewToken: String? = null

    private fun AddonReviewRecord.toReviewUi(): AddonReviewUi {
        return AddonReviewUi(

            manifestUrl = if (configured.querySecret != null) {
                configured.publicBase.toString().trimEnd('/') + "/manifest.json?configured"
            } else {
                configured.publicBase.toString().trimEnd('/') + "/manifest.json"
            },
            addonName = manifest.name,
            addonVersion = manifest.version,
            description = manifest.description,
            endpoint = configured.origin,
            trustLabel = trust.name,
            requestedResourceTypes = snapshot.requestedResourceTypes,
            catalogVisibility = snapshot.catalogVisibility.map { "${it.type.wire}:${it.id} (${it.name})" },
            networkDisclosure = disclosure.networkDisclosure,
            credentialUse = disclosure.credentialUse,
            lanRequested = lanRequested,
        )
    }

    private fun InstalledAddon.toManagementItem(viewer: Profile): AddonManagementItemUi {
        val health = db.sourceDao().getById(addonId)?.healthState ?: HealthState.UNKNOWN

        val newestObservation = addonStore.recentHealthObservations(addonId, 1).firstOrNull()
        val refreshFailed = newestObservation != null && !newestObservation.healthy
        return AddonManagementItemUi(
            addonId = addonId,
            displayName = displayName,
            origin = origin,
            enabled = enabled,
            healthLabel = health.name,
            ownerProfileId = ownerProfileId,
            ownedByViewer = ownerProfileId == viewer.profileId,

            canManage = ownerProfileId == viewer.profileId,
            installedAtMs = installedAtMs,
            requestedResourceTypes = disclosure.requestedResourceTypes,
            catalogVisibility = disclosure.catalogVisibility.map { "${it.type.wire}:${it.id} (${it.name})" },
            networkDisclosure = disclosure.networkDisclosure,
            credentialUse = disclosure.credentialUse,
            lanGranted = lanConsented,
            refreshFailed = refreshFailed,
            refreshFailureReason = if (refreshFailed) newestObservation.reason else null,
        )
    }

    fun homeCatalogPort(
        discoverService: DiscoverService,
    ): HomeCatalogPort = object : HomeCatalogPort {
        override fun viewerProfile(): Profile = activeViewer()

        override fun primaryProfile(): Profile = this@AddonShellPorts.primaryProfile()

        override fun changes(): Flow<Unit> = merge(
            manager.installed.map { Unit },
            homeSelectionChanges,
        )

        override fun availableCatalogs(
            viewer: Profile,
            primary: Profile,
        ): List<HomeCatalogOption> = visibleInstalled(viewer, primary)
            .filter { it.enabled }
            .flatMap { addon ->
                val runtime = manager.runtimeFor(addon.addonId)
                addon.snapshot.catalogs
                    .asSequence()
                    .filter { catalog ->
                        catalog.type != StremioContentType.UNKNOWN &&
                            runtime != null &&
                            dev.infusion.source.addon.resource.AddonResourceMatcher.supports(
                                addon.snapshot,
                                dev.infusion.source.stremio.wire.Resource.CATALOG,
                                catalog.type,
                            )
                    }
                    .map { catalog ->
                        HomeCatalogOption(
                            key = HomeCatalogKey(
                                addonId = addon.addonId,
                                catalogType = catalog.type.wire,
                                catalogId = catalog.id,
                            ),
                            addonDisplayName = addon.displayName,
                            displayName = catalog.name.ifBlank { catalog.id },
                        )
                    }
                    .toList()
            }

        override fun selectedCatalogs(profileId: String): List<HomeCatalogKey> =
            db.homeCatalogPreferenceDao().list(profileId).map { preference ->
                HomeCatalogKey(
                    addonId = preference.addonId,
                    catalogType = preference.catalogType,
                    catalogId = preference.catalogId,
                )
            }

        override fun replaceSelection(profileId: String, selected: List<HomeCatalogKey>) {
            val preferences = selected
                .distinct()
                .filter { key -> db.addonDao().getById(key.addonId) != null }
                .mapIndexed { index, key ->
                    dev.infusion.core.database.entity.HomeCatalogPreferenceEntity(
                        profileId = profileId,
                        addonId = key.addonId,
                        catalogType = key.catalogType,
                        catalogId = key.catalogId,
                        sortOrder = index,
                    )
                }
            db.runInTransaction {
                db.homeCatalogPreferenceDao().deleteForProfile(profileId)
                if (preferences.isNotEmpty()) {
                    db.homeCatalogPreferenceDao().insertAll(preferences)
                }
            }
            homeSelectionChanges.tryEmit(Unit)
        }

        override fun providerPreferences(profileId: String): List<HomeProviderHomePreference> =
            db.homeProviderPreferenceDao().list(profileId).map { preference ->
                HomeProviderHomePreference(
                    addonId = preference.addonId,
                    hiddenFromHome = preference.hiddenFromHome,
                )
            }

        override fun replaceProviderPreferences(
            profileId: String,
            providers: List<HomeProviderHomePreference>,
        ) {

            val rows = providers
                .distinctBy { it.addonId }
                .filter { provider -> db.addonDao().getById(provider.addonId) != null }
                .mapIndexed { index, provider ->
                    HomeProviderPreferenceEntity(
                        profileId = profileId,
                        addonId = provider.addonId,
                        hiddenFromHome = provider.hiddenFromHome,
                        sortOrder = index,
                    )
                }
            db.runInTransaction {
                db.homeProviderPreferenceDao().deleteForProfile(profileId)
                if (rows.isNotEmpty()) {
                    db.homeProviderPreferenceDao().insertAll(rows)
                }
            }
            homeSelectionChanges.tryEmit(Unit)
        }

        override fun presentationPreferences(profileId: String): HomePresentationPreferences {
            val row = db.homePresentationPreferenceDao().get(profileId)

            val defaults = HomePresentationPreferences()
            return HomePresentationPreferences(
                showProviderName = row?.showProviderName ?: HomePresentationDefaults.SHOW_PROVIDER_NAME,
                separateContinueWatching = row?.separateContinueWatching
                    ?: HomePresentationDefaults.SEPARATE_CONTINUE_WATCHING,

                spotlightAutoRollSeconds = row?.spotlightAutoRollSeconds
                    ?: defaults.spotlightAutoRollSeconds,

                showSpotlight = row?.showSpotlight
                    ?: defaults.showSpotlight,

                spotlightSource = HomeSpotlightSourceCodec.decode(row?.spotlightSource),
            )
        }

        override fun setShowProviderName(profileId: String, show: Boolean) {
            val current = presentationPreferences(profileId)
            db.homePresentationPreferenceDao().insert(
                HomePresentationPreferenceEntity(
                    profileId = profileId,
                    showProviderName = show,
                    separateContinueWatching = current.separateContinueWatching,
                    spotlightAutoRollSeconds = current.spotlightAutoRollSeconds,
                    showSpotlight = current.showSpotlight,
                    spotlightSource = HomeSpotlightSourceCodec.encodeOrAutomatic(current.spotlightSource),
                ),
            )
            homeSelectionChanges.tryEmit(Unit)
        }

        override fun setSeparateContinueWatching(profileId: String, separate: Boolean) {
            val current = presentationPreferences(profileId)
            db.homePresentationPreferenceDao().insert(
                HomePresentationPreferenceEntity(
                    profileId = profileId,
                    showProviderName = current.showProviderName,
                    separateContinueWatching = separate,
                    spotlightAutoRollSeconds = current.spotlightAutoRollSeconds,
                    showSpotlight = current.showSpotlight,
                    spotlightSource = HomeSpotlightSourceCodec.encodeOrAutomatic(current.spotlightSource),
                ),
            )
            homeSelectionChanges.tryEmit(Unit)
        }

        override fun setSpotlightAutoRollSeconds(profileId: String, seconds: Int) {
            val current = presentationPreferences(profileId)
            db.homePresentationPreferenceDao().insert(
                HomePresentationPreferenceEntity(
                    profileId = profileId,
                    showProviderName = current.showProviderName,
                    separateContinueWatching = current.separateContinueWatching,
                    spotlightAutoRollSeconds = seconds.coerceAtLeast(0),
                    showSpotlight = current.showSpotlight,
                    spotlightSource = HomeSpotlightSourceCodec.encodeOrAutomatic(current.spotlightSource),
                ),
            )
            homeSelectionChanges.tryEmit(Unit)
        }

        override fun setShowSpotlight(profileId: String, show: Boolean) {
            val current = presentationPreferences(profileId)
            db.homePresentationPreferenceDao().insert(
                HomePresentationPreferenceEntity(
                    profileId = profileId,
                    showProviderName = current.showProviderName,
                    separateContinueWatching = current.separateContinueWatching,
                    spotlightAutoRollSeconds = current.spotlightAutoRollSeconds,
                    showSpotlight = show,
                    spotlightSource = HomeSpotlightSourceCodec.encodeOrAutomatic(current.spotlightSource),
                ),
            )
            homeSelectionChanges.tryEmit(Unit)
        }

        override fun setSpotlightSource(profileId: String, source: HomeSpotlightSource) {
            val current = presentationPreferences(profileId)
            db.homePresentationPreferenceDao().insert(
                HomePresentationPreferenceEntity(
                    profileId = profileId,
                    showProviderName = current.showProviderName,
                    separateContinueWatching = current.separateContinueWatching,
                    spotlightAutoRollSeconds = current.spotlightAutoRollSeconds,
                    showSpotlight = current.showSpotlight,
                    spotlightSource = HomeSpotlightSourceCodec.encodeOrAutomatic(source),
                ),
            )
            homeSelectionChanges.tryEmit(Unit)
        }

        override suspend fun shelves(
            viewer: Profile,
            primary: Profile,
            selected: List<HomeCatalogKey>,
        ): List<dev.infusion.source.aggregation.discover.DiscoverShelf> =
            shelfStream(viewer, primary, selected)
                .toList()
                .sortedBy { it.order }
                .map { it.shelf }

        override fun shelfStream(
            viewer: Profile,
            primary: Profile,
            selected: List<HomeCatalogKey>,
        ): Flow<DiscoverShelfEmission> = flow {
            val providerHomeOrder = homeProviderOrder(
                providerPreferences(viewer.profileId),
                selected.map { it.addonId }.distinct(),
            )
            val ordered = orderHomeCatalogKeys(selected, providerHomeOrder)
            val rank = ordered.withIndex().associate { it.value.shelfKey to it.index }
            emitAll(
                discoverService.shelfStream(
                    viewer,
                    primary,

                    DiscoverCatalogPlan.Keys(rank.keys),
                )
                    .filter { emission -> emission.shelf.key in rank }
                    .map { emission ->
                        DiscoverShelfEmission(
                            order = rank.getValue(emission.shelf.key),
                            shelf = emission.shelf,
                        )
                    },
            )
        }

        override fun explicitShelfStream(
            viewer: Profile,
            primary: Profile,
            keys: List<HomeCatalogKey>,
        ): Flow<DiscoverShelfEmission> = flow {
            val ordered = keys.distinctBy { it.shelfKey }
            val rank = ordered.withIndex().associate { it.value.shelfKey to it.index }
            emitAll(
                discoverService.shelfStream(
                    viewer,
                    primary,
                    DiscoverCatalogPlan.Keys(rank.keys),
                )
                    .filter { emission -> emission.shelf.key in rank }
                    .map { emission ->
                        DiscoverShelfEmission(
                            order = rank.getValue(emission.shelf.key),
                            shelf = emission.shelf,
                        )
                    },
            )
        }

        override suspend fun shelfPage(
            viewer: Profile,
            primary: Profile,
            shelfKey: String,
            skip: Int,
        ): dev.infusion.source.aggregation.discover.DiscoverShelf? =
            discoverService.shelfPage(viewer, primary, shelfKey, skip)
    }

    fun addonRegistry(): AddonRegistryPort = object : AddonRegistryPort {
        override fun runtimesFor(viewer: Profile, primary: Profile): List<AddonRuntimeDescriptor> =
            catalogFor(viewer, primary)
                .filter { it.enabled }
                .mapNotNull { entry ->
                    manager.runtimeFor(entry.addonId)?.let { runtime ->
                        AddonRuntimeDescriptor(
                            addonId = runtime.addonId,
                            sourceId = SourceId.fromString(runtime.addonId),
                            displayName = runtime.installed.displayName,
                            origin = runtime.installed.origin,
                            enabled = runtime.installed.enabled,

                            manifestId = runtime.installed.snapshot.id,

                            declaredCatalogs = runtime.installed.snapshot.catalogs
                                .withIndex()
                                .sortedWith(
                                    compareBy(
                                        {
                                            catalogRank(
                                                it.index,
                                                it.value.type.wire,
                                                it.value.id,
                                                entry.catalogPreferences,
                                            )
                                        },
                                        { it.index },
                                    ),
                                )
                                .map { it.value }

                                .filter { it.type != StremioContentType.UNKNOWN }
                                .map { catalog ->
                                    DeclaredCatalog(
                                        type = catalog.type,
                                        id = catalog.id,
                                        name = catalog.name,
                                        extras = catalog.extras.map { it.name },
                                    )
                                },

                            streamTypes = if (
                                hasStreamResource(runtime.installed.snapshot)
                            ) {
                                dev.infusion.source.addon.resource.AddonResourceMatcher
                                    .effectiveTypes(runtime.installed.snapshot, dev.infusion.source.stremio.wire.Resource.STREAM)
                            } else {
                                emptySet()
                            },
                            streamIdPrefixes = if (hasStreamResource(runtime.installed.snapshot)) {
                                dev.infusion.source.addon.resource.AddonResourceMatcher
                                    .effectiveIdPrefixes(runtime.installed.snapshot, dev.infusion.source.stremio.wire.Resource.STREAM)
                            } else {
                                emptyList()
                            },
                        )
                    }
                }
    }

    private fun hasStreamResource(snapshot: AddonManifestSnapshot): Boolean =
        snapshot.resources.any { dev.infusion.source.stremio.wire.Resource.parse(it.name) == dev.infusion.source.stremio.wire.Resource.STREAM }

    fun canonicalTitleLookup(): CanonicalTitleLookup = object : CanonicalTitleLookup {
        override fun byExternalId(external: TypedExternalId): List<Title> {
            val row = db.externalIdDao().get(external.authority, external.value) ?: return emptyList()
            val titleId = row.titleId ?: return emptyList()
            val entity = db.titleDao().getById(titleId) ?: return emptyList()
            return listOf(entity.toCanonical())
        }

        override fun byNameAndYear(name: String, year: Int?): List<Title> =

            db.titleDao().listCanonical().map { it.toCanonical() }

        override fun byId(titleId: TitleId): Title? {

            val entity = db.titleDao().getById(titleSupersession.survivorOf(titleId).value.toString())
                ?: return null

            val aliases = db.externalIdDao().listByTitle(entity.titleId)
                .map { TypedExternalId(it.authority, it.nativeId) }
                .toSet()
            return entity.toCanonical().copy(aliases = aliases)
        }
    }

    fun availabilityStore(): AddonAvailabilityStore = object : AddonAvailabilityStore {
        override fun upsert(availability: Availability) {
            db.availabilityDao().upsert(availability.toEntity())
        }

        override fun upsertAll(statements: List<Availability>) {
            if (statements.isEmpty()) return
            db.availabilityDao().upsertAll(statements.map { it.toEntity() })
        }

        override fun byTitle(titleId: TitleId): List<Availability> =
            db.availabilityDao()
                .listForTitle(titleSupersession.survivorOf(titleId).value.toString(), null)
                .map { it.toCanonical() }
    }

    private fun addonProvenance(item: AddonMetaItem, normalizedBy: String): Provenance {
        val sourceId = item.addonSourceId
            ?: SourceId.fromString(
                java.util.UUID.nameUUIDFromBytes("addon:${item.nativeItemId}".toByteArray()).toString(),
            )
        return Provenance(
            sourceId = sourceId,
            protocolVersion = "stremio/1",
            retrievedAt = java.time.Instant.now(),
            normalizedBy = normalizedBy,
        )
    }

    fun canonicalTitleMaterializer(): CanonicalTitleMaterializer =
        CanonicalTitleMaterializer { item, externalId, kind ->

            var result: Title? = null
            db.runInTransaction {
                val existing = db.externalIdDao().get(externalId.authority, externalId.value)
                val existingTitleId = existing?.titleId
                if (existingTitleId != null) {
                    result = db.titleDao().getById(existingTitleId)?.toCanonical()
                    return@runInTransaction
                }
                val titleId = CanonicalTitleMaterializer.deterministicTitleId(externalId)
                val row = db.titleDao().getById(titleId.value.toString())
                val dbKind = when (kind) {
                    dev.infusion.core.model.TitleKind.MOVIE ->
                        dev.infusion.core.database.model.TitleKind.MOVIE
                    dev.infusion.core.model.TitleKind.SERIES ->
                        dev.infusion.core.database.model.TitleKind.SERIES

                    else -> return@runInTransaction
                }
                if (row == null) {

                    val description = item.description?.takeIf { it.isNotBlank() }
                    val genres = item.genres.takeIf { it.isNotEmpty() }
                    val rating = item.imdbRating?.takeIf { it.isNotBlank() }
                    db.titleDao().upsert(
                        TitleEntity(
                            titleId = titleId.value.toString(),
                            kind = dbKind,
                            primaryTitle = item.displayTitle,
                            originalTitle = null,
                            year = item.year,
                            createdAtMs = java.time.Instant.now().toEpochMilli(),
                            synopsis = description,
                            synopsisProvenanceJson = description
                                ?.let { addonProvenance(item, "AddonShellPorts/synopsis") }
                                ?.let(dev.infusion.core.database.store.ProvenanceCodec::encode),
                            genres = genres?.let(dev.infusion.core.database.store.GenreCodec::encode),
                            genresProvenanceJson = genres
                                ?.let { addonProvenance(item, "AddonShellPorts/genres") }
                                ?.let(dev.infusion.core.database.store.ProvenanceCodec::encode),
                            rating = rating,
                            ratingProvenanceJson = rating
                                ?.let { addonProvenance(item, "AddonShellPorts/rating") }
                                ?.let(dev.infusion.core.database.store.ProvenanceCodec::encode),
                        ),
                    )
                }

                db.externalIdDao().upsert(
                    ExternalIdEntity(
                        authority = externalId.authority,
                        nativeId = externalId.value,
                        titleId = titleId.value.toString(),
                        editionId = null,
                        createdAtMs = java.time.Instant.now().toEpochMilli(),
                    ),
                )
                result = db.titleDao().getById(titleId.value.toString())?.toCanonical()
            }
            result
        }

    fun addonNativeProjectionWriter(): AddonNativeProjectionWriter =
        object : AddonNativeProjectionWriter {
            override fun writeUnit(
                runtime: AddonRuntimeDescriptor,
                seriesTitleId: TitleId,
                titleId: TitleId,
                kind: TitleKind,
                displayTitle: String,
                alias: TypedExternalId,
                seasonNumber: Int?,
                episodeNumber: Int?,
                nativeVideoId: String?,
            ) {
                db.runInTransaction {

                    if (db.titleDao().getById(titleId.value.toString()) == null) {
                        db.titleDao().upsert(
                            TitleEntity(
                                titleId = titleId.value.toString(),
                                kind = when (kind) {
                                    TitleKind.SEASON -> dev.infusion.core.database.model.TitleKind.SEASON
                                    TitleKind.EPISODE -> dev.infusion.core.database.model.TitleKind.EPISODE

                                    else -> return@runInTransaction
                                },
                                primaryTitle = displayTitle,
                                originalTitle = null,
                                year = null,
                                createdAtMs = java.time.Instant.now().toEpochMilli(),
                                seasonNumber = seasonNumber,
                                episodeNumber = episodeNumber,
                            ),
                        )
                    }

                    if (db.externalIdDao().get(alias.authority, alias.value) == null) {
                        db.externalIdDao().upsert(
                            ExternalIdEntity(
                                authority = alias.authority,
                                nativeId = alias.value,
                                titleId = titleId.value.toString(),
                                editionId = null,
                                createdAtMs = java.time.Instant.now().toEpochMilli(),
                            ),
                        )
                    }

                    if (kind == TitleKind.SEASON) {
                        if (db.titleLinkDao().get(seriesTitleId.value.toString(), titleId.value.toString()) == null) {
                            db.titleLinkDao().insert(
                                dev.infusion.core.database.entity.TitleLinkEntity(
                                    parentTitleId = seriesTitleId.value.toString(),
                                    childTitleId = titleId.value.toString(),
                                    relation = dev.infusion.core.database.model.TitleRelation.SEASON_OF,
                                ),
                            )
                        }
                    } else if (kind == TitleKind.EPISODE && seasonNumber != null) {

                        val seasonAlias = TypedExternalId(
                            "addon:${runtime.addonId}",
                            "season:${alias.value.removePrefix("episode:").substringBeforeLast(':')}",
                        )
                        val seasonTitleId = db.externalIdDao().get(seasonAlias.authority, seasonAlias.value)?.titleId
                        if (seasonTitleId != null &&
                            db.titleLinkDao().get(seasonTitleId, titleId.value.toString()) == null
                        ) {
                            db.titleLinkDao().insert(
                                dev.infusion.core.database.entity.TitleLinkEntity(
                                    parentTitleId = seasonTitleId,
                                    childTitleId = titleId.value.toString(),
                                    relation = dev.infusion.core.database.model.TitleRelation.EPISODE_OF,
                                ),
                            )
                        }
                    }

                    if (kind == TitleKind.EPISODE && nativeVideoId != null && nativeVideoId.isNotBlank()) {
                        db.availabilityDao().upsert(
                            AvailabilityEntity(
                                availabilityId = java.util.UUID.nameUUIDFromBytes(
                                    "addon:${runtime.addonId}:$nativeVideoId".toByteArray(),
                                ).toString(),
                                titleId = titleId.value.toString(),
                                editionId = null,
                                sourceId = runtime.sourceId.value.toString(),
                                nativeResourceId = nativeVideoId,
                                kind = dev.infusion.core.database.model.AvailabilityKind.ADDON,
                                region = null,
                                expiresAtMs = null,
                                entitlementHintsJson = null,
                                provenance = dev.infusion.core.database.store.ProvenanceCodec.encode(
                                    dev.infusion.core.model.Provenance(
                                        sourceId = runtime.sourceId,
                                        protocolVersion = "stremio/1",
                                        retrievedAt = java.time.Instant.now(),
                                        normalizedBy = "AddonNativeCatalogPort",
                                    ),
                                ),
                                refreshedAtMs = java.time.Instant.now().toEpochMilli(),
                                createdAtMs = java.time.Instant.now().toEpochMilli(),
                            ),
                        )
                    }
                }
            }
        }

    fun titleAvailability(): TitleAvailabilityLookup = object : TitleAvailabilityLookup {
        override fun byTitle(titleId: TitleId): List<Availability> =
            db.availabilityDao()
                .listForTitle(titleSupersession.survivorOf(titleId).value.toString(), null)
                .map { it.toCanonical() }
    }

    fun discoverPort(service: DiscoverService): DiscoverPort = object : DiscoverPort {
        override fun viewerProfile(): Profile = activeViewer()
        override fun primaryProfile(): Profile = this@AddonShellPorts.primaryProfile()

        private fun hiddenShelfKeys(viewer: Profile): Set<String> =
            addonStore.catalogPreferences(viewer.profileId)
                .filterNot { it.discoverVisible }
                .map { preference ->
                    DiscoverService.shelfKey(
                        addonId = preference.addonId,
                        catalogType = preference.catalogType,
                        catalogId = preference.catalogId,
                    )
                }
                .toSet()

        override suspend fun shelves(viewer: Profile, primary: Profile): List<DiscoverShelf> =
            service.shelves(viewer, primary, discoverPlan(viewer, primary))

        override fun shelfStream(
            viewer: Profile,
            primary: Profile,
        ): Flow<DiscoverShelfEmission> =
            service.shelfStream(viewer, primary, discoverPlan(viewer, primary))

        private fun discoverPlan(
            viewer: Profile,
            primary: Profile,
        ): DiscoverCatalogPlan {
            val hidden = hiddenShelfKeys(viewer)
            return DiscoverCatalogPlan.Keys(
                service.declaredShelfKeys(viewer, primary).filterNot { it in hidden }.toSet(),
            )
        }

        override suspend fun shelfPage(
            viewer: Profile,
            primary: Profile,
            shelfKey: String,
            skip: Int,
        ): DiscoverShelf? = service.shelfPage(viewer, primary, shelfKey, skip)

        override fun declaredShelfKeys(viewer: Profile, primary: Profile): List<String> =
            declaredShelfDeclarations(viewer, primary).map { declaration -> declaration.key }

        override fun declaredShelfDeclarations(
            viewer: Profile,
            primary: Profile,
        ): List<DiscoverShelfDeclaration> {
            val hidden = hiddenShelfKeys(viewer)
            return service.declaredShelfDeclarations(viewer, primary)
                .filterNot { declaration -> declaration.key in hidden }
        }

        override suspend fun shelfFirstPage(
            viewer: Profile,
            primary: Profile,
            shelfKey: String,
        ): DiscoverShelf? {
            if (shelfKey in hiddenShelfKeys(viewer)) return null
            return service.shelfFirstPage(viewer, primary, shelfKey)
        }
    }

    fun searchPort(
        service: SearchService,
        canonicalDiscovery: CanonicalSearchDiscovery? = null,
        groupDestinations: AddonGroupDestinations? = null,

        peopleDiscovery: PeopleSearchDiscovery? = null,
    ): AddonSearchPort = object : AddonSearchPort {
        private val results = SearchResultsService(
            addonCatalogSearch = service,
            canonicalDiscovery = canonicalDiscovery,
            groupDestinations = groupDestinations,
            peopleDiscovery = peopleDiscovery,
        )
        override fun viewerProfile(): Profile = activeViewer()
        override fun primaryProfile(): Profile = this@AddonShellPorts.primaryProfile()

        override fun activeProfileId(): String = this@AddonShellPorts.activeProfileId()
        override suspend fun search(
            term: String,
            viewer: Profile,
            primary: Profile,
        ): SearchResults = results.search(term, viewer, primary)
    }

    fun activeProfileId(): String = profileScope.activeProfileId

    fun viewerProfileSnapshot(): Profile = activeViewer()

    fun primaryProfileSnapshot(): Profile = primaryProfile()

    private fun visibleInstalled(viewer: Profile, primary: Profile): List<InstalledAddon> {
        val installed = when (val state = manager.installed.value) {
            is AddonRepositoryState.Ready -> state.installed
            else -> emptyList()
        }
        return AddonProfileVisibility.filterVisible(installed, { it.ownerProfileId }, viewer, primary)
    }

    private data class CatalogEntry(
        val addonId: String,
        val displayName: String,
        val ownerProfileId: String,
        val enabled: Boolean,

        val catalogs: List<DeclaredCatalogUi>,
        val catalogPreferences: List<CatalogPreferenceEntry>,
    )

    private data class DeclaredCatalogUi(
        val type: dev.infusion.source.stremio.policy.StremioContentType,
        val id: String,
        val name: String,
    )

    private data class CatalogPreferenceEntry(
        val catalogType: String,
        val catalogId: String,
        val sortOrder: Int,

        val visibleInDiscover: Boolean = true,
    )

    private fun catalogRank(
        manifestIndex: Int,
        catalogTypeWire: String,
        catalogId: String,
        preferences: List<CatalogPreferenceEntry>,
    ): Int = preferences
        .firstOrNull { it.catalogType == catalogTypeWire && it.catalogId == catalogId }
        ?.sortOrder
        ?: manifestIndex

    private fun catalogFor(viewer: Profile, primary: Profile): List<CatalogEntry> {
        val visible = visibleInstalled(viewer, primary)

        val prefs = addonStore.catalogPreferences(viewer.profileId)
            .filterNot { it.hidden }
            .groupBy { it.addonId }
            .mapValues { (_, rows) -> rows.sortedBy { it.sortOrder } }
        return visible
            .sortedWith(compareBy<InstalledAddon> { it.sortOrder }.thenBy { it.addonId })
            .map { addon ->
                val catalogPreferences = prefs[addon.addonId].orEmpty()
                    .map {
                        CatalogPreferenceEntry(
                            it.catalogType,
                            it.catalogId,
                            it.sortOrder,
                            visibleInDiscover = it.discoverVisible,
                        )
                    }
                CatalogEntry(
                    addonId = addon.addonId,
                    displayName = addon.displayName,
                    ownerProfileId = addon.ownerProfileId,
                    enabled = addon.enabled,

                    catalogs = addon.snapshot.catalogs
                        .withIndex()
                        .sortedWith(
                            compareBy(
                                { catalogRank(it.index, it.value.type.wire, it.value.id, catalogPreferences) },
                                { it.index },
                            ),
                        )
                        .map { (_, catalog) ->
                            DeclaredCatalogUi(catalog.type, catalog.id, catalog.name)
                        },
                    catalogPreferences = catalogPreferences,
                )
            }
    }

    private fun repositoryStateUi(
        state: AddonRepositoryState,
        viewer: Profile,
        primary: Profile,
    ): AddonManagementRepositoryState = when (state) {
        is AddonRepositoryState.Restoring -> AddonManagementRepositoryState.Restoring
        is AddonRepositoryState.Ready -> AddonManagementRepositoryState.Ready(
            AddonProfileVisibility.filterVisible(state.installed, { it.ownerProfileId }, viewer, primary)
                .map { it.toManagementItem(viewer) },
        )
        is AddonRepositoryState.Failed -> AddonManagementRepositoryState.Failed(
            state.issues.map { issue ->

                issue.reason.take(160)
            },
        )
    }

    private fun canMutate(addonId: String, viewer: Profile): Boolean {
        val installed = when (val state = manager.installed.value) {
            is AddonRepositoryState.Ready -> state.installed
            else -> return false
        }
        val addon = installed.firstOrNull { it.addonId == addonId } ?: return false
        return AddonProfileVisibility.isVisible(addon.ownerProfileId, viewer, primaryProfile()) &&
            addon.ownerProfileId == viewer.profileId
    }

    private fun activeViewer(): Profile {
        val primary = primaryProfile()
        val profiles = db.profileDao().list()
        return profiles.firstOrNull { it.profileId == profileScope.activeProfileId }?.toCanonical() ?: primary
    }

    private fun primaryProfile(): Profile {
        val primary = db.profileDao().getPrimaryByIndex() ?: db.profileDao().getDefault()
        return primary?.toCanonical() ?: Profile(
            profileId = dev.infusion.core.domain.profile.ProfilePolicy.DEFAULT_PROFILE_ID,
            name = "Primary",
            isDefault = true,
            profileIndex = 1,
            usesPrimarySources = true,
            pinProtected = false,
            createdAtMs = 0L,
        )
    }

    private companion object {
        const val MAX_HEALTH_HISTORY = 20
    }
}

class LiveProfileAddonRegistry(
    private val base: AddonRegistryPort,
    private val viewerProvider: () -> Profile,
    private val primaryProvider: () -> Profile,
) : AddonRegistryPort {
    override fun runtimesFor(viewer: Profile, primary: Profile): List<AddonRuntimeDescriptor> =
        base.runtimesFor(viewerProvider(), primaryProvider())
}

internal fun dev.infusion.core.database.entity.TitleEntity.toCanonical(): Title = Title(
    titleId = TitleId.fromString(titleId),
    kind = when (kind) {
        dev.infusion.core.database.model.TitleKind.MOVIE -> TitleKind.MOVIE
        dev.infusion.core.database.model.TitleKind.SERIES -> TitleKind.SERIES
        dev.infusion.core.database.model.TitleKind.SEASON -> TitleKind.SEASON
        dev.infusion.core.database.model.TitleKind.EPISODE -> TitleKind.EPISODE
        dev.infusion.core.database.model.TitleKind.CLIP -> TitleKind.CLIP
    },
    displayTitle = primaryTitle,
    originalTitle = originalTitle,
    year = year,

    synopsis = synopsis,
    synopsisProvenance = synopsis?.let { dev.infusion.core.database.store.ProvenanceCodec.decode(synopsisProvenanceJson) },

    genres = dev.infusion.core.database.store.GenreCodec.decode(genres),
    genresProvenance = genres?.let { dev.infusion.core.database.store.ProvenanceCodec.decode(genresProvenanceJson) },
    rating = rating,
    ratingProvenance = rating?.let { dev.infusion.core.database.store.ProvenanceCodec.decode(ratingProvenanceJson) },
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
)

internal fun dev.infusion.core.database.entity.ProfileEntity.toCanonical(): Profile = Profile(
    profileId = profileId,
    name = name,
    isDefault = isDefault,
    profileIndex = profileIndex,
    usesPrimarySources = usesPrimarySources,
    pinProtected = pinHash != null,
    createdAtMs = createdAtMs,
)

internal fun Availability.toEntity(): AvailabilityEntity = AvailabilityEntity(
    availabilityId = availabilityId.value.toString(),
    titleId = titleId.value.toString(),
    editionId = editionId?.value?.toString(),
    sourceId = sourceId.value.toString(),
    nativeResourceId = nativeResourceId,
    kind = dev.infusion.core.database.model.AvailabilityKind.valueOf(kind.name),
    region = regionHint,
    expiresAtMs = expiresAt?.toEpochMilli(),
    entitlementHintsJson = null,
    provenance = provenance?.let { p ->
        JSONObject()
            .put("sourceId", p.sourceId.value.toString())
            .put("protocolVersion", p.protocolVersion)
            .put("retrievedAt", p.retrievedAt.toEpochMilli())
            .put("normalizedBy", p.normalizedBy)
            .toString()
    },
    refreshedAtMs = observedAt.toEpochMilli(),
    createdAtMs = observedAt.toEpochMilli(),
)

internal fun AvailabilityEntity.toCanonical(): Availability {
    val provenance = runCatching {
        val o = JSONObject(provenance ?: "")
        Provenance(
            sourceId = SourceId.fromString(o.getString("sourceId")),
            protocolVersion = o.optString("protocolVersion", "stremio/1"),
            retrievedAt = Instant.ofEpochMilli(o.optLong("retrievedAt", refreshedAtMs ?: createdAtMs)),
            normalizedBy = o.optString("normalizedBy", "AddonShellPorts"),
        )
    }.getOrElse {
        Provenance(
            sourceId = SourceId.fromString(sourceId),
            protocolVersion = "stremio/1",
            retrievedAt = Instant.ofEpochMilli(refreshedAtMs ?: createdAtMs),
            normalizedBy = "AddonShellPorts",
        )
    }
    return Availability(
        availabilityId = AvailabilityId.fromString(availabilityId),
        titleId = TitleId.fromString(titleId),
        editionId = editionId?.let { EditionId.fromString(it) },
        sourceId = SourceId.fromString(sourceId),
        nativeResourceId = nativeResourceId ?: "",
        kind = AvailabilityKind.valueOf(kind.name),
        regionHint = region,
        expiresAt = expiresAtMs?.let { Instant.ofEpochMilli(it) },
        observedAt = Instant.ofEpochMilli(refreshedAtMs ?: createdAtMs),
        provenance = provenance,
    )
}
