package dev.infusion.feature.vertical.profile

import dev.infusion.core.domain.profile.ProfileScope
import dev.infusion.core.domain.watch.GetContinueWatchingUseCase
import dev.infusion.core.model.TitleId
import dev.infusion.feature.vertical.details.DetailsStateHolder
import dev.infusion.feature.vertical.home.HomeStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class ProfileSwitchingSeam(
    private val profileScope: ProfileScope,
    private val scope: CoroutineScope,
    private val homeFactory: (profileId: String, holderScope: CoroutineScope) -> HomeStateHolder,
    private val continueWatchingFactory: (profileId: String, holderScope: CoroutineScope) -> GetContinueWatchingUseCase,
    private val detailsFactory: (profileId: String, titleId: TitleId, holderScope: CoroutineScope) -> DetailsStateHolder,
) {

    val activeProfileId: StateFlow<String> = profileScope.activeProfileFlow
        .stateIn(scope, SharingStarted.Eagerly, profileScope.activeProfileId)

    val home: ProfileBoundStateHolder<HomeStateHolder> = ProfileBoundStateHolder(
        profileScope = profileScope,
        scope = scope,
        create = { profileId, holderScope -> homeFactory(profileId, holderScope) },
    )

    val continueWatching: ProfileBoundStateHolder<GetContinueWatchingUseCase> = ProfileBoundStateHolder(
        profileScope = profileScope,
        scope = scope,
        create = { profileId, holderScope -> continueWatchingFactory(profileId, holderScope) },
    )

    private val detailsHolders = mutableMapOf<TitleId, ProfileBoundStateHolder<DetailsStateHolder>>()

    fun details(titleId: TitleId): ProfileBoundStateHolder<DetailsStateHolder> =
        detailsHolders.getOrPut(titleId) {
            ProfileBoundStateHolder(
                profileScope = profileScope,
                scope = scope,
                create = { profileId, holderScope -> detailsFactory(profileId, titleId, holderScope) },
            )
        }
}
