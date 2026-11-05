package dev.infusion.source.addon.resource

import dev.infusion.source.addon.repository.InstalledAddonRuntime
import dev.infusion.source.aggregation.resource.AddonCatalogRepository
import dev.infusion.source.aggregation.resource.DeclaredCatalog
import dev.infusion.source.stremio.StremioResult
import dev.infusion.source.stremio.policy.StremioContentType
import dev.infusion.source.stremio.wire.Resource
import dev.infusion.source.stremio.wire.StremioCatalogResponse

class DefaultAddonCatalogRepository(
    private val runtimes: AddonResourceRuntimeAccessor,
) : AddonCatalogRepository {

    override fun declaredCatalogs(addonId: String): List<DeclaredCatalog> {
        val installed = runtimes.enabledInstalled().firstOrNull { it.addonId == addonId } ?: return emptyList()
        return installed.snapshot.catalogs.map { c ->
            DeclaredCatalog(
                type = c.type,
                id = c.id,
                name = c.name,
                extras = c.extras.map { it.name },
            )
        }
    }

    override suspend fun catalog(
        addonId: String,
        type: StremioContentType,
        catalogId: String,
        extras: List<Pair<String, String>>,
        skip: Int?,
    ): StremioResult<StremioCatalogResponse> {
        val runtime = runtimes.runtimeFor(addonId) ?: return notInstalled()

        if (!runtime.installed.enabled || !runtime.providerState.isEnabled) return disabled()
        val snapshot = runtime.installed.snapshot

        if (!AddonResourceMatcher.supports(snapshot, Resource.CATALOG, type)) return notEligible()

        val declared = snapshot.catalogs.firstOrNull { it.id == catalogId && it.type == type }
            ?: return routeRejected()

        val allowedExtras = setOf("search", "skip") + declared.extras.map { it.name }
        val withSkip = if (skip != null) extras + ("skip" to skip.toString()) else extras
        return runtime.useClient { client ->
            client.catalog(
                type = type,
                catalogId = catalogId,
                extras = withSkip,
                allowedExtraKeys = allowedExtras,
                cancellation = runtime.providerState.currentScope(),
            )
        }
    }

    private fun notInstalled(): StremioResult<StremioCatalogResponse> =
        StremioResult.Err(routeRejectedFailure("catalog add-on not installed"))

    private fun disabled(): StremioResult<StremioCatalogResponse> =
        StremioResult.Err(routeRejectedFailure("catalog add-on disabled"))

    private fun notEligible(): StremioResult<StremioCatalogResponse> =
        StremioResult.Err(routeRejectedFailure("add-on does not support catalog for this type/id"))

    private fun routeRejected(): StremioResult<StremioCatalogResponse> =
        StremioResult.Err(routeRejectedFailure("catalog not declared by add-on"))

    private fun routeRejectedFailure(message: String): dev.infusion.source.stremio.StremioFailure =
        dev.infusion.source.stremio.StremioFailure(
            reason = dev.infusion.core.network.DiagnosticReason.SCHEMA_INVALID,
            status = null,
            message = message,
            retryable = false,
            diagnostic = dev.infusion.core.network.RedactedDiagnostic(
                correlationId = dev.infusion.core.network.CorrelationIds.newId(),
                reason = dev.infusion.core.network.DiagnosticReason.SCHEMA_INVALID,
                origin = null,
            ),
        )
}
