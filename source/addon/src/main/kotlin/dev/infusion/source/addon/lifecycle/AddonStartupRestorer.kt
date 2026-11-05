package dev.infusion.source.addon.lifecycle

import dev.infusion.source.addon.repository.AddonRepositoryState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AddonStartupRestorer<R : Any>(
    private val restore: suspend () -> R,
) {

    private val mutex = Mutex()

    @Volatile
    private var published: R? = null

    suspend fun awaitReady(): R = mutex.withLock {
        published?.let { return it }
        val result = restore()
        published = result
        result
    }

    val isOpen: Boolean get() = published != null

    companion object {

        fun repository(manager: AddonLifecycleManager): AddonStartupRestorer<AddonRepositoryState> =
            AddonStartupRestorer {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    manager.restore()
                }
            }
    }
}