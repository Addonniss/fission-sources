package dev.infusion.tv.navigation

import android.net.Uri
import dev.infusion.feature.vertical.media.TrailerUi

enum class TrailerEmbedProvider { YOUTUBE }

sealed interface TrailerTarget {
    data class Embed(val provider: TrailerEmbedProvider, val mediaKey: String) : TrailerTarget
    data object Unsupported : TrailerTarget
}

private const val YOUTUBE_SITE = "youtube"
private const val YOUTUBE_EMBED_HOST = "www.youtube.com"

fun trailerTargetFor(trailer: TrailerUi): TrailerTarget {
    val key = trailer.key.trim()
    if (key.isEmpty()) return TrailerTarget.Unsupported
    return when {
        trailer.site.trim().equals(YOUTUBE_SITE, ignoreCase = true) ->
            TrailerTarget.Embed(TrailerEmbedProvider.YOUTUBE, key)
        else -> TrailerTarget.Unsupported
    }
}

fun trailerEmbedUrl(target: TrailerTarget): String? = when (target) {
    is TrailerTarget.Embed -> when (target.provider) {
        TrailerEmbedProvider.YOUTUBE ->
            "https://$YOUTUBE_EMBED_HOST/embed/${Uri.encode(target.mediaKey)}" +
                "?playsinline=1&autoplay=1&controls=0&rel=0&iv_load_policy=3&disablekb=1"
    }
    TrailerTarget.Unsupported -> null
}

fun trailerEmbedHosts(provider: TrailerEmbedProvider): Set<String> = when (provider) {
    TrailerEmbedProvider.YOUTUBE -> setOf(
        YOUTUBE_EMBED_HOST,
        "youtube.com",
        "m.youtube.com",
    )
}

fun trailerEmbedRequestHeaders(provider: TrailerEmbedProvider): Map<String, String> = when (provider) {
    TrailerEmbedProvider.YOUTUBE -> mapOf("Referer" to TRAILER_EMBED_DOCUMENT_ORIGIN)
}

private const val TRAILER_EMBED_DOCUMENT_ORIGIN = "https://infusion.local/"
