package app.encrogram.core.internal

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Secured database passphrase.
 *
 * A random 32-byte passphrase (base64, like upstream's `randomDatabasePassword`) is generated once and stored
 * encrypted with a non-exportable Android Keystore AES-256-GCM key, in our own SharedPreferences file
 * ("encrogram_secured_keys"). Nothing here is ever logged.
 */
internal class DbKeyStore(context: Context) {

    class KeyFailure(val reason: String) : Exception(reason)

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Returns the passphrase, creating it when there is none yet.
     * @param databaseExists whether database files are already on disk. If they are, a missing or unreadable
     *   passphrase is fatal (a new one could not open them); otherwise a fresh passphrase is made.
     */
    fun obtain(databaseExists: Boolean): String {
        val ct = prefs.getString(KEY_CIPHERTEXT, null)
        val iv = prefs.getString(KEY_IV, null)
        if (ct != null && iv != null) {
            try {
                return decrypt(ct, iv)
            } catch (e: Exception) {
                if (databaseExists) throw KeyFailure("key_unreadable")
                // No database to lose: replace the unusable passphrase below.
            }
        } else if (databaseExists) {
            throw KeyFailure("key_missing")
        }
        val passphrase = newPassphrase()
        val (newCt, newIv) = try {
            encrypt(passphrase)
        } catch (e: Exception) {
            throw KeyFailure("keystore_unavailable")
        }
        // commit(), not apply(): the passphrase must be on disk before the database is created with it.
        if (!prefs.edit().putString(KEY_CIPHERTEXT, newCt).putString(KEY_IV, newIv).commit()) {
            throw KeyFailure("key_not_saved")
        }
        SecuredLog.i(SecuredLog.Ev.DB_KEY_CREATED)
        return passphrase
    }

    /**
     * Replaces the stored passphrase (restore: first the backup's key, then the fresh one after `/_db encryption`).
     * Written with `commit()` before the caller opens the databases with it. Throws [KeyFailure] when it can't be
     * stored; the old value then stays.
     */
    fun replace(passphrase: String) {
        val (ct, iv) = try {
            encrypt(passphrase)
        } catch (e: Exception) {
            throw KeyFailure("keystore_unavailable")
        }
        if (!prefs.edit().putString(KEY_CIPHERTEXT, ct).putString(KEY_IV, iv).commit()) throw KeyFailure("key_not_saved")
        SecuredLog.i(SecuredLog.Ev.DB_KEY_CREATED, "replaced")
    }

    /** A new random passphrase in the same form as [obtain] makes (32 random bytes, base64). */
    fun freshPassphrase(): String = newPassphrase()

    private fun newPassphrase(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return try {
            Base64.getEncoder().encodeToString(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun encrypt(plain: String): Pair<String, String> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val enc = Base64.getEncoder()
        return enc.encodeToString(ct) to enc.encodeToString(cipher.iv)
    }

    private fun decrypt(ct: String, iv: String): String {
        val dec = Base64.getDecoder()
        val key = existingWrappingKey() ?: throw IllegalStateException("no wrapping key")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, dec.decode(iv)))
        return String(cipher.doFinal(dec.decode(ct)), Charsets.UTF_8)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingWrappingKey(): SecretKey? = keyStore().getKey(KEY_ALIAS, null) as? SecretKey

    private fun wrappingKey(): SecretKey {
        existingWrappingKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val PREFS_NAME = "encrogram_secured_keys"
        private const val KEY_CIPHERTEXT = "db_passphrase_ct"
        private const val KEY_IV = "db_passphrase_iv"
        private const val KEY_ALIAS = "encrogram_secured_db_wrap"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}
