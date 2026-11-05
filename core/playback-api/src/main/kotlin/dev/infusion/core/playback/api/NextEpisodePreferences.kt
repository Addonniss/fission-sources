package dev.infusion.core.playback.api

data class NextEpisodePreferences(

    val enableNextEpisode: Boolean = true,

    val onlineNextMetadataPriority: Boolean = true,

    val autoPlayNextEpisode: Boolean = false,

    val autoPlayDelaySeconds: Int = 10,

    val fallbackTriggerPercent: Int = 90,
) {

    fun withEnableNextEpisode(value: Boolean): NextEpisodePreferences = copy(enableNextEpisode = value)

    fun withOnlineNextMetadataPriority(value: Boolean): NextEpisodePreferences =
        copy(onlineNextMetadataPriority = value)

    fun withAutoPlayNextEpisode(value: Boolean): NextEpisodePreferences =
        copy(autoPlayNextEpisode = value)

    fun withAutoPlayDelaySeconds(value: Int): NextEpisodePreferences = copy(
        autoPlayDelaySeconds = value.coerceIn(
            NextEpisodeSettings.AUTO_PLAY_DELAY_MIN,
            NextEpisodeSettings.AUTO_PLAY_DELAY_MAX,
        ),
    )

    fun withFallbackTriggerPercent(value: Int): NextEpisodePreferences = copy(
        fallbackTriggerPercent = value.coerceIn(
            NextEpisodeSettings.FALLBACK_PERCENT_MIN,
            NextEpisodeSettings.FALLBACK_PERCENT_MAX,
        ),
    )

    fun toSettings(): NextEpisodeSettings = NextEpisodeSettings(
        enableNextEpisode = enableNextEpisode,
        onlineNextMetadataPriority = onlineNextMetadataPriority,
        autoPlayNextEpisode = autoPlayNextEpisode,
        autoPlayDelaySeconds = autoPlayDelaySeconds,
        fallbackTriggerPercent = fallbackTriggerPercent,
    )

    companion object {

        val DEFAULT: NextEpisodePreferences = NextEpisodePreferences()
    }
}

data class NextEpisodeSettings(
    val enableNextEpisode: Boolean = true,
    val onlineNextMetadataPriority: Boolean = true,
    val autoPlayNextEpisode: Boolean = false,
    val autoPlayDelaySeconds: Int = 10,
    val fallbackTriggerPercent: Int = 90,
) {
    companion object {
        val DEFAULT: NextEpisodeSettings = NextEpisodeSettings()

        const val AUTO_PLAY_DELAY_MIN: Int = 0
        const val AUTO_PLAY_DELAY_MAX: Int = 20

        const val FALLBACK_PERCENT_MIN: Int = 50
        const val FALLBACK_PERCENT_MAX: Int = 99
    }
}
