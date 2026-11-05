package dev.infusion.source.aggregation.resource

import dev.infusion.source.stremio.StremioResult
import dev.infusion.source.stremio.policy.StremioContentType
import dev.infusion.source.stremio.wire.StremioCatalogResponse

interface AddonCatalogRepository {

    fun declaredCatalogs(addonId: String): List<DeclaredCatalog>

    suspend fun catalog(
        addonId: String,
        type: StremioContentType,
        catalogId: String,
        extras: List<Pair<String, String>> = emptyList(),
        skip: Int? = null,
    ): StremioResult<StremioCatalogResponse>
}
