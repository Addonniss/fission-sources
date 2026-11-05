package dev.infusion.feature.vertical.media

import dev.infusion.core.domain.metadata.MetadataFailureReason
import dev.infusion.core.model.metadata.MediaType
import dev.infusion.core.model.metadata.PersonId
import java.time.LocalDate

data class PersonCreditUi(
    val key: String,
    val card: MediaCardUi,
    val mediaType: MediaType,

    val role: String? = null,
    val year: Int? = null,
)

data class PersonDetailsUi(
    val personId: PersonId,
    val name: String,
    val knownForDepartment: String? = null,
    val biography: String? = null,
    val birthday: LocalDate? = null,
    val deathday: LocalDate? = null,
    val placeOfBirth: String? = null,

    val profileLocator: String? = null,
    val movieCredits: List<PersonCreditUi> = emptyList(),
    val showCredits: List<PersonCreditUi> = emptyList(),
) {

    val hasCredits: Boolean get() = movieCredits.isNotEmpty() || showCredits.isNotEmpty()

    val lifeLine: String?
        get() = buildList {
            birthday?.let { born -> add(if (deathday != null) "$born – $deathday" else born.toString()) }
            deathday?.takeIf { birthday == null }?.let { add("d. $it") }
            placeOfBirth?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.joinToString(" · ").takeIf { it.isNotEmpty() }
}

typealias PersonDetailsState = SectionState<PersonDetailsUi>

fun personFailureCopy(reason: MetadataFailureReason?): String =
    if (reason == MetadataFailureReason.NOT_FOUND) "No details for this person." else "Couldn't load this person."
