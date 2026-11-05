package dev.infusion.source.aggregation.match

import dev.infusion.core.model.Title
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TitleKind
import dev.infusion.core.model.TypedExternalId

fun interface CanonicalTitleMaterializer {

    fun materializeExact(
        item: AddonMetaItem,
        externalId: TypedExternalId,
        kind: TitleKind,
    ): Title?

    companion object {

        val NOOP = CanonicalTitleMaterializer { _, _, _ -> null }

        fun deterministicTitleId(externalId: TypedExternalId): TitleId =
            dev.infusion.core.model.metadata.CanonicalTitleIdentity.of(externalId)
    }
}
