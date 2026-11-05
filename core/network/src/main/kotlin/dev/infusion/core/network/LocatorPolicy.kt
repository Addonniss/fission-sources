package dev.infusion.core.network

enum class LocatorKind {
    URL,
    EXTERNAL_BROWSER,
    TORRENT,
    YOUTUBE,
    USENET,
    ARCHIVE,
    UNKNOWN,

    AMBIGUOUS,
}

object LocatorPolicy {

    private val KIND_BY_FIELD: Map<String, LocatorKind> = mapOf(
        "url" to LocatorKind.URL,
        "externalUrl" to LocatorKind.EXTERNAL_BROWSER,
        "infoHash" to LocatorKind.TORRENT,
        "magnet" to LocatorKind.TORRENT,
        "sources" to LocatorKind.TORRENT,
        "fileIdx" to LocatorKind.TORRENT,
        "ytId" to LocatorKind.YOUTUBE,
        "nzbUrl" to LocatorKind.USENET,
        "servers" to LocatorKind.USENET,
        "rarUrls" to LocatorKind.ARCHIVE,
        "zipUrls" to LocatorKind.ARCHIVE,
        "7zipUrls" to LocatorKind.ARCHIVE,
        "tgzUrls" to LocatorKind.ARCHIVE,
        "tarUrls" to LocatorKind.ARCHIVE,
    )

    fun classify(fields: Map<String, Any?>): LocatorKind {
        val kinds = fields.keys
            .filter { it in KIND_BY_FIELD }
            .map { KIND_BY_FIELD.getValue(it) }
            .distinct()
        return when (kinds.size) {
            0 -> LocatorKind.UNKNOWN
            1 -> kinds.single()
            else -> LocatorKind.AMBIGUOUS
        }
    }

    fun isFetchable(kind: LocatorKind): Boolean = kind == LocatorKind.URL

    fun isBrowserHandoff(kind: LocatorKind): Boolean = kind == LocatorKind.EXTERNAL_BROWSER

    fun isRejected(kind: LocatorKind): Boolean = !isFetchable(kind) && !isBrowserHandoff(kind)
}
