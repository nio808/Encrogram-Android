package app.encrogram.core.internal

import app.encrogram.core.api.MessageStatus

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
}
