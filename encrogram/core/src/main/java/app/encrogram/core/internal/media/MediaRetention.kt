package app.encrogram.core.internal.media

import app.encrogram.core.internal.SecuredLog
import java.io.File
import java.security.MessageDigest

/**
 * "Keep media" (A7 in research/app-performance-reliability.md): how long encrypted media files stay on this phone,
 * and which files are never removed. Pure rules; [MediaCoordinator.applyRetention] and [MediaCoordinator.clearAll]
 * run them, [MediaVault.expire] empties the files.
 *
 * A removed file is emptied in place, never deleted (see [MediaVault.expire]): the core names new files with the first
 * free name (Files.hs uniqueCombine) and deletes a message's file by its stored path, so a freed name could be taken
 * by a newer file that deleting the old message would then destroy.
 */
internal object MediaRetention {

    const val DAY_MS = 24L * 3600 * 1000
    const val MIN_DAYS = 1
    const val MAX_DAYS = 3650

    /**
     * A file written this recently is never removed, not even by "clear media": a download's final write (the agent
     * decrypts into the target at the end), a commit or a forward copy may still be under way.
     */
    const val FRESH_MS = 10L * 60 * 1000

    /** Null (forever) or a whole number of days in [MIN_DAYS]..[MAX_DAYS]. */
    fun valid(days: Int?): Boolean = days == null || days in MIN_DAYS..MAX_DAYS

    /**
     * The files to remove now. [days] null means "clear everything" (no age rule). Never: an empty file (a download's
     * placeholder or an already removed file), one written in the last [FRESH_MS] or dated in the future, or one
     * [keep] holds. With [days], only files older than that many days (strictly: exactly [days] old stays).
     */
    fun select(files: List<StoredFile>, nowMs: Long, days: Int?, keep: (String) -> Boolean): List<StoredFile> {
        require(days == null || days in MIN_DAYS..MAX_DAYS) { "days" }
        val freshFrom = nowMs - FRESH_MS
        val olderThan = if (days == null) Long.MAX_VALUE else nowMs - days * DAY_MS
        return files.filter { f ->
            f.bytes > 0 && f.modifiedMs <= freshFrom && f.modifiedMs < olderThan && !keep(f.name)
        }
    }

    /** First 16 hex characters of SHA-256 of a bare file name: how [UploadPins] and the keep set name files. */
    fun nameHash(name: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(name.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(16)
        for (i in 0 until 8) sb.append("%02x".format(d[i]))
        return sb.toString()
    }
}

/** One encrypted file in the core's files folder as listed ([MediaVault.storedFiles]). The name is never logged. */
internal class StoredFile(val name: String, val bytes: Long, val modifiedMs: Long) {
    override fun toString() = "StoredFile($bytes bytes)"
}

/**
 * The "keep media for N days" setting: one small file in the engine's no-backup folder (next to the ledger, like
 * `archived_chats`), holding the number of days. No file means forever, the default. A file that can't be read or holds
 * anything else also means forever: when in doubt, keep.
 *
 * Any thread: the value is cached after the first read.
 */
internal class RetentionSetting(private val file: File) {

    private val lock = Any()
    @Volatile private var loaded = false
    @Volatile private var cached: Int? = null

    fun days(): Int? {
        if (loaded) return cached
        synchronized(lock) {
            if (!loaded) {
                cached = read()
                loaded = true
            }
            return cached
        }
    }

    /** Saves [days] (null: forever, the file goes). False when it couldn't be saved; the old value then stays. */
    fun set(days: Int?): Boolean {
        require(MediaRetention.valid(days)) { "days" }
        synchronized(lock) {
            val ok = if (days == null) remove() else write(days)
            if (ok) {
                cached = days
                loaded = true
            }
            return ok
        }
    }

    /** The store was replaced or wiped: read the file again next time. */
    fun forget() {
        synchronized(lock) {
            loaded = false
            cached = null
        }
    }

    private fun read(): Int? = try {
        if (!file.isFile) null else file.readText().trim().toIntOrNull()?.takeIf { it in MediaRetention.MIN_DAYS..MediaRetention.MAX_DAYS }
    } catch (e: Exception) {
        SecuredLog.crash(SecuredLog.Ev.MEDIA_IO_FAILED, e)
        null
    }

    private fun write(days: Int): Boolean {
        val tmp = File(file.parentFile, file.name + ".tmp")
        return try {
            file.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
            tmp.writeText(days.toString())
            if (tmp.renameTo(file)) true else {
                tmp.delete()
                SecuredLog.e(SecuredLog.Ev.MEDIA_IO_FAILED, "retention_rename")
                false
            }
        } catch (e: Exception) {
            tmp.delete()
            SecuredLog.crash(SecuredLog.Ev.MEDIA_IO_FAILED, e)
            false
        }
    }

