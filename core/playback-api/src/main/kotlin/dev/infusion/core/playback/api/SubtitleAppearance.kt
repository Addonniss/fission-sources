package dev.infusion.core.playback.api

enum class SubtitleAppearanceMode {

    SYSTEM,

    CUSTOM,
}

enum class SubtitleSizeChoice(val textSizeFraction: Float) {
    SMALL(0.040f),
    MEDIUM(0.0533f),
    LARGE(0.070f),
    EXTRA_LARGE(0.090f),
    ;

    companion object {

        val DEFAULT: SubtitleSizeChoice = SMALL
    }
}

enum class SubtitleFontChoice {
    SYSTEM_DEFAULT,
    SANS_SERIF,
    SERIF,
    MONOSPACE,
    ;

    companion object {

        val DEFAULT: SubtitleFontChoice = SYSTEM_DEFAULT
    }
}

enum class SubtitleTextColorChoice(val argb: Int) {
    WHITE(0xFFFFFFFF.toInt()),
    YELLOW(0xFFFFEB3B.toInt()),
    CYAN(0xFF00E5FF.toInt()),
    GREEN(0xFF69F0AE.toInt()),
    ;

    companion object {

        val DEFAULT: SubtitleTextColorChoice = WHITE
    }
}

enum class SubtitleBackgroundChoice(val argb: Int) {
    BLACK(0xFF000000.toInt()),
    DARK_GREY(0xFF2B2B2B.toInt()),
    DARK_BLUE(0xFF1B2A4A.toInt()),
    NONE(0x00000000),
    ;

    companion object {

        val DEFAULT: SubtitleBackgroundChoice = NONE
    }
}

enum class SubtitleBackgroundOpacityChoice(val alphaFraction: Float) {
    FULL(1.0f),
    HIGH(0.8f),
    MEDIUM(0.6f),
    LOW(0.4f),
    ;

    companion object {

        val DEFAULT: SubtitleBackgroundOpacityChoice = FULL
    }
}

enum class SubtitleEdgeChoice {
    NONE,
    OUTLINE,
    SHADOW,
    ;

    companion object {

        val DEFAULT: SubtitleEdgeChoice = SHADOW
    }
}

enum class SubtitlePositionChoice(val bottomPaddingFraction: Float) {
    BOTTOM(0.08f),
    RAISED(0.18f),
    MIDDLE(0.40f),
    ;

    companion object {

        val DEFAULT: SubtitlePositionChoice = BOTTOM
    }
}

data class SubtitleAppearanceStyle(
    val textSizeFraction: Float,
    val font: SubtitleFontChoice,
    val textColorArgb: Int,
    val backgroundColorArgb: Int,
    val edge: SubtitleEdgeChoice,
    val bottomPaddingFraction: Float,
    val applyEmbeddedStyles: Boolean,
)

data class SubtitleAppearance(
    val mode: SubtitleAppearanceMode = SubtitleAppearanceMode.SYSTEM,
    val size: SubtitleSizeChoice = SubtitleSizeChoice.DEFAULT,
    val font: SubtitleFontChoice = SubtitleFontChoice.DEFAULT,
    val textColor: SubtitleTextColorChoice = SubtitleTextColorChoice.DEFAULT,
    val background: SubtitleBackgroundChoice = SubtitleBackgroundChoice.DEFAULT,
    val backgroundOpacity: SubtitleBackgroundOpacityChoice = SubtitleBackgroundOpacityChoice.DEFAULT,
    val edge: SubtitleEdgeChoice = SubtitleEdgeChoice.DEFAULT,
    val position: SubtitlePositionChoice = SubtitlePositionChoice.DEFAULT,
) {

    val isCustom: Boolean get() = mode == SubtitleAppearanceMode.CUSTOM

    fun withMode(mode: SubtitleAppearanceMode): SubtitleAppearance = copy(mode = mode)

    fun resolvedStyle(): SubtitleAppearanceStyle? = when (mode) {
        SubtitleAppearanceMode.SYSTEM -> null
        SubtitleAppearanceMode.CUSTOM -> SubtitleAppearanceStyle(
            textSizeFraction = size.textSizeFraction,
            font = font,
            textColorArgb = textColor.argb,
            backgroundColorArgb = scaledAlpha(background.argb, backgroundOpacity.alphaFraction),
            edge = edge,
            bottomPaddingFraction = position.bottomPaddingFraction,

            applyEmbeddedStyles = false,
        )
    }

    companion object {

        val DEFAULT: SubtitleAppearance = SubtitleAppearance()

        fun fromStored(
            mode: String? = null,
            size: String? = null,
            font: String? = null,
            textColor: String? = null,
            background: String? = null,
            backgroundOpacity: String? = null,
            edge: String? = null,
            position: String? = null,
        ): SubtitleAppearance = SubtitleAppearance(
            mode = enumOrNull<SubtitleAppearanceMode>(mode) ?: SubtitleAppearanceMode.SYSTEM,
            size = enumOrNull<SubtitleSizeChoice>(size) ?: SubtitleSizeChoice.DEFAULT,
            font = enumOrNull<SubtitleFontChoice>(font) ?: SubtitleFontChoice.DEFAULT,
            textColor = enumOrNull<SubtitleTextColorChoice>(textColor) ?: SubtitleTextColorChoice.DEFAULT,
            background = enumOrNull<SubtitleBackgroundChoice>(background) ?: SubtitleBackgroundChoice.DEFAULT,
            backgroundOpacity = enumOrNull<SubtitleBackgroundOpacityChoice>(backgroundOpacity)
                ?: SubtitleBackgroundOpacityChoice.DEFAULT,
            edge = enumOrNull<SubtitleEdgeChoice>(edge) ?: SubtitleEdgeChoice.DEFAULT,
            position = enumOrNull<SubtitlePositionChoice>(position) ?: SubtitlePositionChoice.DEFAULT,
        )

        private inline fun <reified T : Enum<T>> enumOrNull(stored: String?): T? =
            stored?.let { value -> enumValues<T>().firstOrNull { it.name == value } }

        private fun scaledAlpha(argb: Int, alphaFraction: Float): Int {
            val ownAlpha = (argb ushr 24) and 0xFF
            val alpha = (ownAlpha * alphaFraction.coerceIn(0f, 1f) + 0.5f).toInt()
            return (argb and 0x00FFFFFF) or (alpha shl 24)
        }
    }
}
