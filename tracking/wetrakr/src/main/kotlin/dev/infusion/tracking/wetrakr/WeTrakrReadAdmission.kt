package dev.infusion.tracking.wetrakr

class WeTrakrReadAdmission(
    private val nowMs: () -> Long = System::currentTimeMillis,

    private val maxWaitMs: Long = WeTrakrHttpClient.DAILY_WINDOW_MAX_MS,
) {

    data class Closed(val reason: String, val untilMs: Long)

    data class Partition(val profileId: String, val accountId: String?)

    private var appWide: Closed? = null

    private val closedByAppUser = HashMap<String, Closed>()

    private val closedByAccount = HashMap<String, Closed>()

    fun closedFor(partition: Partition): Closed? = synchronized(this) {
        val now = nowMs()
        retireExpired(now)
        val key = accountKey(partition)

        listOfNotNull(appWide, closedByAppUser[key], closedByAccount[key]).maxByOrNull { it.untilMs }
    }

    fun anyClosed(): Boolean = synchronized(this) {
        retireExpired(nowMs())
        appWide != null || closedByAppUser.isNotEmpty() || closedByAccount.isNotEmpty()
    }

    fun close(partition: Partition, scope: WeTrakrQuotaScope, reason: String, waitMs: Long) {
        if (waitMs <= 0L) return
        val until = nowMs() + waitMs.coerceAtMost(maxWaitMs)
        synchronized(this) {
            val entry = Closed(reason, until)
            when (scope) {

                WeTrakrQuotaScope.APP_WIDE -> appWide = laterOf(appWide, entry)
                WeTrakrQuotaScope.APP_USER ->
                    closedByAppUser[accountKey(partition)] = laterOf(closedByAppUser[accountKey(partition)], entry)
                WeTrakrQuotaScope.ACCOUNT ->
                    closedByAccount[accountKey(partition)] = laterOf(closedByAccount[accountKey(partition)], entry)
            }
        }
    }

    fun clear() = synchronized(this) {
        appWide = null
        closedByAppUser.clear()
        closedByAccount.clear()
        Unit
    }

    private fun accountKey(partition: Partition): String {
        val account = partition.accountId?.trim()
        return if (account.isNullOrEmpty()) "profile:${partition.profileId}" else "account:$account"
    }

    private fun retireExpired(now: Long) {
        appWide?.let { if (now >= it.untilMs) appWide = null }
        closedByAppUser.entries.removeAll { (_, entry) -> now >= entry.untilMs }
        closedByAccount.entries.removeAll { (_, entry) -> now >= entry.untilMs }
    }

    private fun laterOf(existing: Closed?, candidate: Closed): Closed =
        if (existing == null || candidate.untilMs >= existing.untilMs) candidate else existing
}

enum class WeTrakrQuotaScope {

    APP_WIDE,

    APP_USER,

    ACCOUNT,
}
