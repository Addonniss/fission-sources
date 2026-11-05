package dev.infusion.source.aggregation

import dev.infusion.core.model.SourceId
import dev.infusion.source.aggregation.resource.DeclaredCatalog
import dev.infusion.source.stremio.policy.StremioContentType

data class AddonRuntimeDescriptor(
    val addonId: String,
    val sourceId: SourceId,
    val displayName: String,
    val origin: String,
    val enabled: Boolean,
    val declaredCatalogs: List<DeclaredCatalog>,

    val manifestId: String? = null,

    val streamTypes: Set<StremioContentType> = emptySet(),

    val streamIdPrefixes: List<String> = emptyList(),
)
