package dev.infusion.feature.vertical.setup

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class JellyfinConnectedUser(
    val userName: String?,
    val avatarLocator: String?,
)

enum class JellyfinConnectionMethod { CREDENTIALS, API_KEY, QUICK_CONNECT }

sealed interface JellyfinConnectOutcome {
    data class Ok(
        val userId: String?,
        val userName: String?,
        val avatarTag: String?,
        val versionLabel: String?,
        val qualificationLabel: String?,
    ) : JellyfinConnectOutcome

    data class Failed(val message: String) : JellyfinConnectOutcome
    data class Invalid(val reason: String) : JellyfinConnectOutcome
}

data class JellyfinUserChoice(
    val userId: String,
    val name: String?,
    val disabled: Boolean = false,
    val hidden: Boolean = false,
)

sealed interface JellyfinUsersOutcome {
    data class Ok(val users: List<JellyfinUserChoice>) : JellyfinUsersOutcome
    data class Failed(val message: String) : JellyfinUsersOutcome
    data class Unsupported(val reason: String) : JellyfinUsersOutcome
}

interface JellyfinConnectionActions {
    suspend fun testCredentials(
        serverUrl: String,
        username: String,
        password: String,
        consentLan: Boolean,
    ): JellyfinConnectOutcome

    suspend fun connectCredentials(
        serverUrl: String,
        username: String,
        password: String,
        consentLan: Boolean,
    ): JellyfinConnectOutcome

    suspend fun testApiKey(
        serverUrl: String,
        apiKey: String,
        targetUserId: String?,
        consentLan: Boolean,
    ): JellyfinConnectOutcome

    suspend fun connectApiKey(
        serverUrl: String,
        apiKey: String,
        targetUserId: String?,
        consentLan: Boolean,
    ): JellyfinConnectOutcome

    suspend fun enumerateUsers(
        serverUrl: String,
        apiKey: String,
        consentLan: Boolean,
    ): JellyfinUsersOutcome

    suspend fun connectQuickConnect(
        serverUrl: String,
        consentLan: Boolean,
        onCode: (String) -> Unit,
        shouldCancel: () -> Boolean,
    ): JellyfinConnectOutcome
}

sealed interface JellyfinConnectionStatus {

    data object Idle : JellyfinConnectionStatus

    data object Working : JellyfinConnectionStatus

    data class TestOk(val versionLabel: String?, val qualificationLabel: String?) : JellyfinConnectionStatus

    data class TestFailed(val message: String) : JellyfinConnectionStatus

    data class UsersReady(val users: List<JellyfinUserChoice>) : JellyfinConnectionStatus

    data class UsersUnavailable(val message: String) : JellyfinConnectionStatus

    data class QuickConnectWaiting(val code: String) : JellyfinConnectionStatus

    data class Connected(val userName: String?, val versionLabel: String?, val qualificationLabel: String?) : JellyfinConnectionStatus

    data class Failed(val message: String) : JellyfinConnectionStatus
}

