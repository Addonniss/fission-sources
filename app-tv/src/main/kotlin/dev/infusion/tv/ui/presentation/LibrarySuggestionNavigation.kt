package dev.infusion.tv.ui.presentation

data class SuggestionPosition(val section: Int, val item: Int)

sealed interface SuggestionStep {

    data class Move(val position: SuggestionPosition) : SuggestionStep

    data object ExitUp : SuggestionStep

    data object Edge : SuggestionStep
}

object LibrarySuggestionNavigation {

    fun rows(sections: List<List<String>>, hidden: Set<String>): List<List<String>> =
        sections.map { row -> row.filterNot { it in hidden } }.filter { it.isNotEmpty() }

    fun entry(
        rows: List<List<String>>,
        preferredItemKey: String? = null,
        rememberedSection: Int? = null,
    ): SuggestionPosition? {
        if (rows.isEmpty()) return null
        if (preferredItemKey != null) {
            val section = rows.indexOfFirst { preferredItemKey in it }
            if (section >= 0) {
                return SuggestionPosition(section, rows[section].indexOf(preferredItemKey))
            }
        }
        val section = (rememberedSection ?: 0).coerceIn(0, rows.lastIndex)
        return SuggestionPosition(section, 0)
    }

    fun step(current: SuggestionPosition, up: Boolean, rows: List<List<String>>): SuggestionStep {
        if (rows.isEmpty()) return SuggestionStep.Edge
        val section = current.section.coerceIn(0, rows.lastIndex)
        if (up) {
            if (section == 0) return SuggestionStep.ExitUp
            val target = section - 1
            return SuggestionStep.Move(
                SuggestionPosition(target, current.item.coerceIn(0, rows[target].lastIndex)),
            )
        }
        if (section >= rows.lastIndex) return SuggestionStep.Edge
        val target = section + 1
        return SuggestionStep.Move(
            SuggestionPosition(target, current.item.coerceIn(0, rows[target].lastIndex)),
        )
    }
}
