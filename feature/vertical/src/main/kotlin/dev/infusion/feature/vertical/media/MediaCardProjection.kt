package dev.infusion.feature.vertical.media

import dev.infusion.core.model.metadata.MediaCard
import dev.infusion.core.model.metadata.MediaCardPage
import dev.infusion.core.model.metadata.ReviewPage

internal fun mediaCardUi(card: MediaCard): MediaCardUi = MediaCardUi(
    key = "card:${card.identity.cacheKey() ?: card.title}",
    title = card.title,
    year = card.year,
    identity = card.identity,
    imageLocator = card.preview.artwork?.locator,

    artwork = card.preview.artwork,
)

internal fun cardPageUi(page: MediaCardPage): CardPageUi = CardPageUi(
    page = page.page,
    totalPages = page.totalPages,
    hasMore = page.hasMore,
)

internal fun reviewPageUi(page: ReviewPage): CardPageUi = CardPageUi(
    page = page.page,
    totalPages = page.totalPages,
    hasMore = page.hasMore,
)
