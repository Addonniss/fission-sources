package dev.infusion.core.playback.api

enum class SubtitleModePreference {

    AUTOMATIC,

    OFF,
}

enum class SubtitleSameLanguagePolicy {

    OFF,

    FORCED_ONLY,

    FULL,
}

enum class SubtitleTypePreference {

    AUTOMATIC,

    STANDARD,

    SDH,

    FORCED_ONLY,
}

sealed interface SubtitleLanguagePreference {

    data object None : SubtitleLanguagePreference

    data class Tag private constructor(val value: String) : SubtitleLanguagePreference {

        override fun toString(): String = value

        companion object {

            fun of(raw: String?): Tag? =
                AudioLanguageTag.canonicalize(raw)?.let { canonical -> Tag(canonical) }
        }
    }
}

data class SubtitlePreferences(
    val mode: SubtitleModePreference = SubtitleModePreference.AUTOMATIC,
    val primary: SubtitleLanguagePreference = DEFAULT_PRIMARY,
    val secondary: SubtitleLanguagePreference = SubtitleLanguagePreference.None,
    val sameLanguage: SubtitleSameLanguagePolicy = SubtitleSameLanguagePolicy.FULL,
    val type: SubtitleTypePreference = SubtitleTypePreference.AUTOMATIC,
) {
    init {
        val explicitPrimary = (primary as? SubtitleLanguagePreference.Tag)?.value
        val explicitSecondary = (secondary as? SubtitleLanguagePreference.Tag)?.value
        require(
            explicitPrimary == null || explicitSecondary == null || explicitPrimary != explicitSecondary,
        ) { "the primary and secondary subtitle language preferences must not be the same value" }
    }

    val isRestrictive: Boolean
        get() = sameLanguage != SubtitleSameLanguagePolicy.FULL || type != SubtitleTypePreference.AUTOMATIC

    fun withPrimary(preference: SubtitleLanguagePreference): SubtitlePreferences {
        val cleared = preference == secondary && preference is SubtitleLanguagePreference.Tag
        return copy(primary = preference, secondary = if (cleared) SubtitleLanguagePreference.None else secondary)
    }

    fun withSecondary(preference: SubtitleLanguagePreference): SubtitlePreferences {
        val cleared = preference == primary && preference is SubtitleLanguagePreference.Tag
        return copy(secondary = if (cleared) SubtitleLanguagePreference.None else preference)
    }

    companion object {

        val DEFAULT_PRIMARY: SubtitleLanguagePreference =
            SubtitleLanguagePreference.Tag.of(DEFAULT_PRIMARY_TAG) ?: SubtitleLanguagePreference.None

        const val DEFAULT_PRIMARY_TAG: String = "en"

        const val STORED_NONE: String = "none"

        fun storedTag(preference: SubtitleLanguagePreference): String = when (preference) {
            is SubtitleLanguagePreference.Tag -> preference.value
            SubtitleLanguagePreference.None -> STORED_NONE
        }

        private fun primaryFromStored(stored: String?): SubtitleLanguagePreference {
            val trimmed = stored?.trim().orEmpty()
            if (trimmed.isEmpty()) return DEFAULT_PRIMARY
            if (trimmed.equals(STORED_NONE, ignoreCase = true)) return SubtitleLanguagePreference.None
            return SubtitleLanguagePreference.Tag.of(trimmed) ?: DEFAULT_PRIMARY
        }

        private fun secondaryFromStored(
            stored: String?,
            primary: SubtitleLanguagePreference,
        ): SubtitleLanguagePreference {
            val trimmed = stored?.trim().orEmpty()
            if (trimmed.isEmpty() || trimmed.equals(STORED_NONE, ignoreCase = true)) {
                return SubtitleLanguagePreference.None
            }
            val tag = SubtitleLanguagePreference.Tag.of(trimmed) ?: return SubtitleLanguagePreference.None

            return if (tag == primary) SubtitleLanguagePreference.None else tag
        }

        val DEFAULT: SubtitlePreferences = SubtitlePreferences()

        fun fromStored(
            mode: String? = null,
            primaryTag: String? = null,
            secondaryTag: String? = null,
            sameLanguage: String? = null,
            type: String? = null,
        ): SubtitlePreferences {
            val primary = primaryFromStored(primaryTag)
            val secondary = secondaryFromStored(secondaryTag, primary)
            return SubtitlePreferences(
                mode = modeOrNull(mode) ?: SubtitleModePreference.AUTOMATIC,
                primary = primary,
                secondary = secondary,
                sameLanguage = sameLanguageOrNull(sameLanguage) ?: SubtitleSameLanguagePolicy.FULL,
                type = typeOrNull(type) ?: SubtitleTypePreference.AUTOMATIC,
            )
        }

        fun modeOrNull(stored: String?): SubtitleModePreference? =
            stored?.let { value -> SubtitleModePreference.entries.firstOrNull { it.name == value } }

        fun sameLanguageOrNull(stored: String?): SubtitleSameLanguagePolicy? =
            stored?.let { value -> SubtitleSameLanguagePolicy.entries.firstOrNull { it.name == value } }

        fun typeOrNull(stored: String?): SubtitleTypePreference? =
            stored?.let { value -> SubtitleTypePreference.entries.firstOrNull { it.name == value } }
    }
}
