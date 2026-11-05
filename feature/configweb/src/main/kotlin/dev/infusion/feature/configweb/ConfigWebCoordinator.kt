package dev.infusion.feature.configweb

import dev.infusion.core.lanweb.Clock
import dev.infusion.core.lanweb.LanwebBodyCaps
import dev.infusion.core.lanweb.LanwebRequest
import dev.infusion.core.lanweb.LanwebResponse
import dev.infusion.core.lanweb.LanwebRouter
import dev.infusion.core.lanweb.LanwebValidation
import dev.infusion.core.lanweb.SystemClock
import dev.infusion.core.pairing.PairingRandom
import dev.infusion.core.pairing.PairingRandomness
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

object ConfigWebEndpoints {

    const val PAGE: String = "/m"
    const val CSS: String = "/m/app.css"
    const val JS: String = "/m/app.js"

    const val STATE: String = "/api/state"

    const val SESSION: String = "/api/session"
    const val DRAFT: String = "/api/draft"
    const val DRAFT_BY_ID: String = "/api/draft/{draftId}"
    const val DRAFT_SUBMIT: String = "/api/draft/{draftId}/submit"
    const val DRAFT_CANCEL: String = "/api/draft/{draftId}/cancel"

    const val SESSION_HEADER: String = "X-Infusion-Session"

    const val BODY_CAP_BYTES: Int = LanwebBodyCaps.MANAGEMENT

    val VALIDATION: LanwebValidation = LanwebValidation.BROWSER
}

class ConfigWebStart internal constructor(
    val session: ConfigWebSession,
    val wireToken: String,
    val manualCode: String,
    val alreadyRunning: Boolean,
) {
    override fun toString(): String =
        "ConfigWebStart(sessionId=${session.id}, alreadyRunning=$alreadyRunning, token=[REDACTED])"
}

data class ConfigWebSessionView(
    val sessionId: String,
    val profileId: String,
    val remainingMs: Long,
)

data class ConfigWebPendingView(
    val draftId: String,
    val summary: ConfigDraftSummary,
)

data class ConfigWebTvState(
    val session: ConfigWebSessionView? = null,
    val pending: ConfigWebPendingView? = null,
    val lastResult: ConfigDraftStatus? = null,
)

