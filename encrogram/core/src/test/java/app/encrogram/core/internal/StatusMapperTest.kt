package app.encrogram.core.internal

import app.encrogram.core.api.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusMapperTest {

    @Test
    fun outgoing() {
        assertEquals(MessageStatus.SENDING, StatusMapper.map("sndNew", true))
        assertEquals(MessageStatus.SENT_TO_SERVER, StatusMapper.map("sndSent", true))
        assertEquals(MessageStatus.DELIVERED, StatusMapper.map("sndRcvd", true))
        assertEquals(MessageStatus.FAILED, StatusMapper.map("sndError", true))
        assertEquals(MessageStatus.FAILED, StatusMapper.map("sndErrorAuth", true))
        // A warning is a temporary problem; the engine keeps retrying, so the clock stays.
        assertEquals(MessageStatus.SENDING, StatusMapper.map("sndWarning", true))
        assertEquals(MessageStatus.FAILED, StatusMapper.map("invalid", true))
    }

    @Test
    fun incoming() {
        assertEquals(MessageStatus.RECEIVED, StatusMapper.map("rcvNew", false))
        assertEquals(MessageStatus.RECEIVED, StatusMapper.map("rcvRead", false))
        assertEquals(MessageStatus.RECEIVED, StatusMapper.map("invalid", false))
    }

    @Test
    fun unknownStatesNeverClaimProgress() {
        assertEquals(MessageStatus.SENDING, StatusMapper.map("sndSomethingNew", true))
        assertEquals(MessageStatus.SENDING, StatusMapper.map(null, true))
        assertEquals(MessageStatus.RECEIVED, StatusMapper.map(null, false))
    }
}
