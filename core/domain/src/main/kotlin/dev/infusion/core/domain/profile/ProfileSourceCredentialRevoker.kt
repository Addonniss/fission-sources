package dev.infusion.core.domain.profile

fun interface ProfileSourceCredentialRevoker {

    suspend fun revoke(sourceIds: List<String>)
}