class ConfigWebCoordinator(
    private val port: ConfigOwnerPort,
    private val config: ConfigWebConfig = ConfigWebConfig.DEFAULT,
    private val clock: Clock = SystemClock,
    private val random: PairingRandom = PairingRandomness.DEFAULT,
    private val logger: (String) -> Unit = {},

    private val state: ConfigStatePort = ConfigStatePort.Unavailable,
) {

    private val sessions = ConfigWebSessions(config, clock, random)
    private val drafts = ConfigWebDrafts(config, clock, port, random)
    private val _tvState = MutableStateFlow(ConfigWebTvState())

    val tvState: StateFlow<ConfigWebTvState> = _tvState.asStateFlow()

    @Volatile
    private var activation: MintedSession? = null

    fun startSession(): ConfigWebStart? {
        val live = activation?.takeIf { it.session.isActive(clock.nowEpochMs()) }
        if (live != null) {
            return ConfigWebStart(
                session = live.session,
                wireToken = live.wireToken,
                manualCode = live.manualCode,
                alreadyRunning = true,
            )
        }
        val profileId = runCatching { port.activeProfileId() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val minted = sessions.mint(profileId)
        activation = minted
        logger("configweb session started: ttl=${config.ttlMs}ms, session=${minted.session.id}")
        publish()
        return ConfigWebStart(
            session = minted.session,
            wireToken = minted.wireToken,
            manualCode = minted.manualCode,
            alreadyRunning = false,
        )
    }

    fun endSession() {
        val current = activation ?: return
        sessions.end(current.session, ConfigWebSessionState.ENDED)
        drafts.expireSessionDrafts(current.session.id)
        activation = null
        logger("configweb session ended: STOPPED")
        publish()
    }

    fun tick() {
        val current = activation
        if (current != null) {
            val state = current.session.stateAt(clock.nowEpochMs())
            if (state != ConfigWebSessionState.ACTIVE) {
                drafts.expireSessionDrafts(current.session.id)

                val discarded = drafts.lastDraft(current.session.id)?.let { drafts.status(it) }
                activation = null
                logger("configweb session ended: $state")
                _tvState.value = ConfigWebTvState(lastResult = discarded)
                return
            }
        }
        publish()
    }

    fun confirm(draftId: String): ConfigDraftStatus? {
        val session = liveSession() ?: return null
        return tvApply(drafts.confirm(session, draftId))
    }

    fun reject(draftId: String): ConfigDraftStatus? {
        val session = liveSession() ?: return null
        val draft = when (val cancelled = drafts.cancel(session, draftId)) {
            is DraftCancel.Rejected -> cancelled.draft
            is DraftCancel.Already -> cancelled.draft
            is DraftCancel.TooLate -> cancelled.draft
            DraftCancel.NotFound -> return null
        }
        logger("configweb draft rejected on TV: state=${draft.state().wireName}")
        publish()
        return drafts.status(draft)
    }

    fun retry(draftId: String): ConfigDraftStatus? {
        val session = liveSession() ?: return null
        return tvApply(drafts.retry(session, draftId))
    }

    private fun tvApply(result: DraftApply): ConfigDraftStatus? {
        val draft = when (result) {
            is DraftApply.Attempted -> result.draft
            is DraftApply.Refused -> result.draft
            DraftApply.NotFound -> null
        } ?: return null
        val status = drafts.status(draft)
        logger(
            "configweb apply attempt: state=${status.state.wireName} " +
                "applied=${status.counts.applied} failed=${status.counts.failed} " +
                "notAttempted=${status.counts.notAttempted}",
        )
        publish()
        return status
    }

    private fun liveSession(): ConfigWebSession? =
        activation?.session?.takeIf { it.isActive(clock.nowEpochMs()) }

    private fun publish() {
        val now = clock.nowEpochMs()
        val session = activation?.session?.takeIf { it.isActive(now) }
        val last = session?.let { drafts.lastDraft(it.id) }
        val pending = last?.takeIf { it.state() == ConfigDraftState.SUBMITTED }
        val conclusive = last?.takeIf { !it.state().isLive }
        _tvState.value = ConfigWebTvState(
            session = session?.let {
                ConfigWebSessionView(
                    sessionId = it.id,
                    profileId = it.profileId,
                    remainingMs = it.remainingMs(now),
                )
            },
            pending = pending?.let { ConfigWebPendingView(it.id, drafts.summary(it)) },
            lastResult = conclusive?.let { drafts.status(it) },
        )
    }

    fun declareRoutes(router: LanwebRouter): LanwebRouter = router
        .get(ConfigWebEndpoints.STATE) { handleState(it) }
        .get(ConfigWebEndpoints.SESSION) { handleSession(it) }
        .post(ConfigWebEndpoints.DRAFT) { handleCreateDraft(it) }
        .get(ConfigWebEndpoints.DRAFT_BY_ID) { handleGetDraft(it) }
        .post(ConfigWebEndpoints.DRAFT_SUBMIT) { handleSubmitDraft(it) }
        .post(ConfigWebEndpoints.DRAFT_CANCEL) { handleCancelDraft(it) }

    fun handleState(request: LanwebRequest): LanwebResponse {
        val session = when (val opened = gate(request)) {
            is Gate.Ok -> opened.session
            is Gate.Refused -> return opened.response
        }
        val snapshot = runCatching { state.state() }.getOrNull()
        if (snapshot == null || snapshot.revision.isBlank()) {
            return apiJson(503, JSONObject().put("status", "unavailable"))
        }

        if (!session.tryAcquireStatusRead()) return rateLimited()
        return apiJson(200, ConfigStateSerializer.toJson(snapshot))
    }

    fun handleSession(request: LanwebRequest): LanwebResponse {
        val session = when (val opened = gate(request)) {
            is Gate.Ok -> opened.session
            is Gate.Refused -> return opened.response
        }
        val remaining = session.remainingMs(clock.nowEpochMs())
        publish()

        return apiJson(200, JSONObject().put("status", "ok").put("remainingMs", remaining))
    }

    fun handleCreateDraft(request: LanwebRequest): LanwebResponse {
        val session = when (val opened = gate(request)) {
            is Gate.Ok -> opened.session
            is Gate.Refused -> return opened.response
        }
        val operations = when (val decoded = DraftRequestCodec.decode(request.body)) {
            is DraftRequestCodec.Decoded.Invalid -> return malformed(decoded.field, decoded.reason)
            is DraftRequestCodec.Decoded.Ok -> decoded.operations
        }
        if (!session.tryAcquireDraftCreate()) return rateLimited()
        return when (val created = drafts.createOrReplace(session, operations)) {
            is DraftCreate.Ok -> {
                publish()
                apiJson(
                    200,
                    JSONObject()
                        .put("status", ConfigDraftState.EDITING.wireName)
                        .put("draftId", created.draft.id)
                        .put("revision", created.draft.revision)
                        .put("operationCount", created.draft.operationCount())
                        .put("replaced", created.replaced),
                )
            }
            is DraftCreate.Invalid -> malformed(created.field, created.reason)
            is DraftCreate.SubmittedPending -> apiJson(
                409,
                JSONObject()
                    .put("status", "conflict")
                    .put("reason", "submitted")
                    .put("draftId", created.draft.id),
            )
            is DraftCreate.Unavailable -> apiJson(503, JSONObject().put("status", "unavailable"))
        }
    }

    fun handleGetDraft(request: LanwebRequest): LanwebResponse {
        val session = when (val opened = gate(request)) {
            is Gate.Ok -> opened.session
            is Gate.Refused -> return opened.response
        }
        val draft = drafts.get(session, draftIdOf(request)) ?: return apiError(404)
        if (!session.tryAcquireStatusRead()) return rateLimited()
        publish()
        return apiJson(200, draftStatusJson(drafts.status(draft)))
    }

    fun handleSubmitDraft(request: LanwebRequest): LanwebResponse {
        val session = when (val opened = gate(request)) {
            is Gate.Ok -> opened.session
            is Gate.Refused -> return opened.response
        }
        rejectUnexpectedBodyFields(request)?.let { return it }
        if (!session.tryAcquireSubmit()) return rateLimited()
        return when (val submitted = drafts.submit(session, draftIdOf(request))) {
            is DraftSubmit.Pending -> {
                publish()
                apiJson(
                    200,
                    JSONObject()
                        .put("status", "pending_confirmation")
                        .put("draftId", submitted.draft.id),
                )
            }
            is DraftSubmit.Recorded -> {
                publish()
                apiJson(
                    200,
                    JSONObject()
                        .put("status", submitted.draft.state().wireName)
                        .put("draftId", submitted.draft.id),
                )
            }
            is DraftSubmit.Stale -> {
                publish()
                val body = JSONObject().put("status", "stale").put("draftId", submitted.draft.id)
                submitted.currentRevision?.let { body.put("revision", it) }
                apiJson(409, body)
            }
            is DraftSubmit.SessionEnded -> {
                publish()
                apiJson(410, JSONObject().put("status", "session_ended"))
            }
            DraftSubmit.NotFound -> apiError(404)
        }
    }

    fun handleCancelDraft(request: LanwebRequest): LanwebResponse {
        val session = when (val opened = gate(request)) {
            is Gate.Ok -> opened.session
            is Gate.Refused -> return opened.response
        }
        rejectUnexpectedBodyFields(request)?.let { return it }
        if (!session.tryAcquireSubmit()) return rateLimited()
        return when (val cancelled = drafts.cancel(session, draftIdOf(request))) {
            is DraftCancel.Rejected -> {
                publish()
                apiJson(
                    200,
                    JSONObject()
                        .put("status", ConfigDraftState.REJECTED.wireName)
                        .put("draftId", cancelled.draft.id),
                )
            }
            is DraftCancel.Already -> {
                publish()
                apiJson(
                    200,
                    JSONObject()
                        .put("status", cancelled.draft.state().wireName)
                        .put("draftId", cancelled.draft.id),
                )
            }
            is DraftCancel.TooLate -> apiJson(
                409,
                JSONObject()
                    .put("status", "conflict")
                    .put("reason", cancelled.draft.state().wireName),
            )
            DraftCancel.NotFound -> apiError(404)
        }
    }

    private sealed interface Gate {
        data class Ok(val session: ConfigWebSession) : Gate
        data class Refused(val response: LanwebResponse) : Gate
    }

    private fun gate(request: LanwebRequest): Gate {
        val activeProfile = runCatching { port.activeProfileId() }.getOrNull()
            ?: return Gate.Refused(apiJson(500, JSONObject().put("status", "error")))
        return when (val auth = sessions.authenticate(request.header(ConfigWebEndpoints.SESSION_HEADER), activeProfile)) {
            is ConfigWebAuth.Ok -> Gate.Ok(auth.session)
            is ConfigWebAuth.Ended -> {
                drafts.expireSessionDrafts(auth.session.id)
                if (activation?.session?.id == auth.session.id) activation = null
                logger("configweb session refused: ${auth.reason.wireName()}")
                publish()
                if (auth.reason == ConfigWebSessionState.PROFILE_CHANGED) {
                    Gate.Refused(apiJson(410, JSONObject().put("status", "session_ended")))
                } else {
                    Gate.Refused(apiError(401))
                }
            }
            ConfigWebAuth.Rejected -> {
                logger("configweb session refused: no valid session")
                Gate.Refused(apiError(401))
            }
        }
    }

    private fun malformed(field: String, reason: String): LanwebResponse {
        if (!sessions.tryAcquirePreAuth()) return rateLimited()

        return apiJson(
            400,
            JSONObject()
                .put("status", "invalid")
                .put("field", field.take(MAX_FIELD_CHARS))
                .put("reason", reason.take(ConfigWebLimits.MAX_REASON_CHARS)),
        )
    }

    private fun rateLimited(): LanwebResponse = apiJson(429, JSONObject().put("status", "rate_limited"))

    private fun apiError(status: Int): LanwebResponse =
        apiJson(status, JSONObject(LanwebResponse.ERROR_BODY))

    private fun rejectUnexpectedBodyFields(request: LanwebRequest): LanwebResponse? {
        if (request.body.isEmpty()) return null
        return when (val parsed = StrictJson.parse(request.body)) {
            is StrictJsonResult.Invalid -> malformed(parsed.field, parsed.reason)
            is StrictJsonResult.Ok -> when (val value = parsed.value) {
                is JsonValue.Obj -> if (value.fields.isEmpty()) {
                    null
                } else {
                    malformed("body", "this route does not accept fields")
                }
                else -> malformed(StrictJson.ROOT, "expected a JSON object")
            }
        }
    }

    private fun draftIdOf(request: LanwebRequest): String = request.pathParams["draftId"].orEmpty()

    private fun apiJson(status: Int, body: JSONObject): LanwebResponse =
        LanwebResponse.json(status, body.toString(), API_HEADERS)

    private fun draftStatusJson(status: ConfigDraftStatus): JSONObject {
        val operations = JSONArray()
        status.operations.forEach { operation ->
            val entry = JSONObject()
                .put("index", operation.index)
                .put("family", operation.family.wireName)
                .put("kind", operation.kind.wireName)
                .put("rowKey", operation.rowKey)
                .put("outcome", operation.outcome)
            operation.reason?.let { entry.put("reason", it) }
            operations.put(entry)
        }
        return JSONObject()
            .put("status", status.state.wireName)
            .put("draftId", status.draftId)
            .put("revision", status.revision)
            .put("operations", operations)
            .put(
                "counts",
                JSONObject()
                    .put("total", status.counts.total)
                    .put("applied", status.counts.applied)
                    .put("failed", status.counts.failed)
                    .put("notAttempted", status.counts.notAttempted)
                    .put("pending", status.counts.pending),
            )
    }

    private companion object {
        const val MAX_FIELD_CHARS: Int = 96

        val API_HEADERS: Map<String, String> = mapOf(
            "Cache-Control" to "no-store",
            "X-Content-Type-Options" to "nosniff",
            "Referrer-Policy" to "no-referrer",
        )
    }
}

private fun ConfigWebSessionState.wireName(): String = name.lowercase()
