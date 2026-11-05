package dev.infusion.tv.di

import dev.infusion.core.domain.candidate.CandidateResolverPort
import dev.infusion.core.domain.candidate.ResolutionPhase
import dev.infusion.core.domain.catalog.CatalogImage
import dev.infusion.core.domain.catalog.CatalogPage
import dev.infusion.core.domain.catalog.CatalogPort
import dev.infusion.core.domain.catalog.HomeShelf
import dev.infusion.core.domain.catalog.TitleDetails
import dev.infusion.core.model.EditionId
import dev.infusion.core.model.Source
import dev.infusion.core.model.TitleId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class NoSourceCatalogPort : CatalogPort {

    override fun observeHome(): Flow<List<HomeShelf>> = flowOf(emptyList())

    override fun observeTitleDetails(titleId: TitleId): Flow<TitleDetails?> = flowOf(null)

    override fun sources(): List<Source> = emptyList()

    override suspend fun browse(parentNativeId: String?, startIndex: Int, limit: Int): CatalogPage? = null

    override suspend fun search(searchTerm: String, startIndex: Int, limit: Int): CatalogPage? = null

    override suspend fun children(parentNativeId: String, startIndex: Int, limit: Int): CatalogPage? = null

    override suspend fun nativeItemIdFor(titleId: TitleId): String? = null

    override suspend fun image(nativeItemId: String, type: String): CatalogImage? = null
}

class NoSourceCandidateResolver : CandidateResolverPort {

    override fun resolve(titleId: TitleId, editionId: EditionId?): Flow<ResolutionPhase> = flowOf(
        ResolutionPhase.Candidates(candidates = emptyList(), complete = true),
    )
}
