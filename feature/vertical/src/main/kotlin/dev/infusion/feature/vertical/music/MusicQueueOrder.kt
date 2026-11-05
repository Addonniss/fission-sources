package dev.infusion.feature.vertical.music

import dev.infusion.core.domain.music.MusicQueueEntry
import dev.infusion.core.domain.music.MusicQueueSlotId
import dev.infusion.core.domain.music.QueueEntryId

internal class MusicQueueOrder private constructor(
    private val slots: MutableList<Slot>,
) {

    class Slot(
        val id: MusicQueueSlotId,
        var entry: MusicQueueEntry?,
        var authoredRank: Double,
    )

    companion object {

        const val RANK_EPSILON: Double = 1e-6

        fun sourceOrder(count: Int): MusicQueueOrder {
            val slots = ArrayList<Slot>(count.coerceAtLeast(0))
            for (index in 0 until count.coerceAtLeast(0)) {
                slots += Slot(MusicQueueSlotId.Source(index), null, index.toDouble())
            }
            return MusicQueueOrder(slots)
        }

        fun localOrder(entry: MusicQueueEntry): MusicQueueOrder =
            MusicQueueOrder(mutableListOf(Slot(MusicQueueSlotId.Local(entry.occurrenceId), entry, 0.0)))
    }

    val size: Int get() = slots.size

    fun isEmpty(): Boolean = slots.isEmpty()

    fun slotAt(position: Int): Slot? = slots.getOrNull(position)

    fun indexOf(id: MusicQueueSlotId): Int = slots.indexOfFirst { it.id == id }

    fun appendLocal(entry: MusicQueueEntry): Int {
        val rank = (slots.maxOfOrNull { it.authoredRank } ?: 0.0) + 1.0
        slots += Slot(MusicQueueSlotId.Local(entry.occurrenceId), entry, rank)
        return slots.lastIndex
    }

    fun insertLocalAt(position: Int, entry: MusicQueueEntry): Int {
        val index = position.coerceIn(0, slots.size)
        val rank = rankForIndex(index)
        slots.add(index, Slot(MusicQueueSlotId.Local(entry.occurrenceId), entry, rank))
        return index
    }

    fun appendSource(absoluteIndex: Int): Boolean {
        if (slots.any { it.id == MusicQueueSlotId.Source(absoluteIndex) }) return false
        val rank = (slots.maxOfOrNull { it.authoredRank } ?: -1.0) + 1.0
        slots += Slot(MusicQueueSlotId.Source(absoluteIndex), null, rank)
        return true
    }

    fun remove(id: MusicQueueSlotId): Int? {
        val index = indexOf(id)
        if (index < 0) return null
        slots.removeAt(index)
        return index
    }

    fun moveTo(id: MusicQueueSlotId, position: Int): Int {
        val from = indexOf(id)
        if (from < 0) return -1
        val slot = slots.removeAt(from)
        val target = position.coerceIn(0, slots.size)
        slot.authoredRank = rankForIndex(target)
        slots.add(target, slot)
        return target
    }

    fun shuffle(pinnedId: MusicQueueSlotId?, seed: Long): Boolean {
        if (slots.size <= 1) return false
        val pinned = slots.indexOfFirst { it.id == pinnedId }
        val permutation = ShuffleOrder.create(slots.size, pinned.coerceAtLeast(0), seed)
        val previous = slots.toList()
        slots.clear()
        for (position in 0 until permutation.size) {
            slots += previous[permutation.absoluteAt(position)]
        }
        return true
    }

    fun restoreAuthored() {
        val ordered = slots.sortedBy { it.authoredRank }
        slots.clear()
        slots.addAll(ordered)
    }

    private fun rankForIndex(index: Int): Double {
        if (slots.isEmpty()) return 0.0
        if (index <= 0) return slots.first().authoredRank - 1.0
        if (index >= slots.size) return slots.last().authoredRank + 1.0
        val before = slots[index - 1].authoredRank
        val after = slots[index].authoredRank
        val low = minOf(before, after)
        val high = maxOf(before, after)
        if (high - low <= RANK_EPSILON) {
            rebase()
            return rankForIndex(index)
        }
        return (before + after) / 2.0
    }

    private fun rebase() {
        val canonical = slots.sortedBy { it.authoredRank }
        canonical.forEachIndexed { index, slot -> slot.authoredRank = index.toDouble() }
    }

    fun localOccurrenceIds(): List<QueueEntryId> = slots.mapNotNull { slot ->
        (slot.id as? MusicQueueSlotId.Local)?.occurrenceId
    }
}
