package dev.infusion.source.tmdb

import dev.infusion.core.domain.metadata.MetadataLocale
import dev.infusion.core.model.ArtworkPolicy
import dev.infusion.core.model.MediaArtworkRef
import dev.infusion.core.model.MediaPreview
import dev.infusion.core.model.TitleKind
import dev.infusion.core.model.metadata.CastMember
import dev.infusion.core.model.metadata.Certification
import dev.infusion.core.model.metadata.CollectionDetails
import dev.infusion.core.model.metadata.Company
import dev.infusion.core.model.metadata.CrewMember
import dev.infusion.core.model.metadata.EpisodeDetails
import dev.infusion.core.model.metadata.ExternalIds
import dev.infusion.core.model.metadata.Genre
import dev.infusion.core.model.metadata.ImageRef
import dev.infusion.core.model.metadata.MediaArtwork
import dev.infusion.core.model.metadata.MediaCard
import dev.infusion.core.model.metadata.MediaCardPage
import dev.infusion.core.model.metadata.MediaDetails
import dev.infusion.core.model.metadata.MediaIdentity
import dev.infusion.core.model.metadata.MediaRating
import dev.infusion.core.model.metadata.MediaReview
import dev.infusion.core.model.metadata.MediaType
import dev.infusion.core.model.metadata.MediaVideo
import dev.infusion.core.model.metadata.MovieCollectionSummary
import dev.infusion.core.model.metadata.MovieDetails
import dev.infusion.core.model.metadata.PersonCredit
import dev.infusion.core.model.metadata.PersonDetails
import dev.infusion.core.model.metadata.PersonId
import dev.infusion.core.model.metadata.PersonRef
import dev.infusion.core.model.metadata.ReviewPage
import dev.infusion.core.model.metadata.SeasonDetails
import dev.infusion.core.model.metadata.SeasonSummary
import dev.infusion.core.model.metadata.ShowDetails
import dev.infusion.core.model.metadata.VideoKind
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime

internal class TmdbMapper(private val locale: MetadataLocale) {

    fun details(
        identity: MediaIdentity,
        primary: JSONObject,
        fallback: JSONObject?,
        locale: MetadataLocale = this.locale,
    ): MediaDetails = when (identity.type) {
        MediaType.MOVIE -> movieDetails(identity, primary, fallback)
        MediaType.SHOW -> showDetails(identity, primary, fallback)
    }

    private fun movieDetails(
        identity: MediaIdentity,
        primary: JSONObject,
        fallback: JSONObject?,
    ): MovieDetails {
        val releaseDate = parseDate(field(primary, fallback) { TmdbJson.string(it, "release_date") })
        val collection = TmdbJson.objectOrNull(primary, "belongs_to_collection")?.let { col ->
            TmdbJson.string(col, "name")?.let { name ->
                MovieCollectionSummary(
                    id = TmdbJson.long(col, "id"),
                    name = name,
                    poster = imageRef(TmdbJson.string(col, "poster_path")),
                    backdrop = imageRef(TmdbJson.string(col, "backdrop_path")),
                )
            }
        }
        return MovieDetails(
            identity = identityWithExternalIds(identity, primary),
            title = field(primary, fallback) { TmdbJson.string(it, "title") } ?: "",
            originalTitle = TmdbJson.string(primary, "original_title"),
            overview = field(primary, fallback) { TmdbJson.string(it, "overview") },
            tagline = field(primary, fallback) { TmdbJson.string(it, "tagline") },
            year = releaseDate?.year ?: parseYear(TmdbJson.string(primary, "release_date")),
            genres = genres(primary, fallback),
            artwork = artwork(primary, fallback),
            ratings = ratings(primary),
            certification = movieCertification(primary),
            runtimeMinutes = TmdbJson.int(primary, "runtime")?.takeIf { it > 0 },
            videos = emptyList(),
            cast = cast(primary, fallback),
            crew = crew(primary, fallback),
            directors = directorsFrom(crew(primary, fallback)),
            creators = emptyList(),
            studios = companies(primary, "production_companies"),
            networks = emptyList(),
            keywords = keywords(primary, "keywords", "keywords"),
            originalLanguage = TmdbJson.string(primary, "original_language"),
            originCountries = TmdbJson.stringList(primary, "origin_country"),
            status = TmdbJson.string(primary, "status"),
            releaseDate = releaseDate,
            collection = collection,
        )
    }

