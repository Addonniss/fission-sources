package dev.infusion.core.update

data class TvAssetName(
    val versionName: VersionName,
    val versionCode: Long,
) {
    val text: String get() = fileName(versionName.text, versionCode)

    companion object {

        const val PREFIX = "infusion-tv-"
        const val SUFFIX = ".apk"

        private val PATTERN = Regex("^infusion-tv-(\\d{1,6}\\.\\d{1,3}\\.\\d{1,3})-(\\d{1,9})\\.apk$")

        fun isTvAsset(name: String): Boolean =
            name.startsWith(PREFIX) && name.endsWith(SUFFIX)

        fun fileName(versionName: String, versionCode: Long): String =
            "$PREFIX$versionName-$versionCode$SUFFIX"

        fun parse(name: String): TvAssetName? {
            val match = PATTERN.matchEntire(name) ?: return null
            val versionName = VersionName.parse(match.groupValues[1]) ?: return null
            val versionCode = match.groupValues[2].toLongOrNull() ?: return null
            return TvAssetName(versionName = versionName, versionCode = versionCode)
        }
    }
}
