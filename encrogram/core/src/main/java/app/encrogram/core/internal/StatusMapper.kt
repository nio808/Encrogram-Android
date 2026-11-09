package app.encrogram.core.internal

import app.encrogram.core.api.MessageStatus
import app.encrogram.core.internal.media.FileStatusMapper

/**
 * Maps the engine's CIStatus tag to the honest [MessageStatus] shown as ticks.
 * Never claims more than the engine reported: unknown outgoing states stay at SENDING (clock).
 *
 * Groups: the core folds every member's status into one (Messages.hs:1088-1096 membersGroupItemStatus). An item is
 * `sndRcvd` with `sndProgress: partial` as soon as ONE member sent a receipt, and `complete` only when every member
 * did. So in a group DELIVERED needs `sndRcvd` with `complete`; anything less stays SENT_TO_SERVER. Receipts exist only
 * in groups of 20 or fewer (Subscriber.hs:111-112), so larger groups never reach DELIVERED.
 */
internal object StatusMapper {

    fun map(statusTag: String?, outgoing: Boolean, sndProgress: String? = null, group: Boolean = false): MessageStatus = when {
        // A group message sent to nobody also stays sndNew for good; the engine shows it as SENT_TO_SERVER once the
        // core confirms it had no recipients ([NoRecipientCheck]).
        statusTag == "sndNew" -> MessageStatus.SENDING
        statusTag == "sndSent" -> MessageStatus.SENT_TO_SERVER
        // 1:1 items are always `complete` (absent in older JSON); groups must say `complete` explicitly.
        statusTag == "sndRcvd" -> if (deliveredToAll(sndProgress, group)) MessageStatus.DELIVERED else MessageStatus.SENT_TO_SERVER
        // sndError, sndErrorAuth: the engine gave up on this message.
        statusTag != null && statusTag.startsWith("sndError") -> MessageStatus.FAILED
        // sndWarning: a temporary problem (e.g. relay unreachable); the engine keeps retrying, so still sending.
        statusTag == "sndWarning" -> MessageStatus.SENDING
        statusTag == "rcvNew" || statusTag == "rcvRead" -> MessageStatus.RECEIVED
        // invalid: the engine could not decode the item.
        statusTag == "invalid" -> if (outgoing) MessageStatus.FAILED else MessageStatus.RECEIVED
        else -> if (outgoing) MessageStatus.SENDING else MessageStatus.RECEIVED
    }

    private fun deliveredToAll(sndProgress: String?, group: Boolean): Boolean =
        if (group) sndProgress == "complete" else sndProgress != "partial"

    /**
     * One member's delivery of our own group message: a GroupSndStatus tag (Messages.hs:1118-1126, JSON tags without
     * "GSS", :1433). `rcvd` is that member's delivery receipt (with either msgRcptStatus: they got it), never "read".
     * `sent`: on their relay queue; `forwarded`: passed to a member who relays it to them, so it left this phone too.
     * `new` (not sent yet), `inactive` (they aren't connected yet: it goes when they are) and `warning` (a temporary
     * problem, retried) stay at the clock; `error` and `invalid` failed. Anything newer than this reader: the clock.
     */
    fun memberDelivery(tag: String?): MessageStatus = when (tag) {
        "rcvd" -> MessageStatus.DELIVERED
        "sent", "forwarded" -> MessageStatus.SENT_TO_SERVER
        "error", "invalid" -> MessageStatus.FAILED
        else -> MessageStatus.SENDING
    }
}

/**
 * Our own group messages sent while nobody else was in the group (device test, 9 November 2026: Alice posts in a new
 * empty group, adds Bob, Bob gets the posts as history, and Alice's copies keep the clock forever).
 *
 * The core folds each member's delivery into the item's status ([StatusMapper]). A send makes member rows only for the
 * members it goes to (Commands.hs:4815-4844), so a message sent with no other member has nothing to fold and stays
 * `sndNew`; history sent to someone who joins later is a separate message and never adds a row. Nothing is pending for
 * such a message, so a clock would claim something false: it is shown as SENT_TO_SERVER (one tick), still under the
 * file rule ([FileStatusMapper.cappedStatus]). Any member row (`new` while our phone is offline, `inactive` for an
 * invited member not connected yet, `forwarded`, an error) means a recipient, and the clock stays.
 *
 * Cost: one `/_get item info` ([Commands.itemInfo], [CoreJson.parseItemInfo]) per item and run at most, whatever the
 * answer (a failed call keeps the clock and isn't repeated); only for our own group messages still `sndNew` once they
 * are [AGE_MS] old, so a normal send, on our server within a second ([settled]), never asks; and only for items being
 * shown (chat pages, row previews, items just sent). Engine thread only; [clear] at each stop.
 */
