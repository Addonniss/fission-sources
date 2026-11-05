package dev.infusion.source.aggregation

import dev.infusion.core.model.Title
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TypedExternalId

interface CanonicalTitleLookup {

    fun byExternalId(external: TypedExternalId): List<Title>

    fun byNameAndYear(name: String, year: Int?): List<Title>

    fun byId(titleId: TitleId): Title?
}
