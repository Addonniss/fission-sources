package dev.infusion.feature.vertical.media

import dev.infusion.core.domain.metadata.MetadataLocale
import dev.infusion.core.domain.metadata.MetadataRepository
import dev.infusion.core.domain.metadata.MetadataResult
import dev.infusion.core.model.ArtworkPolicy
import dev.infusion.core.model.metadata.MediaType
import dev.infusion.core.model.metadata.PersonCredit
import dev.infusion.core.model.metadata.PersonDetails
import dev.infusion.core.model.metadata.PersonId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PersonDetailsStateHolder(
    private val personId: PersonId,
    private val repository: MetadataRepository,
    private val scope: CoroutineScope,
    private val locale: () -> MetadataLocale = { MetadataLocale.Default },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,

    private val artworkProvider: String = "tmdb",

    private val resolver: dev.infusion.core.domain.metadata.MediaIdentityResolver? = null,
) {

    private val _state = MutableStateFlow<PersonDetailsState>(SectionState.Loading)

    val state: StateFlow<PersonDetailsState> = _state

    val id: PersonId get() = personId

    private var inFlight = false

    init {
        load()
    }

    private fun load() {
        if (inFlight) return
        inFlight = true
        _state.value = SectionState.Loading
        scope.launch {
            val result = repository.personDetails(personId, locale())
            val projected = when (result) {
                is MetadataResult.Success -> project(result.value)

                is MetadataResult.Failure -> SectionState.Failed(result.reason)
            }
            inFlight = false
            _state.value = projected
        }
    }

    fun retry() {
        if (inFlight) return
        load()
    }

    private suspend fun project(details: PersonDetails): PersonDetailsState {
        val ui = kotlinx.coroutines.withContext(ioDispatcher) {
            PersonDetailsUi(
                personId = details.id,
                name = details.name,
                knownForDepartment = details.knownForDepartment,
                biography = details.biography?.takeIf { it.isNotBlank() },
                birthday = details.birthday,
                deathday = details.deathday,
                placeOfBirth = details.placeOfBirth?.takeIf { it.isNotBlank() },
                profileLocator = metadataArtworkLocator(details.profile, ArtworkPolicy.POSTER, artworkProvider),
                movieCredits = details.creditsFor(MediaType.MOVIE).map { credit ->
                    toCreditUi(credit).let { ui -> ui.copy(card = ui.card.withResolvedCanonicalTitle(resolver)) }
                },
                showCredits = details.creditsFor(MediaType.SHOW).map { credit ->
                    toCreditUi(credit).let { ui -> ui.copy(card = ui.card.withResolvedCanonicalTitle(resolver)) }
                },
            )
        }
        return SectionState.Ready(ui)
    }

    private fun toCreditUi(credit: PersonCredit): PersonCreditUi {
        val card = mediaCardUi(credit.card)
        return PersonCreditUi(

            key = buildString {
                append("credit:")
                append(credit.mediaType.name)
                append(':')
                append(card.key)
                append(':')
                append(credit.character.orEmpty())
                append(':')
                append(credit.job.orEmpty())
            },
            card = card,
            mediaType = credit.mediaType,

            role = credit.character?.takeIf { it.isNotBlank() } ?: credit.job?.takeIf { it.isNotBlank() },
            year = credit.year,
        )
    }
}
