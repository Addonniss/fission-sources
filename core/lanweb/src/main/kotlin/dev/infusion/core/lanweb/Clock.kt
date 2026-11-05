package dev.infusion.core.lanweb

fun interface Clock {
    fun nowEpochMs(): Long
}

object SystemClock : Clock {
    override fun nowEpochMs(): Long = System.currentTimeMillis()
}
