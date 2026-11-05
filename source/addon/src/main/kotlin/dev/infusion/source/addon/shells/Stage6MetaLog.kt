package dev.infusion.source.addon.shells

internal object Stage6MetaLog {
    private val logger = java.util.logging.Logger.getLogger("STAGE6-META")

    fun log(message: String) {
        logger.info(message)
    }
}
