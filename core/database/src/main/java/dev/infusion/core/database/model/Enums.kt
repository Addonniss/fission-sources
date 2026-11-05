package dev.infusion.core.database.model

enum class TitleKind { MOVIE, SERIES, SEASON, EPISODE, CLIP }

enum class TitleRelation { SEASON_OF, EPISODE_OF, SPECIAL_OF }

enum class EditionKind {
    THEATRICAL, DIRECTORS_CUT, REGIONAL, RESTORED, ALTERNATE_ORDERING, UNKNOWN,
}

enum class SourceType { SAF, JELLYFIN, STREMIO_REMOTE }

enum class TrustClass {
    DEVICE_LOCAL, PERSONAL_SERVER, LAN_SHARE, REMOTE_ADDON, TRACKING_SERVICE,
}

enum class HealthState { UNKNOWN, HEALTHY, DEGRADED, UNREACHABLE }

enum class SecretKind { ACCESS_TOKEN, REFRESH_TOKEN, API_KEY, HEADER_VALUE, CLIENT_CERTIFICATE }

enum class AccessState { UNKNOWN, GRANTED, REVOKED, OFFLINE, REAUTHORIZATION_REQUIRED }

enum class AvailabilityKind { LOCAL, SERVER, ADDON, LEGAL_LINK }

enum class ExpectedMode { DIRECT, REMUX, TRANSCODE, EXTERNAL_LINK, UNRESOLVED }

enum class CapabilityState { SUPPORTED, DEGRADED, UNSUPPORTED, UNKNOWN }

enum class ReachabilityState { UNKNOWN, REACHABLE, UNREACHABLE, EXPIRED }

enum class EventOrigin { LOCAL, IMPORT }

enum class RemovalScope {

    REVOKE_ONLY,

    INCLUDING_CACHED_DATA,

    INCLUDING_WATCH_HISTORY,
}
