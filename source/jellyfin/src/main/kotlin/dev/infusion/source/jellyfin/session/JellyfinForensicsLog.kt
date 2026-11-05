package dev.infusion.source.jellyfin.session

import dev.infusion.source.jellyfin.BuildConfig
import java.security.MessageDigest
import java.util.logging.ConsoleHandler
import java.util.logging.Logger

object JellyfinForensicsLog {
    private val logger: Logger = Logger.getLogger("INFUSION-JELLYFIN-FORENSICS").apply {

        if (handlers.isEmpty()) {
            useParentHandlers = false
            addHandler(ConsoleHandler())
        }
    }

    fun event(stage: String, fields: String) {
        if (BuildConfig.DEBUG) logger.info("stage=$stage $fields")
    }

    fun opaque(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(12)
}
