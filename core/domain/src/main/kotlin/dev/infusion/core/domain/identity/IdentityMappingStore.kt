package dev.infusion.core.domain.identity

import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TypedExternalId
import dev.infusion.core.model.identity.IdentityMapping
import kotlinx.coroutines.flow.Flow

interface IdentityMappingStore {

    fun put(mapping: IdentityMapping)

    fun byExternal(external: TypedExternalId): List<IdentityMapping>

    fun byTitle(titleId: TitleId): List<IdentityMapping>

    fun activeByExternal(external: TypedExternalId): IdentityMapping?

    fun observeByExternal(external: TypedExternalId): Flow<List<IdentityMapping>>

    fun all(): List<IdentityMapping>
}
