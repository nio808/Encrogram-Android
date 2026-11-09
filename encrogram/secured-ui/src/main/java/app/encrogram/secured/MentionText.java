package app.encrogram.secured;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Where group mentions sit in a message's text, for the composer and the strict text mapper (bold only). Pure Java.
 *
 * A mention is the token {@code SecuredMentions.token} made: {@code @key}, or {@code @'key'} for names with spaces or
 * dots, at the start of the text or after whitespace, and not running on into more name characters. Only keys the
 * engine reported for the message (or the composer inserted) count, so other {@code @words} stay plain text.
 */
final class MentionText {

    private MentionText() {
    }

    /** Sorted, non-overlapping {start, end} pairs of every mention of {@code keys} in {@code text} at or after {@code from}. */
    static List<int[]> ranges(String text, int from, Collection<String> keys) {
        if (text == null || keys == null || keys.isEmpty() || from >= text.length()) return Collections.emptyList();
        final ArrayList<int[]> out = new ArrayList<>();
        for (String key : keys) {
            if (key == null || key.isEmpty()) continue;
            find(text, Math.max(0, from), "@'" + key + "'", true, out);
            find(text, Math.max(0, from), "@" + key, false, out);
        }
        Collections.sort(out, (a, b) -> Integer.compare(a[0], b[0]));
        // Drop overlaps (a short key inside a longer one's quoted form): the earlier, then longer, range wins.
        final ArrayList<int[]> clean = new ArrayList<>();
        int end = -1;
        for (int[] r : out) {
            if (r[0] >= end) {
                clean.add(r);
                end = r[1];
            }
        }
        return clean;
    }

    /** Whether {@code text} still contains a mention of {@code key} (the composer forgets keys that were deleted). */
    static boolean contains(String text, String key) {
        return !ranges(text, 0, Collections.singletonList(key)).isEmpty();
    }

    /** Removes the keys whose mention is no longer in {@code text}. */
    static void prune(String text, Map<String, Long> mentions) {
        if (mentions.isEmpty()) return;
        mentions.keySet().removeIf(k -> !contains(text, k));
    }

    private static void find(String text, int from, String token, boolean quoted, List<int[]> out) {
        int i = text.indexOf(token, from);
        while (i >= 0) {
            final int end = i + token.length();
            final boolean startOk = i == 0 || Character.isWhitespace(text.charAt(i - 1));
            final boolean endOk = quoted || end == text.length() || !continuesName(text.codePointAt(end));
            if (startOk && endOk) out.add(new int[]{i, end});
            i = text.indexOf(token, i + 1);
        }
    }

    /** A character that would make the core read a longer name: anything but whitespace and punctuation. */
    private static boolean continuesName(int cp) {
        if (Character.isWhitespace(cp)) return false;
        switch (Character.getType(cp)) {
            case Character.CONNECTOR_PUNCTUATION:
            case Character.DASH_PUNCTUATION:
            case Character.START_PUNCTUATION:
            case Character.END_PUNCTUATION:
            case Character.INITIAL_QUOTE_PUNCTUATION:
            case Character.FINAL_QUOTE_PUNCTUATION:
            case Character.OTHER_PUNCTUATION:
                return false;
            default:
                return true;
        }
    }
}
