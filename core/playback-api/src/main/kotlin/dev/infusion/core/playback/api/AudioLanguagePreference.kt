package dev.infusion.core.playback.api

sealed interface AudioLanguagePreference {

    data object SystemLanguage : AudioLanguagePreference

    data object None : AudioLanguagePreference

    data class Tag private constructor(val value: String) : AudioLanguagePreference {

        override fun toString(): String = value

        companion object {

            fun of(raw: String?): Tag? =
                AudioLanguageTag.canonicalize(raw)?.let { canonical -> Tag(canonical) }
        }
    }
}

data class AudioLanguagePreferences(
    val primary: AudioLanguagePreference = AudioLanguagePreference.SystemLanguage,
    val secondary: AudioLanguagePreference = AudioLanguagePreference.None,
) {
    init {
        require(primary != AudioLanguagePreference.None) {
            "the primary audio language preference can never be None"
        }
        require(secondary != AudioLanguagePreference.SystemLanguage) {
            "the secondary audio language preference can never be System language"
        }
        val explicitPrimary = (primary as? AudioLanguagePreference.Tag)?.value
        val explicitSecondary = (secondary as? AudioLanguagePreference.Tag)?.value
        require(
            explicitPrimary == null || explicitSecondary == null || explicitPrimary != explicitSecondary,
        ) { "the primary and secondary audio language preferences must not be the same value" }
    }

    fun withPrimary(preference: AudioLanguagePreference): AudioLanguagePreferences {
        if (preference == AudioLanguagePreference.None) return this
        val cleared = preference == secondary && preference is AudioLanguagePreference.Tag
        return AudioLanguagePreferences(
            primary = preference,
            secondary = if (cleared) AudioLanguagePreference.None else secondary,
        )
    }

    fun withSecondary(preference: AudioLanguagePreference): AudioLanguagePreferences {
        if (preference == AudioLanguagePreference.SystemLanguage) return this
        val cleared = preference == primary && preference is AudioLanguagePreference.Tag
        return AudioLanguagePreferences(
            primary = primary,
            secondary = if (cleared) AudioLanguagePreference.None else preference,
        )
    }

    companion object {

        val DEFAULT: AudioLanguagePreferences = AudioLanguagePreferences()

        fun fromStoredTags(primaryTag: String?, secondaryTag: String?): AudioLanguagePreferences {
            val primary = AudioLanguagePreference.Tag.of(primaryTag)
                ?: AudioLanguagePreference.SystemLanguage
            val storedSecondary = AudioLanguagePreference.Tag.of(secondaryTag)
            val secondary = if (storedSecondary == null || storedSecondary == primary) {
                AudioLanguagePreference.None
            } else {
                storedSecondary
            }
            return AudioLanguagePreferences(primary = primary, secondary = secondary)
        }
    }
}
