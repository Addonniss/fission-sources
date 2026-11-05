package dev.infusion.core.security

data class SecretReference(val id: String) {
    init {
        require(id.isNotBlank()) { "reference id must not be blank" }
    }

    override fun toString(): String = "SecretReference(${id.take(4)}…)"
}

data class SecretContext(
    val purpose: String = "generic",
    val sourceId: String? = null,
)

interface SecretStore {
    fun put(secret: ByteArray, context: SecretContext = SecretContext()): SecretReference
    fun get(reference: SecretReference): ByteArray?
    fun delete(reference: SecretReference): Boolean
    fun exists(reference: SecretReference): Boolean

    fun purge(): Int
}

class InMemorySecretStore : SecretStore {

    private val store = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    override fun put(secret: ByteArray, context: SecretContext): SecretReference {
        val reference = SecretReference(java.util.UUID.randomUUID().toString())
        store[reference.id] = secret.copyOf()
        return reference
    }

    override fun get(reference: SecretReference): ByteArray? = store[reference.id]?.copyOf()

    override fun delete(reference: SecretReference): Boolean = store.remove(reference.id) != null

    override fun exists(reference: SecretReference): Boolean = store.containsKey(reference.id)

    override fun purge(): Int {
        val count = store.size
        store.clear()
        return count
    }
}
