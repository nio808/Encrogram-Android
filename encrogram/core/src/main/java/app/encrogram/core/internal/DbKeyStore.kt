package app.encrogram.core.internal

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
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
internal class DbKeyStore(private val context: Context) {

    class KeyFailure(val reason: String) : Exception(reason)

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Returns the passphrase, creating it when there is none yet.
     * @param databaseExists whether database files are already on disk. If they are, a missing or unreadable
     *   passphrase is fatal (a new one could not open them), and a Keystore error that may pass is retried first
     *   ([readWithRetry]); otherwise a fresh passphrase is made.
     */
    fun obtain(databaseExists: Boolean): String {
        val ct = prefs.getString(KEY_CIPHERTEXT, null)
        val iv = prefs.getString(KEY_IV, null)
        if (ct != null && iv != null) {
            if (databaseExists) return readWithRetry({ decrypt(ct, iv) })
            try {
                return decrypt(ct, iv)
            } catch (e: Exception) {
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

    /**
     * "Reset Secured" (b3): forgets the passphrase (our own prefs file is emptied, then deleted) and deletes its Keystore
     * key. Only this file and alias: never another preference file. False when the passphrase couldn't be removed; a
     * Keystore key that can't be deleted is only logged (with no passphrase left it unlocks nothing).
     */
    fun wipe(): Boolean {
        val cleared = prefs.edit().clear().commit()
        try {
            val ks = keyStore()
            if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
        } catch (e: Exception) {
            SecuredLog.crash(SecuredLog.Ev.DB_KEY_FAILED, e)
        }
        if (cleared) {
            try {
                context.deleteSharedPreferences(PREFS_NAME)
            } catch (e: Exception) {
                SecuredLog.crash(SecuredLog.Ev.DB_KEY_FAILED, e)
            }
        }
        return cleared
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
        val (ctBytes, ivBytes) = try {
            dec.decode(ct) to dec.decode(iv)
        } catch (e: IllegalArgumentException) {
            throw StoredValueBroken()
        }
        val key = existingWrappingKey() ?: throw KeyGone()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, ivBytes))
        return String(cipher.doFinal(ctBytes), Charsets.UTF_8)
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

    /** The Keystore has no key under our alias (getKey returned null). */
    internal class KeyGone : Exception()

    /** The stored passphrase or IV isn't valid base64 any more. */
    internal class StoredValueBroken : Exception()

    companion object {
        /** [KeyFailure] reason: the Keystore kept failing for a few seconds. Not a lost key: a later start can still work. */
        const val KEYSTORE_BUSY = "keystore_busy"

        /** Pauses before each new read after a Keystore error that may pass (daemon restart, right after boot or an update). */
        internal val RETRY_PAUSES_MS = longArrayOf(300, 1_000, 2_000)

        /**
         * True when the passphrase can never be read on this phone (A3): the key is gone or invalidated, the stored values
         * are broken, or the key doesn't open them (the GCM tag fails). Any other Keystore error may pass.
         */
        internal fun readLost(e: Throwable): Boolean =
            e is KeyGone || e is StoredValueBroken || e is KeyPermanentlyInvalidatedException || e is AEADBadTagException

        /**
         * [read] with retries for errors that may pass. Throws [KeyFailure]: "key_unreadable" (only a reset helps,
         * KEY_LOST) at once for [readLost], else [KEYSTORE_BUSY] after the last retry.
         */
        internal fun readWithRetry(read: () -> String, pause: (Long) -> Unit = Thread::sleep): String {
            var retries = 0
            while (true) {
                val failure = try {
                    return read()
                } catch (e: Exception) {
                    e
                }
                if (readLost(failure)) {
                    SecuredLog.e(SecuredLog.Ev.DB_KEY_FAILED, failure.javaClass.simpleName)
                    throw KeyFailure("key_unreadable")
                }
                if (retries == RETRY_PAUSES_MS.size) {
                    SecuredLog.crash(SecuredLog.Ev.DB_KEY_FAILED, failure)
                    throw KeyFailure(KEYSTORE_BUSY)
                }
                SecuredLog.w(SecuredLog.Ev.DB_KEY_FAILED, "retry", retries + 1)
                pause(RETRY_PAUSES_MS[retries++])
            }
        }

        const val PREFS_NAME = "encrogram_secured_keys"
        private const val KEY_CIPHERTEXT = "db_passphrase_ct"
        private const val KEY_IV = "db_passphrase_iv"
        private const val KEY_ALIAS = "encrogram_secured_db_wrap"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}
