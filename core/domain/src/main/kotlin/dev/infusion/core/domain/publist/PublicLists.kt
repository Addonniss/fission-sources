package dev.infusion.core.domain.publist

import dev.infusion.core.model.TitleKind

enum class PublicListProvider(
    val id: String,
    val displayName: String,
) {
    TRAKT("trakt", "Trakt"),
    MDBLIST("mdblist", "MDBList"),
    SIMKL("simkl", "Simkl"),
    ;

    companion object {

        fun fromId(id: String): PublicListProvider? = entries.firstOrNull { it.id == id }
    }
}

enum class PublicListRoute {

    POPULAR,

    TRENDING,

    CURATED,

    OFFICIAL,

    MY_LISTS,

    LIKED_LISTS,

    SEARCH_LISTS,

    SEARCH_USERS,

    USER_LISTS,
}

data class PublicListRouteSupport(
    val route: PublicListRoute,
    val label: String,
    val requiresAccount: Boolean = false,

    val inputLabel: String? = null,
)

data class PublicListCapabilities(
    val provider: PublicListProvider,
    val routes: List<PublicListRouteSupport>,
    val notes: List<String> = emptyList(),
) {

    val hasPublicAccess: Boolean get() = routes.any { !it.requiresAccount }

    val hasAccountRoutes: Boolean get() = routes.any { it.requiresAccount }

    fun offeredRoutes(accountConnected: Boolean): List<PublicListRouteSupport> =
        routes.filter { !it.requiresAccount || accountConnected }

    fun hasUsableRoutes(accountConnected: Boolean): Boolean = offeredRoutes(accountConnected).isNotEmpty()

    val isBrowsable: Boolean get() = routes.isNotEmpty()

    fun supports(route: PublicListRoute): Boolean = routes.any { it.route == route }

    fun labelOf(route: PublicListRoute): String? = routes.firstOrNull { it.route == route }?.label

    fun support(route: PublicListRoute): PublicListRouteSupport? = routes.firstOrNull { it.route == route }
}

data class PublicListRef(
    val provider: PublicListProvider,
    val listId: String,
    val owner: String? = null,
    val slug: String? = null,
) {
    init {
        require(listId.isNotBlank()) { "public list id must not be blank" }
    }
}

data class PublicListSummary(
    val ref: PublicListRef,

    val title: String,

    val owner: String? = null,

    val itemCount: Int? = null,
) {

    val ownerLabel: String? get() = owner?.trim()?.takeIf { it.isNotEmpty() }
}

data class PublicListUser(
    val provider: PublicListProvider,
    val username: String,
    val displayName: String? = null,
)

data class PublicListItem(
    val providerItemId: String,
    val title: String,
    val year: Int? = null,
    val kind: TitleKind? = null,
    val tmdbId: Long? = null,
    val imdbId: String? = null,
    val tvdbId: Long? = null,
    val rank: Int? = null,
) {

    val hasCanonicalAlias: Boolean get() = tmdbId != null || imdbId != null || tvdbId != null
}

data class PublicListPage(
    val items: List<PublicListItem>,
    val hasMore: Boolean = false,
    val nextCursor: String? = null,
)

data class PublicListDirectoryPage(
    val lists: List<PublicListSummary>,
    val hasMore: Boolean = false,
    val nextCursor: String? = null,
)

sealed interface PublicListResult<out T> {
    data class Success<T>(val value: T) : PublicListResult<T>
    data class Unsupported(val reason: String) : PublicListResult<Nothing>
    data class Failed(val reason: String) : PublicListResult<Nothing>

    fun valueOrNull(): T? = (this as? Success)?.value
}

interface PublicListDirectory {

    val provider: PublicListProvider

    fun capabilities(): PublicListCapabilities

    fun accountConnected(): Boolean = false

    suspend fun lists(route: PublicListRoute, query: String? = null): PublicListResult<List<PublicListSummary>>

    suspend fun listPage(
        route: PublicListRoute,
        query: String? = null,
        cursor: String? = null,
    ): PublicListResult<PublicListDirectoryPage> = if (cursor == null) {
        when (val first = lists(route, query)) {
            is PublicListResult.Success -> PublicListResult.Success(PublicListDirectoryPage(first.value))
            is PublicListResult.Unsupported -> first
            is PublicListResult.Failed -> first
        }
    } else {
        PublicListResult.Success(PublicListDirectoryPage(emptyList()))
    }

    suspend fun users(query: String): PublicListResult<List<PublicListUser>>

    suspend fun userLists(username: String): PublicListResult<List<PublicListSummary>>
}

interface PublicListContentSource {

    val provider: PublicListProvider

    suspend fun page(ref: PublicListRef, cursor: String? = null): PublicListResult<PublicListPage>
}
