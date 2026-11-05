package dev.infusion.core.pairing

typealias Clock = dev.infusion.core.lanweb.Clock

object SystemClock : Clock {
    override fun nowEpochMs(): Long = System.currentTimeMillis()
}
