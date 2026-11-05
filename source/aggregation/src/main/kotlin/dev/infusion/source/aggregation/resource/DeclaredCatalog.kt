package dev.infusion.source.aggregation.resource

import dev.infusion.source.stremio.policy.StremioContentType

data class DeclaredCatalog(
    val type: StremioContentType,
    val id: String,
    val name: String,
    val extras: List<String> = emptyList(),
)
