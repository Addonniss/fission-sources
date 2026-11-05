package dev.infusion.core.database.store

import dev.infusion.core.database.dao.TitleDao
import dev.infusion.core.database.entity.TitleEntity
import dev.infusion.core.model.TitleId

class TitleSupersession(
    private val titleDao: TitleDao,
) {

    fun survivorOf(titleId: TitleId): TitleId {
        var current = titleId
        var hops = 0
        while (hops < MAX_HOPS) {
            val next = successorOf(current) ?: return current
            if (next == current) return current
            current = next
            hops++
        }
        return current
    }

    fun successorOf(titleId: TitleId): TitleId? =
        titleDao.getById(titleId.value.toString())
            ?.supersededByTitleId
            ?.let { raw -> runCatching { TitleId.fromString(raw) }.getOrNull() }

    fun survivorsOf(titleIds: Collection<TitleId>): Map<TitleId, TitleId> {
        val origins = titleIds.distinct()
        if (origins.isEmpty()) return emptyMap()
        val resolved = HashMap<TitleId, TitleId>(origins.size)
        val current = HashMap<TitleId, TitleId>(origins.size)
        val hops = HashMap<TitleId, Int>(origins.size)
        for (id in origins) {
            current[id] = id
            hops[id] = 0
        }

        while (resolved.size < origins.size) {
            val frontier = origins.filter { it !in resolved }.map { current.getValue(it) }.distinct()
            val rows = rowsByIds(frontier)
            for (origin in origins) {
                if (origin in resolved) continue
                val at = current.getValue(origin)
                val next = rows[at.value.toString()]
                    ?.supersededByTitleId
                    ?.let { raw -> runCatching { TitleId.fromString(raw) }.getOrNull() }
                when {
                    next == null || next == at -> resolved[origin] = at
                    hops.getValue(origin) + 1 >= MAX_HOPS -> {
                        current[origin] = next
                        resolved[origin] = next
                    }
                    else -> {
                        current[origin] = next
                        hops[origin] = hops.getValue(origin) + 1
                    }
                }
            }
        }
        return origins.associateWith { resolved.getValue(it) }
    }

    fun rowsByIds(titleIds: Collection<TitleId>): Map<String, TitleEntity> {
        if (titleIds.isEmpty()) return emptyMap()
        val ids = titleIds.map { it.value.toString() }.distinct()
        val rows = ArrayList<TitleEntity>(ids.size)
        for (chunk in ids.chunked(BATCH_READ_CHUNK)) rows += titleDao.listByIds(chunk)
        return rows.associateBy { it.titleId }
    }

    private companion object {

        const val MAX_HOPS = 8
    }
}
