package app.encrogram.secured;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** H8 (name cap while typing) and H9 (initials skip symbols): the pure rules behind Kit's helpers. */
public class KitTest {

    private static String typed(String dest, String insert) {
        final CharSequence r = Kit.capInsert(Kit.NAME_MAX_CHARS, insert, 0, insert.length(), dest, dest.length(), dest.length());
        return dest + (r == null ? insert : r.toString());
    }

    private static String repeat(String s, int n) {
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }

    @Test
    public void capStopsAtFiftyCharacters() {
        final String full = repeat("a", 50);
        assertEquals(full, typed(repeat("a", 49), "aaa"));
        assertEquals(full, typed(full, "b"));
        assertEquals(full, typed("", repeat("a", 291)));   // the harsh test pasted 291
        assertNull(Kit.capInsert(50, "x", 0, 1, repeat("a", 49), 49, 49));
    }

    @Test
    public void emojiCountsOnceAndIsNeverCutInHalf() {
        final String smile = "😀";   // one code point, two chars
        final String fortyNine = repeat("a", 49);
        assertEquals(fortyNine + smile, typed(fortyNine, smile + smile));
        assertEquals(repeat(smile, 50), typed("", repeat(smile, 60)));
        assertEquals(fortyNine + "a", typed(fortyNine, "a" + smile));
    }

    @Test
    public void replacingTextFreesRoom() {
        final String full = repeat("a", 50);
        // select the last 5 characters and type 5 new ones: allowed
        assertNull(Kit.capInsert(50, "bbbbb", 0, 5, full, 45, 50));
        // select 1 and paste 3: only 1 fits
        assertEquals("b", Kit.capInsert(50, "bbb", 0, 3, full, 49, 50).toString());
    }

    @Test
    public void initialsSkipSymbolWords() {
        assertArrayEquals(new String[]{"Harsh", "b>"}, Kit.initialWords("Harsh A <b>"));   // "HB", not "H<"
        assertArrayEquals(new String[]{"Ann", null}, Kit.initialWords("Ann 😀"));
        assertArrayEquals(new String[]{"Team", "Test"}, Kit.initialWords("Team Test"));
        assertArrayEquals(new String[]{"Bob", null}, Kit.initialWords("<< Bob >>"));
        assertArrayEquals(new String[]{"7", "Ann"}, Kit.initialWords("7 - Ann"));
    }

    @Test
    public void nameWithoutLettersKeepsTheOldSplit() {
        assertArrayEquals(new String[]{"😀", null}, Kit.initialWords("😀"));
        assertArrayEquals(new String[]{"<", ">"}, Kit.initialWords("< >"));
        assertArrayEquals(new String[]{"", null}, Kit.initialWords(""));
    }

    @Test
    public void colourKeyStillUsesTheFullName() {
        assertEquals(Kit.colorKey("Harsh A <b>"), Kit.colorKey(" Harsh A <b> "));
    }
}
