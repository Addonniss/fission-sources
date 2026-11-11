package dev.infusion.core.update

data class TvAssetName(
    val versionName: VersionName,
    val versionCode: Long,

    val prefix: String = CURRENT_PREFIX,
) {
    val text: String get() = "$prefix${versionName.text}-$versionCode$SUFFIX"

    companion object {

        const val LEGACY_PREFIX = "infusion-tv-"

        const val CURRENT_PREFIX = "fission-tv-"

        const val SUFFIX = ".apk"

        val ACCEPTED_PREFIXES: List<String> = listOf(LEGACY_PREFIX, CURRENT_PREFIX)

        private val PATTERN = Regex(
            "^(infusion-tv|fission-tv)-(\\d{1,6}\\.\\d{1,3}\\.\\d{1,3})-(\\d{1,9})\\.apk$",
        )

        fun isTvAsset(name: String): Boolean =
            name.endsWith(SUFFIX) && ACCEPTED_PREFIXES.any { name.startsWith(it) }

        fun prefixForNewPublication(): String = CURRENT_PREFIX

        fun fileName(versionName: String, versionCode: Long): String =
            "${prefixForNewPublication()}$versionName-$versionCode$SUFFIX"

        fun parse(name: String): TvAssetName? {
            val match = PATTERN.matchEntire(name) ?: return null
            val versionName = VersionName.parse(match.groupValues[2]) ?: return null
            val versionCode = match.groupValues[3].toLongOrNull() ?: return null
            return TvAssetName(
                versionName = versionName,
                versionCode = versionCode,
                prefix = match.groupValues[1] + "-",
            )
        }
    }
}
