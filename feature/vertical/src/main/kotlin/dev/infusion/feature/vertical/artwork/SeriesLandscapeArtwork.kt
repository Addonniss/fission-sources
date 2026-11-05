package dev.infusion.feature.vertical.artwork

import dev.infusion.core.domain.metadata.MediaIdentityResolver
import dev.infusion.core.domain.metadata.MetadataLocale
import dev.infusion.core.domain.metadata.MetadataRepository
import dev.infusion.core.model.ArtworkPolicy
import dev.infusion.core.model.MediaArtworkRef
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.metadata.MediaType
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class SeriesLandscapeState {
    PENDING,
    PRESENT,
    ABSENT,
}

interface SeriesLandscapeArtwork {

    fun englishLandscape(titleId: TitleId, type: MediaType): StateFlow<MediaArtworkRef?>

    fun englishLandscapeState(titleId: TitleId, type: MediaType): StateFlow<SeriesLandscapeState>

    fun demand(titleId: TitleId, type: MediaType)

    companion object {

        val None: SeriesLandscapeArtwork = object : SeriesLandscapeArtwork {
            private val absent = MutableStateFlow<MediaArtworkRef?>(null)
            private val pending = MutableStateFlow(SeriesLandscapeState.PENDING)

            override fun englishLandscape(titleId: TitleId, type: MediaType): StateFlow<MediaArtworkRef?> =
                absent.asStateFlow()

            override fun englishLandscapeState(
                titleId: TitleId,
                type: MediaType,
            ): StateFlow<SeriesLandscapeState> = pending.asStateFlow()

            override fun demand(titleId: TitleId, type: MediaType) = Unit
        }
    }
}

class SeriesLandscapeArtworkStateHolder(
    private val resolver: MediaIdentityResolver,
    private val repository: MetadataRepository,
    private val scope: CoroutineScope,
    private val locale: () -> MetadataLocale = { MetadataLocale.Default },
    private val language: String = ENGLISH_LANGUAGE,
    private val maxConcurrentHydrations: Int = MAX_CONCURRENT_HYDRATIONS,
) : SeriesLandscapeArtwork {

    private val flows =
        java.util.concurrent.ConcurrentHashMap<Pair<TitleId, MediaType>, MutableStateFlow<MediaArtworkRef?>>()

    private val settlements =
        java.util.concurrent.ConcurrentHashMap<Pair<TitleId, MediaType>, MutableStateFlow<SeriesLandscapeState>>()

    private val settled = mutableSetOf<Pair<TitleId, MediaType>>()

    private val inFlight = mutableMapOf<Pair<TitleId, MediaType>, Job>()
    private val lock = Any()

    private val gate = Semaphore(maxConcurrentHydrations.coerceAtLeast(1))

    override fun englishLandscape(titleId: TitleId, type: MediaType): StateFlow<MediaArtworkRef?> =
        flows.computeIfAbsent(titleId to type) { MutableStateFlow(null) }.asStateFlow()

    override fun englishLandscapeState(
        titleId: TitleId,
        type: MediaType,
    ): StateFlow<SeriesLandscapeState> =
        settlements.computeIfAbsent(titleId to type) { MutableStateFlow(SeriesLandscapeState.PENDING) }
            .asStateFlow()

    override fun demand(titleId: TitleId, type: MediaType) {

        englishLandscape(titleId, type)
        val question = titleId to type
        synchronized(lock) {
            if (settled.contains(question) || inFlight.containsKey(question)) return
            inFlight[question] = scope.launch { hydrate(titleId, type) }
        }
    }

    private suspend fun hydrate(titleId: TitleId, type: MediaType) {
        val question = titleId to type
        val resolved = try {
            gate.withPermit { resolveEnglishLandscape(titleId, type) }
        } catch (cancelled: CancellationException) {
            release(question, settled = false)
            throw cancelled
        } catch (_: Exception) {
            null
        }
        release(question, settled = true)

        resolved?.let { flows[question]?.value = it }

        val answered = resolved ?: flows[question]?.value
        settlements.computeIfAbsent(question) { MutableStateFlow(SeriesLandscapeState.PENDING) }
            .value = if (answered != null) SeriesLandscapeState.PRESENT else SeriesLandscapeState.ABSENT
    }

    private fun release(question: Pair<TitleId, MediaType>, settled: Boolean) {
        synchronized(lock) {
            inFlight.remove(question)
            if (settled) this.settled.add(question)
        }
    }

    private suspend fun resolveEnglishLandscape(titleId: TitleId, type: MediaType): MediaArtworkRef? {
        val wanted = language.trim().lowercase(Locale.ROOT)
        if (wanted.isEmpty()) return null
        val identity = try {
            resolver.resolve(titleId, type, locale())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return null
        val image = repository.titledLandscape(identity, wanted).valueOrNull() ?: return null
        return MediaArtworkRef.metadata(
            provider = image.provider,
            path = image.path,
            policy = ArtworkPolicy.BACKDROP,
        )
    }

    private companion object {

        const val ENGLISH_LANGUAGE = "en"

        const val MAX_CONCURRENT_HYDRATIONS = 2
    }
}