internal class NoRecipientCheck(private val ask: (CoreItem) -> CoreReply, private val nowMs: () -> Long) {

    /** Candidates handed out by [take]: to ask [now], and [later] once all of them are old enough ([laterDelayMs]). */
    class Due(val now: List<CoreItem>, val later: List<CoreItem>, val laterDelayMs: Long)

    /** (chat id, item id) -> true when the core said nobody was sent it; false: recipients, moved on, or no answer. */
    private val answers = HashMap<Pair<Long, Long>, Boolean>()
    /** Handed out by [take] and not checked yet, so a reload doesn't hand them out again. */
    private val waiting = HashSet<Pair<Long, Long>>()

    /** Items as shown: our own `sndNew` group messages that had no recipients read SENT_TO_SERVER. */
    fun apply(items: List<CoreItem>): List<CoreItem> =
        if (answers.isEmpty()) items else items.map { if (candidate(it) && answers[keyOf(it)] == true) sent(it) else it }

    /**
     * The candidates among [items] not asked about yet, each handed out once: those at least [AGE_MS] old to ask now,
     * the rest after [Due.laterDelayMs] (when all of them are; never more than [AGE_MS], whatever the clocks say).
     */
    fun take(items: List<CoreItem>): Due {
        val now = nowMs()
        val ready = ArrayList<CoreItem>()
        val later = ArrayList<CoreItem>()
        var delay = 0L
        for (i in items) {
            if (!candidate(i)) continue
            val key = keyOf(i)
            if (key in answers || !waiting.add(key)) continue
            val left = i.timeMs + AGE_MS - now
            if (left <= 0) {
                ready.add(i)
            } else {
                later.add(i)
                delay = maxOf(delay, minOf(left, AGE_MS))
            }
        }
        return Due(ready, later, delay)
    }

    /** A status event: the core moved these items on, so they have recipients and a waiting one needs no asking. */
    fun settled(items: List<CoreItem>) {
        for (i in items) {
            val key = keyOf(i)
            if (waiting.remove(key)) answers[key] = false
        }
    }

    /**
     * Asks the core once about each of [items] not answered yet. Returns those now shown differently (SENT_TO_SERVER),
     * as the core holds them now, for the usual status change.
     */
    fun check(items: List<CoreItem>): List<CoreItem> {
        val changed = ArrayList<CoreItem>()
        for (item in items) {
            val key = keyOf(item)
            waiting.remove(key)
            if (key in answers) continue
            answers[key] = false
            val reply = ask(item) as? CoreReply.Result ?: continue
            if (reply.type != "chatItemInfo") continue
            val (now, nobody) = CoreJson.parseItemInfo(reply.body) ?: continue
            // Trusted only for this very item, still ours and still sndNew.
            if (!nobody || keyOf(now) != key || !candidate(now)) continue
            answers[key] = true
            val shown = sent(now)
            if (shown.status != now.status) changed.add(shown)
        }
        return changed
    }

    fun clear() {
        answers.clear()
        waiting.clear()
    }

    private fun keyOf(i: CoreItem) = i.chatId to i.itemId

    /** Our own group message the core still holds as `sndNew`. */
    private fun candidate(i: CoreItem) = i.group && i.outgoing && !i.local && i.isMessage && !i.deleted && i.statusTag == SND_NEW

    private fun sent(i: CoreItem) = i.copy(status = FileStatusMapper.cappedStatus(MessageStatus.SENT_TO_SERVER, i.file, true))

    companion object {
        const val AGE_MS = 10_000L
        private const val SND_NEW = "sndNew"
    }
}
