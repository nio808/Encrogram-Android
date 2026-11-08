package app.encrogram.core.backup

import app.encrogram.core.internal.SecuredLog
import java.io.File
import java.security.MessageDigest

/**
 * The backup's local bookkeeping in `noBackupFilesDir/encrogram-secured/backup/` (never SharedPreferences, which
 * Telegram's `allowBackup=true` hands to Android backup). The id, key and words live in [KeyVault].
 *
 * Plain files (nothing secret in them; the backup id appears only as a one-way hash, [seenKey]):
 * - `state`: upload counter, last backup time, "changed since the last backup", last error code, the automatic-upload
 *   schedule (owed now, skip the tag check once, paused for another phone, failures in a row, last attempt) and the
 *   newest counter seen per backup (rollback check).
 * - `restore_pending`: contacts and group member connections still being repaired after a restore ([RepairKey]) and the
 *   item id the restore started from, so the work survives restarts.
 *
 * Sealed files (through [Box], an Android Keystore key without user authentication, see [KeyVault.metaBox]):
 * - `server_tag.bin`: the server's ETag of this phone's last upload, bound to the backup id (review finding 4).
 * - `delete_queue.bin`: backup ids whose server copy must still be deleted (a failed "turn off", or the old id after a
 *   phrase change), retried later (finding 7), each with the tag of this phone's last upload under it: only that copy
 *   is ever deleted (R6.3). The ids are server permissions, hence sealed.
 *
 * Thread-safe (every method is synchronized). Writes go to a temp file and are renamed into place.
 */
internal class BackupStore(private val dir: File, private val box: Box? = null) {

    /** Seals small secrets for the files above. Throws on any failure; messages never contain the data. */
    interface Box {
        fun seal(plain: ByteArray): ByteArray
        fun open(sealed: ByteArray): ByteArray
    }

    data class Meta(
        /** Counter of the last upload from this phone (0: never). */
        val counter: Long = 0,
        val lastBackupMs: Long? = null,
        /** Something changed since the last successful backup. */
        val dirty: Boolean = false,
        /** Code of the last failure (see ErrorMapper.backupErrorText), or null. */
        val error: String? = null,
        /** An automatic upload is owed soon: after a restore, a new contact, a finished queue switch or repair sync. */
        val urgent: Boolean = false,
        /** The next upload skips the server-tag check: set by a restore, so the restored phone becomes the backup phone. */
        val unconditional: Boolean = false,
        /** Another phone uploaded with these words: automatic uploads are paused until the user acts. */
        val otherPhone: Boolean = false,
        /** Automatic attempts that failed in a row ([backoffMs]); 0 after a success. */
        val failures: Int = 0,
        /** When the last automatic attempt started. */
        val lastAttemptMs: Long? = null,
    )

    /**
     * One contact or member connection after a restore (RestoreRepair). [proven]: the ratchet is known to work both ways
     * (a completed sync or a delivery receipt on a message sent after the restore); [switched]: `/_switch` was sent;
     * [forced]: a restore `/_sync` was accepted; [syncing]: a sync we started is under way, so `ok` will mean it
     * completed; [reforced]: the one late second force was used; [forcedAtMs]: when the last restore sync was accepted.
     */
    data class Pending(
        val proven: Boolean = false,
        val switched: Boolean = false,
        val forced: Boolean = false,
        val syncing: Boolean = false,
        val reforced: Boolean = false,
        val forcedAtMs: Long = 0L,
    )

    private val stateFile get() = File(dir, STATE_FILE)
    private val pendingFile get() = File(dir, PENDING_FILE)

    private var meta: Meta? = null
    private val seen = HashMap<String, Long>()

    @Synchronized
    fun meta(): Meta = loaded()

    /** Applies [change] and writes it. Returns false (and keeps the old value in memory) when the write failed. */
    @Synchronized
    fun update(change: (Meta) -> Meta): Boolean {
        val old = loaded()
        val next = change(old)
        if (next == old) return true
        meta = next
        if (!save()) {
            meta = old
            return false
        }
        return true
    }

    /** Newest counter this phone has seen for the backup [id], or null. */
    @Synchronized
    fun highestSeen(id: String): Long? {
        loaded()
        return seen[seenKey(id)]
    }

    @Synchronized
    fun noteSeen(id: String, counter: Long): Boolean {
        loaded()
        val k = seenKey(id)
        val old = seen[k]
        if (old != null && old >= counter) return true
        seen[k] = counter
        if (!save()) {
            if (old == null) seen.remove(k) else seen[k] = old
            return false
        }
        return true
    }

