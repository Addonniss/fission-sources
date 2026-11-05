package dev.infusion.source.aggregation

import dev.infusion.core.model.TypedExternalId

internal fun TypedExternalId.stremioWireId(): String? {
    val value = value.trim()
    if (value.isEmpty()) return null

    return if (authority.equals(AUTHORITY_IMDB, ignoreCase = true)) value else "$authority:$value"
}

internal const val AUTHORITY_IMDB = "imdb"

internal fun Iterable<TypedExternalId>.firstStremioWireId(): String? =
    firstNotNullOfOrNull { it.stremioWireId() }
