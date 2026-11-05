package dev.infusion.core.security

interface SecretCipher {
    val keyAlias: String
    val algorithm: String

    fun encrypt(plain: ByteArray): EncryptedBlob

    fun decrypt(blob: EncryptedBlob): ByteArray
}

data class EncryptedBlob(
    val iv: ByteArray,
    val ciphertext: ByteArray,
    val algorithm: String,
    val keyAlias: String,
)

interface SecretStorage {
    fun read(key: String): String?
    fun write(key: String, value: String)
    fun remove(key: String): Boolean
    fun keys(): List<String>
    fun clear()
}

class KeystoreSecretStore(
    private val cipher: SecretCipher,
    private val storage: SecretStorage,
) : SecretStore {

    override fun put(secret: ByteArray, context: SecretContext): SecretReference {
        val blob = cipher.encrypt(secret)
        val reference = SecretReference(java.util.UUID.randomUUID().toString())
        storage.write(storageKey(reference), encode(blob))
        return reference
    }

    override fun get(reference: SecretReference): ByteArray? {
        val raw = storage.read(storageKey(reference)) ?: return null
        val blob = try {
            decode(raw)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return try {
            cipher.decrypt(blob)
        } catch (_: Exception) {
            null
        }
    }

    override fun delete(reference: SecretReference): Boolean = storage.remove(storageKey(reference))

    override fun exists(reference: SecretReference): Boolean = storage.read(storageKey(reference)) != null

    override fun purge(): Int {
        val keys = storage.keys().filter { it.startsWith(PREFIX) }
        keys.forEach { storage.remove(it) }
        return keys.size
    }

    private fun storageKey(reference: SecretReference): String = PREFIX + reference.id

    private fun encode(blob: EncryptedBlob): String = listOf(
        base64(blob.algorithm.toByteArray(Charsets.UTF_8)),
        base64(blob.keyAlias.toByteArray(Charsets.UTF_8)),
        base64(blob.iv),
        base64(blob.ciphertext),
    ).joinToString(SEPARATOR)

    private fun decode(raw: String): EncryptedBlob {
        val parts = raw.split(SEPARATOR)
        require(parts.size == 4) { "malformed blob" }
        return EncryptedBlob(
            iv = decodeBase64(parts[2]),
            ciphertext = decodeBase64(parts[3]),
            algorithm = String(decodeBase64(parts[0]), Charsets.UTF_8),
            keyAlias = String(decodeBase64(parts[1]), Charsets.UTF_8),
        )
    }

    private fun base64(bytes: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(bytes)

    private fun decodeBase64(s: String): ByteArray =
        java.util.Base64.getDecoder().decode(s)

    companion object {
        const val PREFIX = "secret."
        const val SEPARATOR = "|"
    }
}
