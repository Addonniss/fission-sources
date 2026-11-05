package dev.infusion.core.model.metadata

data class PersonId(
    val authority: String,
    val value: String,
) {
    init {
        require(authority.isNotBlank()) { "person authority must not be blank" }
        require(value.isNotBlank()) { "person id value must not be blank" }
    }
}

data class PersonRef(
    val name: String,
    val id: PersonId? = null,
    val profile: ImageRef? = null,
)

data class Genre(
    val name: String,
    val id: Long? = null,
)

data class Certification(
    val code: String,
    val region: String? = null,
) {
    init {
        require(code.isNotBlank()) { "certification code must not be blank" }
    }
}

data class MediaRating(
    val source: String,
    val value: Double,
    val max: Double = 10.0,
    val votes: Int? = null,

    val label: String = source.uppercase(),

    val supplier: String? = null,
) {
    init {
        require(source.isNotBlank()) { "rating source must not be blank" }
        require(max > 0.0) { "rating scale must be positive" }
    }

    val metric: String get() = source

    val fraction: Double get() = (value / max).coerceIn(0.0, 1.0)

    val ratedMetric: RatingMetric? get() = RatingMetric.fromId(source)

    val ratedSupplier: RatingSupplier? get() = RatingSupplier.fromId(supplier)
}

data class MediaVideo(
    val site: String,
    val key: String,
    val kind: VideoKind,
    val title: String? = null,
    val language: String? = null,
    val official: Boolean = false,
    val publishedAt: String? = null,
) {
    init {
        require(site.isNotBlank()) { "video site must not be blank" }
        require(key.isNotBlank()) { "video key must not be blank" }
    }
}

enum class VideoKind { TRAILER, TEASER, CLIP, FEATURETTE, BEHIND_THE_SCENES, OTHER }

data class Company(
    val name: String,
    val id: Long? = null,
    val logo: ImageRef? = null,
) {
    init {
        require(name.isNotBlank()) { "company name must not be blank" }
    }
}

data class CastMember(
    val name: String,
    val character: String? = null,
    val id: PersonId? = null,
    val profile: ImageRef? = null,

    val order: Int? = null,
) {
    init {
        require(name.isNotBlank()) { "cast member name must not be blank" }
    }

    fun ref(): PersonRef = PersonRef(name = name, id = id, profile = profile)
}

data class CrewMember(
    val name: String,
    val job: String? = null,
    val department: String? = null,
    val id: PersonId? = null,
    val profile: ImageRef? = null,
) {
    init {
        require(name.isNotBlank()) { "crew member name must not be blank" }
    }

    fun ref(): PersonRef = PersonRef(name = name, id = id, profile = profile)
}

fun List<CrewMember>.directors(): List<PersonRef> =
    filter { crew -> crew.job?.equals("Director", ignoreCase = true) == true }
        .map { it.ref() }
