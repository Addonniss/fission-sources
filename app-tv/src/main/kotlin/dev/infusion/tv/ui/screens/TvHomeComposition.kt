package dev.infusion.tv.ui.screens

internal data class HomePageScrollTarget(val index: Int, val offsetPx: Int)

internal fun homeAtRestAnchorOwner(
    focusedKey: String?,
    contentAnchorKey: String?,
    lastContentKey: String?,

    lastContentAnchorKey: String? = null,

    rememberedContentAnchorKey: String? = null,

    childReturnOriginPending: Boolean = false,

    atRestAnchorKey: String = HomeHeroSpotlightFocusKey,
): String? {

    if (contentAnchorKey != null) return contentAnchorKey

    if (isTopBarKey(focusedKey)) {
        lastContentAnchorKey?.let { return it }
        return if (childReturnOriginPending) null else atRestAnchorKey
    }

    if (lastContentKey == null) {
        rememberedContentAnchorKey?.let { return it }
        return if (childReturnOriginPending) null else atRestAnchorKey
    }

    return null
}

internal fun homeHeroRevertsToSpotlight(

    focusedKey: String?,

    presentedContentKey: String?,
): Boolean = presentedContentKey == null || focusedKey == HomeHeroSpotlightFocusKey

internal fun homeHeroPresentedContentKey(

    focusedKey: String?,

    presentedContentKey: String?,
): String? = presentedContentKey?.takeUnless { homeHeroRevertsToSpotlight(focusedKey, it) }

internal object HomeVerticalComposition {

    const val NextSectionPeekFraction: Float = 0.10f

    const val ComposedPredecessorSliverPx: Int = 1

    fun sectionStageHeightPx(viewportPx: Int, rowSpacingPx: Int): Int =
        (
            (viewportPx - 2 * rowSpacingPx - ComposedPredecessorSliverPx)
                .coerceAtLeast(0) / (1f + NextSectionPeekFraction)
            ).toInt()

    fun sectionStageTopInsetPx(rowSpacingPx: Int): Int =
        rowSpacingPx.coerceAtLeast(0) + ComposedPredecessorSliverPx

    fun pageEndAllowancePx(viewportPx: Int, rowSpacingPx: Int): Int =
        (viewportPx - sectionStageHeightPx(viewportPx, rowSpacingPx)).coerceAtLeast(0)

    fun heroBlockHeightPx(
        viewportPx: Int,
        contentTopPaddingPx: Int,
        rowSpacingPx: Int,
        firstSectionStagedHeightPx: Int,
        itemsAboveFirstSectionPx: List<Int> = emptyList(),
    ): Int {

        val peekPx = (firstSectionStagedHeightPx * NextSectionPeekFraction).toInt()
        val abovePx = itemsAboveFirstSectionPx.sumOf { it.coerceAtLeast(0) } +
            rowSpacingPx * itemsAboveFirstSectionPx.size
        return (viewportPx - contentTopPaddingPx - rowSpacingPx - abovePx - peekPx)
            .coerceAtLeast(0)
    }

    fun heroAnchorScrollTarget(): HomePageScrollTarget = HomePageScrollTarget(index = 0, offsetPx = 0)

    fun sectionAnchorScrollTarget(
        itemIndex: Int,
        contentTopPaddingPx: Int,
        rowSpacingPx: Int,
    ): HomePageScrollTarget = HomePageScrollTarget(
        index = itemIndex.coerceAtLeast(0),
        offsetPx = (contentTopPaddingPx - sectionStageTopInsetPx(rowSpacingPx)).coerceAtLeast(0),
    )

    fun visibleShare(
        viewportPx: Int,
        itemTopOnScreenPx: Int,
        itemHeightPx: Int,
    ): Float {
        if (itemHeightPx <= 0) return 0f
        val visibleTop = maxOf(itemTopOnScreenPx, 0)
        val visibleBottom = minOf(itemTopOnScreenPx + itemHeightPx, viewportPx)
        return ((visibleBottom - visibleTop).coerceAtLeast(0).toFloat() / itemHeightPx)
    }
}
