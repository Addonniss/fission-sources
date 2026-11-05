package dev.infusion.tv.ui.screens

internal object TvPlayerFooterLayout {

    const val SideBlockMaxFraction: Float = 0.45f

    fun sideBlockMaxWidth(rowWidth: Int): Int {
        if (rowWidth <= 0) return 0
        return (rowWidth * SideBlockMaxFraction).toInt()
    }

    fun centreBlockMaxWidth(rowWidth: Int, sideBlockWidth: Int, gap: Int): Int {
        if (rowWidth <= 0) return 0
        val reserved = sideBlockWidth + gap.coerceAtLeast(0)
        return (rowWidth - 2 * reserved).coerceAtLeast(0)
    }

    fun centreBlockLeft(rowWidth: Int, centreWidth: Int): Int {
        if (rowWidth <= 0 || centreWidth <= 0) return 0
        return ((rowWidth - centreWidth) / 2).coerceAtLeast(0)
    }

    fun allocateLabelWidths(
        available: Int,
        audioNatural: Int,
        subtitleNatural: Int,
    ): Pair<Int, Int> {
        val audio = audioNatural.coerceAtLeast(0)
        val subtitle = subtitleNatural.coerceAtLeast(0)
        val space = available.coerceAtLeast(0)
        if (audio + subtitle <= space) return audio to subtitle
        if (audio <= subtitle) {

            if (audio * 2 <= space) return audio to (space - audio)
            val audioShare = space / 2
            return audioShare to (space - audioShare)
        }

        if (subtitle * 2 <= space) return (space - subtitle) to subtitle
        val subtitleShare = space / 2
        return (space - subtitleShare) to subtitleShare
    }
}
