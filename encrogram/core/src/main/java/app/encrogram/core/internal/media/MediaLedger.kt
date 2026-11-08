package app.encrogram.core.internal.media

import app.encrogram.core.api.MediaKind
import app.encrogram.core.internal.SecuredLog
import java.io.File
import java.security.MessageDigest

/** One ledger row. Ids, enums, sizes and a hash only: never a name, key or description. */
internal data class LedgerEntry(
    val fileId: Long,
    val chatId: Long,
    val itemId: Long,
    val kind: MediaKind,
    val sizeBytes: Long,
    val verdict: Verdict? = null,
    val wanted: Boolean = false,
    val cancelled: Boolean = false,
    val accepted: Boolean = false,
    val receivedMs: Long = 0,
    /** First 12 hex characters of SHA-256 of the item's sharedMsgId; null: memory only, never saved. */
    val msgHash: String? = null,
)

/**
 * What we decided about each incoming file, bound to the message by a short hash of its sharedMsgId so a replaced
 * store can never reuse a verdict. One file, written to a temp file then renamed; loaded leniently (a corrupt line
 * is skipped). At most [MAX_ENTRIES] rows, oldest dropped first. Entries without a sharedMsgId live in memory only.
 *
 * Engine thread only: there is no locking.
 */
internal class MediaLedger(private val file: File) {

    private var map: LinkedHashMap<Long, LedgerEntry>? = null

    private fun loaded(): LinkedHashMap<Long, LedgerEntry> {
        map?.let { return it }
        val m = LinkedHashMap<Long, LedgerEntry>()
        try {
            if (file.isFile) file.readLines().forEach { line -> parse(line)?.let { m[it.fileId] = it } }
        } catch (e: Exception) {
            SecuredLog.crash(SecuredLog.Ev.MEDIA_IO_FAILED, e)
        }
        while (m.size > MAX_ENTRIES) m.remove(m.keys.first())
        map = m
        return m
    }

    /** The entry for [fileId] when it belongs to this message: hashes must match (both absent also matches). */
    fun get(fileId: Long, sharedMsgId: String?): LedgerEntry? =
        loaded()[fileId]?.takeIf { it.msgHash == hashOf(sharedMsgId) }

    /** Stores [entry] bound to [sharedMsgId] (its own msgHash is replaced). Returns false if saving failed. */
    fun put(entry: LedgerEntry, sharedMsgId: String?): Boolean {
        val m = loaded()
        val e = entry.copy(msgHash = hashOf(sharedMsgId))
        m.remove(e.fileId)
        m[e.fileId] = e
        while (m.size > MAX_ENTRIES) m.remove(m.keys.first())
        return save()
    }

    fun markWanted(fileId: Long): Boolean = change(fileId) { it.copy(wanted = true, cancelled = false) }

    /** A cancel overrides auto-download and a queued want. */
    fun markCancelled(fileId: Long): Boolean = change(fileId) { it.copy(cancelled = true, wanted = false, accepted = false) }

    fun markAccepted(fileId: Long): Boolean = change(fileId) { it.copy(accepted = true) }

    fun remove(fileId: Long): Boolean {
        val m = loaded()
        return if (m.remove(fileId) != null) save() else true
    }

    /** Drops the rows of the given items of [chatId] (messages deleted). */
    fun removeForChat(chatId: Long, itemIds: Collection<Long>): Boolean {
        val m = loaded()
        val ids = itemIds.toHashSet()
        val gone = m.values.filter { it.chatId == chatId && it.itemId in ids }.map { it.fileId }
        if (gone.isEmpty()) return true
        gone.forEach { m.remove(it) }
        return save()
    }

    fun clear(): Boolean {
        loaded().clear()
        return save()
    }

    fun entries(): List<LedgerEntry> = loaded().values.toList()

    private fun change(fileId: Long, f: (LedgerEntry) -> LedgerEntry): Boolean {
        val m = loaded()
        val cur = m[fileId] ?: return false
        m[fileId] = f(cur)
        save()
        return true
    }

    private fun save(): Boolean {
        val m = loaded()
        val tmp = File(file.parentFile, file.name + ".tmp")
        return try {
            file.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
            tmp.writeText(m.values.filter { it.msgHash != null }.joinToString("\n") { format(it) })
            if (!tmp.renameTo(file)) {
                tmp.delete()
                SecuredLog.e(SecuredLog.Ev.MEDIA_IO_FAILED, "rename")
                false
            } else true
        } catch (e: Exception) {
            tmp.delete()
            SecuredLog.crash(SecuredLog.Ev.MEDIA_IO_FAILED, e)
            false
        }
    }

    companion object {
        const val MAX_ENTRIES = 5_000

        fun hashOf(sharedMsgId: String?): String? {
            if (sharedMsgId.isNullOrEmpty()) return null
            val d = MessageDigest.getInstance("SHA-256").digest(sharedMsgId.toByteArray(Charsets.UTF_8))
            return d.joinToString("") { "%02x".format(it) }.substring(0, 12)
        }

        /** fileId|chatId|itemId|KIND|size|VERDICT or -|wanted|cancelled|accepted|receivedMs|hash */
        internal fun format(e: LedgerEntry): String = listOf(
            e.fileId, e.chatId, e.itemId, e.kind.name, e.sizeBytes, e.verdict?.name ?: "-",
            if (e.wanted) 1 else 0, if (e.cancelled) 1 else 0, if (e.accepted) 1 else 0, e.receivedMs, e.msgHash,
        ).joinToString("|")

        internal fun parse(line: String): LedgerEntry? {
            val p = line.trim().split('|')
            if (p.size != 11) return null
            return try {
                val hash = p[10]
                if (hash.length != 12 || !hash.all { it in '0'..'9' || it in 'a'..'f' }) return null
                LedgerEntry(
                    fileId = p[0].toLong(), chatId = p[1].toLong(), itemId = p[2].toLong(),
                    kind = MediaKind.valueOf(p[3]), sizeBytes = p[4].toLong(),
                    verdict = if (p[5] == "-") null else Verdict.valueOf(p[5]),
                    wanted = flag(p[6]), cancelled = flag(p[7]), accepted = flag(p[8]),
                    receivedMs = p[9].toLong(), msgHash = hash,
                )
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        private fun flag(s: String): Boolean = when (s) {
            "1" -> true
            "0" -> false
            else -> throw IllegalArgumentException()
        }
    }
}
