package dev.infusion.core.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SharedPrefsSecretStorage(
    private val prefs: SharedPreferences,
) : SecretStorage {

    override fun read(key: String): String? = prefs.getString(key, null)

    override fun write(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String): Boolean {
        val existed = prefs.contains(key)
        prefs.edit().remove(key).apply()
        return existed
    }

    override fun keys(): List<String> = prefs.all.keys.toList()

    override fun clear() {
        prefs.edit().clear().apply()
    }
}

class KeystoreAesGcmCipher(
    override val keyAlias: String = DEFAULT_KEY_ALIAS,
) : SecretCipher {

    override val algorithm: String = TRANSFORMATION

    init {
        ensureKey()
    }

    private fun ensureKey() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(keyAlias)) {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generator.generateKey()
        }
    }

    override fun encrypt(plain: ByteArray): EncryptedBlob {
        val key = loadKey(keyAlias)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return EncryptedBlob(
            iv = cipher.iv,
            ciphertext = cipher.doFinal(plain),
            algorithm = TRANSFORMATION,
            keyAlias = keyAlias,
        )
    }

    override fun decrypt(blob: EncryptedBlob): ByteArray {
        val key = loadKey(blob.keyAlias)
        val cipher = Cipher.getInstance(blob.algorithm)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, blob.iv))
        return cipher.doFinal(blob.ciphertext)
    }

    private fun loadKey(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(alias, null) as? SecretKey
            ?: throw IllegalStateException("keystore key missing: $alias")
    }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_KEY_ALIAS = "infusion.secret.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}

object SecretStoreFactory {
    fun create(context: Context, prefsName: String = "infusion_secrets"): KeystoreSecretStore {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        return KeystoreSecretStore(
            cipher = KeystoreAesGcmCipher(),
            storage = SharedPrefsSecretStorage(prefs),
        )
    }
}
