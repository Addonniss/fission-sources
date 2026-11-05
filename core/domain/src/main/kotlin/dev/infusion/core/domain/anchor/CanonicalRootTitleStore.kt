package dev.infusion.core.domain.anchor

import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TitleKind

interface CanonicalRootTitleStore {

    suspend fun materializeRoot(
        titleId: TitleId,
        kind: TitleKind,
        displayTitle: String,
        year: Int?,
    ): Boolean
}