    private fun showDetails(
        identity: MediaIdentity,
        primary: JSONObject,
        fallback: JSONObject?,
    ): ShowDetails {
        val firstAir = parseDate(field(primary, fallback) { TmdbJson.string(it, "first_air_date") })
        val crewEntries = crew(primary, fallback)
        return ShowDetails(
            identity = identityWithExternalIds(identity, primary),
            title = field(primary, fallback) { TmdbJson.string(it, "name") } ?: "",
            originalTitle = TmdbJson.string(primary, "original_name"),
            overview = field(primary, fallback) { TmdbJson.string(it, "overview") },
            tagline = field(primary, fallback) { TmdbJson.string(it, "tagline") },
            year = firstAir?.year ?: parseYear(TmdbJson.string(primary, "first_air_date")),
            genres = genres(primary, fallback),
            artwork = artwork(primary, fallback),
            ratings = ratings(primary),
            certification = showCertification(primary),
            runtimeMinutes = episodeRuntime(primary),
            videos = emptyList(),
            cast = cast(primary, fallback),
            crew = crewEntries,
            directors = directorsFrom(crewEntries),
            creators = creators(primary, fallback),
            studios = companies(primary, "production_companies"),
            networks = companies(primary, "networks"),
            keywords = keywords(primary, "keywords", "results"),
            originalLanguage = TmdbJson.string(primary, "original_language"),
            originCountries = TmdbJson.stringList(primary, "origin_country"),
            status = TmdbJson.string(primary, "status"),
            releaseDate = firstAir,
            seasons = seasons(primary),
            lastAirDate = parseDate(TmdbJson.string(primary, "last_air_date")),
            numberOfSeasons = TmdbJson.int(primary, "number_of_seasons"),
            numberOfEpisodes = TmdbJson.int(primary, "number_of_episodes"),
            inProduction = TmdbJson.bool(primary, "in_production"),
        )
    }

    private fun episodeRuntime(primary: JSONObject): Int? =
        TmdbJson.arrayOrNull(primary, "episode_run_time")
            ?.let { array ->
                (0 until array.length()).mapNotNull { array.optInt(it, 0) }.firstOrNull { it > 0 }
            }
            ?: TmdbJson.objectOrNull(primary, "last_episode_to_air")?.let { last ->
                TmdbJson.int(last, "runtime")?.takeIf { it > 0 }
            }

    private fun identityWithExternalIds(identity: MediaIdentity, document: JSONObject): MediaIdentity {
        val external = TmdbJson.objectOrNull(document, "external_ids")
        val providerId = TmdbJson.long(document, "id")
        val merged = identity.externalIds.merge(
            ExternalIds(
                tmdbId = providerId,
                imdbId = TmdbJson.string(external, "imdb_id"),
                tvdbId = TmdbJson.long(external, "tvdb_id"),
            ),
        )
        return if (merged == null) identity else identity.copy(externalIds = merged)
    }

    fun attachVideos(details: MediaDetails, videos: List<MediaVideo>): MediaDetails = when (details) {
        is MovieDetails -> details.copy(videos = videos)
        is ShowDetails -> details.copy(videos = videos)
    }

    fun videos(primary: JSONObject, fallback: JSONObject?): List<MediaVideo> {
        val localized = parseVideos(primary)
        val extra = if (fallback == null) emptyList() else parseVideos(fallback)
        return rankVideos((localized + extra).distinctBy { it.site.lowercase() to it.key })
    }

    private fun parseVideos(document: JSONObject): List<MediaVideo> {
        val results = TmdbJson.objectOrNull(document, "videos")
            ?.let { TmdbJson.arrayOrNull(it, "results") } ?: return emptyList()
        return buildList {
            TmdbJson.forEach(results) { entry ->
                val site = TmdbJson.string(entry, "site") ?: return@forEach
                if (!site.equals("YouTube", ignoreCase = true)) return@forEach
                val key = TmdbJson.string(entry, "key") ?: return@forEach
                if (!YOUTUBE_KEY.matches(key)) return@forEach
                add(
                    MediaVideo(
                        site = "youtube",
                        key = key,
                        kind = videoKind(TmdbJson.string(entry, "type")),
                        title = TmdbJson.string(entry, "name"),
                        language = TmdbJson.string(entry, "iso_639_1"),
                        official = TmdbJson.bool(entry, "official") == true,
                        publishedAt = TmdbJson.string(entry, "published_at"),
                    ),
                )
            }
        }
    }

