package dev.infusion.core.testing

import dev.infusion.core.model.PlayableCandidate
import dev.infusion.core.model.TransportDescriptor
import dev.infusion.core.playback.api.SessionLineage
import dev.infusion.core.playback.api.TransportRequest

object FakeTransport {

    const val TEST_TONE_SHA256 = "e30a9f63dd5d80cd13ee594f95ce605a67790b7bede88669ca22d3c6a304d630"

    const val TEST_TONE_DURATION_MS = 2_000L

    fun descriptor(name: String): TransportDescriptor =
        TransportDescriptor("local://generated/test-tone-2s.wav?candidate=$name")

    fun requestFor(candidate: PlayableCandidate, lineage: SessionLineage): TransportRequest =
        TransportRequest(
            requestId = lineage.requestId,
            uri = "local://generated/test-tone-2s.wav?candidate=${candidate.candidateId.value}",
            mimeType = "audio/wav",
        )
}
