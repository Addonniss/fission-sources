package dev.infusion.core.network

enum class DiagnosticReason {

    INVALID_URL,

    SCHEME_DENIED,

    PRIVATE_DESTINATION,

    REDIRECT_POLICY,

    SCHEMA_INVALID,

    LIMIT_EXCEEDED,

    UNSUPPORTED_LOCATOR,

    UNSUPPORTED_ENCODING,

    CONCURRENCY_EXCEEDED,

    TIMEOUT,

    CANCELLED,

    DNS_FAILED,

    CONNECTION_FAILED,

    CLEARTEXT_BLOCKED,

    UNAUTHORIZED,

    FORBIDDEN,

    NOT_FOUND,

    RATE_LIMITED,

    SERVER_ERROR,

    INTERNAL,
}

val DiagnosticReason.isProviderObservation: Boolean
    get() = this != DiagnosticReason.CONCURRENCY_EXCEEDED &&
        this != DiagnosticReason.CANCELLED &&
        this != DiagnosticReason.INTERNAL
