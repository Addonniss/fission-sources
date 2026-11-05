package dev.infusion.core.playback.media3

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.StuckPlayerException
import dev.infusion.core.playback.api.FailureCategory
import dev.infusion.core.playback.api.FailureSeverity
import dev.infusion.core.playback.api.PlaybackFailure
import dev.infusion.core.playback.api.PlaybackStall
import dev.infusion.core.playback.api.Recoverability

object Media3FailureMapper {

    internal const val MAX_CAUSE_DEPTH: Int = 8

    fun map(
        error: PlaybackException,
        timestampMs: Long,
    ): PlaybackFailure {

        val classification = classifyCause(error) ?: classify(error.errorCode)
        return PlaybackFailure(
            category = classification.category,
            code = classification.code,
            severity = classification.severity,
            recoverability = classification.recoverability,
            message = classification.message,
            engineDetail = engineDetail(error),
            timestampMs = timestampMs,
            stall = stallOf(deepestCause(error)),
        )
    }

    internal fun stallOf(deepest: Throwable): PlaybackStall? = when (deepest) {
        is StuckPlayerException -> when (deepest.stuckType) {
            StuckPlayerException.STUCK_PLAYING_NOT_ENDING -> PlaybackStall.NOT_ENDING
            StuckPlayerException.STUCK_SUPPRESSED -> PlaybackStall.SUPPRESSED

            else -> PlaybackStall.NO_PROGRESS
        }
        else -> null
    }

    internal fun deepestCause(error: Throwable): Throwable {
        var deepest = error
        var depth = 0
        while (depth < MAX_CAUSE_DEPTH) {
            val next = deepest.cause ?: return deepest
            deepest = next
            depth++
        }
        return deepest
    }

    private fun classifyCause(error: Throwable): Classification? =
        if (deepestCause(error) is OutOfMemoryError) OUT_OF_MEMORY else null

    private val OUT_OF_MEMORY = Classification(
        FailureCategory.RESOURCE, "resource.out_of_memory", FailureSeverity.TRANSIENT,
        Recoverability.RETRYABLE_TRANSIENT, "Playback ran out of memory.",
    )

    private fun engineDetail(error: PlaybackException): String? {
        val codeName = error.errorCodeName
            ?.substringAfterLast('.')
            ?.takeIf { it.isNotBlank() }
        val causeClass = error.cause?.javaClass?.simpleName
        return when {
            codeName != null && causeClass != null -> "$codeName ($causeClass)"
            codeName != null -> codeName
            causeClass != null -> causeClass
            else -> null
        }
    }

    internal data class Classification(
        val category: FailureCategory,
        val code: String,
        val severity: FailureSeverity,
        val recoverability: Recoverability,
        val message: String,
    )

