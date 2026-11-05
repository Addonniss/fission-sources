package dev.infusion.core.domain.metadata

import dev.infusion.core.model.TitleId

interface CanonicalShowHierarchy {

    suspend fun seasonNumbers(seriesTitleId: TitleId): List<Int>

    suspend fun episodes(seriesTitleId: TitleId, seasonNumber: Int): List<CanonicalEpisodeRef>
}

data class CanonicalEpisodeRef(
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String? = null,
    val released: String? = null,
    val runtimeMinutes: Int? = null,
)
