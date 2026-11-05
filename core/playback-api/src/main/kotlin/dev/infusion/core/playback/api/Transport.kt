package dev.infusion.core.playback.api

data class TransportRequest(

    val requestId: String,

    val uri: String,

    val mimeType: String? = null,

    val headers: Map<String, String> = emptyMap(),

    val expiresAtEpochMs: Long? = null,

    val sidecarUris: List<String> = emptyList(),

    val sidecarMimeTypes: List<String?> = emptyList(),

    val sidecarLanguages: List<String?> = emptyList(),

    val sidecarForced: List<Boolean?> = emptyList(),

    val sidecarLabels: List<String?> = emptyList(),

    val sidecarSources: List<String?> = emptyList(),

    val startPositionMs: Long = 0L,

    val contentOffsetMs: Long = 0L,
) {
    init {
        require(requestId.isNotBlank()) { "requestId must not be blank" }
        require(uri.isNotBlank()) { "uri must not be blank" }
        require(uri.length <= MAX_URI_LENGTH) { "uri exceeds $MAX_URI_LENGTH chars" }
        require(sidecarUris.all { it.length <= MAX_URI_LENGTH }) {
            "a sidecar uri exceeds $MAX_URI_LENGTH chars"
        }
        require(
            sidecarMimeTypes.isEmpty() || sidecarMimeTypes.size == sidecarUris.size,
        ) { "sidecarMimeTypes must align with sidecarUris when declared" }
        require(
            sidecarLanguages.isEmpty() || sidecarLanguages.size == sidecarUris.size,
        ) { "sidecarLanguages must align with sidecarUris when declared" }
        require(
            sidecarForced.isEmpty() || sidecarForced.size == sidecarUris.size,
        ) { "sidecarForced must align with sidecarUris when declared" }
        require(
            sidecarLabels.isEmpty() || sidecarLabels.size == sidecarUris.size,
        ) { "sidecarLabels must align with sidecarUris when declared" }
        require(
            sidecarSources.isEmpty() || sidecarSources.size == sidecarUris.size,
        ) { "sidecarSources must align with sidecarUris when declared" }
        require(startPositionMs >= 0L) { "startPositionMs must be >= 0" }
        require(contentOffsetMs >= 0L) { "contentOffsetMs must be >= 0" }
        require(startPositionMs >= contentOffsetMs || startPositionMs == 0L) {
            "startPositionMs must be >= contentOffsetMs (an offset stream cannot start before its own beginning)"
        }
    }

    companion object {

        const val MAX_URI_LENGTH: Int = 4096
    }
}
