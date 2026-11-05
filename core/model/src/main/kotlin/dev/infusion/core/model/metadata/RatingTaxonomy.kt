package dev.infusion.core.model.metadata

enum class RatingScale(

    val max: Double,
) {
    TEN(10.0),
    FIVE(5.0),
    PERCENT(100.0),
    HUNDRED(100.0),
}

enum class RatingMetric(
    val id: String,

    val chipLabel: String,

    val abbreviatedLabel: String,

    val scale: RatingScale,

    val displayedByDefault: Boolean,

    val displayDivisor: Double = 1.0,

    val supportsExactEpisode: Boolean = false,
) {
    IMDB("imdb", "IMDb", "IMDb", RatingScale.TEN, true, supportsExactEpisode = true),
    RT_CRITICS("rt_critics", "RT", "RT", RatingScale.PERCENT, true),

    METACRITIC("metacritic", "Metacritic", "MC", RatingScale.HUNDRED, true, displayDivisor = 10.0),
    RT_AUDIENCE("rt_audience", "RT Audience", "RT-A", RatingScale.PERCENT, true),

    TMDB("tmdb", "TMDB", "TMDB", RatingScale.TEN, true, supportsExactEpisode = true),

    TRAKT("trakt", "Trakt", "Trakt", RatingScale.TEN, true, supportsExactEpisode = true),
    LETTERBOXD("letterboxd", "Letterboxd", "LB", RatingScale.FIVE, true),

    SIMKL("simkl", "Simkl", "Simkl", RatingScale.TEN, true),
    MAL("mal", "MAL", "MAL", RatingScale.TEN, true),
    JELLYFIN_COMMUNITY("jellyfin_community", "Jellyfin", "JF", RatingScale.TEN, true),

    JELLYFIN_CRITICS("jellyfin_critics", "Jellyfin Critics", "JF-C", RatingScale.HUNDRED, true),
    ;

    val defaultMax: Double get() = scale.max

    companion object {

        fun fromId(id: String?): RatingMetric? =
            entries.firstOrNull { it.id == id?.trim()?.lowercase() }

        val DefaultDisplayed: Set<RatingMetric> = entries.filter { it.displayedByDefault }.toSet()

        val ExactEpisode: List<RatingMetric> = entries.filter { it.supportsExactEpisode }

        val EpisodeWiringPending: Set<RatingMetric> =
            setOf(JELLYFIN_COMMUNITY, JELLYFIN_CRITICS)

        val SortableEpisodeSources: List<RatingMetric> = ExactEpisode

        val DefaultEpisode: Set<RatingMetric> = SortableEpisodeSources.toSet()

        val DefaultEpisodeOrder: List<RatingMetric> = SortableEpisodeSources
    }
}

fun ratingScoreText(metric: RatingMetric?, value: Double): String {
    val displayed = value / (metric?.displayDivisor ?: 1.0)
    val score = formatRatingDecimal(displayed)
    return when (metric?.scale) {
        RatingScale.PERCENT -> "$score%"
        else -> score
    }
}

fun formatRatingDecimal(value: Double): String {
    val rounded = kotlin.math.round(value * 10.0) / 10.0
    return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
}

enum class RatingSupplier(
    val id: String,
    val displayName: String,

    val configurable: Boolean,
) {
    OMDB("omdb", "OMDb", true),
    SIMKL("simkl", "Simkl", true),
    MDBLIST("mdblist", "MDBList", true),
    TRAKT("trakt", "Trakt", true),
    TMDB("tmdb", "TMDB", false),
    JELLYFIN("jellyfin", "Jellyfin", false),
    ADDON("addon", "Add-on", false),
    ;

    companion object {

        val DefaultOrder: List<RatingSupplier> = listOf(OMDB, SIMKL, MDBLIST, TRAKT)

        val Configurable: List<RatingSupplier> = entries.filter { it.configurable }

        val DefaultEnabled: Set<RatingSupplier> = setOf(OMDB, SIMKL, TRAKT)

        val DefaultDisabled: Set<RatingSupplier> = Configurable.filterNot { it in DefaultEnabled }.toSet()

        fun fromId(id: String?): RatingSupplier? =
            entries.firstOrNull { it.id == id?.trim()?.lowercase() }
    }
}

enum class RatingAppearance {
    TEXT,
    ABBREVIATED_TEXT,
    COLOUR_LOGOS,
    MONOCHROME_LOGOS,
    ;

    val usesMark: Boolean get() = this == COLOUR_LOGOS || this == MONOCHROME_LOGOS

    val isMonochrome: Boolean get() = this == MONOCHROME_LOGOS

    companion object {
        val Default: RatingAppearance = MONOCHROME_LOGOS
    }
}
