package dev.infusion.feature.vertical.browse

import dev.infusion.core.domain.metadata.MediaIdentityResolver
import dev.infusion.core.model.MediaArtworkRef
import dev.infusion.core.model.TitleKind
import dev.infusion.core.model.metadata.MediaIdentity

internal data class SourceRowIdentity(

    val providerItemId: String,

    val identity: MediaIdentity,
    val kind: TitleKind?,
    val title: String,
    val year: Int?,

    val artwork: MediaArtworkRef?,
)

internal suspend fun <T> List<T>.mapSourceRowFacts(
    identityResolver: MediaIdentityResolver,
    identityAnchor: SourceCardIdentityAnchor,
    describe: (T) -> SourceRowIdentity?,
): List<SourceCardFact> {
    if (isEmpty()) return emptyList()
    val described = mapNotNull(describe)
    if (described.isEmpty()) return emptyList()

    val titleIds = identityResolver.titleIdsFor(described.map { item -> item.identity })
    val resolved = described.mapIndexedNotNull { index, item ->
        titleIds.getOrNull(index)?.let { titleId -> item to titleId }
    }

    identityAnchor.anchorAll(
        resolved.map { (item, titleId) ->
            SourceCardIdentityToAnchor(
                identity = item.identity,
                titleId = titleId,
                kind = item.kind,
                displayTitle = item.title,
                year = item.year,
            )
        },
    )
    return resolved.map { (item, titleId) ->
        SourceCardFact(
            providerItemId = item.providerItemId,
            identity = item.identity,
            titleId = titleId,
            kind = item.kind,
            title = item.title,
            year = item.year,
            artwork = item.artwork,
        )
    }
}
