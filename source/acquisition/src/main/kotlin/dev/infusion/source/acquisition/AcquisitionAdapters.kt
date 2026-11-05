package dev.infusion.source.acquisition

import dev.infusion.core.domain.acquisition.AcquisitionConnection
import dev.infusion.core.domain.acquisition.AcquisitionPort
import dev.infusion.core.domain.acquisition.AcquisitionService
import dev.infusion.core.network.OkHttpHardenedClient

fun radarrAcquisitionAdapter(
    connection: AcquisitionConnection,
    apiKey: String,
    transportFactory: (() -> OkHttpHardenedClient)? = null,
): Result<RadarrAcquisitionAdapter> {
    if (apiKey.isBlank()) return Result.failure(IllegalArgumentException("api key must not be blank"))
    return acquisitionTransport(connection.url, transportFactory).map { (url, transport) ->
        RadarrAcquisitionAdapter(
            serviceId = AcquisitionService.RADARR.id,
            serviceInstance = url.origin,
            connection = connection,
            http = AcquisitionHttp(url, transport, apiKey),
        )
    }
}

fun sonarrAcquisitionAdapter(
    connection: AcquisitionConnection,
    apiKey: String,
    transportFactory: (() -> OkHttpHardenedClient)? = null,
): Result<SonarrAcquisitionAdapter> {
    if (apiKey.isBlank()) return Result.failure(IllegalArgumentException("api key must not be blank"))
    return acquisitionTransport(connection.url, transportFactory).map { (url, transport) ->
        SonarrAcquisitionAdapter(
            serviceId = AcquisitionService.SONARR.id,
            serviceInstance = url.origin,
            connection = connection,
            http = AcquisitionHttp(url, transport, apiKey),
        )
    }
}
