package dev.infusion.feature.vertical.details

import dev.infusion.core.model.watch.WatchKey

fun interface WatchedStateDemand {

    fun demand(watchKey: WatchKey)

    fun demandAll(watchKeys: Collection<WatchKey>) {
        for (key in watchKeys) demand(key)
    }

    companion object {

        val None: WatchedStateDemand = WatchedStateDemand { }
    }
}
