package app.encrogram.secured;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

public class MentionTextTest {

    private static List<int[]> r(String text, String... keys) {
        return MentionText.ranges(text, 0, Arrays.asList(keys));
    }

    @Test
    public void plainAndQuotedTokensAreFound() {
        final List<int[]> a = r("hi @Bob and @'Ann Lee', look", "Bob", "Ann Lee");
        assertEquals(2, a.size());
        assertArrayEquals(new int[]{3, 7}, a.get(0));
        assertArrayEquals(new int[]{12, 22}, a.get(1));
    }

    @Test
    public void onlyAtWordStartAndWholeNames() {
        assertTrue(r("mail@Bob", "Bob").isEmpty());          // not after whitespace
        assertTrue(r("@Bobby here", "Bob").isEmpty());       // runs on into a longer name
        assertEquals(1, r("@Bob, hello", "Bob").size());     // punctuation ends the name
        assertEquals(1, r("@Bob", "Bob").size());            // end of text
        assertEquals(1, r("line\n@Bob", "Bob").size());      // after a newline
    }

    @Test
    public void unknownNamesStayPlain() {
        assertTrue(r("hi @Carol", "Bob").isEmpty());
        assertTrue(MentionText.ranges("hi @Bob", 0, java.util.Collections.emptyList()).isEmpty());
    }

    @Test
    public void searchStartsAfterPrefix() {
        // The mapper skips its own "Forwarded" label line.
        assertTrue(MentionText.ranges("@Bob\nrest", 5, Arrays.asList("Bob")).isEmpty());
        assertEquals(1, MentionText.ranges("label\n@Bob", 6, Arrays.asList("Bob")).size());
    }

    @Test
    public void overlapsKeepTheEarlierLongerRange() {
        final List<int[]> a = r("@'Bob Ray' x", "Bob Ray", "Bob");
        assertEquals(1, a.size());
        assertArrayEquals(new int[]{0, 10}, a.get(0));
    }

    @Test
    public void pruneForgetsDeletedMentions() {
        final LinkedHashMap<String, Long> m = new LinkedHashMap<>();
        m.put("Bob", 1L);
        m.put("Ann Lee", 2L);
        MentionText.prune("hello @Bob", m);
        assertTrue(m.containsKey("Bob"));
        assertFalse(m.containsKey("Ann Lee"));
    }
}
