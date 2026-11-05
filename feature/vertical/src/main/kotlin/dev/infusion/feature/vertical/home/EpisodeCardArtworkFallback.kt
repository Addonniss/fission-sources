package dev.infusion.feature.vertical.home

import dev.infusion.core.model.ArtworkPolicy
import dev.infusion.core.model.MediaArtworkRef
import dev.infusion.core.model.metadata.ImageRef

fun parentShowIdentityAgrees(canonicalTitle: String?, parentShowTitle: String?): Boolean {
    val card = canonicalTitle?.let(::normalizedIdentityTitle)?.takeIf { it.isNotEmpty() } ?: return true
    val parent = parentShowTitle?.let(::normalizedIdentityTitle)?.takeIf { it.isNotEmpty() } ?: return true
    return card == parent
}

fun episodeCardParentBackdropRef(
    parentBackdrop: ImageRef?,
    canonicalTitle: String?,
    parentShowTitle: String?,
): MediaArtworkRef? {
    if (parentBackdrop == null) return null
    if (!parentShowIdentityAgrees(canonicalTitle, parentShowTitle)) return null
    val provider = parentBackdrop.provider.trim().ifEmpty { return null }
    val path = parentBackdrop.path.trim().ifEmpty { return null }
    return MediaArtworkRef.metadata(provider = provider, path = path, policy = ArtworkPolicy.BACKDROP)
}

private fun normalizedIdentityTitle(title: String): String =
    title.trim().replace(WHITESPACE, " ").lowercase()

private val WHITESPACE = Regex("\\s+")
