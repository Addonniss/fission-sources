package dev.infusion.core.domain.refresh

data class LibraryChangeHint(

    val serviceId: String,

    val serviceInstance: String,

    val changedNativeIds: Set<String> = emptySet(),

    val removedNativeIds: Set<String> = emptySet(),

    val scopeOnly: Boolean = false,

    val observedAtMs: Long,
) {

    val isEmpty: Boolean
        get() = !scopeOnly && changedNativeIds.isEmpty() && removedNativeIds.isEmpty()
}

enum class LibraryFeedStatus {

    CONNECTING,

    CONNECTED,

    DISCONNECTED,

    UNSUPPORTED,
}

interface LibraryChangeFeed : AutoCloseable {

    fun start()

    override fun close()
}

data class ServiceFeed(
    val serviceId: String,
    val serviceInstance: String,
    val feed: LibraryChangeFeed,
)

enum class ProvenImportKind {
    MOVIE,
    SERIES,
    SEASON,
    EPISODE,
}

data class ProvenImportTarget(

    val titleId: String,
    val kind: ProvenImportKind,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,

    val provenAtMs: Long,
) {

    val key: String
        get() = listOf(titleId, kind.name, seasonNumber ?: "-", episodeNumber ?: "-", provenAtMs)
            .joinToString("|")
}

interface LibraryRefreshReconciler {

    fun serviceIds(): Set<String>

    val catchUpIntervalMs: Long

    fun catchUpWhileConnected(serviceId: String): Boolean = false

    suspend fun reconcileProvenImport(target: ProvenImportTarget): Boolean = false

    suspend fun reconcile(hint: LibraryChangeHint)

    suspend fun catchUp(serviceId: String)
}
