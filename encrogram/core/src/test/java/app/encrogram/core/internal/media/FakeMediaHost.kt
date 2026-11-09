package app.encrogram.core.internal.media

import app.encrogram.core.api.SecuredCallback
import app.encrogram.core.api.SecuredMessage
import app.encrogram.core.api.SecuredResult
import app.encrogram.core.internal.CoreItem
import app.encrogram.core.internal.CoreReply
import app.encrogram.core.internal.MessageMapper

/**
 * The engine side of MediaCoordinator, for tests: synchronous, recording. [toMessage] and [messageChanged] annotate
 * through the coordinator, as the engine's localView does.
 */
internal class FakeMediaHost(var now: Long) : MediaHost {

    lateinit var coordinator: MediaCoordinator

    @Volatile var running = true

    class Progress(val chatId: Long, val itemId: Long, val done: Long, val total: Long)

    val changed = ArrayList<SecuredMessage>()
    val added = ArrayList<CoreItem>()
    val progress = ArrayList<Progress>()
    val delivered = ArrayList<SecuredResult<*>>()

    override fun run(cmd: String): CoreReply = throw AssertionError("the coordinator reaches the core only through its transport")

    override fun toMessage(item: CoreItem): SecuredMessage = MessageMapper.toMessage(coordinator.annotate(listOf(item)).first())

    /** The store as the fake engine holds it: the latest item registered per (chatId, itemId), unless [lookup] is set. */
    val store = HashMap<Pair<Long, Long>, CoreItem>()
    var lookup: ((Long, Long) -> CoreItem?)? = null

    override fun currentItem(chatId: Long, itemId: Long): CoreItem? =
        lookup?.invoke(chatId, itemId) ?: store[chatId to itemId]

    /** What [savedFiles] answers: the names Saved Messages holds, or null for "couldn't list them". */
    var saved: Set<String>? = emptySet()

    override fun savedFiles(): Set<String>? = saved

    override fun messageChanged(items: List<CoreItem>) {
        for (i in items) changed += toMessage(i)
    }

    override fun itemsAdded(items: List<CoreItem>) {
        added += items
    }

    override fun progress(chatId: Long, itemId: Long, done: Long, total: Long) {
        progress += Progress(chatId, itemId, done, total)
    }

    override fun <T> deliver(callback: SecuredCallback<T>, result: SecuredResult<T>) {
        synchronized(delivered) { delivered += result }
        callback.onResult(result)
    }

    override fun onEngine(task: () -> Unit) = task()

    override fun running(): Boolean = running

    override fun nowMs(): Long = now

    /** SystemClock.elapsedRealtime as the fake phone has it (retention's trusted clock). */
    var elapsed: Long = 0

    override fun elapsedMs(): Long = elapsed
}
