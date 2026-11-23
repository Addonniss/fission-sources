package dev.infusion.source.aggregation.subtitles

import dev.infusion.core.model.EmbeddedSubtitle
import dev.infusion.core.model.PlayableCandidate
import dev.infusion.source.aggregation.resource.AddonSubtitleRepository
import dev.infusion.source.aggregation.resource.ProviderResourceState
import dev.infusion.source.aggregation.resource.SubtitleBatch
import dev.infusion.source.stremio.policy.StremioContentType
import dev.infusion.source.stremio.wire.StremioSubtitle
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeoutOrNull

data class MergedSubtitle(
    val id: String,
    val url: String,
    val language: String?,
    val format: String?,
    val sourceAddonId: String,
)

class AddonSubtitleMerger(
    private val subtitleRepository: AddonSubtitleRepository,
    private val providerTimeoutMs: Long = DEFAULT_DISCOVERY_TIMEOUT_MS,
) {

    suspend fun mergeStandaloneSubtitles(
        candidate: PlayableCandidate,
        type: StremioContentType,
    ): PlayableCandidate {
        val merged = runCatching {
            val id = candidate.nativeItemId.nativeId

            val delivered = mutableListOf<SubtitleBatch>()
            withTimeoutOrNull(providerTimeoutMs) {
                subtitleRepository.subtitles(type, id).collect { delivered += it }
            }
            mergeBatches(candidate, completedProviders(type, id, delivered))
        }.getOrElse { candidate.embeddedSubtitles }
        return if (merged == candidate.embeddedSubtitles) candidate else candidate.copy(embeddedSubtitles = merged)
    }

    private fun completedProviders(
        contentType: StremioContentType,
        id: String,
        delivered: List<SubtitleBatch>,
    ): List<SubtitleBatch> {
        val query = subtitleRepository.states.value
        if (query == null || query.type != contentType || query.id != id) return delivered
        val published = delivered.mapTo(HashSet()) { it.addonId }
        val recovered = query.providers.filter { provider ->
            provider.addonId !in published && provider.state !is ProviderResourceState.Pending
        }
        return if (recovered.isEmpty()) delivered else delivered + recovered
    }

    private fun mergeBatches(
        candidate: PlayableCandidate,
        batches: List<dev.infusion.source.aggregation.resource.SubtitleBatch>,
    ): List<EmbeddedSubtitle> {

        val standalone = batches
            .mapNotNull { batch -> (batch.state as? ProviderResourceState.Loaded)?.value?.let { batch to it } }
            .flatMap { (batch, response) -> response.subtitles.map { subtitle -> batch to subtitle } }
        val embedded = candidate.embeddedSubtitles
        if (standalone.isEmpty()) return embedded
        val byUrl = LinkedHashMap<String, EmbeddedSubtitle>()

        embedded.forEach { byUrl[it.url] = it }
        standalone.forEach { (batch, sub) ->
            byUrl.putIfAbsent(sub.url.toString(), sub.toEmbedded(sourceName = batch.addonDisplayName))
        }
        return byUrl.values.toList()
    }

    private fun StremioSubtitle.toEmbedded(sourceName: String?): EmbeddedSubtitle =
        EmbeddedSubtitle(
            id = id,
            url = url.toString(),
            language = lang,
            format = format,
            sourceName = sourceName,
        )

    companion object {

        const val REPOSITORY_PROVIDER_TIMEOUT_MS = 10_000L

        const val DISCOVERY_FRONTIER_MARGIN_MS = 2_000L

        const val DEFAULT_DISCOVERY_TIMEOUT_MS = REPOSITORY_PROVIDER_TIMEOUT_MS + DISCOVERY_FRONTIER_MARGIN_MS
    }
}
