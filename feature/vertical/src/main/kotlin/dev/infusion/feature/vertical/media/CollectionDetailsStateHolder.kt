package dev.infusion.feature.vertical.media

import dev.infusion.core.domain.metadata.MetadataLocale
import dev.infusion.core.domain.metadata.MetadataRepository
import dev.infusion.core.domain.metadata.MetadataResult
import dev.infusion.core.model.ArtworkPolicy
import dev.infusion.core.model.metadata.CollectionDetails
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class CollectionDetailsStateHolder(
    private val collectionId: Long,
    private val repository: MetadataRepository,
    private val scope: CoroutineScope,

    private val currentTitleKey: String? = null,
    private val locale: () -> MetadataLocale = { MetadataLocale.Default },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val artworkProvider: String = "tmdb",

    private val resolver: dev.infusion.core.domain.metadata.MediaIdentityResolver? = null,
) {

    private val _state = MutableStateFlow<CollectionDetailsState>(SectionState.Loading)

    val state: StateFlow<CollectionDetailsState> = _state

    val id: Long get() = collectionId

    private var inFlight = false

    init {
        load()
    }

    private fun load() {
        if (inFlight) return
        inFlight = true
        _state.value = SectionState.Loading
        scope.launch {
            val result = repository.collectionDetails(collectionId, locale())
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

    private suspend fun project(details: CollectionDetails): CollectionDetailsState {
        val ui = kotlinx.coroutines.withContext(ioDispatcher) {
            CollectionDetailsUi(
                id = details.id,
                name = details.name,
                overview = details.overview?.takeIf { it.isNotBlank() },
                posterLocator = metadataArtworkLocator(details.poster, ArtworkPolicy.POSTER, artworkProvider),
                backdropLocator = metadataArtworkLocator(details.backdrop, ArtworkPolicy.BACKDROP, artworkProvider),
                members = details.members.map { member ->
                    mediaCardUi(member).withResolvedCanonicalTitle(resolver)
                },
                currentTitleKey = currentTitleKey,
            )
        }

        return SectionState.Ready(ui)
    }
}
