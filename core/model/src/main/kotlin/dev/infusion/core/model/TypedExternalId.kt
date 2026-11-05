package dev.infusion.core.model

import java.util.Locale

data class TypedExternalId private constructor(
    val authority: String,
    val value: String,
) {
    init {
        require(authority.isNotBlank()) { "authority must not be blank" }
        require(value.isNotBlank()) { "value must not be blank" }
    }

    companion object {

        operator fun invoke(authority: String, value: String): TypedExternalId =
            TypedExternalId(authority.trim().lowercase(Locale.ROOT), value)
    }
}
