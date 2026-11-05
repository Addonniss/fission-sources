package dev.infusion.source.aggregation

import dev.infusion.core.model.Availability
import dev.infusion.core.model.TitleId

interface TitleAvailabilityLookup {

    fun byTitle(titleId: TitleId): List<Availability>
}
