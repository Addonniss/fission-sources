package dev.infusion.core.domain.format

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

object AirDateFormat {

    private val FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)

    fun format(date: LocalDate): String = FORMATTER.format(date)
}