    /** Backup turned off: forget the schedule, time, error and server tag; keep the rollback counters and the delete queue. */
    @Synchronized
    fun reset(): Boolean {
        loaded()
        meta = Meta()
        val tagGone = deleteFile(File(dir, TAG_FILE))
        return save() && tagGone
    }

    // ---- server tag (sealed) ----

    /** The tag of this phone's last upload of backup [id], or null (none stored, another id, or unreadable). */
    @Synchronized
    fun serverTag(id: String): String? {
        val text = readSealed(TAG_FILE) ?: return null
        val nl = text.indexOf('\n')
        if (nl <= 0 || text.substring(0, nl) != seenKey(id)) return null
        return BackupClient.cleanTag(text.substring(nl + 1))
    }

    /** Stores [tag] for backup [id]; null (or an unusable tag) forgets it. False when it couldn't be written. */
    @Synchronized
    fun setServerTag(id: String, tag: String?): Boolean {
        val t = BackupClient.cleanTag(tag) ?: return deleteFile(File(dir, TAG_FILE))
        return writeSealed(TAG_FILE, seenKey(id) + "\n" + t)
    }

    // ---- ids still to delete on the server (sealed) ----

    /** A backup id whose server copy is to be deleted, and the tag of this phone's last upload under it (or null). */
    data class QueuedDelete(val id: String, val tag: String?) {
        override fun toString(): String = "QueuedDelete(redacted)"
    }

    @Synchronized
    fun pendingDeletes(): List<String> = pendingDeleteEntries().map { it.id }

    /** Lines are `<id>` or `<id> <tag>` (a tag never holds a line break, [BackupClient.cleanTag]). */
    @Synchronized
    fun pendingDeleteEntries(): List<QueuedDelete> {
        val out = LinkedHashMap<String, QueuedDelete>()
        readSealed(DELETE_FILE)?.split('\n')?.forEach { line ->
            val id = line.take(64)
            if (!isBackupId(id)) return@forEach
            val tag = if (line.length > 65 && line[64] == ' ') BackupClient.cleanTag(line.substring(65)) else null
            out.remove(id)
            out[id] = QueuedDelete(id, tag)
        }
        return out.values.toList()
    }

    /**
     * Queues [id] for deletion with [tag], the tag of this phone's last upload of it (newest last, at most
     * [MAX_PENDING_DELETES]). False when it couldn't be stored.
     */
    @Synchronized
    fun addPendingDelete(id: String, tag: String? = null): Boolean {
        if (!isBackupId(id)) return false
        val list = pendingDeleteEntries().filter { it.id != id } + QueuedDelete(id, BackupClient.cleanTag(tag))
        return writeDeletes(list.takeLast(MAX_PENDING_DELETES))
    }

    @Synchronized
    fun removePendingDelete(id: String): Boolean {
        val list = pendingDeleteEntries()
        if (list.none { it.id == id }) return true
        return writeDeletes(list.filter { it.id != id })
    }

    private fun writeDeletes(list: List<QueuedDelete>): Boolean =
        if (list.isEmpty()) deleteFile(File(dir, DELETE_FILE))
        else writeSealed(DELETE_FILE, list.joinToString("\n") { if (it.tag != null) it.id + " " + it.tag else it.id })

    // ---- restore repair list ----

    @Synchronized
    fun pending(): Map<RepairKey, Pending> = readPending().second

    /**
     * Item id the last restore started from: our messages with a higher id were sent after the restore, so their
     * delivery receipts prove the ratchet. [Long.MAX_VALUE] when unknown (no receipt counts).
     */
    @Synchronized
    fun repairSince(): Long = readPending().first ?: Long.MAX_VALUE

    /** A new repair list after a restore: [connections] and the item id the restore started from. */
    @Synchronized
    fun startRepair(connections: Map<RepairKey, Pending>, sinceItemId: Long): Boolean = writePending(sinceItemId, connections)

    @Synchronized
    fun setPending(map: Map<RepairKey, Pending>): Boolean = writePending(readPending().first, map)

    /** Applies [change] to the pending list. */
    @Synchronized
    fun updatePending(change: (MutableMap<RepairKey, Pending>) -> Unit): Boolean {
        val (since, current) = readPending()
        val map = LinkedHashMap(current)
        change(map)
        return if (map == current) true else writePending(since, map)
    }

