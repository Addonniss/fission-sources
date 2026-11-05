package dev.infusion.feature.vertical.home

import dev.infusion.core.domain.home.HomeIdentityPresentation
import dev.infusion.core.domain.home.HomePresentationPolicy
import dev.infusion.core.domain.home.HomePresentationStore

class PersistedUpNextPresentationRetention(
    private val store: HomePresentationStore?,
    private val localeToken: () -> String = { "" },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val memoryLimit: Int = MEMORY_LIMIT,
) : UpNextPresentationRetention {

    private val memory: MutableMap<String, UpNextCardEnrichment> =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String, UpNextCardEnrichment>(64, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, UpNextCardEnrichment>,
                ): Boolean = size > memoryLimit
            },
        )

    override fun get(profileId: String, cardKey: String): UpNextCardEnrichment? {
        val memoryKey = "$profileId\u0000$cardKey"
        memory[memoryKey]?.let { return it }
        val cache = store ?: return null
        val record = runCatching { cache.readPresentation(profileId, identityKey(cardKey)) }
            .getOrNull() ?: return null
        if (!HomePresentationPolicy.presentationIsFresh(record, nowMs())) return null
        val enrichment = record.toUpNextCardEnrichment()
        if (!enrichment.isNotEmpty()) return null
        memory[memoryKey] = enrichment
        return enrichment
    }

    override fun put(profileId: String, cardKey: String, enrichment: UpNextCardEnrichment) {

        if (!enrichment.isNotEmpty()) return
        memory["$profileId\u0000$cardKey"] = enrichment
        val cache = store ?: return
        runCatching {
            cache.writePresentation(profileId, enrichment.toHomeIdentityPresentation(cardKey, localeToken(), nowMs()))
        }
    }

    private fun identityKey(cardKey: String): String = "$cardKey#l=${localeToken()}"

    private companion object {

        const val MEMORY_LIMIT = 256
    }
}

fun UpNextCardEnrichment.toHomeIdentityPresentation(
    cardKey: String,
    localeToken: String,
    storedAtEpochMs: Long,
): HomeIdentityPresentation = HomeIdentityPresentation(
    identityKey = "$cardKey#l=$localeToken",
    displayTitle = seriesTitle.orEmpty(),
    year = null,
    airDate = airDate,
    episodeTitleId = episodeTitleId,
    episodeTitle = episodeTitle,
    runtimeMinutes = runtimeMinutes,
    provenanceLabel = trackerName,
    artwork = HomePresentationPolicy.reusableArtwork(artwork),
    storedAtEpochMs = storedAtEpochMs,
)

fun HomeIdentityPresentation.toUpNextCardEnrichment(): UpNextCardEnrichment = UpNextCardEnrichment(
    seriesTitle = displayTitle.takeIf { it.isNotBlank() },
    episodeTitleId = episodeTitleId,
    artwork = HomePresentationPolicy.reusableArtwork(artwork),
    trackerName = provenanceLabel,
    episodeTitle = episodeTitle,
    runtimeMinutes = runtimeMinutes,
    airDate = airDate,
)
