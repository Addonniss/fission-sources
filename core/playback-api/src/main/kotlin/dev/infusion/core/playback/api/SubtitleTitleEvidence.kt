package dev.infusion.core.playback.api

object SubtitleTitleEvidence {

    val SDH_TITLE_TOKENS: List<String> = listOf(
        "sdh",
        "hearing impaired",
        "hard of hearing",
    )

    fun isSdhTitle(title: String?): Boolean {
        val words = normalizedWords(title)
        if (words.isEmpty()) return false
        return SDH_TITLE_TOKENS.any { token -> containsWordRun(words, normalizedWords(token)) }
    }

    private fun normalizedWords(text: String?): List<String> {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        return raw.lowercase(java.util.Locale.ROOT)
            .map { character ->
                when (character) {

                    '-', '_', '.' -> ' '
                    else -> character
                }
            }
            .joinToString("")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
    }

    private fun containsWordRun(words: List<String>, phrase: List<String>): Boolean {
        if (phrase.isEmpty() || phrase.size > words.size) return false
        for (start in 0..(words.size - phrase.size)) {
            var matched = true
            for (offset in phrase.indices) {
                if (words[start + offset] != phrase[offset]) {
                    matched = false
                    break
                }
            }
            if (matched) return true
        }
        return false
    }
}

enum class SubtitleTrackType {

    OTHER,

    SDH,

    FORCED,
}

val SubtitleTrack.typeOf: SubtitleTrackType
    get() = when {
        isForced == true -> SubtitleTrackType.FORCED
        isSdh == true -> SubtitleTrackType.SDH
        else -> SubtitleTrackType.OTHER
    }
