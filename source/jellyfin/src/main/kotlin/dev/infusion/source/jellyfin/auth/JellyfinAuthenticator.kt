package dev.infusion.source.jellyfin.auth

import dev.infusion.core.network.NetworkLimits
import dev.infusion.core.security.SecretContext
import dev.infusion.core.security.SecretReference
import dev.infusion.core.security.SecretStore
import dev.infusion.source.jellyfin.client.ApiResult
import dev.infusion.source.jellyfin.client.FailureKind
import dev.infusion.source.jellyfin.client.JellyfinClient
import dev.infusion.source.jellyfin.client.JellyfinFailure
import java.nio.charset.StandardCharsets

sealed class AuthOutcome {
    data class Authenticated(val session: JellyfinSession) : AuthOutcome()
    data class Failed(val failure: JellyfinFailure) : AuthOutcome()
}

data class JellyfinSession(
    val sourceId: String,
    val userId: String,
    val serverId: String,
    val userName: String?,
    val tokenRef: SecretReference,

    val avatarTag: String? = null,
)

class JellyfinAuthenticator(
    private val client: JellyfinClient,
    private val secretStore: SecretStore,
    private val limits: NetworkLimits = NetworkLimits.DEFAULT,
) {

    suspend fun authenticate(
        sourceId: String,
        username: String,
        password: String,
        serverIdHint: String? = null,
    ): AuthOutcome {
        if (username.isBlank() || password.isEmpty()) {
            return AuthOutcome.Failed(
                JellyfinFailure(FailureKind.BAD_REQUEST, null, "username/password required", retryable = false),
            )
        }
        return when (val result = client.authenticate(username, password, limits = limits)) {
            is ApiResult.Err -> AuthOutcome.Failed(result.failure)
            is ApiResult.Ok -> {
                val auth = result.value
                val token = auth.accessToken
                val user = auth.user
                val userId = user?.id
                if (token == null || userId == null) {
                    return AuthOutcome.Failed(
                        JellyfinFailure(FailureKind.UNKNOWN, result.status, "auth response missing token/user", retryable = false),
                    )
                }
                val reference = secretStore.put(
                    token.toByteArray(StandardCharsets.UTF_8),
                    SecretContext(purpose = PURPOSE_ACCESS_TOKEN, sourceId = sourceId),
                )
                AuthOutcome.Authenticated(
                    JellyfinSession(
                        sourceId = sourceId,
                        userId = userId,
                        serverId = auth.serverId ?: serverIdHint ?: "",
                        userName = user.name,
                        tokenRef = reference,
                        avatarTag = user.primaryImageTag,
                    ),
                )
            }
        }
    }

    fun adoptAccessToken(
        sourceId: String,
        accessToken: String,
        userId: String,
        userName: String?,
        serverId: String,
        avatarTag: String?,
    ): JellyfinSession {
        val reference = secretStore.put(
            accessToken.toByteArray(StandardCharsets.UTF_8),
            SecretContext(purpose = PURPOSE_ACCESS_TOKEN, sourceId = sourceId),
        )
        return JellyfinSession(
            sourceId = sourceId,
            userId = userId,
            serverId = serverId,
            userName = userName,
            tokenRef = reference,
            avatarTag = avatarTag,
        )
    }

    suspend fun authenticateWithApiKey(
        sourceId: String,
        apiKey: String,
        userId: String,
        userName: String?,
        serverId: String,
        avatarTag: String?,
    ): AuthOutcome {
        if (apiKey.isBlank() || userId.isBlank()) {
            return AuthOutcome.Failed(
                JellyfinFailure(FailureKind.BAD_REQUEST, null, "api key and target user required", retryable = false),
            )
        }
        return AuthOutcome.Authenticated(
            adoptAccessToken(
                sourceId = sourceId,
                accessToken = apiKey,
                userId = userId,
                userName = userName,
                serverId = serverId,
                avatarTag = avatarTag,
            ),
        )
    }

    suspend fun validate(session: JellyfinSession): ApiResult<JellyfinSession> {
        val token = token(session) ?: return ApiResult.Err(
            JellyfinFailure(FailureKind.UNAUTHORIZED, null, "stored token missing", retryable = false),
        )
        return when (val result = client.me(token, limits = limits)) {
            is ApiResult.Err -> ApiResult.Err(result.failure)
            is ApiResult.Ok -> ApiResult.Ok(session, result.status)
        }
    }

    suspend fun logout(session: JellyfinSession): ApiResult<Unit> {
        val token = token(session)
        if (token != null) {
            when (val result = client.logout(token, limits = limits)) {
                is ApiResult.Err -> return ApiResult.Err(result.failure)
                is ApiResult.Ok -> Unit
            }
        }
        val deleted = try {
            secretStore.delete(session.tokenRef)
        } catch (_: Exception) {
            return ApiResult.Err(
                JellyfinFailure(
                    FailureKind.UNKNOWN,
                    null,
                    "secret deletion failed",
                    retryable = true,
                ),
            )
        }

        if (!deleted) {
            return ApiResult.Ok(Unit, 204)
        }
        return ApiResult.Ok(Unit, 204)
    }

    suspend fun logoutAllowLocalRevocation(session: JellyfinSession) {
        val token = token(session)
        if (token != null) {
            try {
                client.logout(token, limits = limits)
            } catch (_: Exception) {

            }
        }
        try {
            secretStore.delete(session.tokenRef)
        } catch (_: Exception) {

        }
    }

    fun token(session: JellyfinSession): String? =
        secretStore.get(session.tokenRef)?.toString(StandardCharsets.UTF_8)

    companion object {
        const val PURPOSE_ACCESS_TOKEN = "jellyfin.access-token"
    }
}
