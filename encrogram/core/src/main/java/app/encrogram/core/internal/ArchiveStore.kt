package app.encrogram.core.internal

import java.io.File

/**
 * The local "archived" flag of Secured chats: a set of contact ids in one small file inside the engine's
 * no-backup folder (`noBackupFilesDir/encrogram-secured/archived_chats`). Never sent to anyone.
 *
 * Not SharedPreferences: those live in `shared_prefs/`, which Telegram's manifest (allowBackup=true) lets
 * Android back up, and a restored id list would mark the wrong chats (contact ids start again on a new device).
 *
 * Engine thread only. Writes go to a temp file first and are then renamed, so a crash never leaves half a list.
 */
internal class ArchiveStore(private val file: File) {

    private var ids: MutableSet<Long>? = null

    private fun loaded(): MutableSet<Long> {
        ids?.let { return it }
        val set = LinkedHashSet<Long>()
        try {
            if (file.isFile) file.readLines().mapNotNullTo(set) { it.trim().toLongOrNull() }
        } catch (e: Exception) {
            SecuredLog.crash(SecuredLog.Ev.ARCHIVE_IO_FAILED, e)
        }
        ids = set
        return set
    }

    fun isArchived(chatId: Long): Boolean = chatId in loaded()

    fun all(): Set<Long> = loaded().toSet()

    /** Returns false when the change could not be written; the stored state is then unchanged. */
    fun set(chatId: Long, archived: Boolean): Boolean {
        val current = loaded()
        if ((chatId in current) == archived) return true
        val next = LinkedHashSet(current)
        if (archived) next.add(chatId) else next.remove(chatId)
        return save(next)
    }

    /** Drops ids of chats the engine no longer has (ids can be reused by new contacts). */
    fun retainOnly(known: Set<Long>): Boolean {
        val current = loaded()
        if (known.containsAll(current)) return true
        return save(current.filterTo(LinkedHashSet()) { it in known })
    }

    private fun save(next: LinkedHashSet<Long>): Boolean {
        val tmp = File(file.parentFile, file.name + ".tmp")
        return try {
            file.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
            tmp.writeText(next.joinToString("\n"))
            if (!tmp.renameTo(file)) {
                tmp.delete()
                SecuredLog.e(SecuredLog.Ev.ARCHIVE_IO_FAILED, "rename")
                return false
            }
            ids = next
            true
        } catch (e: Exception) {
            tmp.delete()
            SecuredLog.crash(SecuredLog.Ev.ARCHIVE_IO_FAILED, e)
            false
        }
    }
}
