package dev.infusion.core.metadata

import dev.infusion.core.domain.metadata.CanonicalAliasCompletion
import dev.infusion.core.domain.metadata.MediaIdentityStore
import dev.infusion.core.domain.metadata.MetadataLocale
import dev.infusion.core.domain.metadata.MetadataProvider
import dev.infusion.core.model.Title
import dev.infusion.core.model.TitleKind
import dev.infusion.core.model.identity.MappingOrigin
import dev.infusion.core.model.metadata.ExternalIds
import dev.infusion.core.model.metadata.MediaIdentity
import dev.infusion.core.model.metadata.MediaType
import kotlinx.coroutines.CancellationException

class DefaultCanonicalAliasCompletion(
    private val provider: MetadataProvider,
    private val store: MediaIdentityStore,
    private val locale: MetadataLocale = MetadataLocale.Default,
) : CanonicalAliasCompletion {

    override suspend fun complete(title: Title): Title {
        val type = mediaTypeOf(title.kind) ?: return title
        if (title.aliases.isEmpty()) return title
        val identity = MediaIdentity(
            type = type,
            titleId = title.titleId,
            externalIds = ExternalIds.fromTyped(title.aliases),
        )

        val stated = runCatching { provider.externalIds(identity, locale) }
            .onFailure { error -> if (error is CancellationException) throw error }
            .getOrNull() ?: return title
        val merged = identity.externalIds.merge(stated) ?: return title
        val learned = merged.typed().filterNot { alias -> title.aliases.any { it.sameAs(alias) } }
        if (learned.isEmpty()) return title
        for (alias in learned) {

            runCatching { store.bind(alias, title.titleId, MappingOrigin.CONNECTOR) }
                .onFailure { error -> if (error is CancellationException) throw error }
        }
        return title.copy(aliases = title.aliases + learned)
    }

    private companion object {

        fun mediaTypeOf(kind: TitleKind?): MediaType? = when (kind) {
            TitleKind.MOVIE -> MediaType.MOVIE
            TitleKind.SERIES -> MediaType.SHOW
            else -> null
        }

        fun dev.infusion.core.model.TypedExternalId.sameAs(
            other: dev.infusion.core.model.TypedExternalId,
        ): Boolean =
            authority.equals(other.authority, ignoreCase = true) && value == other.value
    }
}
