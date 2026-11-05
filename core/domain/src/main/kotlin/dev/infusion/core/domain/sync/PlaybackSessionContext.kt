package dev.infusion.core.domain.sync

data class PlaybackSessionContext(
    val sourceId: String,
    val watchSessionId: String,
    val itemId: String,
    val mediaSourceId: String,
    val playSessionId: String,
)

interface PlaybackSessionContextStore {

    fun put(context: PlaybackSessionContext)

    fun get(sourceId: String, watchSessionId: String): PlaybackSessionContext?

    fun active(sourceId: String): List<PlaybackSessionContext>

    fun remove(sourceId: String, watchSessionId: String)

    fun deleteBySource(sourceId: String): Int
}
