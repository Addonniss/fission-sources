package dev.infusion.feature.vertical.player

import java.util.logging.ConsoleHandler
import java.util.logging.Logger

internal object PlayerForensicsLog {
    private val logger: Logger = Logger.getLogger("INFUSION-PLAYER-FORENSICS").apply {

        if (handlers.isEmpty()) {
            useParentHandlers = false
            addHandler(ConsoleHandler())
        }
    }

    fun event(stage: String, fields: String) {
        logger.info("stage=$stage $fields")
    }
}
