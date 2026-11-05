package dev.infusion.source.addon.shells

import dev.infusion.core.database.InfusionDatabase
import dev.infusion.core.database.sources.SourceRemoval
import dev.infusion.core.domain.catalog.ShelfStageMetrics
import dev.infusion.core.domain.profile.ProfileScope
import dev.infusion.core.network.LanTrustRegistry
import dev.infusion.core.security.SecretStore
import dev.infusion.source.addon.cache.AddonManifestCache
import dev.infusion.source.addon.lifecycle.AddonLifecycleManager
import dev.infusion.source.addon.lifecycle.AddonStartupRestorer
import dev.infusion.source.addon.repository.AddonRepository
import dev.infusion.source.addon.repository.AddonRepositoryState
import dev.infusion.source.addon.repository.InstalledAddonRuntime
import dev.infusion.source.addon.repository.RoomAddonRepository
import dev.infusion.source.addon.resource.AddonResourceBundle
import dev.infusion.source.addon.store.AddonCredentialStore
import dev.infusion.source.addon.store.RoomAddonStore
import dev.infusion.source.aggregation.AddonAvailabilityStore
import dev.infusion.source.aggregation.AddonRegistryPort
import dev.infusion.source.aggregation.CanonicalTitleLookup
import dev.infusion.source.aggregation.TitleAvailabilityLookup
import dev.infusion.source.aggregation.details.AddonDetailsService
import dev.infusion.source.aggregation.discover.DiscoverService
import dev.infusion.source.aggregation.match.CanonicalMatcher
import dev.infusion.source.aggregation.search.SearchService
import dev.infusion.source.aggregation.subtitles.AddonSubtitleMerger
import dev.infusion.source.stremio.AddonClientFactory