    private fun rankVideos(videos: List<MediaVideo>): List<MediaVideo> = videos.sortedWith(
        compareBy<MediaVideo> { videoKindPriority(it.kind) }
            .thenByDescending { it.official }
            .thenByDescending { it.publishedAt.orEmpty() }
            .thenBy { it.key },
    )

    private fun videoKindPriority(kind: VideoKind): Int = when (kind) {
        VideoKind.TRAILER -> 0
        VideoKind.TEASER -> 1
        VideoKind.CLIP -> 2
        VideoKind.FEATURETTE -> 3
        VideoKind.BEHIND_THE_SCENES -> 4
        VideoKind.OTHER -> 5
    }

    private fun videoKind(raw: String?): VideoKind = when (raw?.trim()?.lowercase()) {
        "trailer" -> VideoKind.TRAILER
        "teaser" -> VideoKind.TEASER
        "clip" -> VideoKind.CLIP
        "featurette" -> VideoKind.FEATURETTE
        "behind the scenes" -> VideoKind.BEHIND_THE_SCENES
        else -> VideoKind.OTHER
    }

    fun artwork(primary: JSONObject, fallback: JSONObject?): MediaArtwork {
        val images = TmdbJson.objectOrNull(primary, "images")
        val primaryImages = images ?: TmdbJson.objectOrNull(fallback, "images")
        return MediaArtwork(
            poster = bestImage(primaryImages, "posters", languageFirst = true)
                ?: imageRef(field(primary, fallback) { TmdbJson.string(it, "poster_path") }),
            backdrop = bestImage(primaryImages, "backdrops", languageFirst = false)
                ?: imageRef(field(primary, fallback) { TmdbJson.string(it, "backdrop_path") }),
            logo = bestImage(primaryImages, "logos", languageFirst = true),
        )
    }

    fun titledLandscape(document: JSONObject, language: String): ImageRef? {
        val images = TmdbJson.objectOrNull(document, "images") ?: return null
        val entries = TmdbJson.arrayOrNull(images, "backdrops") ?: return null
        var best: Pair<Int, JSONObject>? = null
        TmdbJson.forEach(entries) { entry ->
            val path = TmdbJson.string(entry, "file_path") ?: return@forEach
            if (!TmdbJson.string(entry, "iso_639_1").equals(language, ignoreCase = true)) {
                return@forEach
            }
            best = pickBetter(best, 0 to entry, path)
        }
        return imageRef(best?.second?.let { TmdbJson.string(it, "file_path") })
    }

    private fun bestImage(images: JSONObject?, arrayKey: String, languageFirst: Boolean): ImageRef? {
        val entries = TmdbJson.arrayOrNull(images, arrayKey) ?: return null
        var best: Pair<Int, JSONObject>? = null
        TmdbJson.forEach(entries) { entry ->
            val path = TmdbJson.string(entry, "file_path") ?: return@forEach
            val language = TmdbJson.string(entry, "iso_639_1")
            val tier = languageTier(language, languageFirst)
            val candidate = tier to entry
            best = pickBetter(best, candidate, path)
        }
        val winner = best?.second ?: return null
        return imageRef(TmdbJson.string(winner, "file_path"))
    }

    private fun pickBetter(
        current: Pair<Int, JSONObject>?,
        candidate: Pair<Int, JSONObject>,
        candidatePath: String,
    ): Pair<Int, JSONObject> {
        if (current == null) return candidate
        if (candidate.first != current.first) return if (candidate.first < current.first) candidate else current
        val currentScore = TmdbJson.double(current.second, "vote_average") ?: 0.0
        val candidateScore = TmdbJson.double(candidate.second, "vote_average") ?: 0.0
        if (candidateScore != currentScore) return if (candidateScore > currentScore) candidate else current
        val currentVotes = TmdbJson.long(current.second, "vote_count") ?: 0L
        val candidateVotes = TmdbJson.long(candidate.second, "vote_count") ?: 0L
        if (candidateVotes != currentVotes) return if (candidateVotes > currentVotes) candidate else current
        val currentPath = TmdbJson.string(current.second, "file_path") ?: ""
        return if (candidatePath < currentPath) candidate else current
    }

