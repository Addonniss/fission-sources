package dev.infusion.source.aggregation

import dev.infusion.core.model.Availability
import dev.infusion.core.model.TitleId

interface AddonAvailabilityStore {

    fun upsert(availability: Availability)

    fun upsertAll(statements: List<Availability>) {
        statements.forEach { upsert(it) }
    }

    fun byTitle(titleId: TitleId): List<Availability>
}
