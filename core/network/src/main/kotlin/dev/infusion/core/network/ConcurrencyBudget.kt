package dev.infusion.core.network

import java.util.concurrent.Semaphore

enum class ConcurrencyWorkClass {
    REQUESTED,
    SPECULATIVE,
}

class ConcurrencyBudget(
    private val permits: Int,
    private val reservedForRequested: Int = 0,
) : AutoCloseable {

    init {
        require(permits > 0) { "permits must be positive" }
        require(reservedForRequested >= 0) { "reservedForRequested must not be negative" }
        require(reservedForRequested < permits) {
            "the reserve must leave at least one permit for requested work"
        }
    }

    private val all = Semaphore(permits)

    private val speculativeShare = Semaphore(permits - reservedForRequested)

    val budgetPermits: Int get() = permits

    val reservedPermits: Int get() = reservedForRequested

    val availablePermits: Int get() = all.availablePermits()

    val availableSpeculativePermits: Int get() = speculativeShare.availablePermits()

    fun tryAcquire(cls: ConcurrencyWorkClass = ConcurrencyWorkClass.REQUESTED): Boolean = when (cls) {
        ConcurrencyWorkClass.REQUESTED -> all.tryAcquire()
        ConcurrencyWorkClass.SPECULATIVE -> {
            if (!speculativeShare.tryAcquire()) {
                false
            } else if (all.tryAcquire()) {
                true
            } else {

                speculativeShare.release()
                false
            }
        }
    }

    fun release(cls: ConcurrencyWorkClass = ConcurrencyWorkClass.REQUESTED) {
        when (cls) {
            ConcurrencyWorkClass.REQUESTED -> all.release()
            ConcurrencyWorkClass.SPECULATIVE -> {
                all.release()
                speculativeShare.release()
            }
        }
    }

    fun acquire() = all.acquire()

    fun <T> use(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    override fun close() = Unit

    companion object {
        fun unlimited() = ConcurrencyBudget(Int.MAX_VALUE)
    }
}
