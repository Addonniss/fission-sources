package dev.infusion.feature.vertical.home

import dev.infusion.core.model.ArtworkLocator
import dev.infusion.core.model.ArtworkPolicy
import dev.infusion.core.model.MediaArtworkRef
import dev.infusion.core.model.TitleKind
import dev.infusion.core.model.metadata.ImageRef

suspend fun selectCwArtwork(
    ownerRefs: List<MediaArtworkRef?>,
    canonicalRef: suspend () -> MediaArtworkRef?,
    parentShowRef: suspend () -> MediaArtworkRef? = { null },
    draws: suspend (MediaArtworkRef) -> Boolean,
): MediaArtworkRef? {
    cwArtworkCandidates(ownerRefs, canonicalRef = null)
        .firstOrNull { ref -> draws(ref) }
        ?.let { return it }
    val canonical = canonicalRef()
    if (canonical != null && draws(canonical)) return canonical
    val parentShow = parentShowRef() ?: return null
    return parentShow.takeIf { ref -> draws(ref) }
}

fun cwArtworkCandidates(
    ownerRefs: List<MediaArtworkRef?>,
    canonicalRef: MediaArtworkRef?,
): List<MediaArtworkRef> =
    (ownerRefs.filterNotNull() + listOfNotNull(canonicalRef)).distinct()

fun canonicalCwArtworkRef(
    poster: ImageRef?,
    backdrop: ImageRef?,
    policy: ArtworkPolicy = ArtworkPolicy.POSTER,
): MediaArtworkRef? = poster?.let { image ->
    MediaArtworkRef.metadata(
        provider = image.provider,
        path = image.path,
        policy = policy,
        landscapePath = backdrop?.path,
    )
}

fun episodeCwStillRef(nativeItemId: String?): MediaArtworkRef? =
    nativeItemId?.trim()?.takeIf { it.isNotEmpty() }?.let { MediaArtworkRef.still(it) }

fun episodeCwAddonStillRef(addonId: String?, nativeItemId: String?): MediaArtworkRef? {
    val owner = addonId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val native = nativeItemId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return MediaArtworkRef.addonStill(owner, native)
}

fun canonicalCwStillRef(still: ImageRef?): MediaArtworkRef? = still?.let { image ->
    MediaArtworkRef.metadata(
        provider = image.provider,
        path = image.path,
        policy = ArtworkPolicy.STILL,
    )
}

fun serverCwOwnerArtworkRefs(
    nativeItemId: String?,
    kind: TitleKind?,
    statedImageTypes: Set<String>,
    drawsLandscape: Boolean,
): List<MediaArtworkRef> {
    val native = nativeItemId?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
    return when {
        kind == TitleKind.EPISODE ->
            listOfNotNull(
                episodeCwStillRef(native)
                    ?.takeIf { ArtworkLocator.DEFAULT_CATALOG_TYPE in statedImageTypes },
            )
        drawsLandscape ->
            listOfNotNull(
                MediaArtworkRef
                    .catalogTyped(native, ArtworkLocator.CATALOG_BACKDROP_TYPE)
                    .takeIf { ArtworkLocator.CATALOG_BACKDROP_TYPE in statedImageTypes },
            )
        else ->
            listOfNotNull(
                MediaArtworkRef
                    .catalogTyped(native, ArtworkLocator.DEFAULT_CATALOG_TYPE)
                    .takeIf { ArtworkLocator.DEFAULT_CATALOG_TYPE in statedImageTypes },
            )
    }
}
