package app.encrogram.core.backup

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import app.encrogram.core.internal.SecuredLog
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * The backup's secrets on this phone, under two Android Keystore keys (docs/recovery-phrase-plan.md):
 *
 * 1. `backup/keys.bin`: backupId + backupKey, AES-256-GCM under [IDS_ALIAS], a key WITHOUT user authentication,
 *    so the engine can refresh the backup by itself.
 * 2. `backup/phrase.bin`: the 16 bytes of entropy behind the 12 words, RSA-OAEP under [PHRASE_ALIAS], a key pair
 *    whose private half needs the phone's screen lock (`setUserAuthenticationRequired(true)`, 30 s validity,
 *    `setUnlockedDeviceRequired(true)` on API 28+, StrongBox when the phone has one). Encrypting uses only the
 *    public key, so storing the words after "Back up" or a restore needs no unlock; reading them ("Show 12 words")
 *    needs the real unlock within 30 s, not just a dialog.
 *
 * 3. [metaBox]: BackupStore's sealed bookkeeping (the server tag of the last upload, ids still to delete on the
 *    server), AES-256-GCM under [META_ALIAS], a key without user authentication. [deleteAll] keeps it, so a delete
 *    queued by "Turn off" can still be retried after the other keys are gone.
 *
 * Files live in `noBackupFilesDir` (outside Android backup), never in SharedPreferences. Nothing here is logged.
 * Methods are synchronized: the backup thread, the phrase thread and the engine thread all use the vault.
 */
internal class KeyVault(private val context: Context, private val dir: File) {

    class NeedsUnlock : Exception("unlock needed")
    /** The screen lock was removed or reset since the words were stored: the key is gone for good. */
    class Invalidated : Exception("key invalidated")
    class Missing : Exception("nothing stored")
    class Unavailable(val reason: String) : Exception(reason)

    private val idsFile get() = File(dir, IDS_FILE)
    private val phraseFile get() = File(dir, PHRASE_FILE)

    /** Whether backup secrets are stored (backup is on). Cheap. */
    fun hasSecrets(): Boolean = idsFile.isFile

    fun hasPhrase(): Boolean = phraseFile.isFile

    /** True when the phone has a PIN, pattern or password (needed for the auth-bound key). */
    fun deviceSecure(): Boolean = try {
        (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isDeviceSecure == true
    } catch (e: Exception) {
        false
    }

    @Synchronized
    @Throws(Unavailable::class)
    fun storeSecrets(secrets: BackupSecrets) {
        val plain = hexToBytes(secrets.id) + secrets.key
        try {
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.ENCRYPT_MODE, aesKey(IDS_ALIAS))
            val ct = cipher.doFinal(plain)
            if (!write(idsFile, byteArrayOf(FORMAT) + cipher.iv + ct)) throw Unavailable("write")
        } catch (e: Unavailable) {
            throw e
        } catch (e: Exception) {
            SecuredLog.crash(SecuredLog.Ev.VAULT_FAILED, e)
            throw Unavailable("keystore")
        } finally {
            plain.fill(0)
        }
    }