class AddonInfrastructure private constructor(
    private val database: InfusionDatabase,
    val repository: RoomAddonRepository,
    val manager: AddonLifecycleManager,
    val resources: AddonResourceBundle,
    val ports: AddonShellPorts,
    val startupRestorer: AddonStartupRestorer<AddonRepositoryState>,

    val canonicalShowHierarchy: dev.infusion.core.domain.metadata.CanonicalShowHierarchy? = null,

    val shelfStageMetrics: ShelfStageMetrics = ShelfStageMetrics.None,
) {

    val registry: AddonRegistryPort by lazy { ports.addonRegistry() }

    val lookup: CanonicalTitleLookup by lazy { ports.canonicalTitleLookup() }

    val availabilityStore: AddonAvailabilityStore by lazy { ports.availabilityStore() }

    val titleAvailability: TitleAvailabilityLookup by lazy { ports.titleAvailability() }

    val matcher: CanonicalMatcher by lazy {
        CanonicalMatcher(
            lookup = lookup,
            identityStore = dev.infusion.core.database.store.RoomIdentityMappingStore(
                database.identityMappingDao(),
            ),

            materializer = ports.canonicalTitleMaterializer(),
        )
    }

    val discoverService: DiscoverService by lazy {
        DiscoverService(
            registry = registry,
            catalogRepository = resources.catalog,
            matcher = matcher,
            availabilityStore = availabilityStore,
            artworkRepository = resources.artwork,
            stageMetrics = shelfStageMetrics,
        )
    }

    val searchService: SearchService by lazy {
        SearchService(
            registry = registry,
            catalogRepository = resources.catalog,
            matcher = matcher,
            availabilityStore = availabilityStore,
            titleAvailability = titleAvailability,

            artworkRepository = resources.artwork,
        )
    }

    val detailsService: AddonDetailsService by lazy {
        AddonDetailsService(
            registry = registry,
            metaRepository = resources.meta,
            availabilityStore = availabilityStore,
            viewer = placeholderProfile(),
            primary = placeholderProfile(),
        )
    }

    val destinationAnchor: dev.infusion.core.domain.anchor.CanonicalDestinationAnchor by lazy {
        dev.infusion.source.aggregation.anchor.CanonicalDestinationAnchorService(
            lookup = lookup,
            rootTitleStore = dev.infusion.core.database.store.RoomCanonicalRootTitleStore(
                titleDao = database.titleDao(),
                transaction = { block -> database.runInTransaction(block) },
            ),
            aliasStore = dev.infusion.core.database.store.RoomMediaIdentityStore(
                externalIdDao = database.externalIdDao(),
                titleDao = database.titleDao(),
                transaction = { block -> database.runInTransaction(block) },
            ),
            availabilityStore = availabilityStore,
            registry = registry,
            metaRepository = resources.meta,
            viewer = placeholderProfile(),
            primary = placeholderProfile(),

            convergence = dev.infusion.core.database.store.RoomCanonicalConvergenceStore(
                titleDao = database.titleDao(),
                titleLinkDao = database.titleLinkDao(),
                externalIdDao = database.externalIdDao(),
                identityMappingDao = database.identityMappingDao(),
                availabilityDao = database.availabilityDao(),
                editionDao = database.editionDao(),
                serverItemDao = database.serverItemDao(),
                localAssetDao = database.localAssetDao(),
                playableCandidateDao = database.playableCandidateDao(),
                metadataCacheDao = database.metadataCacheDao(),
                watchEventDao = database.watchEventDao(),
                watchStateDao = database.watchStateDao(),
                transaction = { block -> database.runInTransaction(block) },
                removedSourceIds = { database.sourceDao().listRemovedSourceIds().toSet() },
            ),
        )
    }

    fun nativeCatalogPort(base: () -> dev.infusion.core.domain.catalog.CatalogPort): dev.infusion.source.addon.shells.AddonNativeCatalogPort =
        dev.infusion.source.addon.shells.AddonNativeCatalogPort(
            base = base(),
            lookup = lookup,
            availabilityStore = availabilityStore,
            registry = registry,
            metaRepository = resources.meta,
            viewer = placeholderProfile(),
            primary = placeholderProfile(),

            destinationAnchor = destinationAnchor,

            canonicalHierarchy = canonicalShowHierarchy,

            artworkRepository = resources.artwork,

            addonSources = {
                when (val state = repository.installed.value) {
                    is AddonRepositoryState.Ready -> state.installed
                        .filter { it.enabled }
                        .map { installed ->
                            dev.infusion.core.model.Source(
                                sourceId = dev.infusion.core.model.SourceId.fromString(installed.addonId),
                                type = dev.infusion.core.model.SourceType.STREMIO_REMOTE,
                                trustClass = dev.infusion.core.model.TrustClass.REMOTE_ADDON,
                                displayName = installed.displayName,
                                endpoint = installed.origin.substringBefore('?').takeIf { it.isNotBlank() },
                                enabled = true,
                            )
                        }
                    else -> emptyList()
                }
            },
        ).apply {
            projectionWriter = ports.addonNativeProjectionWriter()
        }

    val subtitleMerger: AddonSubtitleMerger by lazy {
        AddonSubtitleMerger(
            subtitleRepository = resources.subtitle,
            lookup = lookup,
        )
    }

    fun installedAddonIds(): List<String> = manager.installedAddonIds()

    fun runtimeFor(addonId: String): InstalledAddonRuntime? = manager.runtimeFor(addonId)

    val installedState: kotlinx.coroutines.flow.StateFlow<AddonRepositoryState> = repository.installed

    suspend fun ensureRestored(): AddonRepositoryState = startupRestorer.awaitReady()

    fun close() = manager.close()

    private fun placeholderProfile(): dev.infusion.core.model.profile.Profile =
        dev.infusion.core.model.profile.Profile(
            profileId = dev.infusion.core.domain.profile.ProfilePolicy.DEFAULT_PROFILE_ID,
            name = "Placeholder",
            isDefault = true,
            profileIndex = 1,
            usesPrimarySources = true,
            pinProtected = false,
            createdAtMs = 0L,
        )

    companion object {

        fun create(
            database: InfusionDatabase,
            secretStore: SecretStore,
            lanTrust: LanTrustRegistry,
            profileScope: ProfileScope,
            clientFactory: AddonClientFactory,
            onSourceRemoved: (addonId: String) -> Unit = {},

            canonicalShowHierarchy: () -> dev.infusion.core.domain.metadata.CanonicalShowHierarchy? = { null },

            shelfStageMetrics: dev.infusion.core.domain.catalog.ShelfStageMetrics =
                dev.infusion.core.domain.catalog.ShelfStageMetrics.None,
        ): AddonInfrastructure {
            val store = RoomAddonStore(
                dao = database.addonDao(),
                transaction = { block -> database.runInTransaction(block) },
            )
            val sourceRemoval = SourceRemoval(
                database = database,
                sourceDao = database.sourceDao(),
                secretDao = database.secretReferenceDao(),
                serverItemDao = database.serverItemDao(),
                assetDao = database.localAssetDao(),
                availabilityDao = database.availabilityDao(),
                candidateDao = database.playableCandidateDao(),
                watchEventDao = database.watchEventDao(),
                watchStateDao = database.watchStateDao(),
                playSessionContextDao = database.playSessionContextDao(),
                candidateHandoffStore = dev.infusion.core.database.store.RoomCandidateHandoffStore(
                    dao = database.candidateHandoffDao(),
                    secretStore = secretStore,
                ),
            )
            val repository = RoomAddonRepository(
                addonStore = store,
                credentialStore = AddonCredentialStore(secretStore),
                sourceDao = database.sourceDao(),
                sourceRemoval = sourceRemoval,
                lanTrust = lanTrust,
                transaction = { block -> database.runInTransaction(block) },
                clientFactory = clientFactory,
                onSourceRemoved = onSourceRemoved,
            )
            val manager = AddonLifecycleManager(
                repository = repository,
                lanTrust = lanTrust,
                profileScope = profileScope,
                clientFactory = clientFactory,
            )
            val resources = AddonResourceBundle(repository)
            val ports = AddonShellPorts(
                manager = manager,
                addonStore = store,
                db = database,
                profileScope = profileScope,
            )
            val restorer = AddonStartupRestorer.repository(manager)
            return AddonInfrastructure(
                database = database,
                repository = repository,
                manager = manager,
                resources = resources,
                ports = ports,
                startupRestorer = restorer,
                canonicalShowHierarchy = canonicalShowHierarchy(),
                shelfStageMetrics = shelfStageMetrics,
            )
        }
    }
}
