package dev.infusion.feature.vertical.media

import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TitleKind
import dev.infusion.core.model.TypedExternalId
import dev.infusion.core.model.metadata.CanonicalTitleIdentity
import dev.infusion.core.model.metadata.MediaIdentity
import dev.infusion.core.model.metadata.MediaType

val MediaCardUi.canonicalKind: TitleKind?
    get() = when (identity.type) {
        MediaType.MOVIE -> TitleKind.MOVIE
        MediaType.SHOW -> TitleKind.SERIES
    }

fun MediaCardUi.canonicalTitleIdOrNull(): TitleId? =
    canonicalTitleId
        ?: identity.titleId
        ?: canonicalTitleIdFromAliases(identity.externalIds.typed())

fun canonicalTitleIdFromAliases(aliases: List<TypedExternalId>): TitleId? =
    CARD_TITLE_ALIAS_ORDER.firstNotNullOfOrNull { authority ->
        aliases.firstOrNull { it.authority == authority }?.let(CanonicalTitleIdentity::of)
    }

private val CARD_TITLE_ALIAS_ORDER = listOf("imdb", "tmdb", "tvdb")

fun MediaIdentity.canonicalTitleIdOrNull(): TitleId? =
    titleId ?: canonicalTitleIdFromAliases(externalIds.typed())

internal suspend fun MediaCardUi.withResolvedCanonicalTitle(
    resolver: dev.infusion.core.domain.metadata.MediaIdentityResolver?,
): MediaCardUi {
    if (resolver == null || identity.externalIds.isEmpty) return this
    val resolved = runCatching { resolver.titleIdFor(identity) }.getOrNull() ?: return this
    return copy(canonicalTitleId = resolved)
}
