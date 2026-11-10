package app.encrogram.secured;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;

import app.encrogram.core.api.ChatSummary;
import app.encrogram.core.api.MessageStatus;

/** H10: only a real message gives the row a time to read; status lines do not. Plus the existing self-check. */
public class ChatRowMapperTest {

    private final ChatRowMapper.Labels labels = new ChatRowMapper.Labels("Connecting…", "No messages yet");
    private static final long NOW = 1_700_000_000_000L;

    @Test
    public void emptyChatHasNoTime() {
        final ChatRowMapper.Row r = ChatRowMapper.map(new ChatSummary(3, "Cy", null, 0, 0, false, true, false, null, false, false, false, null), labels, NOW);
        assertEquals("No messages yet", r.message);
        assertFalse(r.timed);
    }

    @Test
    public void connectingRowHasNoTime() {
        final ChatRowMapper.Row r = ChatRowMapper.map(new ChatSummary(2, "Bob", "secret", 0, 0, true, false, true, MessageStatus.SENDING, false, false, false, null), labels, NOW);
        assertEquals("Connecting…", r.message);
        assertFalse(r.timed);
    }

    @Test
    public void realMessageHasTime() {
        final ChatRowMapper.Row r = ChatRowMapper.map(new ChatSummary(1, "Ann", "hi", 1000, 1, false, true, false, MessageStatus.RECEIVED, false, false, false, null), labels, NOW);
        assertTrue(r.timed);
    }

    @Test
    public void selfCheckPasses() {
        assertEquals(Collections.emptyList(), ChatRowMapper.selfCheck());
    }
}
