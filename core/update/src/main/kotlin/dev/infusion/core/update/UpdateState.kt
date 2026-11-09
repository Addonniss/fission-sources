package dev.infusion.core.update

enum class UpdateFailureKind(

    val message: String?,

    val retryable: Boolean,
) {

    DOWNLOAD_INTERRUPTED("The download stopped.", retryable = true),

    DOWNLOAD_FAILED("The download couldn't be completed.", retryable = true),

    HASH_MISMATCH("The downloaded update didn't match its checksum.", retryable = false),

    CHECKSUMS_UNAVAILABLE("The update's checksum couldn't be verified.", retryable = false),

    PACKAGE_MISMATCH("This update isn't for fission TV.", retryable = false),

    VERSION_MISMATCH("This update's version doesn't match its release.", retryable = false),

    DEBUGGABLE_REFUSED("This update is a development build and can't be installed.", retryable = false),

    SIGNER_MISMATCH("This update isn't signed for your installation.", retryable = false),

    DOWNGRADE_REFUSED("This update isn't newer than the installed version.", retryable = false),

    INSUFFICIENT_STORAGE("Not enough storage for the update.", retryable = true),

    INSTALL_PERMISSION_MISSING("fission isn't allowed to install apps.", retryable = false),

    INSTALL_FAILED("The install didn't complete.", retryable = true),
}

enum class SilentSkipReason {
    NO_NETWORK,
    POLICY_DENIED,
    RATE_LIMITED,
    API_UNAVAILABLE,
    MALFORMED_RESPONSE,
    DRAFT,
    NOT_ALPHA_CHANNEL,
    BURNED_REHEARSAL,
    MALFORMED_VERSION,
    ASSET_INCONSISTENT,
    NO_TV_ASSET,
    ASSET_AMBIGUOUS,
    DIGEST_UNAVAILABLE,
    NOT_STRICTLY_NEWER,

    UP_TO_DATE,
}

sealed interface UpdateState {

    data object Idle : UpdateState

    data object Checking : UpdateState

    data object UpToDate : UpdateState

    data class Available(val candidate: UpdateCandidate) : UpdateState

    data class Downloading(val bytesRead: Long, val totalBytes: Long) : UpdateState

    data object Verifying : UpdateState

    data object ReadyToInstall : UpdateState

    data object NeedsPermission : UpdateState

    data object UserDismissed : UpdateState

    data class SilentSkip(val reason: SilentSkipReason) : UpdateState

    data class Failed(val kind: UpdateFailureKind) : UpdateState
}

object UpdatePresentation {

    fun isDialogVisible(state: UpdateState, playbackLive: Boolean): Boolean = when (state) {
        is UpdateState.Available -> !playbackLive
        is UpdateState.Downloading,
        is UpdateState.Verifying,
        is UpdateState.ReadyToInstall,
        is UpdateState.NeedsPermission,
        is UpdateState.Failed,
        -> true
        is UpdateState.Idle,
        is UpdateState.Checking,
        is UpdateState.UpToDate,
        is UpdateState.UserDismissed,
        is UpdateState.SilentSkip,
        -> false
    }
}
