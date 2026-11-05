package dev.infusion.core.domain.tracking

import dev.infusion.core.model.TitleId
import dev.infusion.core.model.watch.WatchKey

interface TrackingMediaResolverPort {

    fun resolve(key: WatchKey): TrackingMediaReference?

    fun hasProvenIdentity(titleId: TitleId): Boolean
}