    private fun languageTier(language: String?, languageFirst: Boolean): Int {
        val matches = language != null && language.equals(locale.language, ignoreCase = true)
        val neutral = language == null
        return when {
            languageFirst && matches -> 0
            languageFirst && neutral -> 1

            neutral -> 0
            matches -> 1
            else -> 2
        }
    }

    private fun imageRef(path: String?): ImageRef? =
        path?.trim()?.takeIf { it.isNotEmpty() }?.let { ImageRef(TmdbEndpoints.IMAGE_PROVIDER, it) }

    fun cast(primary: JSONObject, fallback: JSONObject?): List<CastMember> {
        val credits = TmdbJson.objectOrNull(primary, "aggregate_credits")
            ?: TmdbJson.objectOrNull(primary, "credits")
            ?: TmdbJson.objectOrNull(fallback, "aggregate_credits")
            ?: TmdbJson.objectOrNull(fallback, "credits")
            ?: return emptyList()
        val array = TmdbJson.arrayOrNull(credits, "cast") ?: return emptyList()
        return buildList {
            TmdbJson.forEach(array) { entry ->
                val name = TmdbJson.string(entry, "name") ?: return@forEach
                add(
                    CastMember(
                        name = name,
                        character = characterOf(entry),
                        id = personId(entry),
                        profile = imageRef(TmdbJson.string(entry, "profile_path")),
                        order = TmdbJson.int(entry, "order"),
                    ),
                )
            }
        }
    }

    private fun characterOf(entry: JSONObject): String? {
        TmdbJson.string(entry, "character")?.let { return it }
        val roles = TmdbJson.arrayOrNull(entry, "roles") ?: return null
        for (i in 0 until roles.length()) {
            val role = roles.optJSONObject(i) ?: continue
            TmdbJson.string(role, "character")?.let { return it }
        }
        return null
    }

    fun crew(primary: JSONObject, fallback: JSONObject?): List<CrewMember> {
        val credits = TmdbJson.objectOrNull(primary, "aggregate_credits")
            ?: TmdbJson.objectOrNull(primary, "credits")
            ?: TmdbJson.objectOrNull(fallback, "aggregate_credits")
            ?: TmdbJson.objectOrNull(fallback, "credits")
            ?: return emptyList()
        val array = TmdbJson.arrayOrNull(credits, "crew") ?: return emptyList()
        return buildList {
            TmdbJson.forEach(array) { entry ->
                val name = TmdbJson.string(entry, "name") ?: return@forEach
                add(
                    CrewMember(
                        name = name,
                        job = jobOf(entry),
                        department = TmdbJson.string(entry, "department"),
                        id = personId(entry),
                        profile = imageRef(TmdbJson.string(entry, "profile_path")),
                    ),
                )
            }
        }
    }

    private fun jobOf(entry: JSONObject): String? {
        TmdbJson.string(entry, "job")?.let { return it }
        val jobs = TmdbJson.arrayOrNull(entry, "jobs") ?: return null
        var first: String? = null
        for (i in 0 until jobs.length()) {
            val job = jobs.optJSONObject(i) ?: continue
            val name = TmdbJson.string(job, "job") ?: continue
            if (first == null) first = name
        }
        return first
    }

    fun creators(primary: JSONObject, fallback: JSONObject?): List<PersonRef> {
        val array = TmdbJson.arrayOrNull(primary, "created_by")
            ?: TmdbJson.arrayOrNull(fallback, "created_by")
            ?: return emptyList()
        return buildList {
            TmdbJson.forEach(array) { entry ->
                val name = TmdbJson.string(entry, "name") ?: return@forEach
                add(PersonRef(name = name, id = personId(entry), profile = imageRef(TmdbJson.string(entry, "profile_path"))))
            }
        }
    }

    private fun directorsFrom(crew: List<CrewMember>): List<PersonRef> =
        crew.filter { it.job?.equals("Director", ignoreCase = true) == true }
            .map { it.ref() }
            .distinctBy { it.id?.value ?: it.name }

    private fun personId(entry: JSONObject): PersonId? =
        TmdbJson.long(entry, "id")?.let { PersonId(TmdbEndpoints.IMAGE_PROVIDER, it.toString()) }

