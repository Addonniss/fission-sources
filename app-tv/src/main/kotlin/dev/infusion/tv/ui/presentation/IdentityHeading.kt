package dev.infusion.tv.ui.presentation

enum class IdentityMetadataState {

    PENDING,

    SETTLED,
}

sealed interface IdentityHeading {

    data class Logo(val locator: String) : IdentityHeading

    data class Title(val text: String) : IdentityHeading

    data object Empty : IdentityHeading
}

private const val RowEvidenceUnknownTitleLabel = "Unknown title"

internal fun isRealIdentityTitle(candidate: String?): Boolean {
    val title = candidate?.trim().orEmpty()
    return title.isNotEmpty() && !title.equals(RowEvidenceUnknownTitleLabel, ignoreCase = true)
}

fun identityHeading(
    declaredLogoLocator: String?,
    canonicalTitle: String?,
    evidenceTitle: String?,
    metadata: IdentityMetadataState,
): IdentityHeading {
    val logo = declaredLogoLocator?.takeIf { it.isNotBlank() }
    if (logo != null) return IdentityHeading.Logo(logo)
    val canonical = ContentCardTitle.visibleOrNull(canonicalTitle)
    val evidence = if (isRealIdentityTitle(evidenceTitle)) {
        ContentCardTitle.visibleOrNull(evidenceTitle)
    } else {
        null
    }
    return when {

        evidence != null && canonical != null -> IdentityHeading.Title(evidence)

        evidence != null && metadata == IdentityMetadataState.PENDING -> IdentityHeading.Empty
        evidence != null -> IdentityHeading.Title(evidence)

        canonical != null -> IdentityHeading.Title(canonical)
        else -> IdentityHeading.Empty
    }
}
