package dev.infusion.feature.configweb

import dev.infusion.core.lanweb.Clock
import dev.infusion.core.lanweb.RateLimiter
import dev.infusion.core.lanweb.SystemClock
import dev.infusion.core.pairing.ConstantTime
import dev.infusion.core.pairing.PairingRandom
import dev.infusion.core.pairing.PairingRandomness
import java.util.concurrent.ConcurrentHashMap

enum class ConfigWebSessionState {
    ACTIVE,

    EXPIRED,

    ENDED,

    PROFILE_CHANGED,
}

class ConfigWebSession internal constructor(
    val id: String,
    private val tokenDigest: ByteArray,
    val profileId: String,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    budgets: ConfigWebBudgets,
    clock: Clock,
) {

    private val lock = Any()

    @Volatile
    private var state: ConfigWebSessionState = ConfigWebSessionState.ACTIVE

    private val draftCreate = RateLimiter(budgets.draftCreatePerWindow, budgets.windowMs, clock)
    private val submit = RateLimiter(budgets.submitPerWindow, budgets.windowMs, clock)
    private val statusRead = RateLimiter(budgets.statusReadPerWindow, budgets.windowMs, clock)

    fun stateAt(nowMs: Long): ConfigWebSessionState {
        val current = state
        if (current != ConfigWebSessionState.ACTIVE) return current
        if (nowMs >= expiresAtMs) {
            synchronized(lock) {
                if (state == ConfigWebSessionState.ACTIVE && nowMs >= expiresAtMs) {
                    state = ConfigWebSessionState.EXPIRED
                }
                return state
            }
        }
        return ConfigWebSessionState.ACTIVE
    }

    fun isActive(nowMs: Long): Boolean = stateAt(nowMs) == ConfigWebSessionState.ACTIVE

    fun remainingMs(nowMs: Long): Long = (expiresAtMs - nowMs).coerceAtLeast(0L)

    internal fun verifies(candidates: List<ByteArray>): Boolean {
        var matched = false
        for (candidate in candidates) {

            val equal = ConstantTime.matches(SessionCode.digest(candidate), tokenDigest)
            matched = matched or equal
        }
        return matched
    }

    internal fun tryAcquireDraftCreate(): Boolean = draftCreate.tryAcquire()

    internal fun tryAcquireSubmit(): Boolean = submit.tryAcquire()

    internal fun tryAcquireStatusRead(): Boolean = statusRead.tryAcquire()

    internal fun end(reason: ConfigWebSessionState) {
        if (reason == ConfigWebSessionState.ACTIVE) return
        synchronized(lock) {
            if (state == ConfigWebSessionState.ACTIVE) state = reason
        }
    }

    override fun toString(): String =
        "ConfigWebSession(id=$id, profileId=$profileId, state=$state, expiresAtMs=$expiresAtMs)"
}

class MintedSession internal constructor(
    val session: ConfigWebSession,
    private val token: ByteArray,
) {

    val wireToken: String get() = SessionCode.wire(token)

    val manualCode: String get() = SessionCode.display(token)

    override fun toString(): String = "MintedSession(sessionId=${session.id}, token=[REDACTED])"
}

sealed interface ConfigWebAuth {

    data class Ok(val session: ConfigWebSession) : ConfigWebAuth

    data class Ended(val session: ConfigWebSession, val reason: ConfigWebSessionState) : ConfigWebAuth

    data object Rejected : ConfigWebAuth
}

class ConfigWebSessions(
    private val config: ConfigWebConfig = ConfigWebConfig.DEFAULT,
    private val clock: Clock = SystemClock,
    private val random: PairingRandom = PairingRandomness.DEFAULT,
) {

    private val sessions = ConcurrentHashMap<String, ConfigWebSession>()

    private val preAuthMalformed =
        RateLimiter(config.budgets.preAuthMalformedPerWindow, config.budgets.windowMs, clock)

    fun mint(profileId: String): MintedSession {
        require(profileId.isNotBlank()) { "a session must be bound to an active profile" }
        val token = SessionCode.mint(random)
        val now = clock.nowEpochMs()
        val session = ConfigWebSession(
            id = "s_" + hex(random.nextBytes(SESSION_ID_BYTES)),
            tokenDigest = SessionCode.digest(token),
            profileId = profileId,
            createdAtMs = now,

            expiresAtMs = now + config.ttlMs,
            budgets = config.budgets,
            clock = clock,
        )
        sessions[session.id] = session
        prune()
        return MintedSession(session = session, token = token)
    }

    fun authenticate(presented: String?, activeProfileId: String): ConfigWebAuth {
        if (presented.isNullOrBlank()) return ConfigWebAuth.Rejected
        val candidates = SessionCode.parse(presented)
        if (candidates.isEmpty()) return ConfigWebAuth.Rejected
        val now = clock.nowEpochMs()
        val session = sessions.values.firstOrNull { it.verifies(candidates) } ?: return ConfigWebAuth.Rejected
        val state = session.stateAt(now)
        if (state != ConfigWebSessionState.ACTIVE) return ConfigWebAuth.Ended(session, state)
        if (session.profileId != activeProfileId) {
            session.end(ConfigWebSessionState.PROFILE_CHANGED)
            return ConfigWebAuth.Ended(session, ConfigWebSessionState.PROFILE_CHANGED)
        }
        return ConfigWebAuth.Ok(session)
    }

    fun end(session: ConfigWebSession, reason: ConfigWebSessionState) = session.end(reason)

    fun live(nowMs: Long): ConfigWebSession? = sessions.values.firstOrNull { it.isActive(nowMs) }

    fun tryAcquirePreAuth(): Boolean = preAuthMalformed.tryAcquire()

    fun gc(nowMs: Long): Int {
        var removed = 0
        for (session in sessions.values) {
            val state = session.stateAt(nowMs)
            if (state != ConfigWebSessionState.ACTIVE && nowMs >= session.expiresAtMs) {
                if (sessions.remove(session.id, session)) removed++
            }
        }
        return removed
    }

    fun size(): Int = sessions.size

    fun clear() {
        sessions.clear()
    }

    private fun prune() {
        if (sessions.size <= MAX_RETAINED_SESSIONS) return
        val overflow = sessions.size - MAX_RETAINED_SESSIONS
        val now = clock.nowEpochMs()
        sessions.values
            .filter { !it.isActive(now) }
            .sortedBy { it.createdAtMs }
            .take(overflow)
            .forEach { sessions.remove(it.id, it) }
    }

    private companion object {
        const val SESSION_ID_BYTES: Int = 8
        const val MAX_RETAINED_SESSIONS: Int = 8

        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
