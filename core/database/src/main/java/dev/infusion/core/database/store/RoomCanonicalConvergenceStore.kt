package dev.infusion.core.database.store

import dev.infusion.core.database.Clock
import dev.infusion.core.database.dao.AvailabilityDao
import dev.infusion.core.database.dao.EditionDao
import dev.infusion.core.database.dao.ExternalIdDao
import dev.infusion.core.database.dao.IdentityMappingDao
import dev.infusion.core.database.dao.LocalAssetDao
import dev.infusion.core.database.dao.MetadataCacheDao
import dev.infusion.core.database.dao.PlayableCandidateDao
import dev.infusion.core.database.dao.ServerItemDao
import dev.infusion.core.database.dao.TitleDao
import dev.infusion.core.database.dao.TitleLinkDao
import dev.infusion.core.database.dao.WatchEventDao
import dev.infusion.core.database.dao.WatchStateDao
import dev.infusion.core.database.entity.IdentityMappingEntity
import dev.infusion.core.database.entity.TitleEntity
import dev.infusion.core.database.entity.TitleLinkEntity
import dev.infusion.core.database.mapper.WatchEventMapper
import dev.infusion.core.database.mapper.WatchStateMapper
import dev.infusion.core.database.watch.WatchKeyCodec
import dev.infusion.core.domain.identity.CanonicalConvergenceStore
import dev.infusion.core.domain.identity.CanonicalIdentityClaims
import dev.infusion.core.model.TitleId
import dev.infusion.core.model.TypedExternalId
import dev.infusion.core.model.identity.CanonicalClaim
import dev.infusion.core.model.identity.MappingOrigin
import dev.infusion.core.sync.WatchEventReducer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RoomCanonicalConvergenceStore(
    private val titleDao: TitleDao,
    private val titleLinkDao: TitleLinkDao,
    private val externalIdDao: ExternalIdDao,
    private val identityMappingDao: IdentityMappingDao,
    private val availabilityDao: AvailabilityDao,
    private val editionDao: EditionDao,
    private val serverItemDao: ServerItemDao,
    private val localAssetDao: LocalAssetDao,
    private val playableCandidateDao: PlayableCandidateDao,
    private val metadataCacheDao: MetadataCacheDao,
    private val watchEventDao: WatchEventDao,
    private val watchStateDao: WatchStateDao,
    private val clock: Clock = Clock.SYSTEM,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val transaction: (block: () -> Unit) -> Unit = { block -> block() },
    private val removedSourceIds: () -> Set<String> = { emptySet() },
) : CanonicalConvergenceStore {

    private val supersession = TitleSupersession(titleDao)

    override suspend fun claims(aliases: List<TypedExternalId>): CanonicalIdentityClaims =
        withContext(ioDispatcher) {
            val claims = mutableListOf<CanonicalClaim>()
            var userCorrectionConflict = false
            for (alias in aliases.distinct()) {

                claimOf(externalIdDao.get(alias.authority, alias.value)?.titleId)?.let(claims::add)

                val active = identityMappingDao.activeByExternal(alias.authority, alias.value)
                claimOf(active?.canonicalTitleId)?.let(claims::add)

                if (active?.origin == MappingOrigin.USER_CORRECTION) userCorrectionConflict = true
            }
            CanonicalIdentityClaims(
                claims = claims.distinctBy { it.titleId },
                userCorrectionConflict = userCorrectionConflict,
            )
        }

    override suspend fun claimsOf(aliasSets: List<List<TypedExternalId>>): List<CanonicalIdentityClaims> =
        withContext(ioDispatcher) {
            val aliases = aliasSets.flatten().distinct()
            if (aliases.isEmpty()) {
                return@withContext aliasSets.map { CanonicalIdentityClaims(emptyList()) }
            }
            val byAuthority = aliases.groupBy { it.authority }

            val bindings: Map<TypedExternalId, String?> = buildMap {
                for ((authority, ofAuthority) in byAuthority) {
                    for (row in externalIdDao.listByAuthorityAndNativeIdsChunked(
                        authority,
                        ofAuthority.map { it.value },
                    )) {
                        put(TypedExternalId(row.authority, row.nativeId), row.titleId)
                    }
                }
            }

            val actives: Map<TypedExternalId, IdentityMappingEntity> = buildMap {
                for ((authority, ofAuthority) in byAuthority) {
                    for (row in identityMappingDao.activeByAuthorityAndExternalsChunked(
                        authority,
                        ofAuthority.map { it.value },
                    )) {
                        putIfAbsent(TypedExternalId(row.authority, row.externalValue), row)
                    }
                }
            }

            val ownerIds = buildList {
                for (alias in aliases) {
                    parseTitleId(bindings[alias])?.let(::add)
                    parseTitleId(actives[alias]?.canonicalTitleId)?.let(::add)
                }
            }.distinct()
            val survivors = supersession.survivorsOf(ownerIds)
            val survivorRows = titleDao.listByIdsChunked(survivors.values.map { it.value.toString() })
                .associateBy { it.titleId }
            aliasSets.map { set ->
                val claims = mutableListOf<CanonicalClaim>()
                var userCorrectionConflict = false
                for (alias in set.distinct()) {
                    claimFrom(bindings[alias], survivors, survivorRows)?.let(claims::add)
                    val active = actives[alias]
                    claimFrom(active?.canonicalTitleId, survivors, survivorRows)?.let(claims::add)
                    if (active?.origin == MappingOrigin.USER_CORRECTION) userCorrectionConflict = true
                }
                CanonicalIdentityClaims(
                    claims = claims.distinctBy { it.titleId },
                    userCorrectionConflict = userCorrectionConflict,
                )
            }
        }

    override suspend fun successorsOf(titleIds: List<TitleId>): Map<TitleId, TitleId> =
        withContext(ioDispatcher) { supersession.survivorsOf(titleIds.distinct()) }

    private fun claimOf(ownerTitleId: String?): CanonicalClaim? {
        val owner = parseTitleId(ownerTitleId) ?: return null
        val canonical = supersession.survivorOf(owner)
        val title = titleDao.getById(canonical.value.toString()) ?: return null
        return CanonicalClaim(titleId = canonical, declaredAtMs = title.createdAtMs)
    }

    private fun claimFrom(
        ownerTitleId: String?,
        survivors: Map<TitleId, TitleId>,
        rows: Map<String, TitleEntity>,
    ): CanonicalClaim? {
        val owner = parseTitleId(ownerTitleId) ?: return null
        val canonical = survivors[owner] ?: return null
        val title = rows[canonical.value.toString()] ?: return null
        return CanonicalClaim(titleId = canonical, declaredAtMs = title.createdAtMs)
    }

    private fun parseTitleId(raw: String?): TitleId? =
        raw?.let { runCatching { TitleId.fromString(it) }.getOrNull() }

    override suspend fun successorOf(titleId: TitleId): TitleId? = withContext(ioDispatcher) {
        val survivor = supersession.survivorOf(titleId)
        survivor.takeIf { it != titleId }
    }

    override suspend fun converge(loser: TitleId, survivor: TitleId): Boolean {
        if (loser == survivor) return true
        return withContext(ioDispatcher) {
            var converged = false
            transaction { converged = convergeInTransaction(loser, survivor) }
            converged
        }
    }

    private fun convergeInTransaction(loser: TitleId, survivor: TitleId): Boolean {
        val loserId = loser.value.toString()
        val survivorId = survivor.value.toString()
        val loserRow = titleDao.getById(loserId) ?: return false
        val survivorRow = titleDao.getById(survivorId) ?: return false

        if (loserRow.supersededByTitleId == survivorId) return true

        if (loserRow.supersededByTitleId != null) return false
        if (survivorRow.supersededByTitleId != null) return false

        externalIdDao.remapTitle(loserId, survivorId)
        identityMappingDao.remapTitle(loserId, survivorId)
        availabilityDao.remapTitle(loserId, survivorId)
        editionDao.remapTitle(loserId, survivorId)
        serverItemDao.remapTitle(loserId, survivorId)
        localAssetDao.remapTitle(loserId, survivorId)
        playableCandidateDao.remapTitle(loserId, survivorId)

        reparentLinks(loserId, survivorId)

        convergeWatchState(loserId, survivorId)

        val filled = survivorRow.copy(
            originalTitle = survivorRow.originalTitle ?: loserRow.originalTitle,
            year = survivorRow.year ?: loserRow.year,
            synopsis = survivorRow.synopsis ?: loserRow.synopsis,
            synopsisProvenanceJson = survivorRow.synopsisProvenanceJson ?: loserRow.synopsisProvenanceJson,
            genres = survivorRow.genres ?: loserRow.genres,
            genresProvenanceJson = survivorRow.genresProvenanceJson ?: loserRow.genresProvenanceJson,
            rating = survivorRow.rating ?: loserRow.rating,
            ratingProvenanceJson = survivorRow.ratingProvenanceJson ?: loserRow.ratingProvenanceJson,
        )
        if (filled != survivorRow) titleDao.upsert(filled)

        metadataCacheDao.deleteReferencingTitle(loserId)

        titleDao.markSuperseded(loserId, survivorId)
        return true
    }

    private fun reparentLinks(loserId: String, survivorId: String) {
        for (link in titleLinkDao.listForTitle(loserId)) {
            val parent = if (link.parentTitleId == loserId) survivorId else link.parentTitleId
            val child = if (link.childTitleId == loserId) survivorId else link.childTitleId
            titleLinkDao.deleteLink(link.parentTitleId, link.childTitleId)
            if (parent == child) continue
            titleLinkDao.insert(
                TitleLinkEntity(
                    parentTitleId = parent,
                    childTitleId = child,
                    relation = link.relation,
                ),
            )
        }
    }

    private fun convergeWatchState(loserId: String, survivorId: String) {
        val touched = mutableSetOf<Pair<String, String>>()
        for (lead in KEY_LEADS) {
            val fromPrefix = "$lead$loserId"
            val toPrefix = "$lead$survivorId"
            val profiles = (
                watchEventDao.listProfilesWithKeyPrefix(fromPrefix) +
                    watchStateDao.listByKeyPrefix(fromPrefix).map { it.profileId }
                ).distinct()
            for (profileId in profiles) {
                for (fromKey in watchEventDao.listKeysWithPrefix(profileId, fromPrefix)) {
                    val toKey = toPrefix + fromKey.removePrefix(fromPrefix)
                    watchEventDao.rekey(profileId, fromKey, toKey)
                    touched += profileId to toKey
                }
            }
            for (state in watchStateDao.listByKeyPrefix(fromPrefix)) {

                watchStateDao.deleteByKey(state.profileId, state.watchKey)
                touched += state.profileId to (toPrefix + state.watchKey.removePrefix(fromPrefix))
            }
        }
        for ((profileId, encodedKey) in touched) rematerialize(profileId, encodedKey)
    }

    private fun rematerialize(profileId: String, encodedKey: String) {
        val events = watchEventDao.listForKey(profileId, encodedKey)
        if (events.isEmpty()) return
        val key = runCatching { WatchKeyCodec.decode(encodedKey) }.getOrNull() ?: return
        val state = WatchEventReducer.reduce(
            key = key,
            events = events.map(WatchEventMapper::toCanonical),
            removedSourceIds = removedSourceIds(),
        )
        watchStateDao.upsert(WatchStateMapper.toEntity(profileId, state, updatedAtMs = clock.nowMs()))
    }

    private companion object {

        val KEY_LEADS = listOf("T:", "S:")
    }
}
