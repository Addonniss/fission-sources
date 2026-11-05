package dev.infusion.feature.vertical.search

import dev.infusion.core.domain.search.SearchHistoryQueries
import dev.infusion.core.domain.search.SearchHistoryStore
import dev.infusion.core.model.ArtworkPolicy
import dev.infusion.core.model.profile.Profile
import dev.infusion.feature.vertical.media.PersonCardUi
import dev.infusion.feature.vertical.media.metadataArtworkLocator
import dev.infusion.source.aggregation.search.CanonicalSearchResult
import dev.infusion.source.aggregation.search.SearchGroup
import dev.infusion.source.aggregation.search.SearchPersonResult
import dev.infusion.source.aggregation.search.SearchResults
import dev.infusion.source.aggregation.search.SearchSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface AddonSearchPort {

    fun viewerProfile(): Profile

    fun primaryProfile(): Profile

    fun activeProfileId(): String

    suspend fun search(term: String, viewer: Profile, primary: Profile): SearchResults
}

data class AddonSearchUiState(
    val term: String = "",

    val canonical: List<CanonicalSearchResult> = emptyList(),

    val groups: List<SearchGroup> = emptyList(),

    val sections: List<SearchSection> = emptyList(),
    val searching: Boolean = false,

    val searched: Boolean = false,

    val canonicalFailed: Boolean = false,

    val addonFailed: Boolean = false,

    val people: List<PersonCardUi> = emptyList(),

    val peopleFailed: Boolean = false,

    val history: List<String> = emptyList(),

    val historyOwnerProfileId: String? = null,
) {

    val isEmpty: Boolean get() = canonical.isEmpty() && groups.isEmpty() && people.isEmpty()

    val unidentified: List<SearchGroup> get() = groups.filter { it.destination == null }

    val degraded: Boolean get() = canonicalFailed || addonFailed
}

class AddonSearchStateHolder(
    private val port: AddonSearchPort,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,

    private val historyStore: SearchHistoryStore? = null,
) {

    private val _state = MutableStateFlow(AddonSearchUiState())
    val state: StateFlow<AddonSearchUiState> = _state.asStateFlow()

    private var submissions: Int = 0

    private var historyRevision: Int = 0

    private val historyMutex = Mutex()

    fun onTermChanged(term: String) {
        _state.value = _state.value.copy(term = term)
    }

    fun submit() {
        val term = _state.value.term.trim()
        val profileId = port.activeProfileId()
        val mySubmission = ++submissions
        if (term.isBlank()) {
            _state.value = _state.value.copy(
                canonical = emptyList(),
                groups = emptyList(),
                sections = emptyList(),
                people = emptyList(),
                searching = false,
                searched = false,
                canonicalFailed = false,
                addonFailed = false,
                peopleFailed = false,
            )
            return
        }
        _state.value = _state.value.copy(searching = true, searched = true)
        scope.launch(ioDispatcher) {
            recordHistory(term, profileId, mySubmission)
            val results = try {
                val viewer = port.viewerProfile()
                val primary = port.primaryProfile()
                port.search(term, viewer, primary)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {

                SearchResults(canonicalFailed = true, addonFailed = true, peopleFailed = true)
            }

            if (mySubmission != submissions) return@launch
            if (port.activeProfileId() != profileId) return@launch
            _state.value = _state.value.copy(
                canonical = results.canonical,
                groups = results.groups,
                sections = results.sections,
                canonicalFailed = results.canonicalFailed,
                addonFailed = results.addonFailed,
                people = results.people.map { it.toPersonCard() },
                peopleFailed = results.peopleFailed,
                searching = false,
            )
        }
    }

    fun submitQuery(query: String) {
        val recordable = SearchHistoryQueries.recordable(query) ?: return
        _state.value = _state.value.copy(term = recordable)
        submit()
    }

    fun loadHistory() {
        val profileId = port.activeProfileId()
        val store = historyStore
        val revision = ++historyRevision
        if (store == null) {
            _state.value = _state.value.copy(history = emptyList(), historyOwnerProfileId = profileId)
            return
        }
        scope.launch(ioDispatcher) {
            val history = historyMutex.withLock { store.read(profileId) }
            if (revision != historyRevision) return@launch
            if (port.activeProfileId() != profileId) return@launch
            _state.value = _state.value.copy(history = history, historyOwnerProfileId = profileId)
        }
    }

    fun clearHistory() {
        val profileId = port.activeProfileId()
        val store = historyStore
        val revision = ++historyRevision

        _state.value = _state.value.copy(history = emptyList(), historyOwnerProfileId = profileId)
        if (store == null) return
        scope.launch(ioDispatcher) {
            historyMutex.withLock { store.clear(profileId) }
            if (revision != historyRevision) return@launch
        }
    }

    fun onProfileChanged(profileId: String) {
        if (_state.value.historyOwnerProfileId == profileId) return
        submissions += 1
        _state.value = AddonSearchUiState(historyOwnerProfileId = profileId)
        loadHistory()
    }

    private suspend fun recordHistory(term: String, profileId: String, submission: Int) {
        val store = historyStore ?: return
        val revision = ++historyRevision
        val updated = historyMutex.withLock {
            val existing = store.read(profileId)
            val next = SearchHistoryQueries.record(existing, term)
            store.write(profileId, next)
            next
        }

        if (revision != historyRevision) return
        if (submission != submissions) return
        if (port.activeProfileId() != profileId) return
        _state.value = _state.value.copy(history = updated, historyOwnerProfileId = profileId)
    }
}

internal fun SearchPersonResult.toPersonCard(): PersonCardUi = PersonCardUi(
    key = "person:${personId.authority}:${personId.value}",
    name = name,
    role = role,
    personId = personId,
    imageLocator = metadataArtworkLocator(profile, ArtworkPolicy.POSTER),
)
