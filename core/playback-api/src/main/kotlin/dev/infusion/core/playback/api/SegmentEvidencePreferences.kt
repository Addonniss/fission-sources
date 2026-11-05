package dev.infusion.core.playback.api

enum class SegmentFallbackTimecode(val storageKey: String, val ms: Long) {
    SEC_05("00:05", 5_000L),
    SEC_10("00:10", 10_000L),
    SEC_15("00:15", 15_000L),
    SEC_20("00:20", 20_000L),
    SEC_30("00:30", 30_000L),
    SEC_45("00:45", 45_000L),
    MIN_1("01:00", 60_000L),
    MIN_1_15("01:15", 75_000L),
    MIN_1_30("01:30", 90_000L),
    MIN_2("02:00", 120_000L),
    MIN_2_30("02:30", 150_000L),
    MIN_3("03:00", 180_000L),
    ;

    companion object {

        val DEFAULT_START: SegmentFallbackTimecode = SEC_15

        val DEFAULT_END: SegmentFallbackTimecode = MIN_1_30

        fun resolveStart(stored: String?): SegmentFallbackTimecode = resolve(stored, DEFAULT_START)

        fun resolveEnd(stored: String?): SegmentFallbackTimecode = resolve(stored, DEFAULT_END)

        private fun resolve(stored: String?, default: SegmentFallbackTimecode): SegmentFallbackTimecode {
            val key = stored?.trim()
            if (key.isNullOrEmpty()) return default
            entries.firstOrNull { it.storageKey == key }?.let { return it }
            val parsedMs = SegmentTimecode.parseMs(key) ?: return default
            return entries.minByOrNull { kotlin.math.abs(it.ms - parsedMs) } ?: default
        }
    }
}

enum class SegmentDisplayMode(val storageKey: String) {

    OFF("off"),

    ABOVE_PROGRESS_BAR("above"),

    ON_PROGRESS_BAR("on_bar"),
    ;

    companion object {

        val DEFAULT: SegmentDisplayMode = ON_PROGRESS_BAR

        fun resolve(stored: String?): SegmentDisplayMode {
            val key = stored?.trim()?.lowercase()
            if (key.isNullOrEmpty()) return DEFAULT
            return entries.firstOrNull { it.storageKey == key } ?: DEFAULT
        }
    }
}

data class SegmentEvidencePreferences(
    val onlineIntroMetadataPriority: Boolean,
    val manualFallbackEnabled: Boolean,
    val fallbackStart: SegmentFallbackTimecode,
    val fallbackEnd: SegmentFallbackTimecode,

    val displayMode: SegmentDisplayMode = SegmentDisplayMode.DEFAULT,

    val legendEnabled: Boolean = false,

    val enableSkipIntro: Boolean = true,

    val autoSkipIntro: Boolean = false,

    val autoSkipDelaySeconds: Int = 2,

    val persistentSkipButton: Boolean = true,

    val enableStingerAlerts: Boolean = true,

    val stingerTriggerPercent: Int = 85,

    val stingerEarlyNotifySeconds: Int = 10,

    val stingerToastDurationSeconds: Int = 12,
) {
    fun toSettings(): SegmentEvidenceSettings = SegmentEvidenceSettings(
        onlineIntroMetadataPriority = onlineIntroMetadataPriority,
        manualFallbackEnabled = manualFallbackEnabled,
        manualFallbackStartMs = fallbackStart.ms,
        manualFallbackEndMs = fallbackEnd.ms,
        enableSkipIntro = enableSkipIntro,
        autoSkipIntro = autoSkipIntro,
        autoSkipDelaySeconds = autoSkipDelaySeconds,
        persistentSkipButton = persistentSkipButton,
        enableStingerAlerts = enableStingerAlerts,
        stingerTriggerPercent = stingerTriggerPercent,
        stingerEarlyNotifySeconds = stingerEarlyNotifySeconds,
        stingerToastDurationSeconds = stingerToastDurationSeconds,
    )

    fun withOnlineIntroMetadataPriority(value: Boolean): SegmentEvidencePreferences =
        copy(onlineIntroMetadataPriority = value)

    fun withManualFallbackEnabled(value: Boolean): SegmentEvidencePreferences =
        copy(manualFallbackEnabled = value)

    fun withFallbackStart(value: SegmentFallbackTimecode): SegmentEvidencePreferences =
        copy(fallbackStart = value)

    fun withFallbackEnd(value: SegmentFallbackTimecode): SegmentEvidencePreferences =
        copy(fallbackEnd = value)

    fun withDisplayMode(value: SegmentDisplayMode): SegmentEvidencePreferences =
        copy(displayMode = value)

    fun withLegendEnabled(value: Boolean): SegmentEvidencePreferences =
        copy(legendEnabled = value)

    fun withEnableSkipIntro(value: Boolean): SegmentEvidencePreferences =
        copy(enableSkipIntro = value)

    fun withAutoSkipIntro(value: Boolean): SegmentEvidencePreferences =
        copy(autoSkipIntro = value)

    fun withAutoSkipDelaySeconds(value: Int): SegmentEvidencePreferences =
        copy(
            autoSkipDelaySeconds = value.coerceIn(
                SegmentEvidenceSettings.AUTO_SKIP_DELAY_MIN,
                SegmentEvidenceSettings.AUTO_SKIP_DELAY_MAX,
            ),
        )

    fun withPersistentSkipButton(value: Boolean): SegmentEvidencePreferences =
        copy(persistentSkipButton = value)

    fun withEnableStingerAlerts(value: Boolean): SegmentEvidencePreferences =
        copy(enableStingerAlerts = value)

    fun withStingerTriggerPercent(value: Int): SegmentEvidencePreferences =
        copy(
            stingerTriggerPercent = value.coerceIn(
                SegmentEvidenceSettings.STINGER_PERCENT_MIN,
                SegmentEvidenceSettings.STINGER_PERCENT_MAX,
            ),
        )

    fun withStingerEarlyNotifySeconds(value: Int): SegmentEvidencePreferences =
        copy(
            stingerEarlyNotifySeconds = value.coerceIn(
                SegmentEvidenceSettings.STINGER_EARLY_MIN,
                SegmentEvidenceSettings.STINGER_EARLY_MAX,
            ),
        )

    fun withStingerToastDurationSeconds(value: Int): SegmentEvidencePreferences =
        copy(
            stingerToastDurationSeconds = value.coerceIn(
                SegmentEvidenceSettings.STINGER_TOAST_MIN,
                SegmentEvidenceSettings.STINGER_TOAST_MAX,
            ),
        )

    companion object {

        val DEFAULT: SegmentEvidencePreferences = SegmentEvidencePreferences(
            onlineIntroMetadataPriority = true,
            manualFallbackEnabled = false,
            fallbackStart = SegmentFallbackTimecode.DEFAULT_START,
            fallbackEnd = SegmentFallbackTimecode.DEFAULT_END,
            displayMode = SegmentDisplayMode.DEFAULT,
            legendEnabled = false,
            enableSkipIntro = true,
            autoSkipIntro = false,
            autoSkipDelaySeconds = 2,
            persistentSkipButton = true,
            enableStingerAlerts = true,
            stingerTriggerPercent = 85,
            stingerEarlyNotifySeconds = 10,
            stingerToastDurationSeconds = 12,
        )
    }
}
