package dev.infusion.core.domain.preview

import dev.infusion.core.model.MediaPreview
import dev.infusion.core.model.TitleId

data class TitlePreviewHint(
    val preview: MediaPreview,
    val queuedAtNanos: Long,

    val identity: dev.infusion.core.model.metadata.MediaIdentity? = null,
)

interface TitlePreviewHandoff {

    fun put(titleId: TitleId, preview: MediaPreview)

    fun put(
        titleId: TitleId,
        preview: MediaPreview,
        identity: dev.infusion.core.model.metadata.MediaIdentity?,
    ) = put(titleId, preview)

    fun peek(titleId: TitleId): TitlePreviewHint?

    fun take(titleId: TitleId): TitlePreviewHint?

    fun clear()
}

class InMemoryTitlePreviewHandoff(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val ttlNanos: Long = DEFAULT_TTL_NANOS,

    private val clock: () -> Long = System::nanoTime,
) : TitlePreviewHandoff {

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(ttlNanos >= 0) { "ttlNanos must not be negative" }
    }

    private val entries = LinkedHashMap<TitleId, TitlePreviewHint>()

    override fun put(titleId: TitleId, preview: MediaPreview) = put(titleId, preview, null)

    override fun put(
        titleId: TitleId,
        preview: MediaPreview,
        identity: dev.infusion.core.model.metadata.MediaIdentity?,
    ) {
        synchronized(entries) {
            val now = clock()
            purgeExpired(now)

            entries.remove(titleId)
            entries[titleId] = TitlePreviewHint(preview = preview, queuedAtNanos = now, identity = identity)
            while (entries.size > maxEntries) {
                val eldest = entries.keys.firstOrNull() ?: break
                entries.remove(eldest)
            }
        }
    }

    override fun peek(titleId: TitleId): TitlePreviewHint? = synchronized(entries) {
        val now = clock()
        purgeExpired(now)
        entries[titleId]
    }

    override fun take(titleId: TitleId): TitlePreviewHint? = synchronized(entries) {
        val now = clock()
        purgeExpired(now)
        entries.remove(titleId)
    }

    override fun clear() {
        synchronized(entries) { entries.clear() }
    }

    private fun purgeExpired(now: Long) {
        if (entries.isEmpty()) return
        val stale = entries.filterValues { now - it.queuedAtNanos > ttlNanos }.keys
        stale.forEach { entries.remove(it) }
    }

    companion object {

        const val DEFAULT_MAX_ENTRIES: Int = 32

        const val DEFAULT_TTL_NANOS: Long = 60_000_000_000L
    }
}
