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
}
