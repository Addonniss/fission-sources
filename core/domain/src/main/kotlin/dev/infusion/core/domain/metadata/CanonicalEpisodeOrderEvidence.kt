package dev.infusion.core.domain.metadata

import dev.infusion.core.model.metadata.MediaIdentity

data class CanonicalEpisodeOrder(
    val seasons: List<Int>,
    val episodes: List<CanonicalEpisodeRef>,
)

interface CanonicalEpisodeOrderEvidenceStore {

    suspend fun load(identity: MediaIdentity, locale: MetadataLocale): CanonicalEpisodeOrder?

    suspend fun beginRecording(
        identity: MediaIdentity,
        locale: MetadataLocale,
    ): CanonicalEpisodeOrderRecording
}

interface CanonicalEpisodeOrderRecording {

    suspend fun detailsStamp(): Long?

    suspend fun seasonStamp(seasonNumber: Int): Long?

    suspend fun commit(order: CanonicalEpisodeOrder, seasonsRead: Set<Int>): Boolean
}
