package dev.infusion.core.sync

object RetryBackoff {

    const val BASE_DELAY_MS = 5_000L

    const val MAX_DELAY_MS = 900_000L

    fun delayMs(attempts: Int): Long {
        require(attempts >= 1) { "attempts must be >= 1, was $attempts" }
        val exponent = (attempts - 1).coerceAtMost(20)
        return minOf(BASE_DELAY_MS shl exponent, MAX_DELAY_MS)
    }
}