class JellyfinConnectionStateHolder(
    private val scope: CoroutineScope,
    val method: JellyfinConnectionMethod,
    private val actions: JellyfinConnectionActions,

    private val activeProfileId: () -> String,
    private val initialStatus: JellyfinConnectionStatus = JellyfinConnectionStatus.Idle,
    private val actionDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    private val _state = MutableStateFlow(initialStatus)
    val state: StateFlow<JellyfinConnectionStatus> = _state.asStateFlow()

    private var generation: Int = 0

    @Volatile
    private var cancelled: Boolean = false

    val busy: Boolean get() = _state.value is JellyfinConnectionStatus.Working ||
        _state.value is JellyfinConnectionStatus.QuickConnectWaiting

    val testPassed: Boolean get() = _state.value is JellyfinConnectionStatus.TestOk

    fun invalidate() {
        if (busy) {
            cancel()
            return
        }
        _state.value = JellyfinConnectionStatus.Idle
    }

    fun reset() {
        cancel()
        _state.value = JellyfinConnectionStatus.Idle
    }

    fun cancel() {
        generation++
        cancelled = true

        _state.value = JellyfinConnectionStatus.Idle
    }

    fun testCredentials(serverUrl: String, username: String, password: String, consentLan: Boolean = true) =
        runTest(generation = newGeneration()) {
            actions.testCredentials(serverUrl.trim(), username.trim(), password, consentLan)
        }

    fun connectCredentials(serverUrl: String, username: String, password: String, consentLan: Boolean = true) =
        run(generation = newGeneration()) {
            actions.connectCredentials(serverUrl.trim(), username.trim(), password, consentLan)
        }

    fun loadUsers(serverUrl: String, apiKey: String, consentLan: Boolean = true) {
        val gen = newGeneration()
        _state.value = JellyfinConnectionStatus.Working
        val profileAtStart = activeProfileId()
        scope.launch {
            val outcome = withContext(actionDispatcher) {
                actions.enumerateUsers(serverUrl.trim(), apiKey, consentLan)
            }
            if (!isCurrent(gen, profileAtStart)) return@launch
            _state.value = when (outcome) {
                is JellyfinUsersOutcome.Ok ->
                    if (outcome.users.isEmpty()) {
                        JellyfinConnectionStatus.UsersUnavailable("The server returned no user accounts.")
                    } else {
                        JellyfinConnectionStatus.UsersReady(outcome.users)
                    }
                is JellyfinUsersOutcome.Failed -> JellyfinConnectionStatus.UsersUnavailable(outcome.message)
                is JellyfinUsersOutcome.Unsupported -> JellyfinConnectionStatus.UsersUnavailable(outcome.reason)
            }
        }
    }

    fun testApiKey(serverUrl: String, apiKey: String, targetUserId: String?, consentLan: Boolean = true) =
        runTest(generation = newGeneration()) {
            actions.testApiKey(serverUrl.trim(), apiKey, targetUserId?.trim()?.takeIf { it.isNotEmpty() }, consentLan)
        }

    fun connectApiKey(serverUrl: String, apiKey: String, targetUserId: String?, consentLan: Boolean = true) =
        run(generation = newGeneration()) {
            actions.connectApiKey(serverUrl.trim(), apiKey, targetUserId?.trim()?.takeIf { it.isNotEmpty() }, consentLan)
        }

    fun startQuickConnect(serverUrl: String, consentLan: Boolean = true) {
        val gen = newGeneration()
        cancelled = false
        _state.value = JellyfinConnectionStatus.Working
        val profileAtStart = activeProfileId()
        scope.launch {
            val outcome = withContext(actionDispatcher) {
                actions.connectQuickConnect(
                    serverUrl.trim(),
                    consentLan,
                    { code ->

                        if (isCurrent(gen, profileAtStart)) {
                            _state.value = JellyfinConnectionStatus.QuickConnectWaiting(code)
                        }
                    },
                    { cancelled || generation != gen },
                )
            }
            if (!isCurrent(gen, profileAtStart)) return@launch
            _state.value = outcome.toStatus()
        }
    }

    private fun newGeneration(): Int {
        generation++
        cancelled = false
        return generation
    }

    private fun isCurrent(gen: Int, profileAtStart: String): Boolean =
        gen == generation && profileAtStart == activeProfileId()

    private fun run(generation: Int, action: suspend () -> JellyfinConnectOutcome) {
        _state.value = JellyfinConnectionStatus.Working
        val profileAtStart = activeProfileId()
        scope.launch {
            val outcome = withContext(actionDispatcher) { action() }
            if (!isCurrent(generation, profileAtStart)) return@launch
            _state.value = outcome.toStatus()
        }
    }

    private fun runTest(generation: Int, action: suspend () -> JellyfinConnectOutcome) {
        _state.value = JellyfinConnectionStatus.Working
        val profileAtStart = activeProfileId()
        scope.launch {
            val outcome = withContext(actionDispatcher) { action() }
            if (!isCurrent(generation, profileAtStart)) return@launch
            _state.value = when (outcome) {
                is JellyfinConnectOutcome.Ok -> JellyfinConnectionStatus.TestOk(
                    versionLabel = outcome.versionLabel,
                    qualificationLabel = outcome.qualificationLabel,
                )
                is JellyfinConnectOutcome.Failed -> JellyfinConnectionStatus.TestFailed(outcome.message)
                is JellyfinConnectOutcome.Invalid -> JellyfinConnectionStatus.TestFailed(outcome.reason)
            }
        }
    }

    private fun JellyfinConnectOutcome.toStatus(): JellyfinConnectionStatus = when (this) {
        is JellyfinConnectOutcome.Ok -> JellyfinConnectionStatus.Connected(
            userName = userName,
            versionLabel = versionLabel,
            qualificationLabel = qualificationLabel,
        )
        is JellyfinConnectOutcome.Failed -> JellyfinConnectionStatus.Failed(message)
        is JellyfinConnectOutcome.Invalid -> JellyfinConnectionStatus.Failed(reason)
    }
}
