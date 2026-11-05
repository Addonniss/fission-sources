package dev.infusion.core.model.metadata

data class ImageRef(
    val provider: String,
    val path: String,
) {
    init {
        require(provider.isNotBlank()) { "image provider must not be blank" }
        require(path.isNotBlank()) { "image path must not be blank" }
    }
}

data class MediaArtwork(
    val poster: ImageRef? = null,
    val backdrop: ImageRef? = null,
    val logo: ImageRef? = null,
    val still: ImageRef? = null,
) {

    val isEmpty: Boolean get() = poster == null && backdrop == null && logo == null && still == null

    companion object {

        val None: MediaArtwork = MediaArtwork()
    }
}