    internal fun classify(errorCode: Int): Classification = when (errorCode) {

        PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> Classification(
            FailureCategory.SOURCE, "source.read_failed", FailureSeverity.TRANSIENT,
            Recoverability.RETRYABLE_TRANSIENT, "The media source could not read the stream.",
        )
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> Classification(
            FailureCategory.SOURCE, "source.file_not_found", FailureSeverity.PERMANENT,
            Recoverability.NOT_RETRYABLE, "The media file was not found.",
        )
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> Classification(
            FailureCategory.SOURCE, "source.no_permission", FailureSeverity.PERMANENT,
            Recoverability.NOT_RETRYABLE, "The media source denied access.",
        )
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> Classification(
            FailureCategory.SOURCE, "source.invalid_content_type", FailureSeverity.PERMANENT,
            Recoverability.NOT_RETRYABLE, "The media server returned an unexpected content type.",
        )
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> Classification(
            FailureCategory.SOURCE, "source.http_status", FailureSeverity.PERMANENT,
            Recoverability.NOT_RETRYABLE, "The media server rejected the request.",
        )
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> Classification(
            FailureCategory.SOURCE, "source.read_position_out_of_range", FailureSeverity.PERMANENT,
            Recoverability.NOT_RETRYABLE, "The media source could not be read at the requested position.",
        )
        PlaybackException.ERROR_CODE_REMOTE_ERROR -> Classification(
            FailureCategory.NETWORK, "network.remote_error", FailureSeverity.TRANSIENT,
            Recoverability.RETRYABLE_TRANSIENT, "The media server reported an error.",
        )
        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> Classification(
            FailureCategory.SOURCE, "source.behind_live_window", FailureSeverity.TRANSIENT,
            Recoverability.RETRYABLE_TRANSIENT, "Playback fell behind the live window.",
        )

        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> Classification(
            FailureCategory.NETWORK, "network.connection_failed", FailureSeverity.TRANSIENT,
            Recoverability.RETRYABLE_TRANSIENT, "The network connection failed.",
        )
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> Classification(
            FailureCategory.NETWORK, "network.connection_timeout", FailureSeverity.TRANSIENT,
            Recoverability.RETRYABLE_TRANSIENT, "The network connection timed out.",
        )
        PlaybackException.ERROR_CODE_TIMEOUT -> Classification(
            FailureCategory.NETWORK, "network.timeout", FailureSeverity.TRANSIENT,
            Recoverability.RETRYABLE_TRANSIENT, "The operation timed out.",
        )

        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> Classification(
            FailureCategory.POLICY, "policy.cleartext_not_permitted", FailureSeverity.PERMANENT,
            Recoverability.NOT_RETRYABLE, "Cleartext media is not permitted by network policy.",
        )

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> Classification(
            FailureCategory.FORMAT, "format.container_unsupported", FailureSeverity.PERMANENT,
            Recoverability.NOT_RETRYABLE, "The media container is not supported.",
        )
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> Classification(
            FailureCategory.FORMAT, "format.manifest_unsupported", FailureSeverity.PERMANENT,
            Recoverability.NOT_RETRYABLE, "The media manifest is not supported.",
        )
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> Classification(
            FailureCategory.FORMAT, "format.container_malformed", FailureSeverity.PERMANENT,
            Recoverability.NOT_RETRYABLE, "The media container is malformed.",
        )
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> Classification(
            FailureCategory.FORMAT, "format.manifest_malformed", FailureSeverity.PERMANENT,
            Recoverability.NOT_RETRYABLE, "The media manifest is malformed.",
        )

        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> Classification(
            FailureCategory.DECODE, "decode.init_failed", FailureSeverity.TRANSIENT,
            Recoverability.RETRYABLE_TRANSIENT, "The decoder could not be initialized.",
        )
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> Classification(
            FailureCategory.DECODE, "decode.query_failed", FailureSeverity.TRANSIENT,
            Recoverability.RETRYABLE_TRANSIENT, "The device could not report its decoder capabilities.",
        )
        PlaybackException.ERROR_CODE_DECODING_FAILED -> Classification(
            FailureCategory.DECODE, "decode.failed", FailureSeverity.TRANSIENT,
            Recoverability.RETRYABLE_TRANSIENT, "Decoding failed.",
        )
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> Classification(
            FailureCategory.DECODE, "decode.format_unsupported", FailureSeverity.PERMANENT,
            Recoverability.NOT_RETRYABLE, "The media format is not supported by this device.",
        )
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> Classification(
            FailureCategory.DECODE, "decode.format_exceeds_capabilities", FailureSeverity.PERMANENT,
            Recoverability.NOT_RETRYABLE, "The media format exceeds this device's decoder capabilities.",
        )
        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED -> Classification(
            FailureCategory.DECODE, "decode.resources_reclaimed", FailureSeverity.TRANSIENT,
            Recoverability.RETRYABLE_TRANSIENT, "The decoder resources were reclaimed by the system.",
        )

        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED -> Classification(
            FailureCategory.AUDIO_OUTPUT, "audio_output.track_init_failed", FailureSeverity.TRANSIENT,
            Recoverability.RETRYABLE_TRANSIENT, "The audio output could not be initialized.",
        )
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> Classification(
            FailureCategory.AUDIO_OUTPUT, "audio_output.track_write_failed", FailureSeverity.TRANSIENT,
            Recoverability.RETRYABLE_TRANSIENT, "Audio could not be written to the output.",
        )
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED -> Classification(
            FailureCategory.AUDIO_OUTPUT, "audio_output.offload_init_failed", FailureSeverity.TRANSIENT,
            Recoverability.RETRYABLE_TRANSIENT, "The audio offload path could not be initialized.",
        )
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED -> Classification(
            FailureCategory.AUDIO_OUTPUT, "audio_output.offload_write_failed", FailureSeverity.TRANSIENT,
            Recoverability.RETRYABLE_TRANSIENT, "Audio could not be written to the offload path.",
        )

        PlaybackException.ERROR_CODE_DRM_UNSPECIFIED -> Classification(
            FailureCategory.DRM, "drm.unspecified", FailureSeverity.UNKNOWN,
            Recoverability.UNKNOWN, "An unclassified DRM error occurred.",
        )
        PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED -> Classification(
            FailureCategory.DRM, "drm.scheme_unsupported", FailureSeverity.PERMANENT,
            Recoverability.NOT_RETRYABLE, "This DRM scheme is not supported.",
        )
        PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED -> Classification(
            FailureCategory.DRM, "drm.provisioning_failed", FailureSeverity.PERMANENT,
            Recoverability.NOT_RETRYABLE, "DRM provisioning failed.",
        )
        PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR -> Classification(
            FailureCategory.DRM, "drm.content_error", FailureSeverity.PERMANENT,
            Recoverability.NOT_RETRYABLE, "The DRM-protected content could not be read.",
        )
        PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED -> Classification(
            FailureCategory.DRM, "drm.license_acquisition_failed", FailureSeverity.PERMANENT,
            Recoverability.RETRYABLE_REFRESH, "The DRM license could not be acquired.",
        )
        PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION -> Classification(
            FailureCategory.DRM, "drm.disallowed_operation", FailureSeverity.PERMANENT,
            Recoverability.NOT_RETRYABLE, "The DRM operation is not allowed.",
        )
        PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR -> Classification(
            FailureCategory.DRM, "drm.system_error", FailureSeverity.PERMANENT,
            Recoverability.NOT_RETRYABLE, "The DRM system reported an error.",
        )
        PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED -> Classification(
            FailureCategory.DRM, "drm.device_revoked", FailureSeverity.PERMANENT,
            Recoverability.NOT_RETRYABLE, "This device's DRM access has been revoked.",
        )
        PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED -> Classification(
            FailureCategory.DRM, "drm.license_expired", FailureSeverity.PERMANENT,
            Recoverability.RETRYABLE_REFRESH, "The DRM license has expired.",
        )

        PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK -> Classification(
            FailureCategory.INTERNAL, "internal.runtime_check", FailureSeverity.PERMANENT,
            Recoverability.NOT_RETRYABLE, "The player failed an internal consistency check.",
        )

        else -> Classification(
            FailureCategory.UNKNOWN, "unknown.$errorCode",
            FailureSeverity.UNKNOWN, Recoverability.UNKNOWN,
            "An unclassified playback error occurred.",
        )
    }
}
