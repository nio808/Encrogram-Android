package app.encrogram.secured;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
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

    /**
     * Sorted, non-overlapping {start, end} pairs of every mention of {@code keys} in {@code text} at or after
     * {@code from}. Read as the core reads them (SecuredMentions, Markdown.hs mentionP): {@code @'name'} up to the
     * closing quote; otherwise the run up to the next whitespace, without trailing punctuation, must equal a key
     * (so {@code @Bob_1} is "Bob_1", never "Bob").
     */
    static List<int[]> ranges(String text, int from, Collection<String> keys) {
        if (text == null || keys == null || keys.isEmpty() || from >= text.length()) return Collections.emptyList();
        final HashSet<String> names = new HashSet<>(keys);
        final ArrayList<int[]> out = new ArrayList<>();
        final int n = text.length();
        int i = text.indexOf('@', Math.max(0, from));
        while (i >= 0) {
            int next = i + 1;
            if (i == 0 || Character.isWhitespace(text.charAt(i - 1))) {
                if (i + 1 < n && text.charAt(i + 1) == '\'') {
                    final int close = text.indexOf('\'', i + 2);
                    if (close > i + 2 && names.contains(text.substring(i + 2, close))) {
                        out.add(new int[]{i, close + 1});
                        next = close + 1;
                    }
                } else {
                    int end = i + 1;
                    while (end < n) {
                        final int cp = text.codePointAt(end);
                        if (Character.isWhitespace(cp)) break;
                        end += Character.charCount(cp);
                    }
                    final String name = stripPunctuation(text.substring(i + 1, end));
                    if (!name.isEmpty() && names.contains(name)) {
                        out.add(new int[]{i, i + 1 + name.length()});
                    }
                    next = end;
                }
            }
            i = next < n ? text.indexOf('@', next) : -1;
        }
        return out;
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

    /** Data.Char.isPunctuation at the end of a name is not part of it (Markdown.hs splitPunctuation). */
    private static String stripPunctuation(String s) {
        int end = s.length();
        while (end > 0) {
            final int cp = s.codePointBefore(end);
            if (!isPunctuation(cp)) break;
            end -= Character.charCount(cp);
        }
        return s.substring(0, end);
    }

    private static boolean isPunctuation(int cp) {
        switch (Character.getType(cp)) {
            case Character.CONNECTOR_PUNCTUATION:
            case Character.DASH_PUNCTUATION:
            case Character.START_PUNCTUATION:
            case Character.END_PUNCTUATION:
            case Character.INITIAL_QUOTE_PUNCTUATION:
            case Character.FINAL_QUOTE_PUNCTUATION:
            case Character.OTHER_PUNCTUATION:
                return true;
            default:
                return false;
        }
    }
}
