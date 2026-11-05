package dev.infusion.tracking.mdblist

class MDBListCommunityMedia(
    private val client: MDBListHttpClient,
    private val auth: MDBListAuthRepository,
) {

    fun connected(): Boolean = auth.isConnected()

    suspend fun mediaInfo(
        mediaProvider: String,
        mediaType: String,
        mediaId: String,
    ): String? {
        val provider = mediaProvider.trim().lowercase()
        val type = mediaType.trim().lowercase()
        val id = mediaId.trim()
        if (provider !in MEDIA_PROVIDERS) return null
        if (type !in MEDIA_TYPES) return null
        if (id.isEmpty() || id.length > 64) return null

        val request = MDBListRequest(
            method = MDBListMethod.GET,
            path = "/$provider/$type/$id/",
            authenticated = true,
            retryable = true,
        )
        val token = auth.accessToken() ?: return null
        return when (val outcome = client.execute(request, token)) {
            is MDBListOutcome.Success -> outcome.response.body
            is MDBListOutcome.Unauthorized -> {

                val refreshed = auth.refresh() ?: return null
                when (val retry = client.execute(request, refreshed)) {
                    is MDBListOutcome.Success -> retry.response.body
                    else -> null
                }
            }

            is MDBListOutcome.Retry -> null
            is MDBListOutcome.Rejected -> null
        }
    }

    private companion object {

        val MEDIA_PROVIDERS = setOf("imdb", "tmdb", "trakt", "tvdb", "mal", "mdblist")

        val MEDIA_TYPES = setOf("movie", "show", "any")
    }
}
