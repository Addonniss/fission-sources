package dev.infusion.core.model.watch

import dev.infusion.core.model.CandidateFamilyId
import dev.infusion.core.model.NativeItemId
import java.time.Instant

data class ResumeAnchor(
    val nativeItemId: NativeItemId,
    val familyId: CandidateFamilyId,
    val versionId: String? = null,
    val positionMs: Long,
    val anchoredAt: Instant,
) {
    init {
        require(positionMs >= 0) { "positionMs must be >= 0" }
    }
}
