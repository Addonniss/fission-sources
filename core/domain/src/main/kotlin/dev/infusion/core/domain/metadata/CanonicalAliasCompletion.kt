package dev.infusion.core.domain.metadata

import dev.infusion.core.model.Title

fun interface CanonicalAliasCompletion {

    suspend fun complete(title: Title): Title

    companion object {

        val NONE: CanonicalAliasCompletion = CanonicalAliasCompletion { title -> title }
    }
}
