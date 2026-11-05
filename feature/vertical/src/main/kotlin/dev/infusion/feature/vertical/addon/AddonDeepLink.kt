package dev.infusion.feature.vertical.addon

data class AddonDeepLink(

    val addonUrl: String,
) {
    companion object {
        const val STREMIO_SCHEME = "stremio"

        fun parse(raw: String?): AddonDeepLink? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            val https = if (trimmed.startsWith("$STREMIO_SCHEME://", ignoreCase = true)) {
                "https://" + trimmed.substring(STREMIO_SCHEME.length + 3)
            } else {
                trimmed
            }

            if (!https.startsWith("https://") && !https.startsWith("http://")) return null
            return AddonDeepLink(https)
        }
    }
}
