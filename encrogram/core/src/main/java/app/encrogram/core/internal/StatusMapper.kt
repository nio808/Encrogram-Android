package app.encrogram.core.internal

import app.encrogram.core.api.MessageStatus

/**
 * Maps the engine's CIStatus tag to the honest [MessageStatus] shown as ticks.
 * Never claims more than the engine reported: unknown outgoing states stay at SENDING (clock).
 */
internal object StatusMapper {

    fun map(statusTag: String?, outgoing: Boolean): MessageStatus = when {
        statusTag == "sndNew" -> MessageStatus.SENDING
        statusTag == "sndSent" -> MessageStatus.SENT_TO_SERVER
        statusTag == "sndRcvd" -> MessageStatus.DELIVERED
        // sndError, sndErrorAuth: the engine gave up on this message.
        statusTag != null && statusTag.startsWith("sndError") -> MessageStatus.FAILED
        // sndWarning: a temporary problem (e.g. relay unreachable); the engine keeps retrying, so still sending.
        statusTag == "sndWarning" -> MessageStatus.SENDING
        statusTag == "rcvNew" || statusTag == "rcvRead" -> MessageStatus.RECEIVED
        // invalid: the engine could not decode the item.
        statusTag == "invalid" -> if (outgoing) MessageStatus.FAILED else MessageStatus.RECEIVED
        else -> if (outgoing) MessageStatus.SENDING else MessageStatus.RECEIVED
    }
}
