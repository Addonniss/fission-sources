package dev.infusion.source.addon.resource

import dev.infusion.source.addon.repository.RoomAddonRepository
import dev.infusion.source.aggregation.resource.AddonCatalogRepository
import dev.infusion.source.aggregation.resource.AddonMetaRepository
import dev.infusion.source.aggregation.resource.AddonArtworkRepository
import dev.infusion.source.aggregation.resource.AddonStreamRepository
import dev.infusion.source.aggregation.resource.AddonSubtitleRepository

class AddonResourceBundle(
    repository: RoomAddonRepository,

    providerTimeoutMs: Long = DefaultAddonStreamRepository.DEFAULT_PROVIDER_TIMEOUT_MS,
) {

    val runtimes: RepositoryAddonResourceRuntimeAccessor = RepositoryAddonResourceRuntimeAccessor(repository)

    val catalog: AddonCatalogRepository = DefaultAddonCatalogRepository(runtimes)

    val meta: AddonMetaRepository = DefaultAddonMetaRepository(runtimes, providerTimeoutMs = providerTimeoutMs)

    val stream: AddonStreamRepository = DefaultAddonStreamRepository(runtimes, providerTimeoutMs = providerTimeoutMs)

    val subtitle: AddonSubtitleRepository = DefaultAddonSubtitleRepository(runtimes, providerTimeoutMs = providerTimeoutMs)

    val artwork: AddonArtworkRepository = DefaultAddonArtworkRepository(runtimes)
}
