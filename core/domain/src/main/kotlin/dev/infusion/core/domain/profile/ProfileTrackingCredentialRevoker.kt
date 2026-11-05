package dev.infusion.core.domain.profile

fun interface ProfileTrackingCredentialRevoker {

    suspend fun revoke(profileId: String)
}
