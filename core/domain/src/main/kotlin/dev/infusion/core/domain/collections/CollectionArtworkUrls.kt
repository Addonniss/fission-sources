package dev.infusion.core.domain.collections

import java.net.URI
import java.net.URISyntaxException

object CollectionArtworkUrls {

    private const val GITHUB_HOST: String = "github.com"

    private const val RAW_HOST: String = "raw.githubusercontent.com"

    private val POINTER_FORMS: Set<String> = setOf("blob", "raw")

    fun resolutionOrder(reference: String): List<String> {
        val canonical = canonicalRawLocation(reference)
        return if (canonical == null || canonical == reference) {
            listOf(reference)
        } else {
            listOf(reference, canonical)
        }
    }

    fun canonicalRawLocation(reference: String): String? {
        val uri = try {
            URI(reference)
        } catch (_: URISyntaxException) {
            return null
        }
        if (!"https".equals(uri.scheme, ignoreCase = true)) return null
        if (!GITHUB_HOST.equals(uri.host, ignoreCase = true)) return null
        val segments = (uri.rawPath ?: return null)
            .split('/')
            .filter { it.isNotEmpty() }

        if (segments.size < 5) return null
        if (segments[2] !in POINTER_FORMS) return null
        val owner = segments[0]
        val repo = segments[1]
        val file = segments.drop(3).joinToString("/")
        if (owner.isEmpty() || repo.isEmpty() || file.isEmpty()) return null
        return "https://$RAW_HOST/$owner/$repo/$file"
    }
}
