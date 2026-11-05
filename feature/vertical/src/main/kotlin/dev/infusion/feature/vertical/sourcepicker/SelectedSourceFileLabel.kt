package dev.infusion.feature.vertical.sourcepicker

import dev.infusion.core.model.PlayableCandidate

object SelectedSourceFileLabel {

    private val MEDIA_EXTENSION = Regex(
        """\.(mkv|mp4|m4v|avi|mov|webm|ts|m2ts|mpg|mpeg|wmv|flv|strm|m3u8)(?=$|[?#\s])""",
        RegexOption.IGNORE_CASE,
    )

    fun of(candidate: PlayableCandidate, jellyfinSourceId: String?): String? {
        val label = candidate.originalLabel.trim().takeIf { it.isNotEmpty() } ?: return null
        if (candidate.sourceId.value.toString() == jellyfinSourceId) return label
        val description = candidate.providerDescription?.trim()?.takeIf { it.isNotEmpty() }
        return listOfNotNull(label, description).firstOrNull(::looksLikeFileName)
    }

    fun looksLikeFileName(text: String): Boolean {
        val token = text.trim()
        if (token.isEmpty()) return false
        if ("://" in token || '/' in token || '\\' in token) return false
        return MEDIA_EXTENSION.containsMatchIn(token)
    }
}