    fun genres(primary: JSONObject, fallback: JSONObject?): List<Genre> {
        val array = TmdbJson.arrayOrNull(primary, "genres")
            ?: TmdbJson.arrayOrNull(fallback, "genres")
            ?: return emptyList()
        return buildList {
            TmdbJson.forEach(array) { entry ->
                val name = TmdbJson.string(entry, "name") ?: return@forEach
                add(Genre(name = name, id = TmdbJson.long(entry, "id")))
            }
        }
    }

    private fun ratings(primary: JSONObject): List<MediaRating> {
        val average = TmdbJson.double(primary, "vote_average") ?: return emptyList()
        if (average <= 0.0) return emptyList()
        return listOf(
            MediaRating(
                source = TmdbEndpoints.IMAGE_PROVIDER,
                value = average,
                max = 10.0,
                votes = TmdbJson.int(primary, "vote_count")?.takeIf { it > 0 },
                label = "TMDB",
            ),
        )
    }

    fun movieCertification(primary: JSONObject): Certification? {
        val results = TmdbJson.arrayOrNull(TmdbJson.objectOrNull(primary, "release_dates"), "results")
            ?: return null
        val byRegion = LinkedHashMap<String, String>()
        TmdbJson.forEach(results) { country ->
            val region = TmdbJson.string(country, "iso_3166_1")?.uppercase() ?: return@forEach
            val dates = TmdbJson.arrayOrNull(country, "release_dates") ?: return@forEach
            TmdbJson.forEach(dates) { date ->
                val code = TmdbJson.string(date, "certification") ?: return@forEach
                byRegion.putIfAbsent(region, code)
            }
        }
        if (byRegion.isEmpty()) return null
        for (region in regionPreference()) {
            byRegion[region]?.let { return Certification(it, region) }
        }
        val first = byRegion.entries.first()
        return Certification(first.value, first.key)
    }

    fun showCertification(primary: JSONObject): Certification? {
        val results = TmdbJson.arrayOrNull(TmdbJson.objectOrNull(primary, "content_ratings"), "results")
            ?: return null
        val byRegion = LinkedHashMap<String, String>()
        TmdbJson.forEach(results) { country ->
            val region = TmdbJson.string(country, "iso_3166_1")?.uppercase() ?: return@forEach
            val rating = TmdbJson.string(country, "rating") ?: return@forEach
            byRegion.putIfAbsent(region, rating)
        }
        if (byRegion.isEmpty()) return null
        for (region in regionPreference()) {
            byRegion[region]?.let { return Certification(it, region) }
        }
        val first = byRegion.entries.first()
        return Certification(first.value, first.key)
    }

    fun regionPreference(): List<String> = buildList {
        locale.region?.takeIf { it.length == 2 }?.let { add(it.uppercase()) }
        add("US")
        add("GB")
    }.distinct()

    fun keywords(primary: JSONObject, container: String, arrayKey: String): List<String> {
        val array = TmdbJson.arrayOrNull(TmdbJson.objectOrNull(primary, "keywords"), arrayKey)
            ?: return emptyList()
        return buildList {
            TmdbJson.forEach(array) { entry ->
                TmdbJson.string(entry, "name")?.let { add(it) }
            }
        }.distinct()
    }

    fun companies(document: JSONObject, arrayKey: String): List<Company> {
        val array = TmdbJson.arrayOrNull(document, arrayKey) ?: return emptyList()
        return buildList {
            TmdbJson.forEach(array) { entry ->
                val name = TmdbJson.string(entry, "name") ?: return@forEach
                add(
                    Company(
                        name = name,
                        id = TmdbJson.long(entry, "id"),
                        logo = imageRef(TmdbJson.string(entry, "logo_path")),
                    ),
                )
            }
        }
    }

    fun seasons(primary: JSONObject): List<SeasonSummary> {
        val array = TmdbJson.arrayOrNull(primary, "seasons") ?: return emptyList()
        return buildList {
            TmdbJson.forEach(array) { entry ->
                val number = TmdbJson.int(entry, "season_number") ?: return@forEach
                add(
                    SeasonSummary(
                        seasonNumber = number,
                        name = TmdbJson.string(entry, "name"),
                        overview = TmdbJson.string(entry, "overview"),
                        airDate = parseDate(TmdbJson.string(entry, "air_date")),
                        episodeCount = TmdbJson.int(entry, "episode_count"),
                        poster = imageRef(TmdbJson.string(entry, "poster_path")),
                    ),
                )
            }
        }.sortedBy { it.seasonNumber }
    }

