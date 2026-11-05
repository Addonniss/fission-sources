package dev.infusion.source.addon.store

import dev.infusion.core.security.SecretContext
import dev.infusion.core.security.SecretReference
import dev.infusion.core.security.SecretStore
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

class AddonCredentialStore(
    private val secretStore: SecretStore,
) {

    fun stage(query: String): String {
        val reference = secretStore.put(
            query.toByteArray(StandardCharsets.UTF_8),
            SecretContext(purpose = PURPOSE_ADDON_QUERY, sourceId = null),
        )
        return reference.id
    }

    fun resolve(referenceId: String): String? {
        val bytes = secretStore.get(SecretReference(referenceId)) ?: return null
        return decodeStrictUtf8(bytes)
    }

    private fun decodeStrictUtf8(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }

    fun delete(referenceId: String) {
        secretStore.delete(SecretReference(referenceId))
    }

    fun exists(referenceId: String): Boolean = secretStore.exists(SecretReference(referenceId))

    private companion object {
        const val PURPOSE_ADDON_QUERY = "addon.configured.query"
    }
}
