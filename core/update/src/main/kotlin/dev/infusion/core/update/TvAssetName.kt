package dev.infusion.core.update

data class TvAssetName(
    val versionName: VersionName,
    val versionCode: Long,
) {
    val text: String get() = fileName(versionName.text, versionCode)

    companion object {

        const val LEGACY_PREFIX = "infusion-tv-"

        const val CURRENT_PREFIX = "fission-tv-"

        const val CURRENT_PREFIX_MIN_VERSION_CODE = 200L

        const val SUFFIX = ".apk"

        val ACCEPTED_PREFIXES: List<String> = listOf(LEGACY_PREFIX, CURRENT_PREFIX)

        private val PATTERN = Regex(
            "^(infusion-tv|fission-tv)-(\\d{1,6}\\.\\d{1,3}\\.\\d{1,3})-(\\d{1,9})\\.apk$",
        )

        fun isTvAsset(name: String): Boolean =
            name.endsWith(SUFFIX) && ACCEPTED_PREFIXES.any { name.startsWith(it) }

        fun prefixFor(versionCode: Long): String =
            if (versionCode >= CURRENT_PREFIX_MIN_VERSION_CODE) CURRENT_PREFIX else LEGACY_PREFIX

        fun fileName(versionName: String, versionCode: Long): String =
            "${prefixFor(versionCode)}$versionName-$versionCode$SUFFIX"

        fun parse(name: String): TvAssetName? {
            val match = PATTERN.matchEntire(name) ?: return null
            val versionName = VersionName.parse(match.groupValues[2]) ?: return null
            val versionCode = match.groupValues[3].toLongOrNull() ?: return null
            return TvAssetName(versionName = versionName, versionCode = versionCode)
        }
    }
}
