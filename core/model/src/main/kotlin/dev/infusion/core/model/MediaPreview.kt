package dev.infusion.core.model

import java.time.LocalDate

data class MediaPreview(
    val titleId: TitleId?,
    val kind: TitleKind?,
    val displayTitle: String,
    val year: Int?,
    val subtitle: String?,
    val artwork: MediaArtworkRef?,
    val progressFraction: Double?,
    val airDate: LocalDate? = null,
    val watchKey: dev.infusion.core.model.watch.WatchKey? = null,

    val episodeIdentification: EpisodeCardIdentification? = null,

    val episodeTitleId: dev.infusion.core.model.TitleId? = null,

    val fallbackArtwork: MediaArtworkRef? = null,
)

data class EpisodeCardIdentification(

    val seriesContext: String? = null,

    val coordinate: String,

    val episodeTitle: String? = null,

    val runtimeMinutes: Int? = null,

    val airDate: LocalDate? = null,
)

data class MediaArtworkRef(
    val locator: String,
    val policy: ArtworkPolicy,

    val landscapeLocator: String? = null,
) {
    init {
        require(locator.isNotEmpty()) { "artwork locator must not be empty" }
    }

    companion object {

        fun catalog(
            nativeItemId: String,
            policy: ArtworkPolicy = ArtworkPolicy.POSTER,
        ): MediaArtworkRef = MediaArtworkRef(ArtworkLocator.catalog(nativeItemId), policy)

        fun catalogTyped(
            nativeItemId: String,
            type: String,
            policy: ArtworkPolicy = ArtworkPolicy.POSTER,
        ): MediaArtworkRef = MediaArtworkRef(ArtworkLocator.catalog(nativeItemId, type), policy)

        fun addon(
            addonId: String,
            nativeItemId: String,
            policy: ArtworkPolicy = ArtworkPolicy.POSTER,
            landscapeAvailable: Boolean = false,
        ): MediaArtworkRef = MediaArtworkRef(
            locator = ArtworkLocator.addon(addonId, nativeItemId),
            policy = policy,
            landscapeLocator = if (landscapeAvailable) {
                ArtworkLocator.addonLandscape(addonId, nativeItemId)
            } else {
                null
            },
        )

        fun addonLandscape(
            addonId: String,
            nativeItemId: String,
            policy: ArtworkPolicy = ArtworkPolicy.BACKDROP,
        ): MediaArtworkRef =
            MediaArtworkRef(ArtworkLocator.addonLandscape(addonId, nativeItemId), policy)

        fun still(
            nativeItemId: String,
            policy: ArtworkPolicy = ArtworkPolicy.STILL,
        ): MediaArtworkRef = MediaArtworkRef(ArtworkLocator.still(nativeItemId), policy)

        fun addonStill(
            addonId: String,
            nativeItemId: String,
            policy: ArtworkPolicy = ArtworkPolicy.STILL,
        ): MediaArtworkRef =
            MediaArtworkRef(ArtworkLocator.addonStill(addonId, nativeItemId), policy)

        fun metadata(
            provider: String,
            path: String,
            policy: ArtworkPolicy = ArtworkPolicy.POSTER,
            landscapePath: String? = null,
        ): MediaArtworkRef = MediaArtworkRef(
            locator = ArtworkLocator.metadata(
                provider,
                dev.infusion.core.model.metadata.ArtworkSizeBuckets.bucketFor(provider, policy),
                path,
            ),
            policy = policy,
            landscapeLocator = landscapePath
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it != path }
                ?.let {
                    ArtworkLocator.metadata(
                        provider,
                        dev.infusion.core.model.metadata.ArtworkSizeBuckets
                            .bucketFor(provider, ArtworkPolicy.BACKDROP),
                        it,
                    )
                },
        )

        fun of(locator: String, policy: ArtworkPolicy = ArtworkPolicy.POSTER): MediaArtworkRef =
            MediaArtworkRef(locator, policy)
    }
}

fun MediaArtworkRef.landscapeSlot(): MediaArtworkRef? =
    statedLandscapeSlot()
        ?: ArtworkLocator.landscapeSlot(locator)?.let { MediaArtworkRef(it, ArtworkPolicy.BACKDROP) }

fun MediaArtworkRef.statedLandscapeSlot(): MediaArtworkRef? = when {
    policy == ArtworkPolicy.BACKDROP -> this
    else -> landscapeLocator?.let { MediaArtworkRef(it, ArtworkPolicy.BACKDROP) }
}
