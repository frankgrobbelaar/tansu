package io.github.frankgrobbelaar.tansu.servers

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/** Each server's password or key, kept encrypted on the phone. */
interface Secrets {
    suspend fun read(serverId: String): Secret

    suspend fun write(serverId: String, secret: Secret)

    suspend fun forget(serverId: String)
}

/** A secret that can't be opened, as when the phone's key for it was lost. */
class SecretLost(cause: Throwable) : Exception("A secret can't be opened", cause)

/** Seals and opens bytes with a key that never leaves the phone. */
interface SecretCipher {
    fun seal(plain: ByteArray): ByteArray

    fun open(sealed: ByteArray): ByteArray
}

/** [Secrets] in a DataStore of their own, each sealed by [cipher]. */
class StoredSecrets(
    private val store: DataStore<Preferences>,
    private val cipher: SecretCipher = KeystoreCipher(),
) : Secrets {

    override suspend fun read(serverId: String): Secret {
        val sealed = store.data.first()[keyFor(serverId)] ?: return Secret()
        return try {
            val plain = cipher.open(Base64.getDecoder().decode(sealed))
            json.decodeFromString(plain.decodeToString())
        } catch (e: GeneralSecurityException) {
            throw SecretLost(e)
        } catch (e: IllegalArgumentException) {
            throw SecretLost(e)
        }
    }

    override suspend fun write(serverId: String, secret: Secret) {
        val sealed = cipher.seal(json.encodeToString(secret).encodeToByteArray())
        store.edit { it[keyFor(serverId)] = Base64.getEncoder().encodeToString(sealed) }
    }

    override suspend fun forget(serverId: String) {
        store.edit { it.remove(keyFor(serverId)) }
    }

    private fun keyFor(serverId: String) = stringPreferencesKey("server_$serverId")

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** AES-GCM under a non-exportable key in the Android Keystore. */
class KeystoreCipher(private val alias: String = "tansu-secrets") : SecretCipher {

    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        val iv = sealed.copyOfRange(0, IV_BYTES)
        val cipher =
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            }
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    private fun key(): SecretKey {
        val keys = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keys.getKey(alias, null) as? SecretKey)?.let {
            return it
        }
        val spec =
            KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val KEY_BITS = 256
    }
}
