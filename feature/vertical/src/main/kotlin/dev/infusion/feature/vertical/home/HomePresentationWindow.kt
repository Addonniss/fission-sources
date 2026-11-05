package dev.infusion.feature.vertical.home

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

class HomePresentationWindow(initial: Int = InitialWindow) {

    private val window = MutableStateFlow(initial.coerceAtLeast(0))

    val count: StateFlow<Int> = window.asStateFlow()

    fun presents(index: Int): Boolean = index < window.value

    fun report(materialized: Int) {
        if (materialized <= 0) return
        if (materialized > window.value) window.value = materialized
    }

    companion object {

        const val InitialWindow: Int = 12

        const val PresentationLanes: Int = 6
    }
}

class HomeCwPresentationWindows(initial: Int = HomePresentationWindow.InitialWindow) {

    val mixed = HomePresentationWindow(initial)

    val movies = HomePresentationWindow(initial)

    val shows = HomePresentationWindow(initial)

    fun report(shelfKey: String, materialized: Int) {
        when (shelfKey) {
            HomeCwMoviesShelfKey -> movies.report(materialized)
            HomeCwShowsShelfKey -> shows.report(materialized)
            HomeCwShelfKey -> mixed.report(materialized)
            else -> Unit
        }
    }

    suspend fun awaitPresented(unifiedIndex: Int, familyIndex: Int, episodic: Boolean) {
        if (presents(unifiedIndex, familyIndex, episodic)) return
        val family = if (episodic) shows else movies
        combine(mixed.count, family.count) { mixedCount, familyCount ->
            mixedCount > unifiedIndex || familyCount > familyIndex
        }.first { it }
    }

    fun presents(unifiedIndex: Int, familyIndex: Int, episodic: Boolean): Boolean {
        val family = if (episodic) shows else movies
        return mixed.presents(unifiedIndex) || family.presents(familyIndex)
    }
}

fun shelfPresentationWindow(materialized: Int, inViewportNeighbourhood: Boolean): Int = when {
    materialized <= 0 -> 0
    inViewportNeighbourhood -> materialized
    else -> minOf(materialized, HomePresentationWindow.InitialWindow)
}