    private fun readPending(): Pair<Long?, Map<RepairKey, Pending>> {
        val out = LinkedHashMap<RepairKey, Pending>()
        var since: Long? = null
        try {
            if (!pendingFile.isFile) return null to out
            for (line in pendingFile.readLines()) {
                val parts = line.trim().split(' ')
                if (parts.getOrNull(0) == SINCE_KEY) {
                    since = parts.getOrNull(1)?.toLongOrNull()?.takeIf { it >= 0 }
                    continue
                }
                val key = parts.getOrNull(0)?.let { RepairKey.decode(it) } ?: continue
                val flags = parts.getOrNull(1).orEmpty()
                val forcedAt = parts.getOrNull(2)?.toLongOrNull()?.takeIf { it > 0 } ?: 0L
                // 'h' (an incoming message decrypted) from older builds is not proof any more and is ignored.
                out[key] = Pending(
                    proven = 'p' in flags, switched = 's' in flags, forced = 'f' in flags, syncing = 'y' in flags,
                    reforced = 'r' in flags, forcedAtMs = forcedAt,
                )
            }
        } catch (e: Exception) {
            SecuredLog.crash(SecuredLog.Ev.BACKUP_IO_FAILED, e)
        }
        return since to out
    }

    private fun writePending(since: Long?, map: Map<RepairKey, Pending>): Boolean {
        if (map.isEmpty()) return deleteFile(pendingFile)
        val sb = StringBuilder()
        if (since != null) sb.append(SINCE_KEY).append(' ').append(since).append('\n')
        for ((key, p) in map) {
            val flags = (if (p.proven) "p" else "") + (if (p.switched) "s" else "") +
                (if (p.forced) "f" else "") + (if (p.syncing) "y" else "") + (if (p.reforced) "r" else "")
            sb.append(key.encode()).append(' ').append(flags.ifEmpty { "-" })
            if (p.forcedAtMs > 0) sb.append(' ').append(p.forcedAtMs)
            sb.append('\n')
        }
        return write(pendingFile, sb.toString().toByteArray(Charsets.UTF_8))
    }

    // ---- state file ----

    private fun loaded(): Meta {
        meta?.let { return it }
        var m = Meta()
        seen.clear()
        try {
            if (stateFile.isFile) {
                for (line in stateFile.readLines()) {
                    val eq = line.indexOf('=')
                    if (eq <= 0) continue
                    val k = line.substring(0, eq)
                    val v = line.substring(eq + 1)
                    when {
                        k == "counter" -> m = m.copy(counter = v.toLongOrNull()?.coerceAtLeast(0) ?: 0)
                        k == "last_backup_ms" -> m = m.copy(lastBackupMs = v.toLongOrNull()?.takeIf { it > 0 })
                        k == "dirty" -> m = m.copy(dirty = v == "1")
                        k == "error" -> m = m.copy(error = v.takeIf { ERROR_CODE.matches(it) })
                        k == "urgent" -> m = m.copy(urgent = v == "1")
                        k == "unconditional" -> m = m.copy(unconditional = v == "1")
                        k == "other_phone" -> m = m.copy(otherPhone = v == "1")
                        k == "failures" -> m = m.copy(failures = v.toIntOrNull()?.coerceIn(0, MAX_FAILURES) ?: 0)
                        k == "last_attempt_ms" -> m = m.copy(lastAttemptMs = v.toLongOrNull()?.takeIf { it > 0 })
                        k.startsWith("seen.") -> v.toLongOrNull()?.let { seen[k.removePrefix("seen.")] = it }
                    }
                }
            }
        } catch (e: Exception) {
            SecuredLog.crash(SecuredLog.Ev.BACKUP_IO_FAILED, e)
        }
        meta = m
        return m
    }

    private fun save(): Boolean {
        val m = meta ?: Meta()
        val sb = StringBuilder()
        sb.append("counter=").append(m.counter).append('\n')
        m.lastBackupMs?.let { sb.append("last_backup_ms=").append(it).append('\n') }
        sb.append("dirty=").append(if (m.dirty) "1" else "0").append('\n')
        m.error?.takeIf { ERROR_CODE.matches(it) }?.let { sb.append("error=").append(it).append('\n') }
        if (m.urgent) sb.append("urgent=1\n")
        if (m.unconditional) sb.append("unconditional=1\n")
        if (m.otherPhone) sb.append("other_phone=1\n")
        if (m.failures > 0) sb.append("failures=").append(m.failures.coerceAtMost(MAX_FAILURES)).append('\n')
        m.lastAttemptMs?.let { sb.append("last_attempt_ms=").append(it).append('\n') }
        for ((k, v) in seen) sb.append("seen.").append(k).append('=').append(v).append('\n')
        return write(stateFile, sb.toString().toByteArray(Charsets.UTF_8))
    }

