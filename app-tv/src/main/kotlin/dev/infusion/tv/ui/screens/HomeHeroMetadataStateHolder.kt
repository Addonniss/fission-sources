package dev.infusion.tv.ui.screens

import dev.infusion.core.domain.metadata.MetadataIdentityHint
import dev.infusion.core.model.MediaPreview
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.metadata.MediaType
import dev.infusion.feature.vertical.home.HomeHeroPresentationCache
import dev.infusion.feature.vertical.media.CanonicalDetailsStateHolder
import dev.infusion.feature.vertical.media.CanonicalHeroUi
import dev.infusion.tv.navigation.toIdentityHint
import dev.infusion.tv.ui.presentation.IdentityMetadataState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

fun interface HomeHeroMetadataFactory {

    fun create(
        titleId: TitleId,
        mediaType: MediaType,
        holderScope: CoroutineScope,
        hint: MetadataIdentityHint?,
    ): CanonicalDetailsStateHolder
}

internal data class HomeHeroPresentation(
    val hero: CanonicalHeroUi? = null,
    val metadata: IdentityMetadataState = IdentityMetadataState.SETTLED,
)

internal class HomeHeroMetadataStateHolder(
    private val scope: CoroutineScope,
    private val factory: HomeHeroMetadataFactory?,

    private val presentationCache: HomeHeroPresentationCache? = null,

    private val trace: ((detail: String) -> Unit)? = null,
) {

    private val _presentation = MutableStateFlow(HomeHeroPresentation())

    val presentation: StateFlow<HomeHeroPresentation> = _presentation.asStateFlow()

    private var featuredKey: String? = null

    private var featuredAnchor: HomeHeroAnchor? = null

    private var job: Job? = null
    private var holderScope: CoroutineScope? = null

    private var restoreGeneration: Int = 0
    private var restoreJob: Job? = null

    private var restoredHero: CanonicalHeroUi? = null

    private var publishedHero: CanonicalHeroUi? = null

    private var liveFailed: Boolean = false

    fun feature(key: String, anchor: HomeHeroAnchor?, preview: MediaPreview?) {
        if (key == featuredKey) return
        featuredKey = key
        featuredAnchor = anchor
        release()
        val create = factory
        if (anchor == null || create == null) {

            _presentation.value = HomeHeroPresentation(hero = null, metadata = IdentityMetadataState.SETTLED)
            return
        }

        _presentation.value = HomeHeroPresentation(hero = null, metadata = IdentityMetadataState.PENDING)

        publishRestoredPresentation(anchor)

        val childJob = SupervisorJob(scope.coroutineContext[Job])
        val childScope = CoroutineScope(scope.coroutineContext + childJob)
        holderScope = childScope
        val holder = create.create(
            anchor.titleId,
            anchor.mediaType,
            childScope,

            preview?.toIdentityHint(anchor.mediaType),
        )
        job = childScope.launch {
            holder.state.collect { state ->
                val live = HomeHeroPresentation(
                    hero = state.hero,

                    metadata = if (state.hero != null || state.primarySettled ||
                        state.metadataFailure != null
                    ) {
                        IdentityMetadataState.SETTLED
                    } else {
                        IdentityMetadataState.PENDING
                    },
                )
                liveFailed = state.hero == null && state.metadataFailure != null
                publish(live, liveFailed)

                val hero = state.hero
                if (hero != null && hero != publishedHero) {
                    publishedHero = hero
                    trace?.invoke(
                        "cache-write backdrop=${hero.backdropLocator != null}" +
                            " logo=${hero.logoLocator != null}",
                    )
                    presentationCache?.publish(anchor.titleId, anchor.mediaType, hero)
                }
            }
        }
    }

    private fun publish(live: HomeHeroPresentation, liveFailed: Boolean) {
        val restored = restoredHero
        if (restored == null) {
            _presentation.value = live
            return
        }
        if (live.hero != null || liveFailed) {

            restoredHero = null
            trace?.invoke("presentation=live hero=${live.hero != null} failed=$liveFailed")
            _presentation.value = live
            return
        }

        trace?.invoke("presentation=restored")
        _presentation.value = HomeHeroPresentation(
            hero = restored,
            metadata = IdentityMetadataState.SETTLED,
        )
    }

    private fun publishRestoredPresentation(anchor: HomeHeroAnchor?) {
        val cache = presentationCache ?: return
        if (anchor == null) return
        val generation = ++restoreGeneration
        trace?.invoke("restore-start")
        restoreJob = scope.launch {
            val restored = runCatching { cache.restore(anchor.titleId, anchor.mediaType) }.getOrNull()
            if (restored == null) {
                trace?.invoke("restore-miss")
                return@launch
            }
            if (generation != restoreGeneration) return@launch
            if (featuredAnchor != anchor) return@launch

            val current = _presentation.value
            if (current.hero != null || liveFailed) {
                trace?.invoke("restore-dropped live-spoke")
                return@launch
            }
            restoredHero = restored
            trace?.invoke("restore-hit")
            _presentation.value = HomeHeroPresentation(
                hero = restored,
                metadata = IdentityMetadataState.SETTLED,
            )
        }
    }

    fun release() {

        restoreGeneration += 1
        restoreJob?.cancel()
        restoreJob = null
        restoredHero = null
        publishedHero = null
        liveFailed = false
        job?.cancel()
        job = null
        holderScope?.cancel()
        holderScope = null
    }
}
