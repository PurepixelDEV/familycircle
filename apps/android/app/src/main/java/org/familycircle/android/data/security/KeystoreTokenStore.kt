package org.familycircle.android.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import org.familycircle.android.api.ApiJson
import org.familycircle.android.api.TokenStore
import org.familycircle.android.api.Tokens
import org.familycircle.android.util.Logger
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the tokens AES-256-GCM encrypted. The key is generated inside the AndroidKeyStore and never
 * leaves it; only IV + ciphertext are written to a private SharedPreferences file.
 *
 * The key is not bound to user authentication because the foreground service must upload while the
 * screen is locked. If decryption fails (key invalidated, data restored onto another device) the
 * tokens are discarded and the user signs in again.
 *
 * Deliberately not using the deprecated androidx.security:security-crypto library.
 */
class KeystoreTokenStore(context: Context) : TokenStore {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Serializable
    private data class Stored(val a: String, val r: String, val e: Long)

    override fun load(): Tokens? {
        val blob = prefs.getString(KEY_BLOB, null) ?: return null
        return try {
            val bytes = Base64.decode(blob, Base64.NO_WRAP)
            require(bytes.size > IV_LENGTH)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_LENGTH))
            val plain = cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH)
            val stored = ApiJson.decodeFromString(Stored.serializer(), plain.decodeToString())
            Tokens(stored.a, stored.r, stored.e)
        } catch (e: Exception) {
            Logger.w(TAG, "stored tokens unreadable, discarding", e)
            clear()
            null
        }
    }

    override fun save(tokens: Tokens) {
        val plain = ApiJson.encodeToString(Stored.serializer(), Stored(tokens.accessToken, tokens.refreshToken, tokens.accessExpiresAtMs))
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        check(iv.size == IV_LENGTH) { "unexpected IV length" }
        val ct = cipher.doFinal(plain.encodeToByteArray())
        val blob = Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        // commit(): a rotated refresh token must be on disk before it is used, otherwise a crash
        // would leave only the old (now revoked) token behind.
        prefs.edit(commit = true) { putString(KEY_BLOB, blob) }
    }

    override fun clear() {
        prefs.edit(commit = true) { remove(KEY_BLOB) }
    }

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "TokenStore"
        const val FILE = "fc_secure"
        const val KEY_BLOB = "tokens"
        const val KEY_ALIAS = "familycircle_tokens_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}
