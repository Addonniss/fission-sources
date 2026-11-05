package dev.infusion.feature.vertical.home

import dev.infusion.core.domain.home.HomePresentationDefaults

enum class HomeRowMediaKind {

    MOVIE,

    SERIES,

    MIXED,
    ;

    companion object {

        fun fromCatalogType(catalogType: String): HomeRowMediaKind =
            when (catalogType.trim().lowercase()) {
                "movie" -> MOVIE
                "series" -> SERIES
                else -> MIXED
            }
    }
}

sealed interface HomeRowSourceRef {

    data class AddonCatalog(val key: HomeCatalogKey) : HomeRowSourceRef

    data class Widget(val widgetId: String, val key: HomeCatalogKey) : HomeRowSourceRef
}

data class HomeRowPresentation(

    val showProviderName: Boolean = HomePresentationDefaults.SHOW_PROVIDER_NAME,

    val providerDisplayName: String = "",
)

data class HomeRowDefinition(
    val rowId: String,
    val source: HomeRowSourceRef,
    val title: String,
    val mediaKind: HomeRowMediaKind,
    val order: Int,
    val presentation: HomeRowPresentation = HomeRowPresentation(),
) {

    val label: String
        get() = if (presentation.showProviderName) "$title — ${presentation.providerDisplayName}" else title
}

fun addonCatalogRowDefinition(
    catalogKey: HomeCatalogKey,
    publishedRowKey: String,
    title: String,
    providerDisplayName: String,
    order: Int,
    showProviderName: Boolean,
): HomeRowDefinition? {
    if (catalogKey.shelfKey != publishedRowKey) return null
    return HomeRowDefinition(
        rowId = catalogKey.shelfKey,
        source = HomeRowSourceRef.AddonCatalog(catalogKey),
        title = title,
        mediaKind = HomeRowMediaKind.fromCatalogType(catalogKey.catalogType),
        order = order,
        presentation = HomeRowPresentation(
            showProviderName = showProviderName,
            providerDisplayName = providerDisplayName,
        ),
    )
}

fun migratedHomeRowId(
    definitions: List<HomeRowDefinition>,
    publishedRowIds: Set<String>,
): String? = definitions
    .sortedBy { it.order }
    .firstOrNull { it.rowId in publishedRowIds }
    ?.rowId

fun widgetRowDefinition(
    widgetId: String,
    catalogKey: HomeCatalogKey,
    publishedRowKey: String,
    title: String,
    providerDisplayName: String,
    order: Int,
    showProviderName: Boolean,
): HomeRowDefinition? {
    if (homeWidgetRowKey(widgetId) != publishedRowKey) return null
    return HomeRowDefinition(
        rowId = publishedRowKey,
        source = HomeRowSourceRef.Widget(widgetId, catalogKey),
        title = title,
        mediaKind = HomeRowMediaKind.fromCatalogType(catalogKey.catalogType),
        order = order,
        presentation = HomeRowPresentation(
            showProviderName = showProviderName,
            providerDisplayName = providerDisplayName,
        ),
    )
}
