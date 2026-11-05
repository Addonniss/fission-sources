package dev.infusion.core.lanweb

class RateLimiter(
    private val maxPermits: Int,
    private val windowMs: Long,
    private val clock: Clock,
) {
    init {
        require(maxPermits > 0) { "maxPermits must be positive" }
        require(windowMs > 0) { "windowMs must be positive" }
    }

    private var windowStartMs: Long = clock.nowEpochMs()
    private var count = 0

    @Synchronized
    fun tryAcquire(): Boolean {
        val now = clock.nowEpochMs()
        if (now - windowStartMs >= windowMs) {
            windowStartMs = now
            count = 0
        }
        if (count >= maxPermits) return false
        count++
        return true
    }

    @Synchronized
    fun currentCount(): Int {
        val now = clock.nowEpochMs()
        if (now - windowStartMs >= windowMs) {
            windowStartMs = now
            count = 0
        }
        return count
    }
}