    fun season(identity: MediaIdentity, document: JSONObject, seasonNumber: Int): SeasonDetails {
        val episodes = TmdbJson.arrayOrNull(document, "episodes") ?: JSONArray()
        return SeasonDetails(
            seasonNumber = seasonNumber,
            name = TmdbJson.string(document, "name"),
            overview = TmdbJson.string(document, "overview"),
            poster = imageRef(TmdbJson.string(document, "poster_path")),
            episodes = buildList {
                TmdbJson.forEach(episodes) { entry ->
                    val number = TmdbJson.int(entry, "episode_number") ?: return@forEach
                    val average = TmdbJson.double(entry, "vote_average")
                    add(
                        EpisodeDetails(

                            identity = identity,
                            seasonNumber = seasonNumber,
                            episodeNumber = number,
                            name = TmdbJson.string(entry, "name"),
                            overview = TmdbJson.string(entry, "overview"),
                            airDate = parseDate(TmdbJson.string(entry, "air_date")),
                            runtimeMinutes = TmdbJson.int(entry, "runtime")?.takeIf { it > 0 },
                            still = imageRef(TmdbJson.string(entry, "still_path")),
                            rating = average?.takeIf { it > 0.0 }?.let {
                                MediaRating(
                                    source = TmdbEndpoints.IMAGE_PROVIDER,
                                    value = it,
                                    max = 10.0,
                                    votes = TmdbJson.int(entry, "vote_count")?.takeIf { votes -> votes > 0 },
                                    label = "TMDB",
                                )
                            },
                            externalIds = ExternalIds(),
                        ),
                    )
                }
            }.sortedBy { it.episodeNumber },
        )
    }

    fun collection(collectionId: Long, document: JSONObject): CollectionDetails? {

        val name = TmdbJson.string(document, "name") ?: return null
        return CollectionDetails(
            id = TmdbJson.long(document, "id") ?: collectionId,
            name = name,
            overview = TmdbJson.string(document, "overview"),
            poster = imageRef(TmdbJson.string(document, "poster_path")),
            backdrop = imageRef(TmdbJson.string(document, "backdrop_path")),
            members = cards(TmdbJson.arrayOrNull(document, "parts"), MediaType.MOVIE),
        )
    }

    fun person(personId: PersonId, document: JSONObject): PersonDetails? {
        val name = TmdbJson.string(document, "name") ?: return null
        val credits = TmdbJson.objectOrNull(document, "combined_credits")
        val castCredits = TmdbJson.arrayOrNull(credits, "cast") ?: JSONArray()
        val crewCredits = TmdbJson.arrayOrNull(credits, "crew") ?: JSONArray()
        val mapped = buildList {
            TmdbJson.forEach(castCredits) { entry ->
                card(entry, mediaTypeOf(entry))?.let { (type, card) ->
                    add(
                        PersonCredit(
                            card = card,
                            mediaType = type,
                            character = TmdbJson.string(entry, "character"),
                            releaseDate = entryDate(entry),
                        ),
                    )
                }
            }
            TmdbJson.forEach(crewCredits) { entry ->
                card(entry, mediaTypeOf(entry))?.let { (type, card) ->
                    add(
                        PersonCredit(
                            card = card,
                            mediaType = type,
                            job = TmdbJson.string(entry, "job"),
                            releaseDate = entryDate(entry),
                        ),
                    )
                }
            }
        }
        return PersonDetails(
            id = personId,
            name = name,
            biography = TmdbJson.string(document, "biography"),
            birthday = parseDate(TmdbJson.string(document, "birthday")),
            deathday = parseDate(TmdbJson.string(document, "deathday")),
            placeOfBirth = TmdbJson.string(document, "place_of_birth"),
            knownForDepartment = TmdbJson.string(document, "known_for_department"),
            profile = imageRef(TmdbJson.string(document, "profile_path")),

            credits = mapped.distinctBy { it.card.identity.cacheKey() ?: it.card.title },
        )
    }

    fun cardPage(document: JSONObject, type: MediaType, page: Int): MediaCardPage {
        val results = TmdbJson.arrayOrNull(document, "results") ?: JSONArray()
        return MediaCardPage(
            items = cards(results, type),
            page = TmdbJson.int(document, "page") ?: page,
            totalPages = TmdbJson.int(document, "total_pages")?.coerceAtLeast(1) ?: 1,
            totalResults = TmdbJson.int(document, "total_results"),
        )
    }

