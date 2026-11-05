package dev.infusion.feature.vertical.jellyfin.library

enum class GridDirection { LEFT, RIGHT, UP, DOWN }

sealed interface GridStep {

    data class Move(val index: Int) : GridStep

    data object Footer : GridStep

    data object NearEnd : GridStep

    data class LoadUnloaded(val absoluteServerIndex: Int) : GridStep

    data object Edge : GridStep

    data object ExitUp : GridStep
}

object LibraryGridMath {

    fun step(
        current: Int,
        direction: GridDirection,
        cells: List<LibraryGridCell>,
        columns: Int,
        hasFooter: Boolean,
        hasMore: Boolean?,
    ): GridStep {
        val safeColumns = columns.coerceAtLeast(1)
        if (current !in cells.indices) return GridStep.Edge
        val rowStart = (current / safeColumns) * safeColumns
        val rowEnd = minOf(rowStart + safeColumns - 1, cells.lastIndex)

        fun previousFocusable(from: Int, bound: Int): Int? {
            if (bound > from) return null
            for (index in from downTo bound) if (isFocusable(cells, index)) return index
            return null
        }

        fun nextFocusable(from: Int, bound: Int): Int? {
            if (bound < from) return null
            for (index in from..bound) if (isFocusable(cells, index)) return index
            return null
        }

        return when (direction) {
            GridDirection.LEFT -> {
                if (current <= rowStart) {
                    GridStep.Edge
                } else {
                    previousFocusable(current - 1, rowStart)?.let(GridStep::Move) ?: GridStep.Edge
                }
            }

            GridDirection.RIGHT -> {
                if (current >= rowEnd) {
                    GridStep.Edge
                } else {
                    nextFocusable(current + 1, rowEnd)?.let(GridStep::Move) ?: GridStep.Edge
                }
            }

            GridDirection.UP -> {
                if (current - safeColumns < 0) {
                    GridStep.ExitUp
                } else {
                    previousFocusable(current - safeColumns, (rowStart - safeColumns).coerceAtLeast(0))
                        ?.let(GridStep::Move)
                        ?: GridStep.Edge
                }
            }

            GridDirection.DOWN -> {
                val target = current + safeColumns
                when {
                    target < cells.size && isFocusable(cells, target) -> GridStep.Move(target)
                    target < cells.size -> {
                        val absolute = cells[target].absoluteServerIndex
                        if (absolute != null) GridStep.LoadUnloaded(absolute) else GridStep.Edge
                    }
                    else -> {
                        val finalRowStart = (cells.lastIndex / safeColumns) * safeColumns
                        val lastOfFinalRow = lastFocusableInFinalRow(cells, safeColumns)
                        when {

                            current < finalRowStart && lastOfFinalRow != null -> GridStep.Move(lastOfFinalRow)
                            hasFooter -> GridStep.Footer
                            hasMore != false -> GridStep.NearEnd
                            else -> GridStep.Edge
                        }
                    }
                }
            }
        }
    }

    fun isFocusable(cells: List<LibraryGridCell>, index: Int): Boolean =
        cells.getOrNull(index)?.isFocusable == true

    fun lastFocusableInFinalRow(cells: List<LibraryGridCell>, columns: Int): Int? {
        if (cells.isEmpty()) return null
        val safeColumns = columns.coerceAtLeast(1)
        val finalRowStart = (cells.lastIndex / safeColumns) * safeColumns
        return (cells.lastIndex downTo finalRowStart).firstOrNull { isFocusable(cells, it) }
    }

    fun firstFocusable(cells: List<LibraryGridCell>): Int? =
        cells.indices.firstOrNull { isFocusable(cells, it) }
}
