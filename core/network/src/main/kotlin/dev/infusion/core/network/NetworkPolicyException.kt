package dev.infusion.core.network

import java.io.IOException

class NetworkPolicyException(
    val reason: DiagnosticReason,
    message: String,
    val origin: String? = null,
    cause: Throwable? = null,
) : IOException(message, cause)
