package dev.infusion.core.lanweb

data class LanwebValidation(

    val requireExactHost: Boolean = false,

    val checkOptionalOrigin: Boolean = false,

    val requireJsonContentType: Boolean = false,

    val rejectCrossSiteFetchSite: Boolean = false,
) {
    companion object {

        val NONE: LanwebValidation = LanwebValidation()

        val BROWSER: LanwebValidation = LanwebValidation(
            requireExactHost = true,
            checkOptionalOrigin = true,
            requireJsonContentType = true,
            rejectCrossSiteFetchSite = true,
        )
    }
}

class LanwebRequestValidator(

    authority: String,
    private val rules: LanwebValidation = LanwebValidation.NONE,
) {
    private val expectedHost: String = authority

    val expectedOrigin: String = "http://$authority"

    fun rejection(method: String, headers: Map<String, String>): Int? {
        if (rules.requireExactHost) {
            val host = headers["host"]
            if (host == null || host != expectedHost) return 421
        }
        if (rules.checkOptionalOrigin) {
            val origin = headers["origin"]
            if (origin != null && origin != expectedOrigin) return 403
        }
        if (rules.rejectCrossSiteFetchSite) {
            val site = headers["sec-fetch-site"]
            if (site != null && site.equals(CROSS_SITE, ignoreCase = true)) return 403
        }
        if (rules.requireJsonContentType && method in BODY_METHODS) {
            val contentType = headers["content-type"]
            if (contentType == null || contentType.trim().lowercase() != LanwebContentTypes.JSON) return 415
        }
        return null
    }

    private companion object {
        const val CROSS_SITE: String = "cross-site"

        val BODY_METHODS: Set<String> = setOf("POST")
    }
}
