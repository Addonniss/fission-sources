package dev.infusion.core.playback.media3

import dev.infusion.core.playback.media3.BuildConfig
import java.util.logging.ConsoleHandler
import java.util.logging.Logger

internal object Media3ForensicsLog {
    private val logger: Logger = Logger.getLogger("INFUSION-MEDIA3-FORENSICS").apply {

        if (handlers.isEmpty()) {
            useParentHandlers = false
            addHandler(ConsoleHandler())
        }
    }

    fun event(stage: String, fields: String) {
        if (BuildConfig.DEBUG) logger.info("stage=$stage $fields")
    }
}
