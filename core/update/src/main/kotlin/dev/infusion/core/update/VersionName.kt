package dev.infusion.core.update

data class VersionName(
    val major: Long,
    val minor: Long,
    val patch: Long,
) {
    val text: String get() = "$major.$minor.$patch"

    companion object {

        private val PATTERN = Regex("^(\\d{1,6})\\.(\\d{1,3})\\.(\\d{1,3})$")

        fun parse(raw: String?): VersionName? {
            val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val withoutPrefix = trimmed.removePrefix("v").removePrefix("V")
            val match = PATTERN.matchEntire(withoutPrefix) ?: return null
            return VersionName(
                major = match.groupValues[1].toLongOrNull() ?: return null,
                minor = match.groupValues[2].toLongOrNull() ?: return null,
                patch = match.groupValues[3].toLongOrNull() ?: return null,
            )
        }
    }
}
