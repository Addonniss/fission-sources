package dev.infusion.core.domain.candidate

import dev.infusion.core.model.CandidateFamilyId
import dev.infusion.core.model.NativeItemId
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object CandidateFamily {

    private const val NAMESPACE = "infusion/candidate-family/v1"

    fun derive(
        nativeItemId: NativeItemId,
        versionId: String?,
        namespace: String = NAMESPACE,
    ): CandidateFamilyId {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(namespace.toByteArray(StandardCharsets.UTF_8))
        digest.update(0)
        digest.update(nativeItemId.sourceId.value.toString().toByteArray(StandardCharsets.UTF_8))
        digest.update(0)
        digest.update(nativeItemId.nativeId.toByteArray(StandardCharsets.UTF_8))
        digest.update(0)
        digest.update((versionId ?: "").toByteArray(StandardCharsets.UTF_8))
        val hex = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return CandidateFamilyId(hex)
    }
}
