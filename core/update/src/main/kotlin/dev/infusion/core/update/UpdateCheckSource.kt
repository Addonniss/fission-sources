package dev.infusion.core.update

fun interface UpdateCheckSource {

    suspend fun check(installedVersionCode: Long): UpdateCheckResult
}

sealed interface UpdateCheckResult {

    data class Found(
        val candidate: UpdateCandidate,
        val consistency: ChannelConsistency = ChannelConsistency.NotProbed,
    ) : UpdateCheckResult

    data class NoUpdate(val reason: SilentSkipReason) : UpdateCheckResult
}

sealed interface ChannelConsistency {

    data object NotProbed : ChannelConsistency

    data object NoLatestRelease : ChannelConsistency

    data object Agrees : ChannelConsistency

    data class Contradicts(val latestTag: String?) : ChannelConsistency

    data object Unreadable : ChannelConsistency
}