    private fun remove(): Boolean = try {
        !file.exists() || file.delete()
    } catch (e: SecurityException) {
        false
    }

    override fun toString() = "RetentionSetting"
}

/**
 * Files of our own media messages whose upload hasn't ended: retention and "clear media" never touch them. The core's
 * agent still reads the file while it prepares the upload, and the message shows its clock until the upload is done.
 *
 * One row per file, `chatId|itemId|nameHash`: the name is stored only as [MediaRetention.nameHash], never in clear.
 * [itemId] is [UNKNOWN_ITEM] when the send's reply didn't say which message holds the file; such a pin stays until the
 * chat goes or the store is replaced. Written to a temp file then renamed; loaded leniently (a bad line is skipped); at
 * most [MAX_PINS] rows, oldest dropped. A failed save keeps the pin in memory for this run.
 *
 * Engine thread only: there is no locking.
 */
internal class UploadPins(private val file: File) {

    data class Pin(val chatId: Long, val itemId: Long, val nameHash: String)

    private var map: LinkedHashMap<String, Pin>? = null

    private fun loaded(): LinkedHashMap<String, Pin> {
        map?.let { return it }
        val m = LinkedHashMap<String, Pin>()
        try {
            if (file.isFile) file.readLines().forEach { line -> parse(line)?.let { m[it.nameHash] = it } }
        } catch (e: Exception) {
            SecuredLog.crash(SecuredLog.Ev.MEDIA_IO_FAILED, e)
        }
        while (m.size > MAX_PINS) m.remove(m.keys.first())
        map = m
        return m
    }

    fun isEmpty(): Boolean = loaded().isEmpty()

    fun entries(): List<Pin> = loaded().values.toList()

    fun hashes(): Set<String> = loaded().keys.toHashSet()

    fun has(chatId: Long, itemId: Long): Boolean = loaded().values.any { it.chatId == chatId && it.itemId == itemId }

    /** Pins the file named [name] for message [itemId] of [chatId]. No write when the same pin is already there. */
    fun add(chatId: Long, itemId: Long, name: String): Boolean {
        val m = loaded()
        val pin = Pin(chatId, itemId, MediaRetention.nameHash(name))
        if (m[pin.nameHash] == pin) return true
        m.remove(pin.nameHash)
        m[pin.nameHash] = pin
        while (m.size > MAX_PINS) m.remove(m.keys.first())
        return save()
    }

    /** The upload of [itemId] ended (done, failed, cancelled) or the message is gone. */
    fun remove(chatId: Long, itemId: Long): Boolean = removeIf { it.chatId == chatId && it.itemId == itemId }

    fun removeItems(chatId: Long, itemIds: Collection<Long>): Boolean {
        val ids = itemIds.toHashSet()
        return removeIf { it.chatId == chatId && it.itemId in ids }
    }

    /** The whole chat is gone or cleared, pins of unknown messages included. */
    fun removeChat(chatId: Long): Boolean = removeIf { it.chatId == chatId }

    fun clear(): Boolean {
        val m = loaded()
        if (m.isEmpty() && !file.exists()) return true
        m.clear()
        return save()
    }

    private fun removeIf(gone: (Pin) -> Boolean): Boolean {
        val m = loaded()
        if (m.values.none(gone)) return true
        m.values.removeAll(gone)
        return save()
    }

    private fun save(): Boolean {
        val m = loaded()
        val tmp = File(file.parentFile, file.name + ".tmp")
        return try {
            file.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
            tmp.writeText(m.values.joinToString("\n") { "${it.chatId}|${it.itemId}|${it.nameHash}" })
            if (tmp.renameTo(file)) true else {
                tmp.delete()
                SecuredLog.e(SecuredLog.Ev.MEDIA_IO_FAILED, "pins_rename")
                false
            }
        } catch (e: Exception) {
            tmp.delete()
            SecuredLog.crash(SecuredLog.Ev.MEDIA_IO_FAILED, e)
            false
        }
    }

    override fun toString() = "UploadPins"

    companion object {
        const val UNKNOWN_ITEM = -1L
        const val MAX_PINS = 1_000

        internal fun parse(line: String): Pin? {
            val p = line.trim().split('|')
            if (p.size != 3) return null
            val hash = p[2]
            if (hash.length != 16 || !hash.all { it in '0'..'9' || it in 'a'..'f' }) return null
            val chatId = p[0].toLongOrNull() ?: return null
            val itemId = p[1].toLongOrNull() ?: return null
            return Pin(chatId, itemId, hash)
        }
    }
}