    // ---- files ----

    private fun readSealed(name: String): String? {
        val b = box ?: return null
        val f = File(dir, name)
        if (!f.isFile) return null
        return try {
            String(b.open(f.readBytes()), Charsets.UTF_8)
        } catch (e: Exception) {
            SecuredLog.crash(SecuredLog.Ev.BACKUP_IO_FAILED, e)
            null
        }
    }

    private fun writeSealed(name: String, text: String): Boolean {
        val b = box ?: return false
        val sealed = try {
            b.seal(text.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            SecuredLog.crash(SecuredLog.Ev.BACKUP_IO_FAILED, e)
            return false
        }
        return write(File(dir, name), sealed)
    }

    private fun deleteFile(f: File): Boolean = !f.exists() || f.delete() || !f.exists()

    private fun write(file: File, data: ByteArray): Boolean {
        val tmp = File(dir, file.name + ".tmp")
        return try {
            if (!dir.isDirectory && !dir.mkdirs()) return false
            tmp.writeBytes(data)
            if (!tmp.renameTo(file)) {
                tmp.delete()
                SecuredLog.e(SecuredLog.Ev.BACKUP_IO_FAILED, "rename")
                false
            } else {
                true
            }
        } catch (e: Exception) {
            tmp.delete()
            SecuredLog.crash(SecuredLog.Ev.BACKUP_IO_FAILED, e)
            false
        }
    }

    companion object {
        const val STATE_FILE = "state"
        const val PENDING_FILE = "restore_pending"
        const val TAG_FILE = "server_tag.bin"
        const val DELETE_FILE = "delete_queue.bin"
        const val MAX_PENDING_DELETES = 16
        /** Error code that stops automatic uploads: the data won't shrink by itself (finding 5). */
        const val TOO_LARGE = "too_large"
        const val OTHER_PHONE = "other_phone"
        const val DAY_MS = 24L * 60 * 60 * 1000
        /** Backoff after failed automatic uploads: 30 min, 1 h, 2 h, ... at most a day (finding 5). */
        const val FIRST_RETRY_MS = 30L * 60 * 1000
        const val MAX_RETRY_MS = DAY_MS
        private const val MAX_FAILURES = 1000
        private const val SINCE_KEY = "since"
        private val ERROR_CODE = Regex("[a-z_]{1,32}(:[0-9]{1,3})?")

        /** One-way, so the plain file never holds the id itself (the id is the server permission). */
        fun seenKey(id: String): String {
            val d = MessageDigest.getInstance("SHA-256").digest("encrogram/backup/seen/v1:$id".toByteArray(Charsets.US_ASCII))
            return Bip39.hex(d.copyOf(8))
        }

        fun isBackupId(s: String): Boolean = s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' }

        /** Wait after [failures] automatic failures in a row: 0, then 30 min doubling up to 24 h. */
        fun backoffMs(failures: Int): Long {
            if (failures <= 0) return 0L
            val shift = minOf(failures - 1, 20)
            return minOf(FIRST_RETRY_MS shl shift, MAX_RETRY_MS)
        }

        /** Automatic uploads wait for the user: another phone owns the server copy, or the data is too large. */
        fun autoPaused(m: Meta): Boolean = m.otherPhone || m.error == TOO_LARGE

        /**
         * When the next automatic upload is due, or null when none is: now when one is owed ([Meta.urgent]), a day
         * after the last backup when something changed ([Meta.dirty]), never while [autoPaused]. After failures, not
         * before the backoff since the last attempt has passed.
         */
        fun autoDueMs(m: Meta, nowMs: Long): Long? {
            if (autoPaused(m)) return null
            val base = when {
                m.urgent -> nowMs
                m.dirty -> (m.lastBackupMs ?: 0L) + DAY_MS
                else -> return null
            }
            return if (m.failures > 0) maxOf(base, (m.lastAttemptMs ?: 0L) + backoffMs(m.failures)) else base
        }
    }
}
