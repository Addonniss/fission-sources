package dev.infusion.tv.configweb

import android.content.Context
import dev.infusion.core.lanweb.LanwebContentTypes
import dev.infusion.core.lanweb.LanwebResponse
import dev.infusion.core.lanweb.LanwebRouter
import dev.infusion.feature.configweb.ConfigWebEndpoints
import java.io.InputStream

class ConfigWebAssets(
    val pageHtml: String,
    val css: String,
    val javascript: String,
) {
    init {
        require(pageHtml.isNotBlank()) { "the page asset must not be empty" }
        require(css.isNotBlank()) { "the stylesheet asset must not be empty" }
        require(javascript.isNotBlank()) { "the script asset must not be empty" }
    }

    companion object {

        const val PAGE_ASSET: String = "configweb/page.html"
        const val CSS_ASSET: String = "configweb/app.css"
        const val JS_ASSET: String = "configweb/app.js"

        fun load(open: (String) -> InputStream): ConfigWebAssets = ConfigWebAssets(
            pageHtml = open(PAGE_ASSET).use { it.readBytes().decodeToString() },
            css = open(CSS_ASSET).use { it.readBytes().decodeToString() },
            javascript = open(JS_ASSET).use { it.readBytes().decodeToString() },
        )

        fun fromContext(context: Context): ConfigWebAssets =
            load { path -> context.assets.open(path) }
    }
}

object ConfigWebPageRoutes {

    const val CONTENT_SECURITY_POLICY: String =
        "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; " +
            "connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

    val PAGE_HEADERS: Map<String, String> = LinkedHashMap<String, String>().apply {
        put("Content-Security-Policy", CONTENT_SECURITY_POLICY)
        put("X-Frame-Options", "DENY")
        put("X-Content-Type-Options", "nosniff")
        put("Referrer-Policy", "no-referrer")
        put("Cache-Control", "no-store")
    }

    fun declare(router: LanwebRouter, assets: ConfigWebAssets): LanwebRouter = router
        .get(ConfigWebEndpoints.PAGE) {
            LanwebResponse.bytes(
                contentType = LanwebContentTypes.HTML,
                body = assets.pageHtml.toByteArray(Charsets.UTF_8),
                headers = PAGE_HEADERS,
            )
        }
        .get(ConfigWebEndpoints.CSS) {
            LanwebResponse.bytes(
                contentType = LanwebContentTypes.CSS,
                body = assets.css.toByteArray(Charsets.UTF_8),
                headers = PAGE_HEADERS,
            )
        }
        .get(ConfigWebEndpoints.JS) {
            LanwebResponse.bytes(
                contentType = LanwebContentTypes.JAVASCRIPT,
                body = assets.javascript.toByteArray(Charsets.UTF_8),
                headers = PAGE_HEADERS,
            )
        }
}