    /** The stored secrets, or null when backup is off. Throws [Unavailable] when they exist but can't be read. */
    @Synchronized
    @Throws(Unavailable::class)
    fun loadSecrets(): BackupSecrets? {
        val file = idsFile
        if (!file.isFile) return null
        val plain = try {
            val data = file.readBytes()
            if (data.size < 1 + GCM_IV_BYTES + 16 || data[0] != FORMAT) throw Unavailable("format")
            val key = keyStore().getKey(IDS_ALIAS, null) as? SecretKey ?: throw Unavailable("key_missing")
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 1, GCM_IV_BYTES))
            cipher.doFinal(data, 1 + GCM_IV_BYTES, data.size - 1 - GCM_IV_BYTES)
        } catch (e: Unavailable) {
            throw e
        } catch (e: Exception) {
            SecuredLog.crash(SecuredLog.Ev.VAULT_FAILED, e)
            throw Unavailable("unreadable")
        }
        try {
            if (plain.size != 64) throw Unavailable("format")
            return BackupSecrets(Bip39.hex(plain.copyOfRange(0, 32)), plain.copyOfRange(32, 64))
        } finally {
            plain.fill(0)
        }
    }

    /**
     * Stores the words' entropy under a NEW auth-bound key pair (a pair from an earlier screen lock may be
     * invalidated). Throws [Unavailable] when the phone has no screen lock or no usable Keystore.
     */
    @Synchronized
    @Throws(Unavailable::class)
    fun storeEntropy(entropy: ByteArray) {
        if (!deviceSecure()) throw Unavailable("no_screen_lock")
        try {
            val ks = keyStore()
            if (ks.containsAlias(PHRASE_ALIAS)) ks.deleteEntry(PHRASE_ALIAS)
            generatePhraseKey()
            // Encrypt in software with a copy of the public key: public-key use never needs the screen lock.
            val pub = ks.getCertificate(PHRASE_ALIAS)?.publicKey ?: throw Unavailable("key_missing")
            val soft = KeyFactory.getInstance(pub.algorithm).generatePublic(X509EncodedKeySpec(pub.encoded))
            val cipher = Cipher.getInstance(RSA_OAEP)
            cipher.init(Cipher.ENCRYPT_MODE, soft, OAEP_SPEC)
            val ct = cipher.doFinal(entropy)
            if (!write(phraseFile, byteArrayOf(FORMAT) + ct)) throw Unavailable("write")
        } catch (e: Unavailable) {
            throw e
        } catch (e: Exception) {
            SecuredLog.crash(SecuredLog.Ev.VAULT_FAILED, e)
            throw Unavailable("keystore")
        }
    }

    /**
     * The words' entropy. Needs the phone's screen lock confirmed within the last 30 seconds.
     * @throws NeedsUnlock no recent unlock; @throws Invalidated the screen lock changed; @throws Missing none stored.
     */
    @Synchronized
    @Throws(NeedsUnlock::class, Invalidated::class, Missing::class, Unavailable::class)
    fun loadEntropy(): ByteArray {
        val file = phraseFile
        if (!file.isFile) throw Missing()
        val data = try {
            file.readBytes()
        } catch (e: Exception) {
            throw Unavailable("read")
        }
        if (data.size < 2 || data[0] != FORMAT) throw Unavailable("format")
        val key = try {
            keyStore().getKey(PHRASE_ALIAS, null) as? PrivateKey
        } catch (e: Exception) {
            null
        } ?: throw Invalidated()
        val cipher = Cipher.getInstance(RSA_OAEP)
        try {
            cipher.init(Cipher.DECRYPT_MODE, key, OAEP_SPEC)
        } catch (e: UserNotAuthenticatedException) {
            throw NeedsUnlock()
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw Invalidated()
        } catch (e: Exception) {
            SecuredLog.crash(SecuredLog.Ev.VAULT_FAILED, e)
            throw Unavailable("init")
        }
        val entropy = try {
            cipher.doFinal(data, 1, data.size - 1)
        } catch (e: Exception) {
            // Some Keystore versions report a missing unlock only here.
            if (e.javaClass.simpleName == "UserNotAuthenticatedException" || e.cause is UserNotAuthenticatedException) throw NeedsUnlock()
            SecuredLog.crash(SecuredLog.Ev.VAULT_FAILED, e)
            throw Unavailable("decrypt")
        }
        if (entropy.size != Bip39.ENTROPY_BYTES) {
            entropy.fill(0)
            throw Unavailable("format")
        }
        return entropy
    }

    /**
     * Deletes the id+key and words files and their two Keystore keys. Returns false when a file couldn't be deleted.
     * The [metaBox] key stays (BackupStore's delete queue must outlive "Turn off").
     */
    @Synchronized
    fun deleteAll(): Boolean {
        var ok = true
        for (f in listOf(idsFile, phraseFile)) if (f.exists() && !f.delete() && f.exists()) ok = false
        try {
            val ks = keyStore()
            for (alias in listOf(IDS_ALIAS, PHRASE_ALIAS)) if (ks.containsAlias(alias)) ks.deleteEntry(alias)
        } catch (e: Exception) {
            SecuredLog.crash(SecuredLog.Ev.VAULT_FAILED, e)
        }
        return ok
    }

    /** AES-256-GCM under [META_ALIAS] (no user authentication), for BackupStore's sealed files. */
    fun metaBox(): BackupStore.Box = object : BackupStore.Box {
        override fun seal(plain: ByteArray): ByteArray = synchronized(this@KeyVault) {
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.ENCRYPT_MODE, aesKey(META_ALIAS))
            byteArrayOf(FORMAT) + cipher.iv + cipher.doFinal(plain)
        }

        override fun open(sealed: ByteArray): ByteArray = synchronized(this@KeyVault) {
            if (sealed.size < 1 + GCM_IV_BYTES + 16 || sealed[0] != FORMAT) throw IllegalArgumentException("format")
            val key = keyStore().getKey(META_ALIAS, null) as? SecretKey ?: throw IllegalStateException("key_missing")
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 1, GCM_IV_BYTES))
            cipher.doFinal(sealed, 1 + GCM_IV_BYTES, sealed.size - 1 - GCM_IV_BYTES)
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /** The AES key under [alias], created (no user authentication) when missing. */
    private fun aesKey(alias: String): SecretKey {
        (keyStore().getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    @Suppress("DEPRECATION")
    private fun phraseSpec(strongBox: Boolean): KeyGenParameterSpec {
        val b = KeyGenParameterSpec.Builder(PHRASE_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            .setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= 30) {
            b.setUserAuthenticationParameters(AUTH_VALIDITY_SECONDS, KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            b.setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS)
        }
        if (Build.VERSION.SDK_INT >= 28) {
            b.setUnlockedDeviceRequired(true)
            if (strongBox) b.setIsStrongBoxBacked(true)
        }
        return b.build()
    }

    private fun generatePhraseKey() {
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE)
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                gen.initialize(phraseSpec(strongBox = true))
                gen.generateKeyPair()
                return
            } catch (e: Exception) {
                // StrongBoxUnavailableException (no StrongBox), or a chip that refuses this key shape: TEE below.
                SecuredLog.i(SecuredLog.Ev.VAULT_FAILED, "strongbox_fallback")
            }
            try {
                keyStore().let { if (it.containsAlias(PHRASE_ALIAS)) it.deleteEntry(PHRASE_ALIAS) }
            } catch (_: Exception) {
            }
        }
        gen.initialize(phraseSpec(strongBox = false))
        gen.generateKeyPair()
    }

    private fun write(file: File, data: ByteArray): Boolean {
        val tmp = File(dir, file.name + ".tmp")
        return try {
            if (!dir.isDirectory && !dir.mkdirs()) return false
            tmp.writeBytes(data)
            if (!tmp.renameTo(file)) {
                tmp.delete()
                false
            } else {
                true
            }
        } catch (e: Exception) {
            tmp.delete()
            false
        }
    }

    companion object {
        const val IDS_FILE = "keys.bin"
        const val PHRASE_FILE = "phrase.bin"
        const val IDS_ALIAS = "encrogram_secured_backup_ids"
        const val PHRASE_ALIAS = "encrogram_secured_backup_phrase"
        const val META_ALIAS = "encrogram_secured_backup_meta"
        const val AUTH_VALIDITY_SECONDS = 30
        private const val FORMAT: Byte = 1
        private const val GCM_IV_BYTES = 12
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val RSA_OAEP = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"

        /** SHA-256 OAEP with MGF1-SHA1: the MGF1 digest Android Keystore uses for OAEP on every API level. */
        private val OAEP_SPEC = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)

        private fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[2 * i], 16) shl 4) or Character.digit(hex[2 * i + 1], 16)).toByte()
        }
    }
}