    fun reviewPage(document: JSONObject, page: Int): ReviewPage {
        val results = TmdbJson.arrayOrNull(document, "results") ?: JSONArray()
        return ReviewPage(
            items = reviews(results),
            page = TmdbJson.int(document, "page") ?: page,
            totalPages = TmdbJson.int(document, "total_pages")?.coerceAtLeast(1) ?: 1,
            totalResults = TmdbJson.int(document, "total_results"),
        )
    }

    fun reviews(results: JSONArray?): List<MediaReview> = buildList {
        TmdbJson.forEach(results) { entry ->
            val reviewId = TmdbJson.string(entry, "id") ?: return@forEach
            val author = TmdbJson.string(entry, "author") ?: return@forEach
            val details = TmdbJson.objectOrNull(entry, "author_details")
            add(
                MediaReview(
                    reviewId = reviewId,
                    author = author,
                    authorRating = TmdbJson.double(details, "rating")?.takeIf { it > 0.0 },
                    authorAvatar = avatarRef(TmdbJson.string(details, "avatar_path")),
                    content = TmdbJson.string(entry, "content").orEmpty(),
                    createdAt = parseInstant(TmdbJson.string(entry, "created_at")),
                    providerLabel = PROVIDER_LABEL,
                ),
            )
        }
    }.distinctBy { it.reviewId }

    private fun avatarRef(raw: String?): ImageRef? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (value.contains("://")) return null
        return imageRef(value)
    }

    private fun parseInstant(raw: String?): Instant? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { Instant.parse(value) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(value).toInstant(java.time.ZoneOffset.UTC) }.getOrNull()
    }

    fun cards(results: JSONArray?, type: MediaType): List<MediaCard> = buildList {
        TmdbJson.forEach(results) { entry ->
            card(entry, type)?.let { add(it.second) }
        }
    }.distinctBy { it.identity.cacheKey() ?: it.title }

    private fun card(entry: JSONObject, type: MediaType): Pair<MediaType, MediaCard>? {
        val tmdbId = TmdbJson.long(entry, "id") ?: return null
        val title = TmdbJson.string(entry, "title")
            ?: TmdbJson.string(entry, "name")
            ?: return null
        val date = entryDate(entry)
        val identity = MediaIdentity(
            type = type,
            externalIds = ExternalIds(tmdbId = tmdbId),
        )
        val preview = MediaPreview(
            titleId = null,
            kind = if (type == MediaType.MOVIE) TitleKind.MOVIE else TitleKind.SERIES,
            displayTitle = title,
            year = date?.year,
            subtitle = null,

            artwork = imageRef(TmdbJson.string(entry, "poster_path"))
                ?.let {
                    MediaArtworkRef.metadata(
                        provider = it.provider,
                        path = it.path,
                        policy = ArtworkPolicy.POSTER,
                        landscapePath = imageRef(TmdbJson.string(entry, "backdrop_path"))?.path,
                    )
                },
            progressFraction = null,
        )
        return type to MediaCard(identity = identity, preview = preview)
    }

    private fun mediaTypeOf(entry: JSONObject): MediaType {
        val raw = TmdbJson.string(entry, "media_type")
        return if (raw?.equals("tv", ignoreCase = true) == true) MediaType.SHOW else MediaType.MOVIE
    }

    private fun entryDate(entry: JSONObject): LocalDate? =
        parseDate(TmdbJson.string(entry, "release_date"))
            ?: parseDate(TmdbJson.string(entry, "first_air_date"))

    private inline fun <T> field(
        primary: JSONObject,
        fallback: JSONObject?,
        read: (JSONObject) -> T?,
    ): T? = read(primary) ?: fallback?.let(read)

    private fun parseDate(raw: String?): LocalDate? {
        val value = raw?.trim()?.takeIf { it.length >= 10 } ?: return null
        return try {
            LocalDate.parse(value.substring(0, 10))
        } catch (_: java.time.format.DateTimeParseException) {
            null
        }
    }

    private fun parseYear(raw: String?): Int? =
        raw?.trim()?.take(4)?.toIntOrNull()?.takeIf { it in 1800..2200 }

    private companion object {

        val YOUTUBE_KEY = Regex("^[A-Za-z0-9_-]{11}$")

        const val PROVIDER_LABEL = "TMDB"
    }
}
