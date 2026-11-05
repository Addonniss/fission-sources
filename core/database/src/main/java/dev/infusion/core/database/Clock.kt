package dev.infusion.core.database

fun interface Clock {
    fun nowMs(): Long

    companion object {
        val SYSTEM: Clock = Clock { System.currentTimeMillis() }
    }
}
