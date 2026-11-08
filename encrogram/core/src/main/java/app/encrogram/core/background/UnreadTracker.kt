package app.encrogram.core.background

/**
 * Unseen incoming messages per chat, for the one combined notification. Counts only, in memory (a process restart
 * starts the count again, which can only undercount). Pure so a JVM test can pin it down.
 */
internal class UnreadTracker {
    private val counts = LinkedHashMap<Long, Int>()

    @Synchronized fun add(chatId: Long) { counts[chatId] = (counts[chatId] ?: 0) + 1 }
    @Synchronized fun clear(chatId: Long) { counts.remove(chatId) }
    @Synchronized fun clearAll() = counts.clear()
    @Synchronized fun total(): Int = counts.values.sum()
    @Synchronized fun chats(): Int = counts.size

    /** The chat to open on tap when every unseen message is from one chat, else null (the list). */
    @Synchronized fun singleChat(): Long? = if (counts.size == 1) counts.keys.first() else null
}
