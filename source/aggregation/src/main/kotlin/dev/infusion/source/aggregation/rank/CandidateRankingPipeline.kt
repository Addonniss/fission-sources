package dev.infusion.source.aggregation.rank

import dev.infusion.core.model.CapabilityState
import dev.infusion.core.model.ExpectedMode
import dev.infusion.core.model.PlayableCandidate
import dev.infusion.core.model.Reachability

class CandidateRankingPipeline(
    private val preferences: RankingPreferences = RankingPreferences(),
) {

    fun rank(candidates: List<PlayableCandidate>): List<RankedCandidate> {
        val playable = candidates
            .mapNotNull(::validate)
            .filter(::assess)
            .filter(::filter)
        return playable
            .map { candidate -> candidate to score(candidate) }
            .sortedWith(
                compareByDescending<Pair<PlayableCandidate, Double>> { it.second }
                    .thenBy { it.first.sourceId.value.toString() }
                    .thenBy { it.first.nativeItemId.nativeId }
                    .thenBy { it.first.candidateId.value.toString() },
            )
            .mapIndexed { index, (candidate, score) ->
                RankedCandidate(
                    candidate = candidate,
                    rank = index + 1,
                    score = score,
                    explanation = explain(candidate, score),
                )
            }
    }

    private fun validate(candidate: PlayableCandidate): PlayableCandidate? {
        if (candidate.transport.value.isBlank()) return null
        if (candidate.nativeItemId.nativeId.isBlank()) return null
        return candidate
    }

    private fun assess(candidate: PlayableCandidate): Boolean =
        candidate.capability?.state != CapabilityState.UNSUPPORTED

    private fun filter(candidate: PlayableCandidate): Boolean {
        if (candidate.expectedMode == ExpectedMode.EXTERNAL_LINK) return false
        if (candidate.reachability == Reachability.UNREACHABLE) return false
        val expiresAt = candidate.expiresAt
        if (expiresAt != null && !expiresAt.isAfter(java.time.Instant.now())) return false
        return true
    }

    private fun score(candidate: PlayableCandidate): Double {
        var score = modeScore(candidate.expectedMode)

        if (candidate.sourceId.value.toString() in preferences.pinnedSourceIds) score += 100.0

        val language = candidate.facts.language
        if (language != null && preferences.preferredAudioLanguage != null &&
            language.equals(preferences.preferredAudioLanguage, ignoreCase = true) &&
            candidate.facts.confidence != dev.infusion.core.model.FactConfidence.UNKNOWN
        ) {
            score += 15.0
        }

        val resolution = parseResolutionPixels(candidate.facts.resolution)
        val cap = preferences.displayResolutionCap
        if (resolution != null) {
            score += when {
                cap == null -> 5.0
                resolution <= cap -> 10.0
                else -> 0.0
            }
        }

        if (candidate.reachability == Reachability.REACHABLE) score += 8.0
        return score
    }

    private fun modeScore(mode: ExpectedMode): Double = when (mode) {
        ExpectedMode.DIRECT -> 100.0
        ExpectedMode.REMUX -> 70.0
        ExpectedMode.TRANSCODE -> 40.0
        ExpectedMode.UNRESOLVED -> 10.0
        ExpectedMode.EXTERNAL_LINK -> 0.0
    }

    private fun parseResolutionPixels(raw: String?): Int? {
        val text = raw?.lowercase() ?: return null
        val p = Regex("(\\d{3,4})p").find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (p != null) return p
        return when {
            "8k" in text -> 7680
            "4k" in text -> 2160
            else -> null
        }
    }

    private fun explain(candidate: PlayableCandidate, score: Double): List<String> = buildList {
        add("${candidate.providerName}: ${candidate.originalLabel}")
        add("Expected mode: ${candidate.expectedMode.name.lowercase().replace('_', ' ')}")
        candidate.facts.resolution?.let { add("Resolution: $it") }
        candidate.facts.dynamicRange?.let { add("HDR: $it") }
        candidate.facts.audioCodec?.let { add("Audio: $it") }
        candidate.facts.language?.let { add("Language: $it") }
        candidate.facts.container?.let { add("Container: $it") }
        candidate.facts.sizeBytes?.let { add("Size: ${it / (1024 * 1024)} MB") }
        if (candidate.sourceId.value.toString() in preferences.pinnedSourceIds) add("Pinned source")
        if (candidate.reachability == Reachability.REACHABLE) add("Known reachable")
        add("Score: ${score.toInt()}")
    }
}
