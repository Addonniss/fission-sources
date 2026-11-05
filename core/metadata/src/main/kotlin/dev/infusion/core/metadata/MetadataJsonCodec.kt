package dev.infusion.core.metadata

import dev.infusion.core.domain.metadata.MetadataCodec
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
import dev.infusion.core.model.SourceId
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TitleKind
import dev.infusion.core.model.MediaPreview
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class MetadataJsonCodec : MetadataCodec {

    override fun encodeDetails(details: MediaDetails): String = when (details) {
        is MovieDetails -> encodeCommon(
            kind = "movie",
            document = common(details).apply {
                details.collection?.let { collection ->
                    put(
                        "collection",
                        JSONObject()
                            .put("id", collection.id)
                            .put("name", collection.name)
                            .put("poster", image(collection.poster))
                            .put("backdrop", image(collection.backdrop)),
                    )
                }
            },
        )
        is ShowDetails -> encodeCommon(
            kind = "show",
            document = common(details).apply {
                put("lastAirDate", details.lastAirDate?.toString().orNull())
                put("numberOfSeasons", details.numberOfSeasons)
                put("numberOfEpisodes", details.numberOfEpisodes)
                put("inProduction", details.inProduction)
                put(
                    "seasons",
                    JSONArray().apply {
                        details.seasons.forEach { season ->
                            put(
                                JSONObject()
                                    .put("seasonNumber", season.seasonNumber)
                                    .put("name", season.name.orNull())
                                    .put("overview", season.overview.orNull())
                                    .put("airDate", season.airDate?.toString().orNull())
                                    .put("episodeCount", season.episodeCount)
                                    .put("poster", image(season.poster)),
                            )
                        }
                    },
                )
            },
        )
    }

    override fun decodeDetails(payload: String): MediaDetails? = decodeDocument(payload) { document ->
        when (document.optString("kind")) {
            "movie" -> MovieDetails(
                identity = decodeIdentity(document.optJSONObject("identity")) ?: return@decodeDocument null,
                title = document.optString("title").takeIf { it.isNotEmpty() } ?: return@decodeDocument null,
                originalTitle = document.stringOrNull("originalTitle"),
                overview = document.stringOrNull("overview"),
                tagline = document.stringOrNull("tagline"),
                year = document.intOrNull("year"),
                genres = decodeGenres(document.optJSONArray("genres")),
                artwork = decodeArtwork(document.optJSONObject("artwork")),
                ratings = decodeRatings(document.optJSONArray("ratings")),
                certification = decodeCertification(document.optJSONObject("certification")),
                runtimeMinutes = document.intOrNull("runtimeMinutes"),
                videos = decodeVideos(document.optJSONArray("videos")),
                cast = decodeCast(document.optJSONArray("cast")),
                crew = decodeCrew(document.optJSONArray("crew")),
                directors = decodePersonRefs(document.optJSONArray("directors")),
                creators = decodePersonRefs(document.optJSONArray("creators")),
                studios = decodeCompanies(document.optJSONArray("studios")),
                networks = decodeCompanies(document.optJSONArray("networks")),
                keywords = decodeStrings(document.optJSONArray("keywords")),
                originalLanguage = document.stringOrNull("originalLanguage"),
                originCountries = decodeStrings(document.optJSONArray("originCountries")),
                status = document.stringOrNull("status"),
                releaseDate = decodeDate(document.stringOrNull("releaseDate")),
                collection = document.optJSONObject("collection")?.let { collection ->
                    val name = collection.stringOrNull("name") ?: return@let null
                    MovieCollectionSummary(
                        id = collection.longOrNull("id"),
                        name = name,
                        poster = decodeImage(collection.optJSONObject("poster")),
                        backdrop = decodeImage(collection.optJSONObject("backdrop")),
                    )
                },
            )

            "show" -> ShowDetails(
                identity = decodeIdentity(document.optJSONObject("identity")) ?: return@decodeDocument null,
                title = document.optString("title").takeIf { it.isNotEmpty() } ?: return@decodeDocument null,
                originalTitle = document.stringOrNull("originalTitle"),
                overview = document.stringOrNull("overview"),
                tagline = document.stringOrNull("tagline"),
                year = document.intOrNull("year"),
                genres = decodeGenres(document.optJSONArray("genres")),
                artwork = decodeArtwork(document.optJSONObject("artwork")),
                ratings = decodeRatings(document.optJSONArray("ratings")),
                certification = decodeCertification(document.optJSONObject("certification")),
                runtimeMinutes = document.intOrNull("runtimeMinutes"),
                videos = decodeVideos(document.optJSONArray("videos")),
                cast = decodeCast(document.optJSONArray("cast")),
                crew = decodeCrew(document.optJSONArray("crew")),
                directors = decodePersonRefs(document.optJSONArray("directors")),
                creators = decodePersonRefs(document.optJSONArray("creators")),
                studios = decodeCompanies(document.optJSONArray("studios")),
                networks = decodeCompanies(document.optJSONArray("networks")),
                keywords = decodeStrings(document.optJSONArray("keywords")),
                originalLanguage = document.stringOrNull("originalLanguage"),
                originCountries = decodeStrings(document.optJSONArray("originCountries")),
                status = document.stringOrNull("status"),
                releaseDate = decodeDate(document.stringOrNull("releaseDate")),
                seasons = decodeSeasons(document.optJSONArray("seasons")),
                lastAirDate = decodeDate(document.stringOrNull("lastAirDate")),
                numberOfSeasons = document.intOrNull("numberOfSeasons"),
                numberOfEpisodes = document.intOrNull("numberOfEpisodes"),
                inProduction = document.boolOrNull("inProduction"),
            )

            else -> null
        }
    }

    override fun encodeSeason(season: SeasonDetails): String = JSONObject()
        .put("seasonNumber", season.seasonNumber)
        .put("name", season.name.orNull())
        .put("overview", season.overview.orNull())
        .put("poster", image(season.poster))
        .put(
            "episodes",
            JSONArray().apply {
                season.episodes.forEach { episode ->
                    put(
                        JSONObject()
                            .put("identity", encodeIdentity(episode.identity))
                            .put("seasonNumber", episode.seasonNumber)
                            .put("episodeNumber", episode.episodeNumber)
                            .put("name", episode.name.orNull())
                            .put("overview", episode.overview.orNull())
                            .put("airDate", episode.airDate?.toString().orNull())
                            .put("runtimeMinutes", episode.runtimeMinutes)
                            .put("still", image(episode.still))
                            .put("rating", episode.rating?.let(::encodeRating))
                            .put("ratings", JSONArray().apply { episode.ratings.forEach { put(encodeRating(it)) } })
                            .put("externalIds", encodeExternalIds(episode.externalIds)),
                    )
                }
            },
        )
        .toString()

    override fun decodeSeason(payload: String): SeasonDetails? = decodeDocument(payload) { document ->
        val seasonNumber = document.intOrNull("seasonNumber") ?: return@decodeDocument null
        SeasonDetails(
            seasonNumber = seasonNumber,
            name = document.stringOrNull("name"),
            overview = document.stringOrNull("overview"),
            poster = decodeImage(document.optJSONObject("poster")),
            episodes = buildList {
                val episodes = document.optJSONArray("episodes") ?: return@buildList
                for (i in 0 until episodes.length()) {
                    val entry = episodes.optJSONObject(i) ?: continue
                    val identity = decodeIdentity(entry.optJSONObject("identity")) ?: continue
                    val number = entry.intOrNull("episodeNumber") ?: continue
                    add(
                        EpisodeDetails(
                            identity = identity,
                            seasonNumber = entry.intOrNull("seasonNumber") ?: seasonNumber,
                            episodeNumber = number,
                            name = entry.stringOrNull("name"),
                            overview = entry.stringOrNull("overview"),
                            airDate = decodeDate(entry.stringOrNull("airDate")),
                            runtimeMinutes = entry.intOrNull("runtimeMinutes"),
                            still = decodeImage(entry.optJSONObject("still")),
                            rating = entry.optJSONObject("rating")?.let(::decodeRating),
                            ratings = decodeRatings(entry.optJSONArray("ratings")),
                            externalIds = decodeExternalIds(entry.optJSONObject("externalIds")),
                        ),
                    )
                }
            },
        )
    }

    override fun encodeCollection(collection: CollectionDetails): String = JSONObject()
        .put("id", collection.id)
        .put("name", collection.name)
        .put("overview", collection.overview.orNull())
        .put("poster", image(collection.poster))
        .put("backdrop", image(collection.backdrop))
        .put("members", JSONArray().apply { collection.members.forEach { put(encodeCard(it)) } })
        .toString()

    override fun decodeCollection(payload: String): CollectionDetails? = decodeDocument(payload) { document ->
        val name = document.stringOrNull("name") ?: return@decodeDocument null
        CollectionDetails(
            id = document.longOrNull("id"),
            name = name,
            overview = document.stringOrNull("overview"),
            poster = decodeImage(document.optJSONObject("poster")),
            backdrop = decodeImage(document.optJSONObject("backdrop")),
            members = decodeCards(document.optJSONArray("members")),
        )
    }

    override fun encodePerson(person: PersonDetails): String = JSONObject()
        .put("id", JSONObject().put("authority", person.id.authority).put("value", person.id.value))
        .put("name", person.name)
        .put("biography", person.biography.orNull())
        .put("birthday", person.birthday?.toString().orNull())
        .put("deathday", person.deathday?.toString().orNull())
        .put("placeOfBirth", person.placeOfBirth.orNull())
        .put("knownForDepartment", person.knownForDepartment.orNull())
        .put("profile", image(person.profile))
        .put(
            "credits",
            JSONArray().apply {
                person.credits.forEach { credit ->
                    put(
                        JSONObject()
                            .put("card", encodeCard(credit.card))
                            .put("mediaType", credit.mediaType.name)
                            .put("character", credit.character.orNull())
                            .put("job", credit.job.orNull())
                            .put("releaseDate", credit.releaseDate?.toString().orNull()),
                    )
                }
            },
        )
        .toString()

    override fun decodePerson(payload: String): PersonDetails? = decodeDocument(payload) { document ->
        val idDocument = document.optJSONObject("id") ?: return@decodeDocument null
        val authority = idDocument.stringOrNull("authority") ?: return@decodeDocument null
        val value = idDocument.stringOrNull("value") ?: return@decodeDocument null
        val name = document.stringOrNull("name") ?: return@decodeDocument null
        PersonDetails(
            id = PersonId(authority, value),
            name = name,
            biography = document.stringOrNull("biography"),
            birthday = decodeDate(document.stringOrNull("birthday")),
            deathday = decodeDate(document.stringOrNull("deathday")),
            placeOfBirth = document.stringOrNull("placeOfBirth"),
            knownForDepartment = document.stringOrNull("knownForDepartment"),
            profile = decodeImage(document.optJSONObject("profile")),
            credits = buildList {
                val credits = document.optJSONArray("credits") ?: return@buildList
                for (i in 0 until credits.length()) {
                    val entry = credits.optJSONObject(i) ?: continue
                    val card = decodeCard(entry.optJSONObject("card")) ?: continue
                    val mediaType = entry.stringOrNull("mediaType")?.let { name ->
                        runCatching { MediaType.valueOf(name) }.getOrNull()
                    } ?: continue
                    add(
                        PersonCredit(
                            card = card,
                            mediaType = mediaType,
                            character = entry.stringOrNull("character"),
                            job = entry.stringOrNull("job"),
                            releaseDate = decodeDate(entry.stringOrNull("releaseDate")),
                        ),
                    )
                }
            },
        )
    }

    override fun encodeCardPage(page: MediaCardPage): String = JSONObject()
        .put("page", page.page)
        .put("totalPages", page.totalPages)
        .put("totalResults", page.totalResults)
        .put("items", JSONArray().apply { page.items.forEach { put(encodeCard(it)) } })
        .toString()

    override fun decodeCardPage(payload: String): MediaCardPage? = decodeDocument(payload) { document ->
        MediaCardPage(
            items = decodeCards(document.optJSONArray("items")),
            page = document.intOrNull("page") ?: 1,
            totalPages = document.intOrNull("totalPages")?.coerceAtLeast(1) ?: 1,
            totalResults = document.intOrNull("totalResults"),
        )
    }

    override fun encodeReviewPage(page: ReviewPage): String = JSONObject()
        .put("page", page.page)
        .put("totalPages", page.totalPages)
        .put("totalResults", page.totalResults)
        .put("items", JSONArray().apply { page.items.forEach { put(encodeReview(it)) } })
        .toString()

    override fun decodeReviewPage(payload: String): ReviewPage? = decodeDocument(payload) { document ->
        ReviewPage(
            items = decodeReviews(document.optJSONArray("items")),
            page = document.intOrNull("page") ?: 1,
            totalPages = document.intOrNull("totalPages")?.coerceAtLeast(1) ?: 1,
            totalResults = document.intOrNull("totalResults"),
        )
    }

    override fun encodeLanguageArtwork(image: ImageRef): String = JSONObject()
        .put("image", image(image))
        .toString()

    override fun decodeLanguageArtwork(payload: String): ImageRef? = decodeDocument(payload) { document ->
        decodeImage(document.optJSONObject("image"))
    }

    private fun common(details: MediaDetails): JSONObject = JSONObject()
        .put("identity", encodeIdentity(details.identity))
        .put("title", details.title)
        .put("originalTitle", details.originalTitle.orNull())
        .put("overview", details.overview.orNull())
        .put("tagline", details.tagline.orNull())
        .put("year", details.year)
        .put("genres", JSONArray().apply {
            details.genres.forEach { put(JSONObject().put("name", it.name).put("id", it.id)) }
        })
        .put("artwork", encodeArtwork(details.artwork))
        .put("ratings", JSONArray().apply { details.ratings.forEach { put(encodeRating(it)) } })
        .put("certification", details.certification?.let {
            JSONObject().put("code", it.code).put("region", it.region.orNull())
        })
        .put("runtimeMinutes", details.runtimeMinutes)
        .put("videos", JSONArray().apply { details.videos.forEach { put(encodeVideo(it)) } })
        .put("cast", JSONArray().apply { details.cast.forEach { put(encodeCast(it)) } })
        .put("crew", JSONArray().apply { details.crew.forEach { put(encodeCrew(it)) } })
        .put("directors", JSONArray().apply { details.directors.forEach { put(encodePersonRef(it)) } })
        .put("creators", JSONArray().apply { details.creators.forEach { put(encodePersonRef(it)) } })
        .put("studios", JSONArray().apply { details.studios.forEach { put(encodeCompany(it)) } })
        .put("networks", JSONArray().apply { details.networks.forEach { put(encodeCompany(it)) } })
        .put("keywords", JSONArray().apply { details.keywords.forEach { put(it) } })
        .put("originalLanguage", details.originalLanguage.orNull())
        .put("originCountries", JSONArray().apply { details.originCountries.forEach { put(it) } })
        .put("status", details.status.orNull())
        .put("releaseDate", details.releaseDate?.toString().orNull())

    private fun encodeCommon(kind: String, document: JSONObject): String =
        document.put("kind", kind).toString()

    private fun encodeIdentity(identity: MediaIdentity): JSONObject = JSONObject()
        .put("type", identity.type.name)
        .put("titleId", identity.titleId?.value?.toString().orNull())
        .put("externalIds", encodeExternalIds(identity.externalIds))
        .put(
            "sourceIds",
            JSONObject().apply {
                identity.sourceIds.entries
                    .sortedBy { it.key.value.toString() }
                    .forEach { (sourceId, native) -> put(sourceId.value.toString(), native) }
            },
        )

    private fun decodeIdentity(document: JSONObject?): MediaIdentity? {
        if (document == null) return null
        val type = document.stringOrNull("type")?.let { raw ->
            runCatching { MediaType.valueOf(raw) }.getOrNull()
        } ?: return null
        val titleId = document.stringOrNull("titleId")?.let { raw ->
            runCatching { TitleId(UUID.fromString(raw)) }.getOrNull()
        }
        val sourceIds = LinkedHashMap<SourceId, String>()
        document.optJSONObject("sourceIds")?.let { sources ->
            sources.keys().forEach { key ->
                val native = sources.optString(key).takeIf { it.isNotEmpty() } ?: return@forEach
                runCatching { SourceId(UUID.fromString(key)) }.getOrNull()?.let { sourceIds[it] = native }
            }
        }
        return MediaIdentity(
            type = type,
            titleId = titleId,
            externalIds = decodeExternalIds(document.optJSONObject("externalIds")),
            sourceIds = sourceIds,
        )
    }

    private fun encodeExternalIds(ids: ExternalIds): JSONObject = JSONObject()
        .put("tmdbId", ids.tmdbId)
        .put("imdbId", ids.imdbId.orNull())
        .put("tvdbId", ids.tvdbId)

    private fun decodeExternalIds(document: JSONObject?): ExternalIds {
        if (document == null) return ExternalIds()
        return ExternalIds(
            tmdbId = document.longOrNull("tmdbId"),
            imdbId = document.stringOrNull("imdbId"),
            tvdbId = document.longOrNull("tvdbId"),
        )
    }

    private fun image(ref: ImageRef?): Any? = ref?.let {
        JSONObject().put("provider", it.provider).put("path", it.path)
    }

    private fun decodeImage(document: JSONObject?): ImageRef? {
        if (document == null) return null
        val provider = document.stringOrNull("provider") ?: return null
        val path = document.stringOrNull("path") ?: return null
        return runCatching { ImageRef(provider, path) }.getOrNull()
    }

    private fun encodeArtwork(artwork: MediaArtwork): JSONObject = JSONObject()
        .put("poster", image(artwork.poster))
        .put("backdrop", image(artwork.backdrop))
        .put("logo", image(artwork.logo))
        .put("still", image(artwork.still))

    private fun decodeArtwork(document: JSONObject?): MediaArtwork {
        if (document == null) return MediaArtwork.None
        return MediaArtwork(
            poster = decodeImage(document.optJSONObject("poster")),
            backdrop = decodeImage(document.optJSONObject("backdrop")),
            logo = decodeImage(document.optJSONObject("logo")),
            still = decodeImage(document.optJSONObject("still")),
        )
    }

    private fun encodeRating(rating: MediaRating): JSONObject = JSONObject()
        .put("source", rating.source)
        .put("value", rating.value)
        .put("max", rating.max)
        .put("votes", rating.votes)
        .put("label", rating.label)

        .put("supplier", rating.supplier.orNull())

    private fun decodeRating(document: JSONObject): MediaRating? {
        val source = document.stringOrNull("source") ?: return null
        val value = document.doubleOrNull("value") ?: return null
        val max = document.doubleOrNull("max") ?: 10.0
        if (max <= 0.0) return null
        return runCatching {
            MediaRating(
                source = source,
                value = value,
                max = max,
                votes = document.intOrNull("votes"),
                label = document.stringOrNull("label") ?: source.uppercase(),
                supplier = document.stringOrNull("supplier"),
            )
        }.getOrNull()
    }

    private fun decodeRatings(array: JSONArray?): List<MediaRating> =
        mapArray(array) { decodeRating(it) }

    private fun encodeVideo(video: MediaVideo): JSONObject = JSONObject()
        .put("site", video.site)
        .put("key", video.key)
        .put("kind", video.kind.name)
        .put("title", video.title.orNull())
        .put("language", video.language.orNull())
        .put("official", video.official)
        .put("publishedAt", video.publishedAt.orNull())

    private fun decodeVideos(array: JSONArray?): List<MediaVideo> = mapArray(array) { document ->
        val site = document.stringOrNull("site") ?: return@mapArray null
        val key = document.stringOrNull("key") ?: return@mapArray null
        val kind = document.stringOrNull("kind")?.let { raw ->
            runCatching { VideoKind.valueOf(raw) }.getOrNull()
        } ?: VideoKind.OTHER
        MediaVideo(
            site = site,
            key = key,
            kind = kind,
            title = document.stringOrNull("title"),
            language = document.stringOrNull("language"),
            official = document.optBoolean("official", false),
            publishedAt = document.stringOrNull("publishedAt"),
        )
    }

    private fun encodePersonRef(ref: PersonRef): JSONObject = JSONObject()
        .put("name", ref.name)
        .put("id", ref.id?.let { JSONObject().put("authority", it.authority).put("value", it.value) })
        .put("profile", image(ref.profile))

    private fun decodePersonRef(document: JSONObject): PersonRef? {
        val name = document.stringOrNull("name") ?: return null
        return PersonRef(
            name = name,
            id = decodePersonId(document.optJSONObject("id")),
            profile = decodeImage(document.optJSONObject("profile")),
        )
    }

    private fun decodePersonId(document: JSONObject?): PersonId? {
        if (document == null) return null
        val authority = document.stringOrNull("authority") ?: return null
        val value = document.stringOrNull("value") ?: return null
        return runCatching { PersonId(authority, value) }.getOrNull()
    }

    private fun decodePersonRefs(array: JSONArray?): List<PersonRef> =
        mapArray(array) { decodePersonRef(it) }

    private fun encodeCast(member: CastMember): JSONObject = JSONObject()
        .put("name", member.name)
        .put("character", member.character.orNull())
        .put("id", member.id?.let { JSONObject().put("authority", it.authority).put("value", it.value) })
        .put("profile", image(member.profile))
        .put("order", member.order)

    private fun decodeCast(array: JSONArray?): List<CastMember> = mapArray(array) { document ->
        val name = document.stringOrNull("name") ?: return@mapArray null
        CastMember(
            name = name,
            character = document.stringOrNull("character"),
            id = decodePersonId(document.optJSONObject("id")),
            profile = decodeImage(document.optJSONObject("profile")),
            order = document.intOrNull("order"),
        )
    }

    private fun encodeCrew(member: CrewMember): JSONObject = JSONObject()
        .put("name", member.name)
        .put("job", member.job.orNull())
        .put("department", member.department.orNull())
        .put("id", member.id?.let { JSONObject().put("authority", it.authority).put("value", it.value) })
        .put("profile", image(member.profile))

    private fun decodeCrew(array: JSONArray?): List<CrewMember> = mapArray(array) { document ->
        val name = document.stringOrNull("name") ?: return@mapArray null
        CrewMember(
            name = name,
            job = document.stringOrNull("job"),
            department = document.stringOrNull("department"),
            id = decodePersonId(document.optJSONObject("id")),
            profile = decodeImage(document.optJSONObject("profile")),
        )
    }

    private fun encodeCompany(company: Company): JSONObject = JSONObject()
        .put("name", company.name)
        .put("id", company.id)
        .put("logo", image(company.logo))

    private fun decodeCompanies(array: JSONArray?): List<Company> = mapArray(array) { document ->
        val name = document.stringOrNull("name") ?: return@mapArray null
        Company(name = name, id = document.longOrNull("id"), logo = decodeImage(document.optJSONObject("logo")))
    }

    private fun decodeGenres(array: JSONArray?): List<Genre> = mapArray(array) { document ->
        val name = document.stringOrNull("name") ?: return@mapArray null
        Genre(name = name, id = document.longOrNull("id"))
    }

    private fun decodeCertification(document: JSONObject?): Certification? {
        if (document == null) return null
        val code = document.stringOrNull("code") ?: return null
        return runCatching { Certification(code, document.stringOrNull("region")) }.getOrNull()
    }

    private fun decodeSeasons(array: JSONArray?): List<SeasonSummary> = mapArray(array) { document ->
        val number = document.intOrNull("seasonNumber") ?: return@mapArray null
        SeasonSummary(
            seasonNumber = number,
            name = document.stringOrNull("name"),
            overview = document.stringOrNull("overview"),
            airDate = decodeDate(document.stringOrNull("airDate")),
            episodeCount = document.intOrNull("episodeCount"),
            poster = decodeImage(document.optJSONObject("poster")),
        )
    }

    private fun encodeCard(card: MediaCard): JSONObject = JSONObject()
        .put("identity", encodeIdentity(card.identity))
        .put(
            "preview",
            JSONObject()
                .put("titleId", card.preview.titleId?.value?.toString().orNull())
                .put("kind", card.preview.kind?.name.orNull())
                .put("displayTitle", card.preview.displayTitle)
                .put("year", card.preview.year)
                .put("subtitle", card.preview.subtitle.orNull())
                .put(
                    "artwork",
                    card.preview.artwork?.let {
                        JSONObject()
                            .put("locator", it.locator)
                            .put("policy", it.policy.name)

                            .put("landscapeLocator", it.landscapeLocator.orNull())
                    },
                )
                .put("progressFraction", card.preview.progressFraction)

                .put("airDate", card.preview.airDate?.toString().orNull()),
        )

    private fun decodeCard(document: JSONObject?): MediaCard? {
        if (document == null) return null
        val identity = decodeIdentity(document.optJSONObject("identity")) ?: return null
        val preview = document.optJSONObject("preview") ?: return null
        val displayTitle = preview.stringOrNull("displayTitle") ?: return null
        val kind = preview.stringOrNull("kind")?.let { raw ->
            runCatching { TitleKind.valueOf(raw) }.getOrNull()
        }
        val artwork = preview.optJSONObject("artwork")?.let { art ->
            val locator = art.stringOrNull("locator") ?: return@let null
            val policy = art.stringOrNull("policy")?.let { raw ->
                runCatching { dev.infusion.core.model.ArtworkPolicy.valueOf(raw) }.getOrNull()
            } ?: return@let null

            runCatching {
                dev.infusion.core.model.MediaArtworkRef(
                    locator = locator,
                    policy = policy,
                    landscapeLocator = art.stringOrNull("landscapeLocator"),
                )
            }.getOrNull()
        }
        return MediaCard(
            identity = identity,
            preview = MediaPreview(
                titleId = preview.stringOrNull("titleId")?.let { raw ->
                    runCatching { TitleId(UUID.fromString(raw)) }.getOrNull()
                },
                kind = kind,
                displayTitle = displayTitle,
                year = preview.intOrNull("year"),
                subtitle = preview.stringOrNull("subtitle"),
                artwork = artwork,
                progressFraction = preview.doubleOrNull("progressFraction"),
                airDate = decodeDate(preview.stringOrNull("airDate")),
            ),
        )
    }

    private fun decodeCards(array: JSONArray?): List<MediaCard> = mapArray(array) { decodeCard(it) }

    private fun encodeReview(review: MediaReview): JSONObject = JSONObject()
        .put("reviewId", review.reviewId)
        .put("author", review.author)
        .put("authorRating", review.authorRating)
        .put("authorAvatar", image(review.authorAvatar))
        .put("content", review.content)

        .put("createdAt", review.createdAt?.toString().orNull())
        .put("providerLabel", review.providerLabel.orNull())

    private fun decodeReview(document: JSONObject): MediaReview? {
        val reviewId = document.stringOrNull("reviewId") ?: return null
        val author = document.stringOrNull("author") ?: return null
        return runCatching {
            MediaReview(
                reviewId = reviewId,
                author = author,
                authorRating = document.doubleOrNull("authorRating"),
                authorAvatar = decodeImage(document.optJSONObject("authorAvatar")),
                content = document.optString("content"),
                createdAt = document.stringOrNull("createdAt")?.let { raw ->
                    runCatching { Instant.parse(raw) }.getOrNull()
                },
                providerLabel = document.stringOrNull("providerLabel"),
            )
        }.getOrNull()
    }

    private fun decodeReviews(array: JSONArray?): List<MediaReview> = mapArray(array) { decodeReview(it) }

    private fun decodeStrings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                array.optString(i).takeIf { it.isNotEmpty() }?.let { add(it) }
            }
        }
    }

    private fun <T> decodeDocument(payload: String, decode: (JSONObject) -> T?): T? {
        val document = try {
            JSONObject(payload)
        } catch (_: org.json.JSONException) {
            return null
        }
        return try {
            decode(document)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: org.json.JSONException) {
            null
        }
    }

    private fun <T : Any> mapArray(array: JSONArray?, map: (JSONObject) -> T?): List<T> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val entry = array.optJSONObject(i) ?: continue
                map(entry)?.let { add(it) }
            }
        }
    }

    private fun decodeDate(raw: String?): LocalDate? =
        raw?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}

private fun String?.orNull(): Any? = this ?: JSONObject.NULL

private fun JSONObject.stringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

private fun JSONObject.intOrNull(key: String): Int? = if (!has(key) || isNull(key)) null else optInt(key)

private fun JSONObject.longOrNull(key: String): Long? = if (!has(key) || isNull(key)) null else optLong(key)

private fun JSONObject.doubleOrNull(key: String): Double? =
    if (!has(key) || isNull(key)) null else optDouble(key).takeIf { it.isFinite() }

private fun JSONObject.boolOrNull(key: String): Boolean? = if (!has(key) || isNull(key)) null else optBoolean(key)
