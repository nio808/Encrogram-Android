package app.encrogram.secured;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import app.encrogram.core.api.MessageStatus;
import app.encrogram.core.api.SecuredMessage;

/** AR9 draft store (memory only) and AR8 "same bubble" comparison: no Android needed. */
public class SecuredDraftTest {

    @Test
    public void draftIsKeptPerChatAndClearedByBlankText() {
        SecuredUi.setDraft(9001L, "hello", 5L, "Draft: ");
        final SecuredUi.Draft d = SecuredUi.draft(9001L);
        assertNotNull(d);
        assertEquals("hello", d.text);
        assertEquals(Long.valueOf(5L), d.replyId);
        assertEquals("Draft: ", SecuredUi.draftLabel());
        assertNull(SecuredUi.draft(9002L));
        SecuredUi.setDraft(9001L, "  ", null, null);
        assertNull(SecuredUi.draft(9001L));
    }

    private static SecuredMessage msg(MessageStatus s, String text) {
        return new SecuredMessage(1L, 2L, true, text, 1000L, s);
    }

    @Test
    public void statusOnlyChangeIsTheSameBubble() {
        final SecuredMessage a = msg(MessageStatus.SENDING, "hi");
        assertEquals(true, SecuredMessageMapper.sameBubble(a, msg(MessageStatus.DELIVERED, "hi")));
        assertEquals(false, SecuredMessageMapper.sameBubble(a, msg(MessageStatus.SENDING, "hi!")));
    }
}
